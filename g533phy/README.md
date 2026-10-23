# G533 PHY Lab — Android v1.0

Installable offline I/Q analysis APK based on the earlier Python `g533_phy_lab/g533_iq_lab.py`.

## Use

1. **Synthetic demonstration** works immediately and does not need root, computer, SDR, or files. Produces pi/4-DQPSK **artificial** I/Q plus noise and compares ON vs OFF.
2. **Import OFF and ON**: Android Storage Access Framework document picker, supports `cf32le`, `cs16le`, `cu8`. Specify sample rate in Hz, center in MHz, optional symbol rate *only if known*, then analyze.
3. View relative spectrum graph and report; share as plain text.
4. Display a **candidate** 38-channel RF grid. The app does not scan these frequencies.

## Scope and limitations

This APK **does NOT capture RF** from Broadcom BCM4375B1 or any Android radio. It has no RF receive/transmit implementation, no G533 pairing, no connection, no audio/microphone data, no permissions for Wi-Fi/Bluetooth and no system modifications. Results are from synthetic data or imported external I/Q only. A frequency peak does not identify G533. A phase score is an unverified pi/4-DQPSK hypothesis, not a packet decoder.

Bandwidth coverage: a capture's instantaneous RF span is `sample_rate_hz` around its recorded `center_mhz`. If the SDR was tuned incorrectly, changing the displayed center field cannot recover a G533 signal.

Limits: first 524288 complex samples per file; no signal resampling or DQPSK clock recovery; FFT length 2048. Real captures with 2.4 GHz center but no known sample metadata cannot be reliably interpreted.

The Python CLI remains useful on a PC for long captures; the APK runs analysis without Python or NumPy. Unit tests validate the synthetic signal and raw input parsers.

Original G533 FCC radio description: receiver A-00073, 38 channels, DQPSK, 2403.35–2477.35 MHz (report RF161007E01, FCC JNZA00073).
The grid at 2 MHz spacings is a **candidate**, not a verified hopping sequence.

## Next RF engineering gate

To test G533 traffic for real, obtain controlled raw I/Q from a **capable SDR** or validate a true raw I/Q export from BCM4375, not Wi-Fi network scans. Capture repeated OFF/ON trials at the same gain, antenna, center and sampling rate. Verify temporal/spectral repeatability; then derive sync, packet format and pairing. Neither this app nor Nexmon 802.11 monitor mode proves Avnera PHY access.
