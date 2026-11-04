#!/usr/bin/env python3
"""Apple Final V8: DSU exact 2392MHz + GPU exponential UV up to -4%."""
from pathlib import Path

def sub(path,a,b):
    p=Path(path); s=p.read_text()
    assert s.count(a)==1,(path,a[:95],s.count(a))
    p.write_text(s.replace(a,b,1))

sub("src/arch/arm64/configs/exynos2100-unified_defconfig",
    "CONFIG_SOC_EXYNOS2100_GPU_UV=7\n","CONFIG_SOC_EXYNOS2100_GPU_UV=4\n")
sub("src/drivers/soc/samsung/cal-if/exynos2100/cmucal-node.c",
    "struct cmucal_pll_table pll_dsu_rate_table[] = {\n\tPLL_RATE_MPS(2210000000, 85, 1, 0),",
    "struct cmucal_pll_table pll_dsu_rate_table[] = {\n\t/* APPLE FINAL V8: PLL DSU 2392MHz; 26MHz * 92 / 1 */\n\tPLL_RATE_MPS(2392000000, 92, 1, 0),\n\tPLL_RATE_MPS(2210000000, 85, 1, 0),")

gpu="src/drivers/gpu/arm/exynos/backend/gpexbe_clock.c"
sub(gpu,"static unsigned int cal_id;\n",r'''static unsigned int cal_id;
/* APPLE FINAL V8: GPU exponential voltage-margin profile.
 * Percentage discount 4*exp(-ln4*x), where x=(f-fmin)/(949-fmin).
 * Q12 fixed point: exp(-z) ~= (1-z/8)^8, varying from -4 to -1.
 * Firmware accepts global margin per rate; not a per-OPP SRAM write.
 */
static int apple_gpu_v8_margin(int clk)
{
	unsigned int low = cal_dfs_get_min_freq(cal_id);
	unsigned int factor = 4096, term;
	int i, pct;

	if (!low || low >= 949)
		return -4;
	clk = clamp_val(clk, (int)low, 949);
	term = 4096 - (710U * (clk - low))/(949 - low);
	for (i = 0; i < 8; i++)
		factor = (factor * term + 2048) / 4096;
	pct = (4 * factor + 2048) / 4096;
	return -clamp_val(pct, 1, 4);
}
static int apple_gpu_v8_last_margin = -4;
''')
sub(gpu,r'''int gpexbe_clock_set_rate(int clk)
{
	int ret = 0;

	gpex_debug_new_record(HIST_CLOCK);
	gpex_debug_record_prev_data(HIST_CLOCK, gpexbe_clock_get_rate());

	ret = cal_dfs_set_rate(cal_id, clk);

	gpex_debug_record_time(HIST_CLOCK);''',r'''int gpexbe_clock_set_rate(int clk)
{
	int ret = 0;
	int previous = gpexbe_clock_get_rate();
	int old_margin = apple_gpu_v8_last_margin;
	int next_margin = apple_gpu_v8_margin(clk);

	gpex_debug_new_record(HIST_CLOCK);
	gpex_debug_record_prev_data(HIST_CLOCK, previous);

	/* Before frequency rise: relax UV. After frequency fall: increase UV.
	 * Roll back margin after failed upward CAL rate request.
	 */
	if (clk > previous && next_margin != old_margin) {
		cal_dfs_set_volt_margin(cal_id, next_margin);
		apple_gpu_v8_last_margin = next_margin;
	}
	ret = cal_dfs_set_rate(cal_id, clk);
	if (ret && clk > previous && next_margin != old_margin) {
		cal_dfs_set_volt_margin(cal_id, old_margin);
		apple_gpu_v8_last_margin = old_margin;
	}
	if (!ret && clk <= previous && next_margin != old_margin) {
		cal_dfs_set_volt_margin(cal_id, next_margin);
		apple_gpu_v8_last_margin = next_margin;
	}

	gpex_debug_record_time(HIST_CLOCK);''')

dsu="src/drivers/cpufreq/exynos-dsufreq.c"
sub(dsu,"static struct dsufreq_domain dsufreq;",r'''static struct dsufreq_domain dsufreq;
/* APPLE FINAL V8 DSU2392. Stock fallback on CAL reject/readback mismatch. */
#define APPLE_DSU_V8_MAX 2392000U
static unsigned int apple_dsu_v8_native_max;
static bool apple_dsu_v8_rejected;
static unsigned int apple_dsu_v8_ack;
static unsigned int apple_dsu_v8_fail;
static unsigned int apple_dsu_v8_readback;
''')
sub(dsu,r'''	target_freq = resolve_dsufreq(target_freq);
	if (!target_freq)
		return ret;

	if (dsufreq.cur_freq == target_freq)''',r'''	target_freq = resolve_dsufreq(target_freq);
	if (!target_freq)
		return ret;

	/* Let high native requests reach 2392 as long as current QoS allows.
	 * No forced startup frequency; rejection blocks further OC attempts.
	 */
	if (!apple_dsu_v8_rejected && apple_dsu_v8_native_max &&
	    dsufreq.max_freq >= APPLE_DSU_V8_MAX &&
	    target_freq >= apple_dsu_v8_native_max)
		target_freq = APPLE_DSU_V8_MAX;

	if (dsufreq.cur_freq == target_freq)''')
