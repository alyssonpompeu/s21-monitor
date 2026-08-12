# IA Offline Workspace — Windows v1

Primeira variante nativa Win32 da IA Offline. O executável principal é pequeno e os modelos permanecem externos para evitar um `.exe` de vários gigabytes e permitir atualizar modelos/plugins sem recompilar a interface.

## Estrutura esperada

```text
IA-Offline-Windows-x64.exe
settings.ini                 # criado automaticamente
runtime/
  llama-cli.exe
  llama-cli-vulkan.exe       # opcional/futuro
  sd-cli.exe
  sd-cli-vulkan.exe          # opcional/futuro
models/
  Qwen3.5-2B-Q4_K_M.gguf
plugins/
  coder/
    coder.gguf               # opcional
  tiny-sd/
    segmind_tiny-sd-q4_K.gguf
output/
```

## Recursos deste primeiro build

- interface Win32 nativa, sem Electron e sem WebView;
- texto local por `llama.cpp`;
- Tiny-SD local por `stable-diffusion.cpp`;
- aba/diagnóstico de plugins locais;
- limites persistentes de CPU, GPU e RAM;
- CPU entre 25–90%, GPU/orçamento Vulkan entre 0–90% e RAM entre 35–75%;
- reserva mínima de RAM antes de tarefas pesadas;
- número de threads derivado do orçamento de CPU;
- backend `Automático`, `CPU` ou `Vulkan` por política global;
- `Automático`/`Vulkan` só usam GPU quando existe um runtime Vulkan correspondente; caso contrário o programa cai para CPU;
- geração de imagem em 512×512 com Tiny-SD;
- diretório de saída separado.

## Limites reais

Os percentuais de CPU/GPU são alvos de política, não quotas rígidas do kernel. O Windows não oferece ao aplicativo um limitador portátil para dizer que uma GPU deve ficar exatamente em determinado percentual. RAM recebe uma barreira conservadora antes de iniciar tarefas, e a execução usa poucos workers para reduzir picos.

O build inicial publicado pelo CI inclui runtimes CPU. A interface já reconhece runtimes Vulkan separados (`llama-cli-vulkan.exe` e `sd-cli-vulkan.exe`) quando forem adicionados/testados.

## Objetivo

Manter a mesma filosofia da versão Android: aplicação principal enxuta, modelos grandes e capacidades opcionais fora do executável, funcionamento totalmente offline depois que os arquivos locais forem colocados nas pastas esperadas.
