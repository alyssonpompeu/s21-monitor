# Catálogo v8

A interface apresenta os recursos como plugins essenciais, mesmo quando sua implementação é compilada no Core. O roteador automático escolhe a capacidade a partir da intenção do usuário; modos explícitos continuam disponíveis.

## Built-in

- `tools.exact`
- `search.local`
- `document.ocr`
- `files.universal`
- `database.sqlite`
- `security.apk`
- `developer.binary`
- `developer.logcat`
- `backup.projects`
- `image.tools`
- `audio.tts.system`
- `device.s21`

## Model-backed

- `model.qwen` -> Qwen General `.iapack`
- `model.coder` -> Coder `.iapack`
- `model.tinysd` -> Tiny-SD `.iapack`

Model-backed plugins are deliberately not duplicated inside the APK. Their weights are orders of magnitude larger than the orchestration code and must remain independently replaceable.
