# S21 RF Diagnostic

Aplicativo Android de diagnóstico root para o projeto MA-RX42/A7105.

## O que coleta

- confirmação de `uid=0` via `su`;
- modelo, SoC/hardware e versão do kernel;
- propriedades Android relacionadas a Wi-Fi e Bluetooth;
- `wlan0` e driver associado no sysfs;
- módulos de kernel e módulos vendor relacionados ao rádio;
- nomes de firmware/configuração em `/vendor`;
- linhas relevantes do `dmesg`;
- informações básicas do Bluetooth sysfs.

O aplicativo é somente leitura: não altera clocks, sysfs, firmware, Wi-Fi, Bluetooth ou configurações de rádio.

## Build

```bash
gradle :rx42diag:assembleDebug
```

APK local: `rx42diag/build/outputs/apk/debug/rx42diag-debug.apk`.

O workflow `build-rx42diag.yml` publica `RX42-S21-RF-Diagnostic.apk` como artifact e release `rx42diag-latest`.

> Branch `verify-rx42diag-build`: alteração de documentação usada apenas para disparar a verificação de compilação em pull request.
