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
    if (!clk_info || request > clk_info->gpu_unlock_max_clock ||
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
    int unlocked_max = clk_info ? clk_info->gpu_unlock_max_clock : 0;
    int o910=apple_gpuoc_acpm_valid_opp(910000);
    int o936=apple_gpuoc_acpm_valid_opp(936000);
    int o975=apple_gpuoc_acpm_valid_opp(975000);
    int o1001=apple_gpuoc_acpm_valid_opp(1001000);

    return scnprintf(buf,PAGE_SIZE,
        "mode=ACPM_ONLY\nfirmware_max_khz=%d\nplatform_dt_max_khz=%d\nunlock_max_khz=%d\n"
        "fw_valid_910=%d\nfw_valid_936=%d\nfw_valid_975=%d\nfw_valid_1001=%d\n"
        "direct_pll_writes=DISABLED\n",
        fw_max,dt_max,unlocked_max,o910,o936,o975,o1001);
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

# Fix a latent Samsung GPU clock reporting issue: the stock frontend
# discards the ACPM/CAL return code and reports success in some paths.
# This is important for correct failure handling even at supported OPPs.
front=Path("src/drivers/gpu/arm/exynos/frontend/gpex_clock.c")
src=front.read_text()
steps=[
    ("	gpexbe_clock_set_rate(clk);\n\n	gpexbe_debug_dbg_snapshot_freq_out(",
     "	ret = gpexbe_clock_set_rate(clk);\n	if (ret)\n		goto err;\n\n	gpexbe_debug_dbg_snapshot_freq_out("),
    ("	set_clock_using_calapi(clock);\n\n	if (!is_up)",
     "	ret = set_clock_using_calapi(clock);\n	if (ret)\n		return ret;\n\n	if (!is_up)"),
    ("	gpex_clock_set_helper(target_clk);\n\n	ret = gpex_dvfs_set_clock_callback();",
     "	ret = gpex_clock_set_helper(target_clk);\n	if (ret) {\n		mutex_unlock(&clk_info.clock_lock);\n		return ret;\n	}\n\n	ret = gpex_dvfs_set_clock_callback();"),
]
for a,b in steps:
    if src.count(a)!=1:
        raise SystemExit("Clock error-path patch source mismatch "+repr(a[:60]))
    src=src.replace(a,b,1)
front.write_text(src)
print("APPLE GPUOC V2: all clock setting errors now propagate to callers")


# FloppyKernel-inspired separation of stock GPU max and unlock GPU max.
# Unlike the original V1 hack, candidates must be present in BOTH ASV and DT.
internal=Path("src/drivers/gpu/arm/exynos/frontend/gpex_clock_internal.h")
iv=internal.read_text()
old="	int gpu_max_clock;\n"
new="	int gpu_max_clock;\n	int gpu_stock_max_clock;\n	int gpu_unlock_max_clock;\n"
if iv.count(old)!=1: raise SystemExit("Floppy-inspired struct anchor missing")
internal.write_text(iv.replace(old,new,1))

front=Path("src/drivers/gpu/arm/exynos/frontend/gpex_clock.c")
fc=front.read_text()
old="	clk_info.gpu_max_clock = gpexbe_devicetree_get_int(gpu_max_clock);\n"
new="""	clk_info.gpu_max_clock = gpexbe_devicetree_get_int(gpu_max_clock);
	clk_info.gpu_stock_max_clock = clk_info.gpu_max_clock;
	clk_info.gpu_unlock_max_clock = clk_info.gpu_stock_max_clock;
"""
if fc.count(old)!=1:raise SystemExit("Max initialization missing")
fc=fc.replace(old,new,1)
old="if (cal_freq <= clk_info.gpu_max_clock && cal_freq >= clk_info.gpu_min_clock)"
new="if (cal_freq <= clk_info.gpu_max_clock_limit && cal_freq >= clk_info.gpu_min_clock)"
if fc.count(old)!=1:raise SystemExit("ASV max guard missing")
fc=fc.replace(old,new,1)
old="					clk_info.table[j].voltage = cal_vol;\n"
new="""					clk_info.table[j].voltage = cal_vol;
					/* Limit unlock to actual CAL/ASV+DT OPPs. */
					if (cal_vol > 0 &&
					    cal_freq > clk_info.gpu_unlock_max_clock)
						clk_info.gpu_unlock_max_clock = cal_freq;
"""
if fc.count(old)!=1:raise SystemExit("GPU DT/ASV frequency-matching block missing")
fc=fc.replace(old,new,1)
needle="int gpex_clock_prepare_runtime_off()\n"
method="""/*
 * Floppy-style runtime cap selector, without altering the ACPM OPP table.
 * All thermal/user QoS caps continue to apply independently.
 */
int gpex_clock_set_runtime_max_clock(int rate)
{
	int ret = 0;

	if (rate != clk_info.gpu_stock_max_clock &&
	    rate != clk_info.gpu_unlock_max_clock)
		return -EINVAL;
	if (gpex_clock_get_table_idx(rate) < 0)
		return -EOPNOTSUPP;
	mutex_lock(&clk_info.clock_lock);
	clk_info.gpu_max_clock = rate;
	mutex_unlock(&clk_info.clock_lock);
	return ret;
}

"""
if fc.count(needle)!=1:raise SystemExit("Runtime cap insertion missing")
fc=fc.replace(needle,method+needle,1)
front.write_text(fc)
h=Path("src/drivers/gpu/arm/exynos/include/gpex_clock.h")
hs=h.read_text()
needle="int gpex_clock_set(int clk);\n"
if hs.count(needle)!=1:raise SystemExit("Clock API insertion missing")
hs=hs.replace(needle,needle+"int gpex_clock_set_runtime_max_clock(int rate);\n",1)
h.write_text(hs)

gp=Path("src/drivers/gpu/arm/exynos/frontend/gpex_clock_sysfs.c")
gs=gp.read_text()
needle="CREATE_SYSFS_DEVICE_READ_FUNCTION(show_apple_gpuoc_caps)\n"
extra=r"""CREATE_SYSFS_DEVICE_READ_FUNCTION(show_apple_gpuoc_caps)

/* Runtime governor cap toggle. It never creates a new frequency/voltage OPP. */
GPEX_STATIC ssize_t show_apple_gpu_unlock(char *buf)
{
    return scnprintf(buf,PAGE_SIZE,"%d\n",
        clk_info &&
        clk_info->gpu_max_clock > clk_info->gpu_stock_max_clock);
}
CREATE_SYSFS_DEVICE_READ_FUNCTION(show_apple_gpu_unlock)

GPEX_STATIC ssize_t set_apple_gpu_unlock(const char *buf,size_t count)
{
    int enable, max_khz, ret;
    ret = kstrtoint(buf,0,&enable);
    if (ret || (enable != 0 && enable != 1))
        return -EINVAL;
    if (!clk_info)
        return -ENODEV;
    max_khz = enable ? clk_info->gpu_unlock_max_clock :
                       clk_info->gpu_stock_max_clock;
    ret = gpex_clock_set_runtime_max_clock(max_khz);
    return ret ? ret : count;
}
CREATE_SYSFS_DEVICE_WRITE_FUNCTION(set_apple_gpu_unlock)
"""
if gs.count(needle)!=1:raise SystemExit("Sysfs hook anchor missing")
gs=gs.replace(needle,extra,1)
needle="	GPEX_UTILS_SYSFS_DEVICE_FILE_ADD_RO(apple_gpuoc_caps,show_apple_gpuoc_caps);\n"
if gs.count(needle)!=1:raise SystemExit("Caps sysfs register missing")
gs=gs.replace(needle,needle+"	GPEX_UTILS_SYSFS_DEVICE_FILE_ADD(apple_gpu_unlock, show_apple_gpu_unlock, set_apple_gpu_unlock);\n",1)
gp.write_text(gs)
print("Floppy-style GPU runtime cap unlock added; strict CAL/ASV+DT+ACPM validation remains.")

print("APPLE GPUOC V3 patched; native ACPM with Floppy-style runtime GPU unlock")
