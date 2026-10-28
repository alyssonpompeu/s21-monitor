#!/usr/bin/env python3
"""Apple Final: default Floppy GPU DVFS limit = 949 MHz at boot.

Preserves native Floppy GPU Unlock 0=stock limit / 1=unlock limit and
dynamic governor (does NOT force GPU frequency at idle).
Only enables 949 MHz when it is a real simultaneous CAL/ASV + DT DVFS OPP.
"""
from pathlib import Path

p=Path("src/drivers/gpu/arm/exynos/frontend/gpex_clock.c")
s=p.read_text()
needle="""\tkfree(fv_array);

\treturn 0;
}

static int set_clock_using_calapi"""
replacement="""\t/*
\t * Apple Final GPU 949 default:
\t * Start with the native Floppy 'unlock' ceiling at boot, but only
\t * when the actual firmware AND Device Tree tables both have a valid
\t * 949 MHz operating point. Keep gpu_stock_max_clock untouched, so
\t * writing 0 to /sys/kernel/gpu/gpu_unlock still restores 858 MHz.
\t * GPU frequency remains managed by GPEX DVFS / thermal governors.
\t */
\tif (clk_info.gpu_unlock_max_clock == 949 &&
\t    clk_info.gpu_stock_max_clock < 949) {
\t\tfor (j = 0; j < clk_info.table_size; j++) {
\t\t\tif (clk_info.table[j].clock == 949 &&
\t\t\t    clk_info.table[j].voltage > 0) {
\t\t\t\tclk_info.gpu_max_clock = 949;
\t\t\t\tGPU_LOG(MALI_EXYNOS_INFO,
\t\t\t\t\t"Apple Final: 949 MHz GPU unlock is default (DVFS retained)\\\\n");
\t\t\t\tbreak;
\t\t\t}
\t\t}
\t}

\tkfree(fv_array);

\treturn 0;
}

static int set_clock_using_calapi"""
assert s.count(needle)==1, "Floppy clock initialization structure does not match"
# The native GPU clocks in Samsung GPEX are MHz, not kHz.
assert "cal_freq == dt_clock_table[j].clock" in s
s=s.replace(needle,replacement,1)
p.write_text(s)
print("GPU 949 MHz is boot DEFAULT iff firmware+DT valid; native unlock-off remains reversible")
