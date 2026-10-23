# G533 Bridge Preflight — Galaxy S21 SM-G991B

Read-only Android diagnostic module for Logitech G533 and BCM4375B1.

The module reads firmware version, Broadcom driver info, OS build, root availability, firmware candidate paths, and availability of iw/nexutil. It exports a plain text report and includes extracted G533 MAC-config resource bytes. Root is optional and requested only when the user taps the advanced button. No vendor or firmware changes; no monitor activation, injection, reflashing or SELinux changes.

Nexmon PR #662 merged the 18.41.117 base firmware, while PR #663 is an open experimental patch with a September 2026 S21 success report. An 802.11 monitor-mode patch is not a G533 Avnera demodulator. The app does not connect or pretend to connect the G533.

G533 MAC config:
- Client/headset: offset 0x4F17, 26 bytes, 8616006306d2cb010d030611b1000000000101040cb100000000
- Host/receiver: offset 0x10000, 18 bytes, 860e006b06d2d8010c0305d3c00000000001

Sources:
https://github.com/seemoo-lab/nexmon/pull/662
https://github.com/seemoo-lab/nexmon/pull/663
https://fccid.io/JNZA00072
https://fccid.io/JNZA00073

## v1.1.0 — prova binária (somente leitura)

Novo botão ROOT: calcula Git blob SHA-1 do arquivo instalado `/vendor/firmware/bcmdhd_sta.bin_b1` e compara com a imagem Nexmon upstream `18_41_117_sta` (#662), que tem 1.297.036 bytes e Git blob `8ba9a17a2e75dcacb3d47e4bea58a76c006e7e1d`. Calcula SHA-256 e tamanhos dos firmwares STA, MON, MFG e HCDs SEMCO para preservar evidências. Tudo via shell read-only; nunca grava e nunca faz dump de EFS/NVRAM/IMEI. `BYTE_EXACT_MATCH` confirma somente igualdade de bytes com a imagem referência, não garante funcionamento do PR #663 e muito menos rádio Avnera.
