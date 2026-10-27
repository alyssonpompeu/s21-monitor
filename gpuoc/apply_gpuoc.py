#!/usr/bin/env python3
"""APPLE FINAL GPUOC V2: firmware-validated ACPM/DT OPPs, no direct PLL writes.

The previous v1 used ra_set_rate(PLL_G3D) during active GPU work and rebooted
at every experimental speed. V2 strictly uses the original Mali/GPEX clock
path -> CAL -> ACPM and only when BOTH authoritative firmware FV/ASV and
the platform devicetree frequency/QoS table advertise the requested OPP.

Critical: the HZA6 baseline likely advertises 858 MHz as its maximum.
This patch DOES NOT manufacture a firmware OPP, and will correctly reject
910..1001 on a stock 858 MHz firmware. A stable OC cannot be guaranteed.
"""
from pathlib import Path

p=Path("src/drivers/gpu/arm/exynos/frontend/gpex_clock_sysfs.c")
s=p.read_text()
needle = "#include <gpex_clboost.h>\n"
extra = "#include <gpex_clboost.h>\n#include <gpexbe_clock.h>\n#include <linux/delay.h>\n"
if s.count(needle)!=1:
    raise SystemExit("Unexpected include block")
s=s.replace(needle,extra,1)

header="static struct _clock_info *clk_info;\n"
instrumentation=r"""static struct _clock_info *clk_info;

/*
 * APPLE FINAL GPUOC V2
 * Return true ONLY when the firmware advertises a voltage-backed OPP
 * and the Mali device-tree/QoS table includes exactly this clock.
 *
 * Never extrapolate a voltage or bypass ACPM to change PLL_G3D.
 */
static bool apple_gpuoc_acpm_valid_opp(int request)
{
    struct freq_volt entries[48];
    int n, got, i, stock_volt = 0, candidate_volt = 0;

    if (request <= 858000 || request > 1001000)
        return false;
    if (request != 910000 && request != 936000 &&
        request != 975000 && request != 1001000)
        return false;

    /* GPU driver must have DT QoS and PM parameters for this OPP. */
    if (!clk_info || request > clk_info->gpu_max_clock ||
        request > clk_info->gpu_max_clock_limit ||
        gpex_clock_get_table_idx(request) < 0)
        return false;

    n=gpexbe_clock_get_level_num();
    if (n <= 0 || n > ARRAY_SIZE(entries))
        return false;
    got=gpexbe_clock_get_rate_asv_table(entries,n);
    if (got != n)
        return false;
    for(i=0;i<n;i++) {
        if(entries[i].freq == 858000)
            stock_volt=entries[i].volt;
        if(entries[i].freq == request)
            candidate_volt=entries[i].volt;
    }
    /* No rail changes outside ACPM; reject missing/inconsistent voltages. */
    return stock_volt > 0 && candidate_volt >= stock_volt;
}

GPEX_STATIC ssize_t show_apple_gpuoc_caps(char *buf)
{
    int fw_max = gpexbe_clock_get_max_freq();
    int dt_max = clk_info ? clk_info->gpu_max_clock : 0;
    int o910=apple_gpuoc_acpm_valid_opp(910000);
    int o936=apple_gpuoc_acpm_valid_opp(936000);
    int o975=apple_gpuoc_acpm_valid_opp(975000);
    int o1001=apple_gpuoc_acpm_valid_opp(1001000);

    return scnprintf(buf,PAGE_SIZE,
        "mode=ACPM_ONLY\nfirmware_max_khz=%d\nplatform_dt_max_khz=%d\n"
        "fw_valid_910=%d\nfw_valid_936=%d\nfw_valid_975=%d\nfw_valid_1001=%d\n"
        "direct_pll_writes=DISABLED\n",
        fw_max,dt_max,o910,o936,o975,o1001);
}
CREATE_SYSFS_DEVICE_READ_FUNCTION(show_apple_gpuoc_caps)
"""
if s.count(header)!=1: raise SystemExit("Unexpected static context")
s=s.replace(header,instrumentation,1)
old=r"""	if (clk == 0) {
		gpex_dvfs_enable();
	} else {
		gpex_dvfs_disable();
		gpex_clock_set(clk);
	}

	return count;
"""
new=r"""	/*
	 * V1 direct PLL patch caused GPU reset/reboot at every clock.
	 * V2 makes the SAME clock request through the entire normal
	 * GPEX -> CAL -> ACPM path with firmware-owned voltage scaling.
	 *
	 * If the ACPM firmware / DT does not contain the OC OPP,
	 * DO NOT touch the clock, and report unsupported to userspace.
	 */
	if (clk > 858000) {
		if (!apple_gpuoc_acpm_valid_opp(clk)) {
			GPU_LOG(MALI_EXYNOS_WARNING,
				"APPLE_GPUOC_V2: %u kHz NOT in ACPM/DT OPP tables\n",clk);
			return -EOPNOTSUPP;
		}

		if (!gpex_pm_get_status(true))
			return -EAGAIN;

		ret = gpex_dvfs_disable();
		if (ret)
			return ret;
		ret = gpex_clock_set(clk);
		if (!ret && gpex_clock_get_clock_slow() != clk)
			ret = -EIO;

		/* Very short preflight pulse; NORMAL scheduler and DVFS restored. */
		if (!ret)
			msleep(100);

		gpex_dvfs_enable();

		/* Never advertise success if ACPM rejected/clamped request. */
		return ret ? ret : count;
	}

	if (clk == 0) {
		gpex_dvfs_enable();
	} else {
		gpex_dvfs_disable();
		gpex_clock_set(clk);
	}

	return count;
"""
if s.count(old)!=1:raise SystemExit("Unexpected sysfs set clock flow")
s=s.replace(old,new,1)
target="GPEX_UTILS_SYSFS_DEVICE_FILE_ADD(clock, show_clock, set_clock);\n"
if s.count(target)!=1:raise SystemExit("Unexpected sysfs registration")
s=s.replace(target,target+"\tGPEX_UTILS_SYSFS_DEVICE_FILE_ADD_RO(apple_gpuoc_caps,show_apple_gpuoc_caps);\n",1)
p.write_text(s)
print("APPLE GPUOC V2 patched; firmware/DT gating and ACPM route; no direct PLL programming")
