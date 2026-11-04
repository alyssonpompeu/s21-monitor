#!/usr/bin/env python3
"""Apple Final V8 — GPU exponential DVFS voltage-margin curve and DSU 2.002 GHz.

Runs AFTER the unchanged V7 MIF exponential 13-OPP patch.
GPU: remove legacy -7% static undervolt and set a real ACPM/CAL
G3D domain margin request per GPEX DVFS transition, in discrete
integer-percent steps following a normalized exp(-2*x) shape:
0% at the lowest CAL clock, -4% at the highest CAL clock.
This is NOT a PMIC measured voltage or direct FVMAP per-OPP write.
DSU: remove -6% domain undervolt and allow load-triggered 2002 MHz
only when the native CAL rate table AND ASV rate-voltage table both
include that frequency with positive voltage; verify CAL readback,
revert and block further boosts on mismatch.
DSU 2106/2210 are diagnostic only, not enabled.
"""
from pathlib import Path

def patch(path):
    p=Path(path)
    s=p.read_text()
    return p,s

def swap(s,a,b,tag):
    n=s.count(a)
    assert n==1,(tag,"expected 1 anchor, found",n,a[:130])
    return s.replace(a,b,1)

# GPU static -7 -> 0; preserve CPU and all MIF/INT margins.
p,s=patch("src/arch/arm64/configs/exynos2100-unified_defconfig")
s=swap(s,"CONFIG_SOC_EXYNOS2100_GPU_UV=7","CONFIG_SOC_EXYNOS2100_GPU_UV=0","GPU UV defconfig")
p.write_text(s)

# DSU -6% -> 0%, because a higher DSU clock should not inherit the
# unvalidated old voltage reduction. Runtime dsu_margin_percent remains.
p,s=patch("src/drivers/soc/samsung/cal-if/fvmap.c")
s=swap(s,"static int margin_dsu = -6;","static int margin_dsu = 0;","DSU boot margin")
p.write_text(s)

p,s=patch("src/drivers/gpu/arm/exynos/backend/gpexbe_clock.c")
s=swap(s,"#include <linux/export.h>",
"""#include <linux/export.h>
#include <linux/module.h>
#include <linux/kernel.h>""","GPEX headers")
anchor="static unsigned int cal_id;"
gpu_impl=r'''
/* APPLE FINAL V8: GPU exponential margin implemented as real CAL
 * G3D domain margin requests on the GPU DVFS transition path.
 * It is an integer-percent approximation of:
 *  -4 * (1 - exp(-2*x))/(1 - exp(-2)), x=(f-fmin)/(fmax-fmin).
 * Higher GPU clocks therefore receive UP TO -4%; low clocks receive 0%.
 * Driver/FVMAP voltage readback does not measure physical rail voltage.
 * Firmware margin control is global, so this is transition-synchronized
 * domain-level voltage management, not permanent per-OPP ASV edits.
 */
static bool apple_v8_gpu_exp4_enabled = true;
module_param_named(apple_v8_gpu_exp4_enabled, apple_v8_gpu_exp4_enabled,
		   bool, 0644);
static int apple_v8_gpu_applied_margin;
module_param_named(apple_v8_gpu_applied_margin, apple_v8_gpu_applied_margin,
		   int, 0444);
static unsigned int apple_v8_gpu_min_mhz, apple_v8_gpu_max_mhz;
module_param_named(apple_v8_gpu_min_mhz, apple_v8_gpu_min_mhz, uint, 0444);
module_param_named(apple_v8_gpu_max_mhz, apple_v8_gpu_max_mhz, uint, 0444);

static int apple_v8_gpu_exp_margin(int clock_mhz)
{
	/* exp(-2*x), x=0/16...16/16, quantized to 1/1000.
	 * No floating point is used inside the kernel.
	 */
	static const unsigned short exp2_q1000[17] = {
		1000, 882, 779, 687, 607, 535, 472, 417, 368,
		325, 287, 253, 223, 197, 174, 153, 135
	};
	unsigned int pos;
	unsigned int delta;

	if (!apple_v8_gpu_exp4_enabled ||
	    apple_v8_gpu_max_mhz <= apple_v8_gpu_min_mhz)
		return 0;
	if (clock_mhz <= apple_v8_gpu_min_mhz)
		return 0;
	if (clock_mhz >= apple_v8_gpu_max_mhz)
		return -4;

	delta = (unsigned int)clock_mhz - apple_v8_gpu_min_mhz;
	pos = (delta * 16U +
	      (apple_v8_gpu_max_mhz - apple_v8_gpu_min_mhz) / 2U) /
	      (apple_v8_gpu_max_mhz - apple_v8_gpu_min_mhz);
	pos = min_t(unsigned int, pos, 16U);
	return -(int)((4U * (1000U - exp2_q1000[pos]) + 432U) / 865U);
}
'''
s=swap(s,anchor,anchor+"\n"+gpu_impl,"GPEX exp impl")
anchor="""\tret = cal_dfs_set_rate(cal_id, clk);\n\n\tgpex_debug_record_time(HIST_CLOCK);"""
replacement=r'''	/* On a higher-voltage target first relax UV, then raise clock.
	 * When new voltage is lower, reduce clock BEFORE applying UV.
	 * If clock fails, restore the previous G3D margin.
	 */
	{
		int new_margin = apple_v8_gpu_exp_margin(clk);
		int old_margin = apple_v8_gpu_applied_margin;

		if (new_margin > old_margin)
			cal_dfs_set_volt_margin(cal_id, new_margin);

		ret = cal_dfs_set_rate(cal_id, clk);
		if (ret) {
			if (new_margin > old_margin)
				cal_dfs_set_volt_margin(cal_id, old_margin);
		} else {
			if (new_margin < old_margin)
				cal_dfs_set_volt_margin(cal_id, new_margin);
			apple_v8_gpu_applied_margin = new_margin;
		}
	}

	gpex_debug_record_time(HIST_CLOCK);'''
