#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>

#include <algorithm>
#include <atomic>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace fs = std::filesystem;

namespace {
constexpr UINT WM_RESULT = WM_APP + 1;
constexpr UINT WM_STATUS = WM_APP + 2;
constexpr int ID_PROMPT = 101;
constexpr int ID_OUTPUT = 102;
constexpr int ID_MODE = 103;
constexpr int ID_RUN = 104;
constexpr int ID_PLUGINS = 105;
constexpr int ID_CPU = 106;
constexpr int ID_GPU = 107;
constexpr int ID_RAM = 108;
constexpr int ID_BACKEND = 109;
constexpr int ID_OPEN_OUTPUT = 110;

HWND gMain{};
HWND gPrompt{};
HWND gOutput{};
HWND gMode{};
HWND gRun{};
HWND gStatus{};
HWND gCpu{};
HWND gGpu{};
HWND gRam{};
HWND gBackend{};
std::atomic_bool gBusy{false};

struct Settings {
    int cpuPercent = 60;
    int gpuPercent = 50;
    int ramPercent = 65;
    int backend = 0; // 0 auto, 1 CPU, 2 Vulkan
};
Settings gSettings;

fs::path AppDir() {
    wchar_t buf[MAX_PATH]{};
    GetModuleFileNameW(nullptr, buf, MAX_PATH);
    return fs::path(buf).parent_path();
}

fs::path SettingsPath() { return AppDir() / L"settings.ini"; }

std::wstring Utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    int count = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    if (count <= 0) return std::wstring(s.begin(), s.end());
    std::wstring out(static_cast<size_t>(count), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), out.data(), count);
    return out;
}

std::string WideToUtf8(const std::wstring& s) {
    if (s.empty()) return {};
    int count = WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0, nullptr, nullptr);
    std::string out(static_cast<size_t>(count), '\0');
    WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), out.data(), count, nullptr, nullptr);
    return out;
}

void SaveSettings() {
    std::ofstream f(SettingsPath(), std::ios::trunc);
    if (!f) return;
    f << "cpu=" << gSettings.cpuPercent << "\n";
    f << "gpu=" << gSettings.gpuPercent << "\n";
    f << "ram=" << gSettings.ramPercent << "\n";
    f << "backend=" << gSettings.backend << "\n";
}

void LoadSettings() {
    std::ifstream f(SettingsPath());
    std::string line;
    while (std::getline(f, line)) {
        const auto p = line.find('=');
        if (p == std::string::npos) continue;
        const auto key = line.substr(0, p);
        const int value = std::atoi(line.substr(p + 1).c_str());
        if (key == "cpu") gSettings.cpuPercent = std::clamp(value, 25, 90);
        if (key == "gpu") gSettings.gpuPercent = std::clamp(value, 0, 90);
        if (key == "ram") gSettings.ramPercent = std::clamp(value, 35, 75);
        if (key == "backend") gSettings.backend = std::clamp(value, 0, 2);
    }
}

std::wstring BackendName() {
    if (gSettings.backend == 1) return L"CPU";
    if (gSettings.backend == 2) return L"Vulkan";
    return L"Automático";
}

void RefreshButtons() {
    SetWindowTextW(gCpu, (L"CPU " + std::to_wstring(gSettings.cpuPercent) + L"%").c_str());
    SetWindowTextW(gGpu, (L"GPU " + std::to_wstring(gSettings.gpuPercent) + L"%").c_str());
    SetWindowTextW(gRam, (L"RAM " + std::to_wstring(gSettings.ramPercent) + L"%").c_str());
    SetWindowTextW(gBackend, (L"Backend: " + BackendName()).c_str());
}

int ThreadBudget() {
    SYSTEM_INFO si{};
    GetSystemInfo(&si);
    const int logical = std::max(1u, si.dwNumberOfProcessors);
    const int target = std::max(1, static_cast<int>((logical * gSettings.cpuPercent) / 100));
    return std::clamp(target, 1, 8);
}

