#!/usr/bin/env python3
"""MIF 3672 kHz x1000 ACPM experiment; opt-in, rollback after 10 s."""
from pathlib import Path
p=Path("src/drivers/devfreq/exynos/exynos-devfreq.c")
s=p.read_text()
def sub(a,b):
    global s
    n=s.count(a)
    assert n==1,(a[:80],n)
    s=s.replace(a,b,1)
sub("#include <linux/suspend.h>\n","#include <linux/suspend.h>\n#include <linux/workqueue.h>\n")
marker="static u32 boot_array[2];\n"
code=r'''
/* APPLE FINAL MIF LAB: unlisted 3672000 kHz ACPM attempt.
 * Does not add ECT OPP, DRAM training, timing or voltage.
 * No automatic boot activation. Hard lockup cannot be recovered by worker.
 */
#define APPLE_MIF_LAB_DOMAIN 0
#define APPLE_MIF_LAB_KHZ 3672000UL
static DEFINE_MUTEX(apple_mif_mutex);
static struct delayed_work apple_mif_revert_work;
static struct exynos_devfreq_data *apple_mif_data;
static unsigned long apple_mif_original;
static unsigned long apple_mif_cal;
static bool apple_mif_active;
static int apple_mif_rc = -ENODATA;
/* CAL's cached vclk read can return 0 for an ACPM clock. Prefer direct
 * ACPM FREQ_GET and fall back to devfreq's known normal OPP for rollback.
 */
static unsigned long apple_mif_read_acpm(struct exynos_devfreq_data *d)
{
    return exynos_acpm_get_rate(APPLE_MIF_LAB_DOMAIN);
}
static unsigned long apple_mif_rollback_rate(struct exynos_devfreq_data *d)
{
    unsigned long v = apple_mif_read_acpm(d);
    if (v && v <= d->max_freq)
        return v;
    v = d->old_freq;
    if (v && v <= d->max_freq)
        return v;
    v = d->previous_freq;
    if (v && v <= d->max_freq)
        return v;
    return 0;
}
static void apple_mif_revert_locked(void)
{
    struct exynos_devfreq_data *d = apple_mif_data;
    int rc;
    if (!apple_mif_active || !d)
        return;
    rc = cal_dfs_set_rate(d->dfs_id, apple_mif_original);
    if (rc)
        apple_mif_rc = rc;
    apple_mif_cal = apple_mif_read_acpm(d);
    d->devfreq_disabled = false;
    apple_mif_active = false;
    pr_warn("apple_mif_lab restore target=%lu cal=%lu rc=%d\n",
            apple_mif_original, apple_mif_cal, rc);
}
static void apple_mif_revert_worker(struct work_struct *work)
{
    mutex_lock(&apple_mif_mutex);
    apple_mif_revert_locked();
    mutex_unlock(&apple_mif_mutex);
}
static struct exynos_devfreq_data *apple_mif_get(struct device *dev)
{
    struct platform_device *pdev;
    struct exynos_devfreq_data *d;
    if (!dev || !dev->parent)
        return NULL;
    pdev = container_of(dev->parent, struct platform_device, dev);
    d = platform_get_drvdata(pdev);
    return d && d->devfreq_type == APPLE_MIF_LAB_DOMAIN ? d : NULL;
}
static ssize_t apple_mif_3672_show(struct device *dev,
        struct device_attribute *attr, char *buf)
{
    struct exynos_devfreq_data *d = apple_mif_get(dev);
    unsigned long now;
    ssize_t n;
    if (!d)
        return -ENODEV;
    mutex_lock(&apple_mif_mutex);
    now = apple_mif_read_acpm(d);
    n = scnprintf(buf, PAGE_SIZE,
        "request_khz=3672000\nnormal_max_khz=%u\nactive=%d\n"
        "saved_khz=%lu\nreported_acpm_khz=%lu\nlast_rc=%d\n"
        "last_readback_khz=%lu\nwindow_seconds=10\nboot_auto=0\n"
        "physical_dram_frequency_verified=NO\n",
        d->max_freq, apple_mif_active, apple_mif_original,
        now, apple_mif_rc, apple_mif_cal);
    mutex_unlock(&apple_mif_mutex);
    return n;
}
static ssize_t apple_mif_3672_store(struct device *dev,
        struct device_attribute *attr, const char *buf, size_t count)
{
    struct exynos_devfreq_data *d = apple_mif_get(dev);
    unsigned long req;
    int rc;
    if (!d)
        return -ENODEV;
    if (kstrtoul(buf, 10, &req))
        return -EINVAL;
    if (!req) {
        cancel_delayed_work_sync(&apple_mif_revert_work);
        mutex_lock(&apple_mif_mutex);
        apple_mif_revert_locked();
        mutex_unlock(&apple_mif_mutex);
        return count;
    }
    if (req != APPLE_MIF_LAB_KHZ)
        return -EINVAL;
    mutex_lock(&apple_mif_mutex);
    if (apple_mif_active) {
        mutex_unlock(&apple_mif_mutex);
        return -EBUSY;
    }
    apple_mif_original = apple_mif_rollback_rate(d);
    if (!apple_mif_original || apple_mif_original > d->max_freq) {
        apple_mif_rc = -ERANGE;
        pr_warn("apple_mif_lab baseline unavailable: old=%u previous=%u max=%u\n",
                d->old_freq, d->previous_freq, d->max_freq);
        mutex_unlock(&apple_mif_mutex);
        return -ERANGE;
    }
    d->devfreq_disabled = true;
    apple_mif_active = true;
    apple_mif_rc = -EINPROGRESS;
    schedule_delayed_work(&apple_mif_revert_work, 10 * HZ);
    rc = cal_dfs_set_rate(d->dfs_id, req);
    apple_mif_rc = rc;
    apple_mif_cal = apple_mif_read_acpm(d);
    if (rc || apple_mif_cal != req) {
        pr_warn("apple_mif_lab REJECTED rc=%d ACPM=%lu\n", rc, apple_mif_cal);
        apple_mif_revert_locked();
        cancel_delayed_work(&apple_mif_revert_work);
        mutex_unlock(&apple_mif_mutex);
        return rc ? rc : -EOPNOTSUPP;
    }
    pr_warn("apple_mif_lab ACPM FREQ_GET reports 3672000; DRAM/PHY unverified; timed restore in 10s\n");
    mutex_unlock(&apple_mif_mutex);
    return count;
}
static DEVICE_ATTR(apple_mif_3672, 0600,
                   apple_mif_3672_show, apple_mif_3672_store);
'''
sub(marker,marker+code+"\n")
anchor='''\tret = sysfs_create_group(&data->devfreq->dev.kobj, &devfreq_delay_time_attr_group);
\tif (ret)
\t\tdev_warn(data->dev, "failed create sysfs for devfreq data\\n");

\tdata->devfreq_disabled = false;'''
repl='''\tret = sysfs_create_group(&data->devfreq->dev.kobj, &devfreq_delay_time_attr_group);
\tif (ret)
\t\tdev_warn(data->dev, "failed create sysfs for devfreq data\\n");

\tif (data->devfreq_type == APPLE_MIF_LAB_DOMAIN) {
\t\tINIT_DELAYED_WORK(&apple_mif_revert_work, apple_mif_revert_worker);
\t\tapple_mif_data = data;
\t\tret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_3672);
\t\tif (ret)
\t\t\tdev_warn(data->dev, "MIF Lab sysfs unavailable: %d\\n", ret);
\t}
\tdata->devfreq_disabled = false;'''
sub(anchor,repl)
rem='''\tsysfs_remove_file(&data->devfreq->dev.kobj, &dev_attr_scaling_devfreq_min.attr);'''
rep='''\tif (data->devfreq_type == APPLE_MIF_LAB_DOMAIN) {
\t\tdevice_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_3672);
\t\tcancel_delayed_work_sync(&apple_mif_revert_work);
\t\tmutex_lock(&apple_mif_mutex);
\t\tapple_mif_revert_locked();
\t\tapple_mif_data = NULL;
\t\tmutex_unlock(&apple_mif_mutex);
\t}
\tsysfs_remove_file(&data->devfreq->dev.kobj, &dev_attr_scaling_devfreq_min.attr);'''
sub(rem,rep)
p.write_text(s)
print("Apple Final MIF Lab only: raw ACPM 3672000 request, on-demand, 10-second revert.")
