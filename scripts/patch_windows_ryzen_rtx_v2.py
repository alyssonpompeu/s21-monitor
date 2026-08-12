#!/usr/bin/env python3
from pathlib import Path

p = Path('windows-desktop/src/main.cpp')
s = p.read_text(encoding='utf-8')

s = s.replace('#include <shellapi.h>\n', '#include <shellapi.h>\n#include <memoryapi.h>\n')
s = s.replace('int cpuPercent = 60;\n    int gpuPercent = 50;\n    int ramPercent = 65;\n    int backend = 0; // 0 auto, 1 CPU, 2 Vulkan',
              'int cpuPercent = 90;\n    int gpuPercent = 85;\n    int ramPercent = 65;\n    int backend = 2; // 0 auto, 1 CPU, 2 Vulkan')

s = s.replace('std::atomic_bool gBusy{false};\n', 'std::atomic_bool gBusy{false};\nHANDLE gMemoryJob{};\nconstexpr SIZE_T APP_MEMORY_BUDGET = 5ull * 1024ull * 1024ull * 1024ull;\n')

s = s.replace('return std::clamp(target, 1, 8);', 'return std::clamp(target, 1, 6);')
s = s.replace('const ULONGLONG hardFloor = 1024ull * 1024ull * 1024ull;', 'const ULONGLONG hardFloor = 5ull * 1024ull * 1024ull * 1024ull;')

needle = 'bool RunChild(const std::wstring& command, std::wstring& output, DWORD& exitCode) {'
insert = '''void InitFiveGiBMemoryBudget() {
    SetProcessWorkingSetSizeEx(GetCurrentProcess(), 512ull * 1024ull * 1024ull, APP_MEMORY_BUDGET, 0);

    gMemoryJob = CreateJobObjectW(nullptr, L"IAOffline5GiBJob");
    if (!gMemoryJob) return;

    JOBOBJECT_EXTENDED_LIMIT_INFORMATION info{};
    info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_JOB_MEMORY;
    info.JobMemoryLimit = APP_MEMORY_BUDGET;
    if (!SetInformationJobObject(gMemoryJob, JobObjectExtendedLimitInformation, &info, sizeof(info))) {
        CloseHandle(gMemoryJob);
        gMemoryJob = nullptr;
        return;
    }
    AssignProcessToJobObject(gMemoryJob, GetCurrentProcess());
}

'''
if needle not in s:
    raise SystemExit('RunChild insertion point missing')
s = s.replace(needle, insert + needle, 1)

s = s.replace('    if (!ok) {\n        CloseHandle(readPipe);\n        return false;\n    }',
'''    if (!ok) {
        CloseHandle(readPipe);
        return false;
    }
    if (gMemoryJob) AssignProcessToJobObject(gMemoryJob, pi.hProcess);''')

s = s.replace('const fs::path cpu = runtimeDir / L"llama-cli.exe";\n    const fs::path vk = runtimeDir / L"llama-cli-vulkan.exe";',
              'const fs::path cpu = runtimeDir / L"llama-cpu" / L"llama-cli.exe";\n    const fs::path vk = runtimeDir / L"llama-vulkan" / L"llama-cli.exe";')
s = s.replace('const fs::path cpu = runtimeDir / L"sd-cli.exe";\n    const fs::path vk = runtimeDir / L"sd-cli-vulkan.exe";',
              'const fs::path cpu = runtimeDir / L"sd-cpu" / L"sd-cli.exe";\n    const fs::path vk = runtimeDir / L"sd-vulkan" / L"sd-cli.exe";')

s = s.replace('std::wstring cmd = Quote(runtime) + L" -m " + Quote(model) + L" -f " + Quote(promptFile) +\n                           L" -n 640 -c 4096 -b 128 -t " + std::to_wstring(threads) + L" --temp 0.75";',
'''std::wstring cmd = Quote(runtime) + L" -m " + Quote(model) + L" -f " + Quote(promptFile) +
                           L" -n 640 -c 4096 -b 256 -t " + std::to_wstring(threads) + L" --temp 0.75";
        if (vk) cmd += L" -ngl 99";''')

s = s.replace('fs::exists(AppDir() / L"runtime" / L"llama-cli.exe")', 'fs::exists(AppDir() / L"runtime" / L"llama-cpu" / L"llama-cli.exe")')
s = s.replace('fs::exists(AppDir() / L"runtime" / L"llama-cli-vulkan.exe")', 'fs::exists(AppDir() / L"runtime" / L"llama-vulkan" / L"llama-cli.exe")')
s = s.replace('fs::exists(AppDir() / L"runtime" / L"sd-cli.exe")', 'fs::exists(AppDir() / L"runtime" / L"sd-cpu" / L"sd-cli.exe")')
s = s.replace('fs::exists(AppDir() / L"runtime" / L"sd-cli-vulkan.exe")', 'fs::exists(AppDir() / L"runtime" / L"sd-vulkan" / L"sd-cli.exe")')

s = s.replace('L"IA Offline Windows v1\\r\\n\\r\\nColoque o modelo principal em models\\\\ e os runtimes em runtime\\\\."',
              'L"IA Offline Windows RTX v2\\r\\n\\r\\nPerfil: Windows 10 x64 • Ryzen 5 3500X • RTX 2070 SUPER • 16 GB RAM.\\r\\nVulkan é o backend padrão; CPU AVX2 é fallback."')
s = s.replace('L"Pronto • offline • CPU seguro por padrão"', 'L"Pronto • offline • RTX/Vulkan preferido • orçamento de memória 5 GiB"')
s = s.replace('L"IA Offline Workspace — Windows v1"', 'L"IA Offline Workspace — Windows 10 RTX v2"')
s = s.replace('    LoadSettings();\n\n    WNDCLASSW wc{};', '    LoadSettings();\n    InitFiveGiBMemoryBudget();\n\n    WNDCLASSW wc{};')

p.write_text(s, encoding='utf-8')
print('Windows Ryzen/RTX v2 patch applied')
