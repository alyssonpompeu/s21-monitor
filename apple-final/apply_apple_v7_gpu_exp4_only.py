#!/usr/bin/env python3
"""Apple Final V7 isolated GPU voltage experiment.

Preserves the V7 MIF 13 OPPs and the original DSU code unchanged.
Changes G3D boot CAL/ACPM margin -7% -> -4% and adds an exponential
frequency-dependent margin with -4% at low GPU clock to approximately
-1% at 949 MHz. This is a firmware margin request, not a PMIC reading.
"""
from pathlib import Path

def replace_exact(filename, old, new):
    p = Path(filename)
    s = p.read_text()
    assert s.count(old) == 1, (filename, s.count(old), old[:100])
    p.write_text(s.replace(old, new, 1))

replace_exact("src/arch/arm64/configs/exynos2100-unified_defconfig",
              "CONFIG_SOC_EXYNOS2100_GPU_UV=7\n",
              "CONFIG_SOC_EXYNOS2100_GPU_UV=4\n")

gpu="src/drivers/gpu/arm/exynos/backend/gpexbe_clock.c"
replace_exact(gpu, "static unsigned int cal_id;\n",
r'''static unsigned int cal_id;

/* APPLE FINAL V7 G3D EXP4: voltage MARGIN_REQ profile, not raw Vcore.
 * e^(-ln(4)*x) ~= (1 - ln(4)*x/8)^8 in Q12 integer arithmetic.
 * x=(f-fmin)/(949-fmin); -4% near the minimum, -1% near 949 MHz.
 * ACPM retains voltage regulator control and native thermal protection.
 */
static int apple_v7_g3d_exp4_margin(int clock_mhz)
{
	unsigned int lo = cal_dfs_get_min_freq(cal_id);
	unsigned int factor = 4096, term;
	int i, pct;

	if (!lo || lo >= 949)
		return -4;

	clock_mhz = clamp_val(clock_mhz, (int)lo, 949);
	term = 4096 - (710U * (clock_mhz - lo)) / (949 - lo);
	for (i = 0; i < 8; i++)
		factor = (factor * term + 2048) / 4096;
	pct = (4 * factor + 2048) / 4096;
	return -clamp_val(pct, 1, 4);
}

static int apple_v7_g3d_exp4_applied_margin = -4;
''')

replace_exact(gpu, r'''int gpexbe_clock_set_rate(int clk)
{
	int ret = 0;

	gpex_debug_new_record(HIST_CLOCK);
	gpex_debug_record_prev_data(HIST_CLOCK, gpexbe_clock_get_rate());

	ret = cal_dfs_set_rate(cal_id, clk);

	gpex_debug_record_time(HIST_CLOCK);''',
r'''int gpexbe_clock_set_rate(int clk)
{
	int ret = 0;
	int previous_clock = gpexbe_clock_get_rate();
	int prior_margin = apple_v7_g3d_exp4_applied_margin;
	int requested_margin = apple_v7_g3d_exp4_margin(clk);

	gpex_debug_new_record(HIST_CLOCK);
	gpex_debug_record_prev_data(HIST_CLOCK, previous_clock);

	/* Relax UV before increasing GPU clock; only increase UV after
	 * the frequency drops. Avoid unnecessary ACPM margin IPC calls.
	 */
	if (clk > previous_clock && requested_margin != prior_margin) {
		cal_dfs_set_volt_margin(cal_id, requested_margin);
		apple_v7_g3d_exp4_applied_margin = requested_margin;
	}

	ret = cal_dfs_set_rate(cal_id, clk);

	if (ret && clk > previous_clock &&
	    requested_margin != prior_margin) {
		cal_dfs_set_volt_margin(cal_id, prior_margin);
		apple_v7_g3d_exp4_applied_margin = prior_margin;
	}
	if (!ret && clk <= previous_clock &&
	    requested_margin != prior_margin) {
		cal_dfs_set_volt_margin(cal_id, requested_margin);
		apple_v7_g3d_exp4_applied_margin = requested_margin;
	}

	gpex_debug_record_time(HIST_CLOCK);''')

print("V7 + isolated G3D exp4 patch: CPU/DSU/MIF unchanged, GPU 949MHz default kept")
print("G3D CAL margin is a firmware percentage request; physical PMIC Vcore not measured")
