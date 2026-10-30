#!/usr/bin/env python3
"""Apple Final V5: integrate the 13-point geometric MIF candidate curve.

Keep all Samsung ECT/boot/resume frequencies as internal safety anchors.
Only 12 distinct experimental candidates are added; 421 MHz is already
in the stock ECT. Candidate CAL/ASV figures are metadata, NOT DRAM rails.
ACPM ultimately chooses physical frequency. Guard every novel attempt by
FREQ_GET and revert to the previously known rate if not acknowledged.
"""
from pathlib import Path
import re

p = Path("src/drivers/devfreq/exynos/exynos-devfreq.c")
s = p.read_text()

def sub(a, b):
    global s
    n = s.count(a)
    if n == 0:
        def to_tabs(text):
            return re.sub(r"(?m)^( {4})+", lambda m: "\t" * (len(m.group(0)) // 4), text)
        a, b = to_tabs(a), to_tabs(b)
        n = s.count(a)
    assert n == 1, ("Patch anchor mismatch", a[:110], n)
    s = s.replace(a, b, 1)

# Define the complete 13-level research table, usable by the kernel itself.
header = r'''
/* APPLE FINAL V5: 13-point GEOMETRIC candidate curve.
 * Stock ECT frequencies remain registered as fallback for boot/resume
 * and ACPM's firmware-managed FVP constraints. These figures are
 * CAL/ASV estimates; NOT measured physical DRAM VDD2 or VDDQ.
 */
static const u32 apple_mif_v5_freq_khz[13] = {
    3672000, 3066000, 2559000, 2137000, 1784000,
    1489000, 1243000, 1038000, 867000, 723000,
    604000, 504000, 421000
};
static const u32 apple_mif_v5_cal_est_uv[13] = {
    800000, 768750, 743750, 700000, 656250,
    618750, 587500, 550000, 537500, 531250,
    518750, 512500, 506250
};
static bool apple_mif_v5_rejected[12];
static int apple_mif_v5_candidate_idx(u32 freq)
{
    int i;

    /* 421 MHz is stock and must always retain stock transition logic. */
    for (i = 0; i < ARRAY_SIZE(apple_mif_v5_freq_khz) - 1; i++)
        if (apple_mif_v5_freq_khz[i] == freq)
            return i;
    return -1;
}
'''
sub("/* APPLE FINAL MIF LAB:", header+"\n/* APPLE FINAL MIF LAB:")

start = """\t/* APPLE FINAL V4 EXPERIMENTAL MIF 3672: extend the Linux DVFS"""
end = """\treturn 0;
}"""
i = s.find(start)
assert i > 0, "Cannot find V4 injection"
j = s.find(end, i)
assert j > i, "Cannot find V4 parse_ect function tail"
v4 = s[i:j]
v5 = r'''	/* APPLE FINAL V5: append the 12 novel geometric OPPs, retaining
	 * all 13 ECT entries unmodified in front. This prevents mismatches
	 * for Samsung boot/suspend/reboot and original ACPM FVP tables.
	 * The geometric curve contains 13 points including stock 421 MHz.
	 */
	if (data->devfreq_type == DEVFREQ_MIF) {
		struct exynos_devfreq_opp_table *ext;
		unsigned int n = data->max_state;

		ext = krealloc(data->opp_list,
			       sizeof(*ext) * (n + ARRAY_SIZE(apple_mif_v5_freq_khz) - 1),
			       GFP_KERNEL);
		if (!ext)
			return -ENOMEM;
		data->opp_list = ext;
		for (i = 0; i < ARRAY_SIZE(apple_mif_v5_freq_khz) - 1; i++) {
			ext[n + i].idx = n + i;
			ext[n + i].freq = apple_mif_v5_freq_khz[i];
			ext[n + i].volt = apple_mif_v5_cal_est_uv[i];
		}
		data->max_state = n + ARRAY_SIZE(apple_mif_v5_freq_khz) - 1;
		pr_warn("apple_mif_v5: 13 geometric candidate points; stock ECT anchors retained; ACPM support unknown\n");
	}

'''
s = s[:i] + v5 + s[j:]

# The firmware FVP constraint table must only contain original ECT OPPs.
# V5 candidates are NOT silently pushed as fictitious native firmware rows.
anchor = "static int exynos_devfreq_update_fvp(struct exynos_devfreq_data *data, u32 min_freq, u32 max_freq)"
i = s.find(anchor)
j = s.find("static int exynos_devfreq_reboot(", i)
assert 0 < i < j, "FVP function scope changed"
section = s[i:j]
a = "i < data->max_state; i++"
n = section.count(a)
assert n == 2, ("Expected two FVP state loops", n)
section = section.replace(a, "i < data->max_state - (data->devfreq_type == DEVFREQ_MIF ? 12 : 0); i++")
s = s[:i] + section + s[j:]

# V4's ACPM readback path was restricted to 3672. Expand it to all V5
# experimental points; do not claim FREQ_GET proves physical DRAM timing.
start = """\t/* APPLE FINAL V4: the added Linux OPP is not evidence of firmware"""
end = """\tif (data->bts_update) {"""
i = s.find(start)
assert i > 0
j = s.find(end, i)
assert j > i
oldblock = s[i:j]
newblock = r'''	/* V5 ACPM readback for every NON-STOCK geometric candidate.
	 * Failure of one new rate must not corrupt the tracked stock
	 * state or cause repeated requests for the same rejected OPP.
	 */
	if (data->devfreq_type == DEVFREQ_MIF &&
	    apple_mif_v5_candidate_idx(new_freq) >= 0) {
		int idx = apple_mif_v5_candidate_idx(new_freq);
		unsigned long ack;

		if (apple_mif_v5_rejected[idx])
			return -EOPNOTSUPP;
		if (cal_dfs_set_rate(data->dfs_id, new_freq)) {
			apple_mif_v5_rejected[idx] = true;
			pr_warn("apple_mif_v5: ACPM request %u failed, restoring %u\n",
				new_freq, data->old_freq);
			cal_dfs_set_rate(data->dfs_id, data->old_freq);
			return -EINVAL;
		}
		ack = exynos_acpm_get_rate(DEVFREQ_MIF);
		if (ack != new_freq) {
			apple_mif_v5_rejected[idx] = true;
			pr_warn("apple_mif_v5: requested %u, ACPM=%lu, restoring %u\n",
				new_freq, ack, data->old_freq);
			cal_dfs_set_rate(data->dfs_id, data->old_freq);
			return -EOPNOTSUPP;
		}
		pr_info_ratelimited("apple_mif_v5: ACPM acknowledged %u kHz; DRAM PHY not independently verified\n",
				    new_freq);
	} else if (cal_dfs_set_rate(data->dfs_id, (unsigned long)new_freq)) {
		dev_err(dev, "failed set frequency to CAL (%uKhz)\n", new_freq);
		return -EINVAL;
	}

'''
s = s[:i] + newblock + s[j:]

# Expose precise V5 candidate table inside the kernel, not merely in git docs.
# As in V3, the value is a CAL/ASV estimate, not a PMIC reading.
sysfs = r'''
static ssize_t apple_mif_v5_geometric_show(struct device *dev,
                struct device_attribute *attr, char *buf)
{
    struct exynos_devfreq_data *d = apple_mif_get(dev);
    ssize_t len = 0;
    int i;

    if (!d)
        return -ENODEV;
    len += scnprintf(buf + len, PAGE_SIZE - len,
                     "apple_mif_v5=1\npoints=13\n"
                     "stock_ect_fallback=1\n"
                     "candidate_cal_voltage_is_estimated=1\n"
                     "dram_voltage_measured=0\n"
                     "acpm_reported_khz=%lu\n",
                     exynos_acpm_get_rate(DEVFREQ_MIF));
    for (i = 0; i < ARRAY_SIZE(apple_mif_v5_freq_khz); i++)
        len += scnprintf(buf + len, PAGE_SIZE - len,
                         "level=%d rate_khz=%u cal_est_uv=%u rejected=%d\n",
                         i, apple_mif_v5_freq_khz[i],
                         apple_mif_v5_cal_est_uv[i],
                         i < 12 && apple_mif_v5_rejected[i]);
    return len;
}
static DEVICE_ATTR_RO(apple_mif_v5_geometric);

'''
sub("static ssize_t apple_mif_3672_show(struct device *dev,",
    sysfs + "static ssize_t apple_mif_3672_show(struct device *dev,")
sub('''        ret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_cal_opps);
        if (ret)
            dev_warn(data->dev, "MIF V3 CAL/ASV table unavailable: %d\\n", ret);''',
'''        ret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_cal_opps);
        if (ret)
            dev_warn(data->dev, "MIF V3 CAL/ASV table unavailable: %d\\n", ret);
        ret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_v5_geometric);
        if (ret)
            dev_warn(data->dev, "MIF V5 geometric table unavailable: %d\\n", ret);''')
sub('''        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_cal_opps);
        cancel_delayed_work_sync(&apple_mif_revert_work);''',
'''        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_cal_opps);
        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_v5_geometric);
        cancel_delayed_work_sync(&apple_mif_revert_work);''')

p.write_text(s)
print("Apple Final V5 geometric candidate OPPs applied: 13 research points, 13 stock ECT fallback frequencies.")