bool MemorySafe(std::wstring& reason) {
    MEMORYSTATUSEX mem{};
    mem.dwLength = sizeof(mem);
    if (!GlobalMemoryStatusEx(&mem)) return true;
    const auto reserve = static_cast<ULONGLONG>(mem.ullTotalPhys * (100 - gSettings.ramPercent) / 100.0);
    const ULONGLONG hardFloor = 1024ull * 1024ull * 1024ull;
    const auto required = std::max(reserve, hardFloor);
    if (mem.ullAvailPhys < required) {
        std::wstringstream ss;
        ss << L"Tarefa bloqueada para preservar memória. RAM disponível: "
           << (mem.ullAvailPhys / (1024ull * 1024ull)) << L" MiB; reserva mínima: "
           << (required / (1024ull * 1024ull)) << L" MiB.";
        reason = ss.str();
        return false;
    }
    return true;
}

std::wstring GetText(HWND h) {
    const int n = GetWindowTextLengthW(h);
    std::wstring s(static_cast<size_t>(n), L'\0');
    if (n > 0) GetWindowTextW(h, s.data(), n + 1);
    return s;
}

void PostHeapString(UINT msg, const std::wstring& text) {
    auto* heap = new std::wstring(text);
    PostMessageW(gMain, msg, 0, reinterpret_cast<LPARAM>(heap));
}

std::wstring Quote(const fs::path& p) {
    return L"\"" + p.wstring() + L"\"";
}

std::wstring SanitizePromptForArg(std::wstring prompt) {
    std::replace(prompt.begin(), prompt.end(), L'\"', L'\'');
    std::replace(prompt.begin(), prompt.end(), L'\r', L' ');
    std::replace(prompt.begin(), prompt.end(), L'\n', L' ');
    return prompt;
}

bool RunChild(const std::wstring& command, std::wstring& output, DWORD& exitCode) {
    SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
    HANDLE readPipe{}, writePipe{};
    if (!CreatePipe(&readPipe, &writePipe, &sa, 0)) return false;
    SetHandleInformation(readPipe, HANDLE_FLAG_INHERIT, 0);

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    si.hStdOutput = writePipe;
    si.hStdError = writePipe;
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);

    PROCESS_INFORMATION pi{};
    std::vector<wchar_t> cmd(command.begin(), command.end());
    cmd.push_back(L'\0');

    const BOOL ok = CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW,
                                   nullptr, AppDir().c_str(), &si, &pi);
    CloseHandle(writePipe);
    if (!ok) {
        CloseHandle(readPipe);
        return false;
    }

    std::string bytes;
    char buffer[4096];
    DWORD got = 0;
    while (ReadFile(readPipe, buffer, sizeof(buffer), &got, nullptr) && got > 0) {
        bytes.append(buffer, buffer + got);
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    CloseHandle(readPipe);
    output = Utf8ToWide(bytes);
    return true;
}

fs::path SelectTextRuntime(bool& usingVulkan) {
    const fs::path runtimeDir = AppDir() / L"runtime";
    const fs::path cpu = runtimeDir / L"llama-cli.exe";
    const fs::path vk = runtimeDir / L"llama-cli-vulkan.exe";
    usingVulkan = false;
    if (gSettings.backend == 2 || (gSettings.backend == 0 && gSettings.gpuPercent > 0)) {
        if (fs::exists(vk)) {
            usingVulkan = true;
            return vk;
        }
    }
    return cpu;
}

fs::path SelectImageRuntime(bool& usingVulkan) {
    const fs::path runtimeDir = AppDir() / L"runtime";
    const fs::path cpu = runtimeDir / L"sd-cli.exe";
    const fs::path vk = runtimeDir / L"sd-cli-vulkan.exe";
    usingVulkan = false;
    if (gSettings.backend == 2 || (gSettings.backend == 0 && gSettings.gpuPercent > 0)) {
        if (fs::exists(vk)) {
            usingVulkan = true;
            return vk;
        }
    }
    return cpu;
}

fs::path GeneralModel() { return AppDir() / L"models" / L"Qwen3.5-2B-Q4_K_M.gguf"; }
fs::path CoderModel() { return AppDir() / L"plugins" / L"coder" / L"coder.gguf"; }
fs::path TinySdModel() { return AppDir() / L"plugins" / L"tiny-sd" / L"segmind_tiny-sd-q4_K.gguf"; }

