# IA Offline Workspace — Windows 10 Ryzen/RTX v2

Edição Win32 x64 preparada para **Windows 10**, **Ryzen 5 3500X**, **RTX 2070 SUPER** e **16 GB de RAM**. A interface é nativa e os modelos permanecem externos para que a atualização de IA/plugins não exija recompilar o executável.

## Como iniciar

Use sempre:

```text
IA-Offline-Windows10-Ryzen3500X-RTX2070S-x64.exe
```

Esse launcher verifica a paginação antes de abrir o núcleo. Se detectar menos de aproximadamente 8 GiB de pagefile, oferece configurar **8192 MiB** no volume do sistema usando UAC. A alteração é explícita e pode exigir reinicialização do Windows.

O programa **não aloca 5 GiB artificialmente ao iniciar**. Em vez disso, usa um orçamento agregado de até **5 GiB** para o processo e runtimes filhos e solicita ao Windows um working set máximo de 5 GiB. Isso evita roubar 5 GiB do sistema sem necessidade.

## Estrutura esperada

```text
IA-Offline-Windows10-Ryzen3500X-RTX2070S-x64.exe
IA-Offline-Core-x64.exe
Configurar-Memoria.ps1
settings.ini
runtime/
  llama-cpu/
    llama-cli.exe
    ... DLLs do pacote oficial CPU
  llama-vulkan/
    llama-cli.exe
    ... DLLs do pacote oficial Vulkan
  sd-cpu/
    sd-cli.exe
    ... DLLs do pacote oficial CPU
  sd-vulkan/
    sd-cli.exe
    ... DLLs do pacote oficial Vulkan
models/
  Qwen3.5-2B-Q4_K_M.gguf
plugins/
  coder/
    coder.gguf               # opcional
  tiny-sd/
    segmind_tiny-sd-q4_K.gguf
output/
```

## Perfil de desempenho

- Windows 10 x64 explícito no manifest e macros Win32.
- Interface compilada com AVX2, `/O2`, `/Ob3` e LTCG.
- Ryzen 5 3500X: no máximo 6 workers; padrão de CPU em 90% para preservar responsividade.
- RTX 2070 SUPER: Vulkan é o backend padrão; CPU AVX2 é fallback.
- Qwen3.5 2B: quando Vulkan está ativo, o launcher do modelo recebe offload de até 99 camadas (`-ngl 99`).
- Tiny-SD: runtime Vulkan separado e runtime CPU separado para evitar conflitos entre DLLs.
- Contexto Qwen mantido em 4096 e batch 256 para não pressionar desnecessariamente os 16 GB de RAM.
- Antes de tarefas pesadas, a aplicação exige aproximadamente 5 GiB de memória física disponível.
- O Job Object limita o conjunto app + processo de inferência a 5 GiB de memória comprometida pelo job.

## Pagefile de 8 GiB

`Configurar-Memoria.ps1` desativa o pagefile automático e configura um pagefile fixo de 8192 MiB no volume do sistema. Isso é uma alteração administrativa do Windows e o launcher só a executa depois de confirmação do usuário.

## Observação sobre "reservar 5 GB"

Forçar 5 GiB de RAM física a permanecer ocupados/lockados desde a abertura pioraria a disponibilidade do Windows e poderia aumentar paginação. Por isso esta versão usa **teto de 5 GiB + working-set hint + verificação de 5 GiB disponíveis**, que é mais seguro para uma máquina com 16 GiB.

## Runtimes

O build usa pacotes oficiais e verificados por SHA-256:

- `llama.cpp` b10362: Windows x64 CPU e Vulkan;
- `stable-diffusion.cpp` `master-817-bcc7e29`: Windows x64 CPU e Vulkan.

Os backends ficam em diretórios separados para evitar colisão de DLLs GGML entre os dois projetos.
