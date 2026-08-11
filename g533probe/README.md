# G533 Probe — Galaxy S21

Aplicativo experimental para investigar o Logitech G533 no Galaxy S21.

## Funções
- Bluetooth Classic discovery e lista de dispositivos pareados.
- Bluetooth LE scan em modo LOW_LATENCY.
- Wi‑Fi Direct peer discovery.
- Detecção USB dos IDs conhecidos do G533: `046D:0A66` (receptor) e `046D:0A67` (headset/charger).
- Inventário de `AudioDeviceInfo`, incluindo sample rates, canais e encodings expostos pelo Android.
- Gerador de tom de 1 kHz com 44.1/48/96/192 kHz, 16-bit PCM ou 32-bit float, mono/stereo.
- Seleção de saída preferida para o `AudioTrack`.

## Limitação importante
O G533 usa um enlace Logitech/Avnera proprietário de 2,4 GHz. O app só pode descobrir protocolos/radios que o framework Android e o hardware do telefone expõem. Se o headset não anunciar como Bluetooth, BLE ou Wi‑Fi Direct, um APK convencional não consegue torná-lo magicamente visível.

A seleção de sample rate no teste é uma solicitação ao `AudioTrack`; o Android pode reamostrar internamente.
