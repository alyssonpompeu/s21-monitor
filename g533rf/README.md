# G533 RF Finder

Experimental passive RF-correlation app for Logitech G533 on Galaxy S21.

Embedded confirmed target data:
- Headset A-00072
- Dongle A-00073
- FCC operating band: 2403.35–2477.35 MHz

The app attempts:
1. normal Wi-Fi environment scan for interference context,
2. root `iw survey dump`,
3. optional separate monitor interface `g533mon` for passive channel-busy/noise measurements,
4. OFF-vs-ON snapshot comparison.

The 38-channel / 2 MHz candidate grid is explicitly labelled as an Avnera-family inference, not as a confirmed G533 channel plan.