void RunTask() {
    if (gBusy.exchange(true)) return;
    EnableWindow(gRun, FALSE);

    const std::wstring prompt = GetText(gPrompt);
    if (prompt.empty()) {
        gBusy = false;
        EnableWindow(gRun, TRUE);
        MessageBoxW(gMain, L"Digite um pedido primeiro.", L"IA Offline", MB_OK | MB_ICONINFORMATION);
        return;
    }

    std::wstring memReason;
    if (!MemorySafe(memReason)) {
        gBusy = false;
        EnableWindow(gRun, TRUE);
        MessageBoxW(gMain, memReason.c_str(), L"Proteção de memória", MB_OK | MB_ICONWARNING);
        return;
    }

    const int mode = static_cast<int>(SendMessageW(gMode, CB_GETCURSEL, 0, 0));
    std::thread([prompt, mode]() {
        const int threads = ThreadBudget();
        bool vk = false;

        if (mode == 1) {
            const auto model = TinySdModel();
            const auto runtime = SelectImageRuntime(vk);
            if (!fs::exists(model)) {
                PostHeapString(WM_RESULT, L"Tiny-SD não instalado. Coloque segmind_tiny-sd-q4_K.gguf em plugins\\tiny-sd\\.");
                PostHeapString(WM_STATUS, L"Plugin Tiny-SD ausente");
                return;
            }
            if (!fs::exists(runtime)) {
                PostHeapString(WM_RESULT, L"Runtime de imagem não encontrado em runtime\\sd-cli.exe.");
                PostHeapString(WM_STATUS, L"Runtime Tiny-SD ausente");
                return;
            }
            fs::create_directories(AppDir() / L"output");
            SYSTEMTIME st{}; GetLocalTime(&st);
            wchar_t name[128]{};
            swprintf_s(name, L"imagem_%04d%02d%02d_%02d%02d%02d.png", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
            const fs::path out = AppDir() / L"output" / name;
            std::wstring cmd = Quote(runtime) + L" -m " + Quote(model) + L" -p \"" + SanitizePromptForArg(prompt) +
                               L"\" -o " + Quote(out) + L" --width 512 --height 512 --cfg-scale 7 --steps 18 --sampling-method euler_a --diffusion-fa --vae-tiling -t " +
                               std::to_wstring(std::min(threads, 4));
            PostHeapString(WM_STATUS, vk ? L"Gerando imagem • Vulkan" : L"Gerando imagem • CPU");
            std::wstring childOut; DWORD code = 1;
            const bool ok = RunChild(cmd, childOut, code);
            if (ok && code == 0 && fs::exists(out)) {
                PostHeapString(WM_RESULT, L"Imagem criada:\r\n" + out.wstring() + L"\r\n\r\n" + childOut);
                PostHeapString(WM_STATUS, L"Imagem concluída");
            } else {
                PostHeapString(WM_RESULT, L"Falha no Tiny-SD. Código: " + std::to_wstring(code) + L"\r\n" + childOut);
                PostHeapString(WM_STATUS, L"Falha na geração de imagem");
            }
            return;
        }

        const auto model = GeneralModel();
        const auto runtime = SelectTextRuntime(vk);
        if (!fs::exists(model)) {
            PostHeapString(WM_RESULT, L"Modelo principal não encontrado. Coloque Qwen3.5-2B-Q4_K_M.gguf em models\\.");
            PostHeapString(WM_STATUS, L"Modelo Qwen ausente");
            return;
        }
        if (!fs::exists(runtime)) {
            PostHeapString(WM_RESULT, L"Runtime llama.cpp não encontrado em runtime\\llama-cli.exe.");
            PostHeapString(WM_STATUS, L"Runtime llama.cpp ausente");
            return;
        }

        fs::create_directories(AppDir() / L"temp");
        const fs::path promptFile = AppDir() / L"temp" / L"prompt.txt";
        {
            std::ofstream pf(promptFile, std::ios::binary | std::ios::trunc);
            const std::string utf8 = WideToUtf8(prompt);
            pf.write(utf8.data(), static_cast<std::streamsize>(utf8.size()));
        }
        std::wstring cmd = Quote(runtime) + L" -m " + Quote(model) + L" -f " + Quote(promptFile) +
                           L" -n 640 -c 4096 -b 128 -t " + std::to_wstring(threads) + L" --temp 0.75";
        PostHeapString(WM_STATUS, vk ? L"Gerando texto • Vulkan" : L"Gerando texto • CPU");
        std::wstring childOut; DWORD code = 1;
        const bool ok = RunChild(cmd, childOut, code);
        if (ok && code == 0) {
            PostHeapString(WM_RESULT, childOut);
            PostHeapString(WM_STATUS, L"Concluído");
        } else {
            PostHeapString(WM_RESULT, L"Falha no llama.cpp. Código: " + std::to_wstring(code) + L"\r\n" + childOut);
            PostHeapString(WM_STATUS, L"Falha na geração de texto");
        }
    }).detach();
}

void ShowPlugins() {
    const bool mainModel = fs::exists(GeneralModel());
    const bool coder = fs::exists(CoderModel());
    const bool tiny = fs::exists(TinySdModel());
    const bool llamaCpu = fs::exists(AppDir() / L"runtime" / L"llama-cli.exe");
    const bool llamaVk = fs::exists(AppDir() / L"runtime" / L"llama-cli-vulkan.exe");
    const bool sdCpu = fs::exists(AppDir() / L"runtime" / L"sd-cli.exe");
    const bool sdVk = fs::exists(AppDir() / L"runtime" / L"sd-cli-vulkan.exe");

    std::wstringstream ss;
    ss << L"PLUGINS LOCAIS\n\n"
       << (mainModel ? L"✓" : L"○") << L" Qwen3.5 2B — texto\n"
       << (coder ? L"✓" : L"○") << L" Qwen2.5 Coder — plugins\\coder\\coder.gguf\n"
       << (tiny ? L"✓" : L"○") << L" Tiny-SD — imagem\n\n"
       << L"RUNTIMES\n"
       << (llamaCpu ? L"✓" : L"○") << L" llama.cpp CPU\n"
       << (llamaVk ? L"✓" : L"○") << L" llama.cpp Vulkan\n"
       << (sdCpu ? L"✓" : L"○") << L" stable-diffusion.cpp CPU\n"
       << (sdVk ? L"✓" : L"○") << L" stable-diffusion.cpp Vulkan\n\n"
       << L"Automático usa Vulkan somente quando o runtime correspondente existe; caso contrário usa CPU.";
    MessageBoxW(gMain, ss.str().c_str(), L"Plugins locais", MB_OK | MB_ICONINFORMATION);
}

void CycleValue(int id) {
    if (id == ID_CPU) {
        static const int values[]{25, 40, 50, 60, 75, 90};
        auto it = std::find(std::begin(values), std::end(values), gSettings.cpuPercent);
        gSettings.cpuPercent = (it == std::end(values) || ++it == std::end(values)) ? values[0] : *it;
    } else if (id == ID_GPU) {
        static const int values[]{0, 25, 50, 75, 90};
        auto it = std::find(std::begin(values), std::end(values), gSettings.gpuPercent);
        gSettings.gpuPercent = (it == std::end(values) || ++it == std::end(values)) ? values[0] : *it;
    } else if (id == ID_RAM) {
        static const int values[]{35, 45, 55, 65, 75};
        auto it = std::find(std::begin(values), std::end(values), gSettings.ramPercent);
        gSettings.ramPercent = (it == std::end(values) || ++it == std::end(values)) ? values[0] : *it;
    } else if (id == ID_BACKEND) {
        gSettings.backend = (gSettings.backend + 1) % 3;
    }
    SaveSettings();
    RefreshButtons();
}

void Layout(HWND hwnd) {
    RECT r{}; GetClientRect(hwnd, &r);
    const int w = r.right - r.left;
    const int h = r.bottom - r.top;
    const int pad = 14;
    MoveWindow(gMode, pad, pad, 150, 30, TRUE);
    MoveWindow(gRun, w - pad - 110, pad, 110, 30, TRUE);
    MoveWindow(gPrompt, pad, 56, w - 2 * pad, 92, TRUE);
    MoveWindow(gOutput, pad, 160, w - 2 * pad, std::max(120, h - 270), TRUE);
    const int y = h - 96;
    MoveWindow(gCpu, pad, y, 100, 28, TRUE);
    MoveWindow(gGpu, pad + 108, y, 100, 28, TRUE);
    MoveWindow(gRam, pad + 216, y, 100, 28, TRUE);
    MoveWindow(gBackend, pad + 324, y, 160, 28, TRUE);
    MoveWindow(GetDlgItem(hwnd, ID_PLUGINS), w - pad - 210, y, 100, 28, TRUE);
    MoveWindow(GetDlgItem(hwnd, ID_OPEN_OUTPUT), w - pad - 102, y, 102, 28, TRUE);
    MoveWindow(gStatus, pad, h - 54, w - 2 * pad, 24, TRUE);
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE: {
        gMain = hwnd;
        HFONT font = static_cast<HFONT>(GetStockObject(DEFAULT_GUI_FONT));
        gMode = CreateWindowW(WC_COMBOBOXW, L"", WS_CHILD | WS_VISIBLE | CBS_DROPDOWNLIST,
                              0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_MODE), nullptr, nullptr);
        SendMessageW(gMode, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(L"Texto"));
        SendMessageW(gMode, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(L"Criar imagem"));
        SendMessageW(gMode, CB_SETCURSEL, 0, 0);

        gRun = CreateWindowW(L"BUTTON", L"Gerar", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
                             0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_RUN), nullptr, nullptr);
        gPrompt = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD | WS_VISIBLE | ES_MULTILINE | ES_AUTOVSCROLL | WS_VSCROLL,
                                  0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_PROMPT), nullptr, nullptr);
        gOutput = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"IA Offline Windows v1\r\n\r\nColoque o modelo principal em models\\ e os runtimes em runtime\\.",
                                  WS_CHILD | WS_VISIBLE | ES_MULTILINE | ES_AUTOVSCROLL | ES_READONLY | WS_VSCROLL,
                                  0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_OUTPUT), nullptr, nullptr);
        gCpu = CreateWindowW(L"BUTTON", L"", WS_CHILD | WS_VISIBLE, 0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_CPU), nullptr, nullptr);
        gGpu = CreateWindowW(L"BUTTON", L"", WS_CHILD | WS_VISIBLE, 0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_GPU), nullptr, nullptr);
        gRam = CreateWindowW(L"BUTTON", L"", WS_CHILD | WS_VISIBLE, 0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_RAM), nullptr, nullptr);
        gBackend = CreateWindowW(L"BUTTON", L"", WS_CHILD | WS_VISIBLE, 0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_BACKEND), nullptr, nullptr);
        CreateWindowW(L"BUTTON", L"Plugins", WS_CHILD | WS_VISIBLE, 0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_PLUGINS), nullptr, nullptr);
        CreateWindowW(L"BUTTON", L"Saída", WS_CHILD | WS_VISIBLE, 0,0,0,0, hwnd, reinterpret_cast<HMENU>(ID_OPEN_OUTPUT), nullptr, nullptr);
        gStatus = CreateWindowW(L"STATIC", L"Pronto • offline • CPU seguro por padrão", WS_CHILD | WS_VISIBLE,
                                0,0,0,0, hwnd, nullptr, nullptr, nullptr);

        for (HWND child : {gMode,gRun,gPrompt,gOutput,gCpu,gGpu,gRam,gBackend,GetDlgItem(hwnd,ID_PLUGINS),GetDlgItem(hwnd,ID_OPEN_OUTPUT),gStatus})
            SendMessageW(child, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
        RefreshButtons();
        return 0;
    }
    case WM_SIZE: Layout(hwnd); return 0;
    case WM_COMMAND: {
        const int id = LOWORD(wp);
        if (id == ID_RUN) RunTask();
        else if (id == ID_PLUGINS) ShowPlugins();
        else if (id == ID_OPEN_OUTPUT) {
            fs::create_directories(AppDir() / L"output");
            ShellExecuteW(hwnd, L"open", (AppDir() / L"output").c_str(), nullptr, nullptr, SW_SHOWNORMAL);
        } else if (id == ID_CPU || id == ID_GPU || id == ID_RAM || id == ID_BACKEND) {
            CycleValue(id);
        }
        return 0;
    }
    case WM_RESULT: {
        auto* text = reinterpret_cast<std::wstring*>(lp);
        SetWindowTextW(gOutput, text ? text->c_str() : L"");
        delete text;
        gBusy = false;
        EnableWindow(gRun, TRUE);
        return 0;
    }
    case WM_STATUS: {
        auto* text = reinterpret_cast<std::wstring*>(lp);
        if (text) SetWindowTextW(gStatus, text->c_str());
        delete text;
        return 0;
    }
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE, PWSTR, int show) {
    InitCommonControls();
    LoadSettings();

    WNDCLASSW wc{};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    wc.lpszClassName = L"IAOfflineWindowsClass";
    RegisterClassW(&wc);

    HWND hwnd = CreateWindowExW(0, wc.lpszClassName, L"IA Offline Workspace — Windows v1",
                                WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 960, 700,
                                nullptr, nullptr, hInst, nullptr);
    if (!hwnd) return 1;
    ShowWindow(hwnd, show);
    UpdateWindow(hwnd);

    MSG msg{};
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return static_cast<int>(msg.wParam);
}
