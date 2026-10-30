#!/usr/bin/env python3
"""Apple Final V6: remove the stock MIF ALT-DVFS load-governor ceiling.

The V5 sysfs readback observed:
 current_target_load = 45 676000:60 1014000:70 1539000:80
                       2028000:88 2535000:93 3172000:97
While devfreq max_freq already read 3672000, the ALT governor uses its
last frequency threshold (target_load[num_target_load-2]) as its ceiling.
Extend ONLY the default MIF ALT mode with another terminal threshold.
Preserve stock modes, boot, suspend, ECT, thermal and Samsung fallback.
No forced memory overclock; firmware still decides.
"""
from pathlib import Path

p = Path("src/drivers/devfreq/exynos/exynos-devfreq.c")
s = p.read_text()
anchor = "\t\t\t/* Initial buffer and load setup */"
assert s.count(anchor) == 1, "Samsung ALT-DVFS init anchor changed"

patch = r'''			/* APPLE FINAL V6: MIF default ALT-DVFS was capped to the
			 * 3172000 kHz final target_load threshold even though
			 * the V5 devfreq max and OPPs expose 3672000 kHz.
			 * Append 3672000:97 to the stock curve without changing
			 * the other performance modes, original ECT or FVP.
			 * This makes 3672 an eligible governor target when
			 * load requires it; no boot OC or clock forcing.
			 */
			if (data->devfreq_type == DEVFREQ_MIF) {
				struct devfreq_alt_dvfs_param *m;
				unsigned int *expanded;
				int n;

				if (default_mode < 0 ||
				    default_mode >= alt_data->num_modes)
					return -EINVAL;
				m = &alt_data->alt_param_set[default_mode];
				n = m->num_target_load;
				if (!m->target_load || n < 3 || !(n & 1) ||
				    m->target_load[n - 2] != 3172000) {
					dev_err(data->dev, "apple_mif_v6: unexpected MIF ALT load table; refusing unsafe modification\n");
					return -EINVAL;
				}
				expanded = krealloc(m->target_load,
						    (n + 2) * sizeof(*expanded),
						    GFP_KERNEL);
				if (!expanded)
					return -ENOMEM;
				m->target_load = expanded;
				m->target_load[n] = 3672000;
				m->target_load[n + 1] = m->target_load[n - 1];
				m->num_target_load = n + 2;
				pr_info("apple_mif_v6: default ALT-DVFS MIF target_load ceiling 3172000 -> 3672000 kHz\n");
			}

'''
s=s.replace(anchor,patch+anchor,1)
p.write_text(s)
print("Apple Final V6: MIF default ALT-DVFS 3672 governor ceiling integrated, stock modes preserved.")
