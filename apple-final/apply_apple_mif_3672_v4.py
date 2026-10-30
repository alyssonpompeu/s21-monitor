#!/usr/bin/env python3
"""Apple Final V4: expose 3672 MHz as a real experimental Linux MIF DVFS OPP.

The ACPM/DRAM firmware, NOT this script, ultimately controls whether 3672
is accepted physically. No false claim about DRAM PLL, timings or voltages.
Compile-only source patch. This is NOT a validated stable daily kernel.
"""
from pathlib import Path

p = Path("src/drivers/devfreq/exynos/exynos-devfreq.c")
s = p.read_text()
def sub(old, new):
    global s
    n = s.count(old)
    assert n == 1, (old[:120], n)
    s = s.replace(old, new, 1)

# Only the MIF domain receives the extra OPP. Keep the original Samsung
# ECT frequencies, ABI, and voltage entries otherwise intact.
sub("""\tfor (i = 0; i < dvfs_domain->num_of_level; ++i) {
\t\tdata->opp_list[i].idx = i;
\t\tdata->opp_list[i].freq = dvfs_domain->list_level[i].level;
\t\tdata->opp_list[i].volt = 0;
\t}

\treturn 0;
}""",
"""\tfor (i = 0; i < dvfs_domain->num_of_level; ++i) {
\t\tdata->opp_list[i].idx = i;
\t\tdata->opp_list[i].freq = dvfs_domain->list_level[i].level;
\t\tdata->opp_list[i].volt = 0;
\t}

\t/* APPLE FINAL V4 EXPERIMENTAL MIF 3672: extend the Linux DVFS
\t * table with a real selectable target. Firmware ACPM may still
\t * reject it; the existing hardware voltage map is NOT extended.
\t * MIF OPP voltage is a Linux placeholder, not DRAM VDDQ.
\t */
\tif (data->devfreq_type == DEVFREQ_MIF) {
\t\tstruct exynos_devfreq_opp_table *ext;
\t\tunsigned int n = data->max_state;
\t\text = krealloc(data->opp_list, sizeof(*ext) * (n + 1),
\t\t\t       GFP_KERNEL);
\t\tif (!ext)
\t\t\treturn -ENOMEM;
\t\tdata->opp_list = ext;
\t\tmemmove(ext + 1, ext, sizeof(*ext) * n);
\t\tdata->max_state = n + 1;
\t\tfor (i = 1; i < data->max_state; i++)
\t\t\text[i].idx = i;
\t\text[0].idx = 0;
\t\text[0].freq = 3672000;
\t\text[0].volt = 0;
\t\tpr_warn("apple_mif_v4: synthetic Linux MIF OPP 3672000; ACPM support unverified\\n");
\t}

\treturn 0;
}""")

# DTS normal ceiling was 3172000. Do not touch CPU/GPU and other domains.
sub("""\tdata->max_freq = freq_array[4];
\tdata->reboot_freq = freq_array[5];""",
"""\tdata->max_freq = freq_array[4];
\tif (data->devfreq_type == DEVFREQ_MIF &&
\t    data->max_freq == 3172000)
\t\tdata->max_freq = 3672000;
\tdata->reboot_freq = freq_array[5];""")

# The CAL max reflects the firmware's ORIGINAL table. Keep original max
# for all normal domains; let the synthetic MIF OPP be offered to the
# Linux governor. The ACPM command itself still decides if it is accepted.
sub("""\tif (max_freq < data->max_freq) {
\t\tflags |= DEVFREQ_FLAG_LEAST_UPPER_BOUND;""",
"""\tif (max_freq < data->max_freq &&
\t    !(data->devfreq_type == DEVFREQ_MIF &&
\t      data->max_freq == 3672000)) {
\t\tflags |= DEVFREQ_FLAG_LEAST_UPPER_BOUND;""")

# If the ACPM does not report 3672 after the MIF FREQ_REQ, restore the
# previous normal frequency and avoid repeatedly issuing failed requests.
# This cannot protect against a hard lock during the firmware transition.
sub("""\tif (cal_dfs_set_rate(data->dfs_id, (unsigned long)new_freq)) {
\t\tdev_err(dev, "failed set frequency to CAL (%uKhz)\\n",
\t\t\t\tnew_freq);
\t\treturn -EINVAL;
\t}

\tif (data->bts_update) {""",
"""\t/* APPLE FINAL V4: the added Linux OPP is not evidence of firmware
\t * support. Reject and fall back if ACPM does not actually report it.
\t */
\tif (data->devfreq_type == DEVFREQ_MIF &&
\t    new_freq == 3672000) {
\t\tstatic bool rejected_once;
\t\tunsigned long ack;
\t\tif (rejected_once)
\t\t\treturn -EOPNOTSUPP;
\t\tif (cal_dfs_set_rate(data->dfs_id, 3672000)) {
\t\t\trejected_once = true;
\t\t\treturn -EINVAL;
\t\t}
\t\tack = exynos_acpm_get_rate(DEVFREQ_MIF);
\t\tif (ack != 3672000) {
\t\t\trejected_once = true;
\t\t\tpr_err("apple_mif_v4: ACPM rejected 3672 (ack=%lu); restoring %u\\n",
\t\t\t\tack, data->old_freq);
\t\t\tcal_dfs_set_rate(data->dfs_id, (unsigned long)data->old_freq);
\t\t\treturn -EOPNOTSUPP;
\t\t}
\t\tpr_warn("apple_mif_v4: ACPM reported 3672000; DRAM PHY unverified\\n");
\t} else if (cal_dfs_set_rate(data->dfs_id, (unsigned long)new_freq)) {
\t\tdev_err(dev, "failed set frequency to CAL (%uKhz)\\n",
\t\t\t\tnew_freq);
\t\treturn -EINVAL;
\t}

\tif (data->bts_update) {""")

# Remove the V3 gate that prevented the V2 debug interface from ever
# sending 3672, while retaining the strict 3672-only request check
# and its 10-second timeout/rollback.
sub("""    if (!apple_mif_v3_has_full_opp(d, req)) {
        pr_warn("apple_mif_v3: unsupported MIF OPP %lu; request blocked\\n", req);
        return -EOPNOTSUPP;
    }""",
"""    if (d->max_freq < req)
        return -EOPNOTSUPP;""")
p.write_text(s)
print("Apple Final V4: 3672 MHz Linux MIF governor OPP enabled, ACPM ACK guarded.")