sub(dsu,r'''	ret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);
	if (ret)
		goto out;

	update_dsufreq_time_in_state(dsufreq.stats);''',r'''	ret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);
	if (target_freq == APPLE_DSU_V8_MAX) {
		unsigned int ack = ret ? 0 : cal_dfs_get_rate(dsufreq.cal_id);
		apple_dsu_v8_readback = ack;
		if (ret || ack != APPLE_DSU_V8_MAX) {
			apple_dsu_v8_fail++;
			apple_dsu_v8_rejected = true;
			pr_warn("apple_dsu_v8: requested 2392000, CAL=%u err=%d; fallback to %u\n",
				ack, ret, apple_dsu_v8_native_max);
			target_freq = apple_dsu_v8_native_max;
			ret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);
		} else {
			apple_dsu_v8_ack++;
			pr_info_ratelimited("apple_dsu_v8: CAL acknowledged 2392000 kHz; PLL unverified\n");
		}
	}
	if (ret)
		goto out;

	update_dsufreq_time_in_state(dsufreq.stats);''')
sub(dsu,r'''	if (!of_property_read_u32(dn, "min-freq", &val))
		dsufreq.min_freq = dsufreq.min_freq_orig = max(dsufreq.min_freq, val);

	dsufreq.cur_freq = cal_dfs_get_rate(dsufreq.cal_id);''',r'''	if (!of_property_read_u32(dn, "min-freq", &val))
		dsufreq.min_freq = dsufreq.min_freq_orig = max(dsufreq.min_freq, val);

	apple_dsu_v8_native_max = dsufreq.max_freq;
	if (apple_dsu_v8_native_max &&
	    apple_dsu_v8_native_max < APPLE_DSU_V8_MAX) {
		dsufreq.max_freq = APPLE_DSU_V8_MAX;
		dsufreq.max_freq_orig = APPLE_DSU_V8_MAX;
		pr_info("apple_dsu_v8: original max %u -> experimental 2392000 kHz\n",
			apple_dsu_v8_native_max);
	} else {
		apple_dsu_v8_native_max = 0;
	}

	dsufreq.cur_freq = cal_dfs_get_rate(dsufreq.cal_id);''')
sub(dsu,r'''	if (init_with_dt)
		dsufreq_init_freq_table_dt(stats);
	else
		dsufreq_init_freq_table_ect(stats);

	/* Initialize DSUFreq time_in_state */''',r'''	if (init_with_dt)
		dsufreq_init_freq_table_dt(stats);
	else
		dsufreq_init_freq_table_ect(stats);

	/* Add DSU 2392 in descending-order table, and allocate tracking slot
	 * before time_in_state and last_index calculations.
	 */
	if (apple_dsu_v8_native_max) {
		unsigned long *new_table;
		unsigned int n = stats->table_size;
		if (!n || !stats->freq_table)
			return -EINVAL;
		new_table = krealloc(stats->freq_table,
				    (n + 1) * sizeof(*new_table),
				    GFP_KERNEL);
		if (!new_table)
			return -ENOMEM;
		memmove(new_table + 1, new_table, n * sizeof(*new_table));
		new_table[0] = APPLE_DSU_V8_MAX;
		stats->freq_table = new_table;
		stats->table_size = n + 1;
	}

	/* Initialize DSUFreq time_in_state */''')
sub(dsu,r'''static DEVICE_ATTR(total_trans, S_IRUGO,
		dsufreq_show_total_trans, NULL);''',r'''static DEVICE_ATTR(total_trans, S_IRUGO,
		dsufreq_show_total_trans, NULL);

/* Diagnostic state from driver; CAL get-rate is not physical PLL proof. */
static ssize_t apple_dsu_v8_2392_show(struct device *dev,
		struct device_attribute *attr, char *buf)
{
	return scnprintf(buf, PAGE_SIZE,
		"apple_dsu_v8=1\nrequested_khz=2392000\n"
		"stock_max_khz=%u\nactive_max_khz=%u\n"
		"current_khz=%u\nlast_cal_khz=%u\n"
		"ack_count=%u\nfail_count=%u\nrejected=%u\n",
		apple_dsu_v8_native_max, dsufreq.max_freq,
		dsufreq.cur_freq, apple_dsu_v8_readback,
		apple_dsu_v8_ack, apple_dsu_v8_fail,
		apple_dsu_v8_rejected);
}
static DEVICE_ATTR(apple_dsu_v8_2392, S_IRUGO,
		apple_dsu_v8_2392_show, NULL);''')
sub(dsu,r'''	&dev_attr_total_trans.attr,
	NULL,''',r'''	&dev_attr_total_trans.attr,
	&dev_attr_apple_dsu_v8_2392.attr,
	NULL,''')
print("Apple Final V8: DSU exact 2392 PLL+CAL, GPU exponential max -4% ACPM margin, V7 MIF untouched.")