s=swap(s,anchor,replacement,"GPEX transition")
anchor="""\tcal_id = gpexbe_devicetree_get_int(g3d_cmu_cal_id);\n\n\tif (!cal_id) {"""
replacement=r'''	cal_id = gpexbe_devicetree_get_int(g3d_cmu_cal_id);

	/* Read native CAL GPU clocks once to normalize the exponential
	 * curve; do not invent a GPU clock or ASV voltage.
	 */
	apple_v8_gpu_min_mhz = 0;
	apple_v8_gpu_max_mhz = 0;
	apple_v8_gpu_applied_margin = 0;

	if (!cal_id) {'''
s=swap(s,anchor,replacement,"GPEX init start")
anchor="""\tpm_info.boot_clock = cal_dfs_get_boot_freq(cal_id);"""
replacement=r'''	{
		unsigned long freq_table[48];
		int i, n = cal_dfs_get_lv_num(cal_id);

		if (n > 0 && n <= ARRAY_SIZE(freq_table) &&
		    cal_dfs_get_rate_table(cal_id, freq_table) == n) {
			apple_v8_gpu_min_mhz = freq_table[0];
			apple_v8_gpu_max_mhz = freq_table[0];
			for (i = 1; i < n; i++) {
				apple_v8_gpu_min_mhz =
					min_t(unsigned int, apple_v8_gpu_min_mhz,
					      freq_table[i]);
				apple_v8_gpu_max_mhz =
					max_t(unsigned int, apple_v8_gpu_max_mhz,
					      freq_table[i]);
			}
		} else {
			pr_warn("apple_gpu_v8: CAL GPU clock table unavailable; no undervolt\n");
			apple_v8_gpu_exp4_enabled = false;
		}
	}
	pr_info("apple_gpu_v8: exponential G3D -0..-4%% per DVFS transition CAL range %u..%u MHz\n",
		apple_v8_gpu_min_mhz, apple_v8_gpu_max_mhz);

	pm_info.boot_clock = cal_dfs_get_boot_freq(cal_id);'''
s=swap(s,anchor,replacement,"GPEX CAL table init")
p.write_text(s)

