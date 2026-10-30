#!/usr/bin/env python3
"""Apple Final V3: safe, measured MIF OPP discovery and refusal of unsupported OC.

Runs AFTER apply_apple_mif_lab.py. Does not invent voltage/LPDDR5 timings.
"""
from pathlib import Path

p = Path("src/drivers/devfreq/exynos/exynos-devfreq.c")
s = p.read_text()

def sub(old, new):
    global s
    n = s.count(old)
    assert n == 1, (old[:100], n)
    s = s.replace(old, new, 1)

opp_helpers = r"""
/* APPLE FINAL V3: CAL/ASV MIF table, with bounds checked BEFORE copying.
 * A frequency alone is not a safe DRAM operating point. Both CAL/ASV and
 * Linux OPP tables must expose the exact rate, and the platform max must
 * include it, before an experimental request is accepted.
 */
#define APPLE_MIF_V3_MAX_OPPS 48

static bool apple_mif_v3_has_full_opp(struct exynos_devfreq_data *d,
                                      unsigned long rate)
{
    struct dvfs_rate_volt table[APPLE_MIF_V3_MAX_OPPS];
    struct dev_pm_opp *opp;
    unsigned int count, voltage;
    int n, i;
    bool cal_ok = false;

    count = cal_dfs_get_lv_num(d->dfs_id);
    if (!count || count > ARRAY_SIZE(table))
        return false;
    n = cal_dfs_get_rate_asv_table(d->dfs_id, table);
    if (n <= 0 || n > ARRAY_SIZE(table))
        return false;

    for (i = 0; i < n; i++) {
        if (table[i].rate == rate && table[i].volt > 0) {
            cal_ok = true;
            break;
        }
    }
    if (!cal_ok || rate > d->max_freq)
        return false;

    opp = dev_pm_opp_find_freq_exact(d->dev, rate, true);
    if (IS_ERR(opp))
        return false;
    voltage = dev_pm_opp_get_voltage(opp);
    dev_pm_opp_put(opp);
    return voltage > 0;
}

static ssize_t apple_mif_cal_opps_show(struct device *dev,
                                       struct device_attribute *attr, char *buf)
{
    struct exynos_devfreq_data *d = apple_mif_get(dev);
    struct dvfs_rate_volt table[APPLE_MIF_V3_MAX_OPPS];
    unsigned int count;
    int i, n;
    ssize_t len = 0;

    if (!d)
        return -ENODEV;
    count = cal_dfs_get_lv_num(d->dfs_id);
    if (!count || count > ARRAY_SIZE(table))
        return scnprintf(buf, PAGE_SIZE,
                         "cal_asv_unavailable=1 levels=%u\\n", count);
    n = cal_dfs_get_rate_asv_table(d->dfs_id, table);
    if (n <= 0 || n > ARRAY_SIZE(table))
        return scnprintf(buf, PAGE_SIZE,
                         "cal_asv_unavailable=1 returned=%d\\n", n);

    len += scnprintf(buf + len, PAGE_SIZE - len,
                     "cal_asv_opp_count=%d\\n"
                     "note=ASV_voltage_is_not_measured_DRAM_VDDQ\\n"
                     "v3_permanent_3672_ready=%d\\n",
                     n, apple_mif_v3_has_full_opp(d, APPLE_MIF_LAB_KHZ));
    for (i = 0; i < n && len < PAGE_SIZE - 80; i++)
        len += scnprintf(buf + len, PAGE_SIZE - len,
                         "freq_khz=%lu cal_asv_volt_uv=%u\\n",
                         table[i].rate, table[i].volt);
    return len;
}
static DEVICE_ATTR_RO(apple_mif_cal_opps);
"""
# The baseline code already defines apple_mif_get above the sysfs show.
sub("static ssize_t apple_mif_3672_show(struct device *dev,",
    opp_helpers + "\nstatic ssize_t apple_mif_3672_show(struct device *dev,")

sub("""    if (req != APPLE_MIF_LAB_KHZ)
        return -EINVAL;
    mutex_lock(&apple_mif_mutex);""",
    """    if (req != APPLE_MIF_LAB_KHZ)
        return -EINVAL;
    /* V3 rejects arbitrary 3672 MHz writes if firmware CAL+ASV and Linux
     * OPP disagree. The present stock MIF max is 3172 MHz, so this is
     * expected to be a read-only diagnostic until a validated OPP exists.
     */
    if (!apple_mif_v3_has_full_opp(d, req)) {
        pr_warn("apple_mif_v3: unsupported MIF OPP %lu; request blocked\\n", req);
        return -EOPNOTSUPP;
    }
    mutex_lock(&apple_mif_mutex);""")

sub("""        ret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_3672);
        if (ret)
            dev_warn(data->dev, "MIF Lab sysfs unavailable: %d\\n", ret);""",
    """        ret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_3672);
        if (ret)
            dev_warn(data->dev, "MIF Lab sysfs unavailable: %d\\n", ret);
        ret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_cal_opps);
        if (ret)
            dev_warn(data->dev, "MIF V3 CAL/ASV table unavailable: %d\\n", ret);""")

sub("""        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_3672);
        cancel_delayed_work_sync(&apple_mif_revert_work);""",
    """        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_3672);
        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_cal_opps);
        cancel_delayed_work_sync(&apple_mif_revert_work);""")

p.write_text(s)
print("Apple Final V3: CAL/ASV OPP diagnostics + unsupported 3672 MHz request guard.")
