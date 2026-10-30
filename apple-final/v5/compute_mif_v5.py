#!/usr/bin/env python3
"""Apple Final V5: generate a mathematical 13-level MIF CAL/ASV candidate model.

THIS IS NOT FLASHABLE OR A VALIDATED LPDDR5 VOLTAGE/CLOCK CONFIGURATION.
"""
import bisect
import math

original = sorted([
    (3172000,768750), (2730000,743750), (2535000,737500),
    (2288000,712500), (2028000,687500), (1716000,643750),
    (1539000,618750), (1352000,600000), (1014000,543750),
    (845000,531250), (676000,525000), (546000,512500),
    (421000,506250)
])
step_uv = 6250
growth = (3672000 / 421000) ** (1 / 12)
f0, v0 = original[-2]
f1, v1 = original[-1]
exponent = math.log(v1 / v0) / math.log(f1 / f0)

def voltage_estimate(f):
    if f > f1:
        return v1 * (f / f1) ** exponent
    freqs = [p[0] for p in original]
    j = bisect.bisect_right(freqs, f)
    if j <= 0:
        return float(original[0][1])
    if j >= len(original):
        return float(original[-1][1])
    lo, v_lo = original[j-1]
    hi, v_hi = original[j]
    return v_lo + (v_hi - v_lo) * ((f-lo) / (hi-lo))

print("level,freq_khz,estimated_cal_asv_uv,rounded_up_cal_asv_uv,firmware_verified")
for level in range(13):
    j = 12 - level
    freq = round(421000 * growth ** j / 1000) * 1000
    if level == 0: freq = 3672000
    if level == 12: freq = 421000
    uv = voltage_estimate(freq)
    rounded = math.ceil((uv-1e-8) / step_uv) * step_uv
    verified = "stock_only" if freq in dict(original) else "NO"
    print(f"{level},{freq},{round(uv)},{rounded},{verified}")
