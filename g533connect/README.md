# G533 Connect — Android Galaxy S21 (experimental v1.0)

A polished, usable on-device **research/diagnostic application**, not a working G533 wireless receiver.

## Functionality tested by programmatic code

- Native Android dashboard, card interface and four tabs: Panel, Conectar, Firmware, Logs.
- Read-only Magisk diagnostics: `/sys/wifi/wifiver`, firmware SHA-256/size, SEMCO HCD names/hashes, Android model, USB device VID/PID.
- Device scanner checks for Logitech `046d:0a66` USB receiver and `046d:0a67` wired-headset USB, **not the proprietary RF packets**.
- Direct pairing readiness gate explicitly returns **NOT SUPPORTED** because no Avnera PHY or radio backend was implemented; selecting 'LED piscando' never claims pairing succeeded.
- Import the original G533 V1.13 client/host firmware BIN with Android file picker; verify exact known SHA-256 and bytes, extract `macconfig` at documented offsets, and report V1.13 marker.
- Share a real audit report. No internet access, no Bluetooth/Wi-Fi scan spoofing, no changes to SELinux, drivers, firmware, pairing IDs, or radio.
- Runs without an external SDR, but cannot capture/emit RF signals itself. Root is only used to read protected diagnostics.

## Why no dongle-free pairing yet?

1. AV7300 project (Corsair H2100) provides reverse-engineered **AV7300-family** disassembler/instruction set, but no verified ISA match with G533's Avnera/Carmen V1.13 firmware. Its code was **not copied** into this APK.
2. RadioSploit's experimental Samsung BCM4375 Bluetooth modifications support **Zigbee, Mosart, ESB**, not the G533 proprietary Avnera/DQPSK PHY. Its source was **not copied** into this APK.
3. Nexmon PR #663 targets **Wi-Fi 802.11 monitor/injection** with identical stock firmware blob BCM4375B1 FWID `01-1b4772fd`; it does not implement an Avnera modem and its patch has not been installed/tested on this S21.
4. The G533-specific RF protocol (packet framing, hopping, sync, TX/RX capabilities, audio) is not decoded. A new RF chipset or genuine baseband-level receiver may be required.

Nothing in the UI simulates an RF connection. An application alone cannot bypass unavailable RF baseband capabilities.

## Realistic next technical gate

To develop actual wireless pairing, capture real G533 traffic using suitable RF equipment; inspect packet format and protocol; independently verify a programmable radio PHY that supports it; then integrate a tested native backend into the APK. A phone-only solution depends on actual hardware support and may be physically impossible.

## Sources

- https://github.com/Prehistoricman/AV7300
- https://github.com/RCayre/radiosploit_patches
- https://github.com/seemoo-lab/nexmon/pull/663
- https://github.com/seemoo-lab/nexmon/pull/662
- Logitech official pairing support: https://support.logi.com/hc/en-au/articles/360023347673-Pair-the-G533-gaming-headset-to-the-USB-wireless-adapter
