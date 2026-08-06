# S21 CPU/GPU Monitor

Widget Android para Samsung Galaxy S21 com Exynos 2100.

## Recursos

- Widget redimensionável para CPU, GPU, RAM e temperatura da bateria.
- Atualização a cada 2 segundos enquanto o monitor em primeiro plano estiver ativo.
- Tentativa de leitura dos contadores Mali/Exynos expostos pelo firmware.
- Fallback para frequência relativa quando o Android bloqueia os contadores globais.
- APK debug instalável e assinado automaticamente pelo Android SDK.

## Limitação técnica

Em Android moderno, um aplicativo comum não possui permissão oficial para consultar todos os contadores de CPU/GPU. O app tenta caminhos sysfs conhecidos do Mali-G78/Exynos 2100. Quando esses arquivos estão bloqueados por SELinux, a GPU aparece como indisponível ou estimada pela frequência.

## Compilar

```bash
./gradlew :app:assembleDebug
```

O APK será criado em `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions

O workflow `.github/workflows/build-apk.yml` gera e publica o APK como artifact após cada push para `main` ou execução manual.
