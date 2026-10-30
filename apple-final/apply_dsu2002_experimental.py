#!/usr/bin/env python3
"""Experimental DSU 1898->2002 MHz CAL request with error fallback.
Does NOT add a firmware FVMAP/PLL/voltage OPP. Firmware may reject or freeze.
"""
from pathlib import Path
p = Path('src/drivers/cpufreq/exynos-dsufreq.c')
s = p.read_text()
needle = 'static struct dsufreq_domain dsufreq;'
replacement = '''static struct dsufreq_domain dsufreq;
/* APPLE FINAL DSU2002 - experimental CAL request, not a new firmware OPP. */
#define APPLE_DSU_STOCK_MAX 1898000U
#define APPLE_DSU_TEST_MAX  2002000U
static bool apple_dsu_2002_rejected;
'''
assert s.count(needle)==1, 'DSU domain declaration changed'
s=s.replace(needle,replacement,1)
needle='''\ttarget_freq = resolve_dsufreq(target_freq);
\tif (!target_freq)'''
replacement='''\ttarget_freq = resolve_dsufreq(target_freq);
\t/* Boost only a demand already reaching the former ceiling. Respect
\t * runtime max-frequency restrictions and permanently reject on failure. */
\tif (!apple_dsu_2002_rejected &&
\t    dsufreq.max_freq >= APPLE_DSU_TEST_MAX &&
\t    target_freq == APPLE_DSU_STOCK_MAX)
\t\ttarget_freq = APPLE_DSU_TEST_MAX;
\tif (!target_freq)'''
assert s.count(needle)==1,'DSU target handling changed'
s=s.replace(needle,replacement,1)
needle='''\tret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);
\tif (ret)
\t\tgoto out;
'''
replacement='''\tret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);
\tif (ret && target_freq == APPLE_DSU_TEST_MAX) {
\t\t/* One rejected attempt: keep running with the last known stock OPP. */
\t\tapple_dsu_2002_rejected = true;
\t\tpr_warn("APPLE FINAL DSU2002: CAL rejected 2002 MHz (%d); fallback 1898 MHz\\n", ret);
\t\ttarget_freq = APPLE_DSU_STOCK_MAX;
\t\tret = cal_dfs_set_rate(dsufreq.cal_id, target_freq);
\t}
\tif (ret)
\t\tgoto out;
'''
assert s.count(needle)==1,'DSU CAL path changed'
s=s.replace(needle,replacement,1)
needle='''\tif (!of_property_read_u32(dn, "min-freq", &val))
\t\tdsufreq.min_freq = dsufreq.min_freq_orig = max(dsufreq.min_freq, val);
'''
replacement='''\tif (!of_property_read_u32(dn, "min-freq", &val))
\t\tdsufreq.min_freq = dsufreq.min_freq_orig = max(dsufreq.min_freq, val);
\t/* Deliberate experimental extension of software ceiling only.
\t * This does not create a CAL/FVMAP/PLL level in firmware. */
\tif (dsufreq.max_freq == APPLE_DSU_STOCK_MAX) {
\t\tdsufreq.max_freq = APPLE_DSU_TEST_MAX;
\t\tdsufreq.max_freq_orig = APPLE_DSU_TEST_MAX;
\t\tpr_warn("APPLE FINAL DSU2002: testing CAL request 2002 MHz above stock 1898 MHz\\n");
\t}
'''
assert s.count(needle)==1,'DSU init max/min changed'
s=s.replace(needle,replacement,1)
p.write_text(s)
print('DSU2002 source patched; fallback on CAL error; hardware proof pending')