# DSU: do not repeat the old 2002 driver patch, which set cur_freq to a
# number that could be missing from stats->freq_table (last_index=-1).
p,s=patch("src/drivers/cpufreq/exynos-dsufreq.c")
anchor="static struct dsufreq_domain dsufreq;"
dsu_impl=r'''
/* APPLE FINAL V8: DSU 2002 is gated by BOTH native CAL/ASV tables,
 * and the 2002 rate must also be present in the DSU stats list.
 * No arbitrary PLL write or fabrication of a firmware voltage OPP.
 */
#define APPLE_DSU_V8_TARGET_KHZ 2002000U
static bool apple_dsu_v8_support_2002;
static bool apple_dsu_v8_rejected;
static bool apple_dsu_v8_boost_enabled = true;
module_param_named(apple_dsu_v8_boost_enabled, apple_dsu_v8_boost_enabled,
		   bool, 0644);
static unsigned int apple_dsu_v8_stock_max;
static unsigned int apple_dsu_v8_voltage_uv;

static bool apple_dsu_v8_has_native_2002(unsigned int cal_id)
{
	unsigned long freqs[48];
	struct dvfs_rate_volt opps[48];
	int n, nr, nv, i;
	bool in_rate = false;

	n = cal_dfs_get_lv_num(cal_id);
	if (n <= 0 || n > ARRAY_SIZE(freqs))
		return false;
	nr = cal_dfs_get_rate_table(cal_id, freqs);
	nv = cal_dfs_get_rate_asv_table(cal_id, opps);
	if (nr <= 0 || nv <= 0 || nr != nv || nr > n)
		return false;

	for (i = 0; i < nr; i++) {
		if (freqs[i] == APPLE_DSU_V8_TARGET_KHZ)
			in_rate = true;
		if (opps[i].rate == APPLE_DSU_V8_TARGET_KHZ &&
		    opps[i].volt) {
			apple_dsu_v8_voltage_uv = opps[i].volt;
		}
	}
	return in_rate && apple_dsu_v8_voltage_uv > 0;
}
'''
s=swap(s,anchor,anchor+"\n"+dsu_impl,"DSU helper")
anchor="""\tstats->time_in_state[stats->last_index] += cur_time - stats->last_time;"""
replacement="""\tif (stats->last_index < stats->table_size)
\t\tstats->time_in_state[stats->last_index] += cur_time - stats->last_time;"""
s=swap(s,anchor,replacement,"DSU TIS guard")

anchor="""\ttarget_freq = resolve_dsufreq(target_freq);\n\tif (!target_freq)\n\t\treturn ret;"""
replacement=r'''	target_freq = resolve_dsufreq(target_freq);
	/* Only boost when CPU requests the original DSU ceiling; this
	 * remains workload-driven and honors runtime max_freq throttles.
	 */
	if (apple_dsu_v8_support_2002 && apple_dsu_v8_boost_enabled &&
	    !apple_dsu_v8_rejected &&
	    dsufreq.max_freq >= APPLE_DSU_V8_TARGET_KHZ &&
	    target_freq == apple_dsu_v8_stock_max)
		target_freq = APPLE_DSU_V8_TARGET_KHZ;
	if (!target_freq)
		return ret;'''
s=swap(s,anchor,replacement,"DSU boost trigger")
anchor="""\tret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);\n\tif (ret)\n\t\tgoto out;\n\n\tupdate_dsufreq_time_in_state(dsufreq.stats);"""
replacement=r'''	ret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);
	if (target_freq == APPLE_DSU_V8_TARGET_KHZ &&
	    apple_dsu_v8_support_2002) {
		unsigned long reported = ret ? 0 :
			cal_dfs_get_rate(dsufreq.cal_id);
		if (ret || reported != target_freq) {
			/* Firmware refused the rate or did not read it back.
			 * Disable the experiment for this boot, restore stock.
			 */
			apple_dsu_v8_rejected = true;
			pr_warn("apple_dsu_v8: 2002 rejected (%d, CAL=%lu); reverting to %u\n",
				ret, reported, apple_dsu_v8_stock_max);
			target_freq = apple_dsu_v8_stock_max;
			ret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);
		} else {
			pr_info_ratelimited("apple_dsu_v8: CAL acknowledged 2002 MHz, PHY not independently verified\n");
		}
	}
	if (ret)
		goto out;

	update_dsufreq_time_in_state(dsufreq.stats);'''
