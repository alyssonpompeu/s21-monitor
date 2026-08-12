#include <windows.h>
#include <psapi.h>
#include <shellapi.h>

#include <algorithm>
#include <filesystem>
#include <sstream>
#include <string>

namespace fs = std::filesystem;

static fs::path AppDir() {
    wchar_t buf[MAX_PATH]{};
    GetModuleFileNameW(nullptr, buf, MAX_PATH);
    return fs::path(buf).parent_path();
}

static unsigned long long ApproxPagefileBytes() {
    PERFORMANCE_INFORMATION pi{};
    pi.cb = sizeof(pi);
    if (!GetPerformanceInfo(&pi, sizeof(pi))) return 0;
    const unsigned long long commit = static_cast<unsigned long long>(pi.CommitLimit) * pi.PageSize;
    const unsigned long long physical = static_cast<unsigned long long>(pi.PhysicalTotal) * pi.PageSize;
    return commit > physical ? commit - physical : 0;
}

static bool ConfigurePagefile() {
    const auto script = AppDir() / L"Configurar-Memoria.ps1";
    if (!fs::exists(script)) {
        MessageBoxW(nullptr, L"Configurar-Memoria.ps1 não foi encontrado ao lado do executável.", L"IA Offline", MB_OK | MB_ICONERROR);
        return false;
    }

    std::wstring params = L"-NoProfile -ExecutionPolicy Bypass -File \"" + script.wstring() + L"\"";
    SHELLEXECUTEINFOW sei{};
    sei.cbSize = sizeof(sei);
    sei.fMask = SEE_MASK_NOCLOSEPROCESS;
    sei.lpVerb = L"runas";
    sei.lpFile = L"powershell.exe";
    sei.lpParameters = params.c_str();
    sei.nShow = SW_SHOWNORMAL;
    if (!ShellExecuteExW(&sei)) return false;
    WaitForSingleObject(sei.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(sei.hProcess, &code);
    CloseHandle(sei.hProcess);
    return code == 0;
}

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
    const unsigned long long pagefile = ApproxPagefileBytes();
    constexpr unsigned long long target = 8ull * 1024ull * 1024ull * 1024ull;
    constexpr unsigned long long tolerance = 512ull * 1024ull * 1024ull;

    if (pagefile + tolerance < target) {
        std::wstringstream ss;
        ss << L"Este perfil foi preparado para Windows 10 x64, Ryzen 5 3500X, RTX 2070 SUPER e 16 GB de RAM.\n\n"
           << L"Antes de iniciar, ele recomenda um arquivo de paginação fixo de 8 GiB.\n"
           << L"A configuração atual aparenta ter aproximadamente " << (pagefile / (1024ull * 1024ull)) << L" MiB de paginação.\n\n"
           << L"Deseja configurar 8192 MiB agora? O Windows solicitará permissão de administrador e pode exigir reinicialização.";
        const int answer = MessageBoxW(nullptr, ss.str().c_str(), L"Preparar memória da IA Offline", MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON1);
        if (answer != IDYES) return 2;
        if (!ConfigurePagefile()) {
            MessageBoxW(nullptr, L"Não foi possível configurar o arquivo de paginação. Nenhuma alteração adicional será feita.", L"IA Offline", MB_OK | MB_ICONERROR);
            return 3;
        }
        MessageBoxW(nullptr, L"Arquivo de paginação configurado para 8 GiB. Reinicie o Windows e execute este launcher novamente antes de iniciar a IA.", L"IA Offline", MB_OK | MB_ICONINFORMATION);
        return 0;
    }

    const auto core = AppDir() / L"IA-Offline-Core-x64.exe";
    if (!fs::exists(core)) {
        MessageBoxW(nullptr, L"IA-Offline-Core-x64.exe não foi encontrado ao lado do launcher.", L"IA Offline", MB_OK | MB_ICONERROR);
        return 4;
    }

    HINSTANCE result = ShellExecuteW(nullptr, L"open", core.c_str(), nullptr, AppDir().c_str(), SW_SHOWNORMAL);
    return reinterpret_cast<INT_PTR>(result) > 32 ? 0 : 5;
}
