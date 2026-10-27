#!/usr/bin/env python3
"""Safety hotfix after device freezes at 910, 936, 975 and 1001 MHz.

Preserves Apple Final EMS CPU scheduling while completely removing all direct
PLL/ACPM-bypass modifications from the compiled tree. The original Samsung
PLL tables, CAL/ACPM DVFS and thermal controls are unchanged.

Until firmware-coordinated G3D OPP/voltage transitions are verified on-device,
sysfs manual GPU clock requests above 858 MHz are explicitly rejected.
"""
from pathlib import Path

gpu = Path("src/drivers/gpu/arm/exynos/frontend/gpex_clock_sysfs.c")
s = gpu.read_text()
anchor = """\tif (clk == 0) {
\t\tgpex_dvfs_enable();
\t} else {
\t\tgpex_dvfs_disable();
\t\tgpex_clock_set(clk);
\t}

\treturn count;
"""
replacement = """\t/* APPLE FINAL GPUOC SAFETY HOTFIX
\t * The experimental direct PLL write via ra_set_rate() caused a
\t * freeze/reboot on EVERY candidate frequency on SM-G991B.
\t * No unsupported rate may reach CAL or the G3D PLL.
\t * Restore the original HZA6 ACPM+DVFS policy for valid OPPs.
\t */
\tif (clk > 858000) {
\t\tGPU_LOG(MALI_EXYNOS_WARNING,
\t\t\t\"GPUOC blocked: unsupported clock %u kHz\\n\", clk);
\t\treturn -EOPNOTSUPP;
\t}

""" + anchor
if s.count(anchor) != 1:
    raise SystemExit("GPUOC SAFETY: unexpected source, refusing ambiguous patch")
gpu.write_text(s.replace(anchor,replacement,1))
print("APPLE FINAL SAFE GUARD: original ACPM/PLL kept, manual >858 MHz rejected")
