#!/usr/bin/env python3
"""Estimate a hypothetical MIF CAL/ASV voltage at 3672 MHz.

Usage:
  python3 estimate_mif_voltage.py < /path/to/apple_mif_cal_opps.txt

NO writes to hardware. CAL MIF ASV voltage is NOT a measurement of LPDDR5
DRAM VDDQ/VDD2. Extrapolation is NOT a validated OPP or voltage recommendation.
"""
import math
import re
import sys

rows = [(int(f), int(v)) for f, v in re.findall(
    r"freq_khz=(\d+) cal_asv_volt_uv=(\d+)", sys.stdin.read())
    if int(f) > 0 and int(v) > 0]
rows = sorted(set(rows))
if len(rows) < 2:
    raise SystemExit("Require at least two CAL/ASV voltage entries")
(f0, v0), (f1, v1) = rows[-2:]
if f1 <= f0 or v0 <= 0 or v1 <= 0:
    raise SystemExit("Invalid calibrated entries")
k = math.log(v1 / v0) / math.log(f1 / f0)
target = 3672000
vt = v1 * (target / f1) ** k
dynamic_power_ratio = (vt / v1) ** 2 * (target / f1)
print(f"CAL points: {f0} kHz/{v0} uV -> {f1} kHz/{v1} uV")
print(f"Local inferred exponent: k={k:.4f}")
print(f"EXTRAPOLATED CAL MIF voltage at 3672000 kHz: {vt:.0f} uV")
print(f"Estimated relative dynamic power: {dynamic_power_ratio:.3f}x")
print("NOT for programming RAM voltage: unvalidated, firmware timing/OPP absent.")
