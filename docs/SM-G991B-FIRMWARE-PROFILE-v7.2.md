# Unilaw v7.2 — perfil do firmware SM-G991B

Perfil extraído das imagens fornecidas pelo proprietário do aparelho para orientar a otimização da Unilaw. Este documento é **descritivo**: a aplicação não modifica `boot`, `vendor_boot`, `dtbo`, `vbmeta` ou parâmetros térmicos do firmware.

## Identidade validada

- Dispositivo alvo: Samsung Galaxy S21 5G `SM-G991B`
- SoC: Exynos 2100
- Board DT: `Samsung O1S board based on EXYNOS2100`
- Kernel encontrado: `5.4.242-30958140-abG991BXXSJHZA6`
- Kernel ARM64
- Página de memória: 4 KiB (`CONFIG_ARM64_4K_PAGES=y`)
- Scheduler: preemptivo, HZ=250, PSI/UCLAMP/EMS habilitados
- Governor CPU padrão no kernel: `schedutil`
- Cmdline das imagens analisadas: `androidboot.selinux=permissive loop.max_part=7`

## CPU / cpufreq

O DT descreve três domínios:

| Domínio | CPUs | Papel | mínimo | máximo validado |
|---|---:|---|---:|---:|
| policy 0 | 0–3 | LITTLE | 400 MHz | 2210 MHz |
| policy 1 | 4–6 | MID | 533 MHz | 2808 MHz |
| policy 2 | 7 | BIG | 533 MHz | 2912 MHz |

A v7.2 continua lendo `policy*`, `scaling_available_frequencies` e `stats/time_in_state` no aparelho. Os valores do firmware servem para validar o alvo, não para forçar um caminho inexistente.

## GPU Mali-G78

DT `mali@18500000`, compatível `arm,mali`:

- Governor: `interactive`
- mínimo: 130 MHz
- máximo: 858 MHz
- clock de início: 221 MHz
- clock sustentável declarado: **494 MHz**
- degraus: 130 / 221 / 312 / 403 / 494 / 585 / 676 / 767 / 858 MHz
- controle térmico de GPU habilitado
- polling DVFS: 30 ms

Na v7.2, o perfil root **IA Sustentada** usa 494 MHz somente quando o fingerprint de firmware é reconhecido. Em kernel diferente, o app volta ao modo adaptativo e usa apenas valores que o sysfs atual expõe.

## Térmico

O DT fornecido indica `control-temp` de aproximadamente 70 °C para BIG, MID e G3D. A aplicação não altera trips ou políticas térmicas. O `ResourceGuard` da v7.2 passa a observar `thermal_zone*`, antecipar checkpoint e pausar cargas pesadas quando a temperatura permanece próxima/acima dessa faixa.

## Memória / zRAM

Config do kernel:

- `CONFIG_ZRAM=y`
- `CONFIG_ZRAM_WRITEBACK=y`
- `CONFIG_ZRAM_LRU_WRITEBACK=y`
- `CONFIG_MEMCG=y`
- `CONFIG_PSI=y`

`fstab` do boot analisado configura `/dev/block/zram0` com `zramsize=3221225472`, isto é, **3 GiB**. A Unilaw apenas detecta e mostra a zRAM existente; não recria nem redimensiona zRAM e não trata o armazenamento persistente como swap.

## Módulos relevantes encontrados no vendor ramdisk

Entre os módulos presentes estão `exynos-acme.ko`, `sec_pm_cpufreq.ko`, `exynos_devfreq.ko`, `mali_kbase.ko`, `exynos-gpu-profiler.ko`, `exynos_thermal.ko`, `freq-qos-tracer.ko` e `exynos-migov.ko`. Isso confirma que a build precisa conviver com a pilha Samsung de DVFS/QoS/thermal em vez de tentar substituir seus governors.

## Política de root da Unilaw

A aplicação pode, com autorização explícita de root:

- reduzir/restaurar apenas **frequências máximas** expostas pelo kernel;
- selecionar degraus reais de frequência;
- usar o clock sustentável de 494 MHz da GPU no perfil validado.

Ela não faz overclock, undervolt, alteração de tensão, governor, frequência mínima, thermal trip, LMKD ou zRAM.

## Integridade das imagens fornecidas

SHA-256 dos arquivos `.lz4` analisados:

- `boot.img.lz4`: `607f4342b3cdc1957a37711bd9d8497b47a6d1f431cc0f61f0ab0ae1933ca4c2`
- `vendor_boot.img.lz4`: `8622f5d21c7189cbf2d95f12e28e0198c1909ee52ab3fc2fb3166105e3ff093d`
- `dtbo.img.lz4`: `e7165784b91f95920a5679beb9772fe5b50c22e61976a2b27865b41b77bb99b7`
- `recovery.img.lz4`: `6e218dcb07dd4e8074becd09d301f01a90497b75bf4e7d42e6d11a8700cae70b`
- `vbmeta.img.lz4`: `db801976f728cf1bbaa971e6f5bbfd21db9162c5719537ed295f5e78ba243043`
- `vbmeta_system.img.lz4`: `c1b36e13680f6224df0d26ba6169f42febde4c3f55f4e9d522ad6aacb2259dc5`
- `misc.bin.lz4`: `8bfc3b5f1a227306647921ce991be7bc82af2e3f99178eb5826dfe6024e0cf49`