s=swap(s,anchor,replacement,"DSU guarded CAL transition")
anchor="""\tif (!of_property_read_u32(dn, "min-freq", &val))
\t\tdsufreq.min_freq = dsufreq.min_freq_orig = max(dsufreq.min_freq, val);

\tdsufreq.cur_freq = cal_dfs_get_rate(dsufreq.cal_id);"""
replacement=r'''	if (!of_property_read_u32(dn, "min-freq", &val))
		dsufreq.min_freq = dsufreq.min_freq_orig = max(dsufreq.min_freq, val);

	apple_dsu_v8_stock_max = dsufreq.max_freq;
	apple_dsu_v8_support_2002 = apple_dsu_v8_has_native_2002(dsufreq.cal_id);
	if (apple_dsu_v8_support_2002 &&
	    apple_dsu_v8_stock_max >= 1898000U &&
	    apple_dsu_v8_stock_max < APPLE_DSU_V8_TARGET_KHZ) {
		dusufreq.max_freq = APPLE_DSU_V8_TARGET_KHZ;
		dusufreq.max_freq_orig = APPLE_DSU_V8_TARGET_KHZ;
		pr_info("apple_dsu_v8: enabled native CAL/ASV 2002 MHz, ASV=%u uV; DSU voltage margin 0%%\n",
			apple_dsu_v8_voltage_uv);
	} else {
		pr_info("apple_dsu_v8: native 2002 support=%d; stock cap %u retained\n",
			apple_dsu_v8_support_2002, dsufreq.max_freq);
	}

	dusufreq.cur_freq = cal_dfs_get_rate(dsufreq.cal_id);'''
s=swap(s,anchor,replacement,"DSU native OPP check")

anchor="""static DEVICE_ATTR(total_trans, S_IRUGO, dsufreq_show_total_trans, NULL);"""
replacement=r'''
/* V8 read-only DSU CAL/ASV telemetry for on-device verification. */
static ssize_t apple_v8_dsu_show(struct device *dev,
				 struct device_attribute *attr, char *buf)
{
	struct dvfs_rate_volt opps[48];
	ssize_t len = 0;
	int i, count = cal_dfs_get_rate_asv_table(dsufreq.cal_id, opps);

	len += scnprintf(buf + len, PAGE_SIZE - len,
		"apple_dsu_v8=1\n"
		"native_2002_available=%d\n"
		"boost_enabled=%d\n"
		"rejected=%d\n"
		"stock_max_khz=%u\n"
		"runtime_max_khz=%u\n"
		"target_khz=%u\n"
		"cur_driver_khz=%u\n"
		"cal_reported_khz=%lu\n"
		"asv_2002_uv=%u\n"
		"boot_dsu_margin_percent=0\n"
		"asv_voltages_not_measured=1\n",
		apple_dsu_v8_support_2002, apple_dsu_v8_boost_enabled,
		apple_dsu_v8_rejected, apple_dsu_v8_stock_max,
		dusufreq.max_freq, dsufreq.target_freq, dsufreq.cur_freq,
		cal_dfs_get_rate(dsufreq.cal_id), apple_dsu_v8_voltage_uv);
	if (count <= 0 || count > ARRAY_SIZE(opps))
		return len;
	for (i = 0; i < count && len < PAGE_SIZE - 64; i++)
		len += scnprintf(buf + len, PAGE_SIZE - len,
			"asv_rate_khz=%lu asv_uv=%u\n",
			opps[i].rate, opps[i].volt);
	return len;
}
static DEVICE_ATTR_RO(apple_v8_dsu);
static DEVICE_ATTR(total_trans, S_IRUGO, dsufreq_show_total_trans, NULL);'''
s=swap(s,anchor,replacement,"DSU readback")
anchor="""\t&dev_attr_total_trans.attr,
\tNULL,"""
replacement="""\t&dev_attr_total_trans.attr,
\t&dev_attr_apple_v8_dsu.attr,
\tNULL,"""
s=swap(s,anchor,replacement,"DSU sysfs group")
p.write_text(s)
print("Apple Final V8: GPU dynamic exponential 0..-4% CAL G3D margin; DSU native 2002 conditional CAL/ASV OPP & readback; 0% DSU margin; V7 MIF intact.")
