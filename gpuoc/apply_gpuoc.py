#!/usr/bin/env python3
"""APPLE FINAL GPUOC source experiment. No automatic overclock at boot.
Models tested: SM-G991B/o1s Exynos2100; HZA6. Compile only, not hardware-validated.
Requires the pinned Samsung source used by APPLE FINAL CI.
"""
from pathlib import Path
import re

ROOT = Path("src")
def replace_once(path, before, after):
    p = ROOT / path
    data = p.read_text()
    if data.count(before) != 1:
        raise RuntimeError(f"Refusing ambiguous patch {p}: match count {data.count(before)}")
    p.write_text(data.replace(before, after, 1))
    print(f"GPUOC patch: {p}")

# Reference PLL = 26 MHz; M=70,72,75,77, P=2,S=0.
# These are POSSIBLE PLL rates, not validated Mali operating points/voltages.
pll = "drivers/soc/samsung/cal-if/exynos2100/cmucal-node.c"
replace_once(pll, "struct cmucal_pll_table pll_g3d_rate_table[] = {\n",
"""struct cmucal_pll_table pll_g3d_rate_table[] = {
	/* APPLE_GPUOC_EXPERIMENT: manually requested PLL candidates only.
	 * No change to the ACPM stock DVFS table or normal OPP policy.
	 * 26 MHz * M / (P * 2^S): 910, 936, 975, 1001 MHz.
	 */
	PLL_RATE_MPS(1001000000, 77, 2, 0),
	PLL_RATE_MPS(975000000, 75, 2, 0),
	PLL_RATE_MPS(936000000, 72, 2, 0),
	PLL_RATE_MPS(910000000, 70, 2, 0),
""")

cal = "drivers/soc/samsung/cal-if/cal-if.c"
replace_once(cal, "int cal_dfs_set_rate(unsigned int id, unsigned long rate)\n{\n\tstruct vclk *vclk;\n\tint ret;\n",
"""/* APPLE_GPUOC_EXPERIMENT: Exynos2100 G3D only, manual PLL probe.
 * Normal <=858 MHz requests always take the original ACPM path.
 * This does NOT alter voltage or bypass critical thermal safety.
 * No new rate is active unless a manual Mali sysfs request occurs.
 */
#define APPLE_G3D_ACPM_ID 0x0b04000bu
#define APPLE_G3D_PLL_ID  0x03000002u

int cal_dfs_set_rate(unsigned int id, unsigned long rate)
{
	struct vclk *vclk;
	int ret;
	if (id == APPLE_G3D_ACPM_ID &&
	    (rate == 910000 || rate == 936000 ||
	     rate == 975000 || rate == 1001000)) {
		unsigned long measured;
		ret = ra_set_rate(APPLE_G3D_PLL_ID, (unsigned int)(rate * 1000));
		measured = ra_recalc_rate(APPLE_G3D_PLL_ID);
		if (ret || measured / 1000 != rate) {
			/* Fail closed: return to stock ACPM DVFS before the caller
			 * can assume a successful overclock.
			 */
			exynos_acpm_set_rate(GET_IDX(id), 858000);
			return ret ? ret : -EIO;
		}
		vclk = cmucal_get_node(id);
		if (vclk)
			vclk->vrate = (unsigned int)rate;
		return 0;
	}
""")

gpu = "drivers/gpu/arm/exynos/frontend/gpex_clock_sysfs.c"
replace_once(gpu, "#include <gpex_clboost.h>\n",
"""#include <gpex_clboost.h>
#include <gpexbe_clock.h>
#include <linux/delay.h>
""")
replace_once(gpu,
"""	if (clk == 0) {
		gpex_dvfs_enable();
	} else {
		gpex_dvfs_disable();
		gpex_clock_set(clk);
	}

	return count;
""",
"""	/* APPLE_GPUOC_EXPERIMENT: one-second manually triggered probe.
	 * The default governor & all ordinary clocks stay unchanged.
	 * A failed readback is an error, not a reported OC result.
	 * A new OPP needs a separate voltage/thermal validation.
	 */
	if (clk == 910000 || clk == 936000 ||
	    clk == 975000 || clk == 1001000) {
		int cur;
		if (!gpex_pm_get_status(true))
			return -EAGAIN;
		gpex_dvfs_disable();
		ret = gpexbe_clock_set_rate(clk);
		cur = gpexbe_clock_get_rate();
		if (!ret && cur != clk)
			ret = -EIO;
		if (!ret)
			msleep(1000);
		/* Always command known-stock clock and restore DVFS.
		 * Independent thermal and watchdog protections remain.
		 */
		gpexbe_clock_set_rate(858000);
		gpex_dvfs_enable();
		return ret ? ret : count;
	}

	if (clk == 0) {
		gpex_dvfs_enable();
	} else {
		gpex_dvfs_disable();
		gpex_clock_set(clk);
	}

	return count;
""")

print("GPUOC source application complete; boot-time overclock remains disabled.")
