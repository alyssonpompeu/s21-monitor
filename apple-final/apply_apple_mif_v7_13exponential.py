#!/usr/bin/env python3
"""Apple Final V7: exactly 13 geometric-descending Linux MIF OPP levels.

Mathematical target 4000000 -> 421000 kHz, exp(-0.18761806720163718*i).
The Samsung ECT and ACPM/FVP stock 13 level tables remain independently
available internally; they are NOT duplicated as Linux devfreq OPPs.
One geometric slot may be replaced at boot by the native CAL boot frequency
to ensure the initial state exists in devfreq (13 levels remain exactly).
All candidate voltages remain CAL estimates (not DRAM VDDQ/VDD2 writes).
New frequency requests require ACPM FREQ_GET acknowledgement, still not
a direct PHY measurement. V6 automatic governor behavior is preserved.
"""
from pathlib import Path
p=Path("src/drivers/devfreq/exynos/exynos-devfreq.c")
s=p.read_text()

def one(a,b):
    global s
    n=s.count(a)
    assert n==1, ("V7 source anchor mismatch",a[:140],n)
    s=s.replace(a,b,1)

rates=[4000000,3315725,2748508,2278324,1888574,1565498,1297690,
       1075696,891678,739140,612696,507883,421000]
volts=[812500,781250,750000,712500,668750,625000,593750,
       556250,537500,531250,525000,512500,506250]
assert len(rates)==len(volts)==13 and len(set(rates))==13
assert all(rates[i]>rates[i+1] for i in range(12))
assert abs(rates[1]-4000000*(421000/4000000)**(1/12))<1

def patch_array(name,values):
    import re
    global s
    pattern=r"(static const u32 "+name+r"\[13\] = \{)[^}]*?(\};)"
    m=re.search(pattern,s,re.S)
    assert m, name
    inner="\n    "+", ".join(str(x) for x in values[:5])+",\n    "
    inner+=", ".join(str(x) for x in values[5:10])+",\n    "
    inner+=", ".join(str(x) for x in values[10:])+"\n"
    s=s[:m.start()]+m.group(1)+inner+m.group(2)+s[m.end():]
patch_array("apple_mif_v5_freq_khz",rates)
patch_array("apple_mif_v5_cal_est_uv",volts)
one("static const u32 apple_mif_v5_freq_khz[13]","static u32 apple_mif_v5_freq_khz[13]")

# Keep original ECT positions in a private FVP-only table. Do not expose
# them as Linux devfreq OPPs, because the user wants exactly 13 levels.
anchor="static bool apple_mif_v5_rejected[12];"
one(anchor, """static u32 apple_mif_v7_stock_ect_khz[13];
static u32 apple_mif_v7_boot_snap_khz;
""" + anchor)

start="\t/* APPLE FINAL V5: append the 12 novel geometric OPPs"
finish="\treturn 0;\n}"
i=s.find(start)
j=s.find(finish,i)
assert 0<i<j,"V5 25-OPP injection missing"
replacement=r'''	/* APPLE FINAL V7: exactly 13 *active* MIF OPP entries, not 25.
	 * Preserve the original Samsung ECT clock list separately for ACPM
	 * FVP/constraint tables, but replace the Linux operating list.
	 */
	if (data->devfreq_type == DEVFREQ_MIF) {
		if (data->max_state != ARRAY_SIZE(apple_mif_v5_freq_khz)) {
			pr_err("apple_mif_v7: unexpected Samsung MIF ECT count=%u\n",
			       data->max_state);
			return -EINVAL;
		}
		for (i = 0; i < data->max_state; i++) {
			apple_mif_v7_stock_ect_khz[i] = data->opp_list[i].freq;
			data->opp_list[i].freq = apple_mif_v5_freq_khz[i];
			data->opp_list[i].volt = apple_mif_v5_cal_est_uv[i];
		}
		pr_info("apple_mif_v7: 13 exponential MIF OPPs; native ECT preserved for ACPM/FVP\n");
	}

'''
s=s[:i]+replacement+s[j:]

# MIF max remains firmware-dependent, but use the requested nominal 4GHz
# ceiling rather than V6's 3672. Other devfreq domains are unchanged.
one("""\tif (data->devfreq_type == DEVFREQ_MIF &&
\t    data->max_freq == 3172000)
\t\tdata->max_freq = 3672000;""",
"""\tif (data->devfreq_type == DEVFREQ_MIF &&
\t    data->max_freq == 3172000)
\t\tdata->max_freq = 4000000;""")
one("""data->max_freq == 3672000)) {""","""data->max_freq == 4000000)) {""")

# The MIF firmware constraint tables must still be constructed from ECT,
# not the 13 changed Linux OPPs. This fixes a central V5 limitation.
start="static int exynos_constraint_parse(struct exynos_devfreq_data *data,"
end="static int exynos_devfreq_update_fvp("
i=s.find(start); j=s.find(end,i)
assert 0<i<j
sec=s[i:j]
assert sec.count("data->opp_list[j].freq")==4,sec.count("data->opp_list[j].freq")
sec=sec.replace("data->opp_list[j].freq","dvfs_domain->list_level[j].level")
s=s[:i]+sec+s[j:]

start="static int exynos_devfreq_update_fvp("
end="static int exynos_devfreq_reboot("
i=s.find(start);j=s.find(end,i)
assert 0<i<j
sec=s[i:j]
for v in ("i","j"):
    old=v+" < data->max_state - (data->devfreq_type == DEVFREQ_MIF ? 12 : 0); "+v+"++"
    assert sec.count(old)==1,old
    sec=sec.replace(old,v+" < data->max_state; "+v+"++")
for v,n in (("i",3),("j",3)):
    old="data->opp_list["+v+"].freq"
    assert sec.count(old)==n,(old,sec.count(old))
    sec=sec.replace(old,"(data->devfreq_type == DEVFREQ_MIF ? apple_mif_v7_stock_ect_khz["+v+"] : data->opp_list["+v+"].freq)")
s=s[:i]+sec+s[j:]

# Boot snapping: the native CAL boot rate may differ from every pure
# geometric frequency. Preserve EXACTLY 13 OPPs by replacing the closest
# *non-endpoint* candidate with the known original stock boot rate.
# This avoids failing exynos_devfreq_get_opp_idx() during platform probe.
anchor="""\tif (of_property_read_u32(np, "dfs_id", &data->dfs_id) &&
\t\t\tof_property_match_string(np, "clock-names", buf))
\t\treturn -ENODEV;"""
insert=r'''
	if (data->devfreq_type == DEVFREQ_MIF) {
		u32 boot_khz = cal_dfs_get_boot_freq(data->dfs_id);
		u32 best_delta = ~0U;
		int k, nearest = -1;
		bool found = false;

		for (k = 0; k < data->max_state; k++) {
			if (data->opp_list[k].freq == boot_khz) {
				found = true;
				break;
			}
		}
		if (!found) {
			for (k = 1; k < data->max_state - 1; k++) {
				u32 rate = data->opp_list[k].freq;
				u32 delta = rate > boot_khz ?
					rate - boot_khz : boot_khz - rate;
				if (delta < best_delta) {
					nearest = k;
					best_delta = delta;
				}
			}
			if (nearest < 0 ||
			    boot_khz >= data->opp_list[nearest - 1].freq ||
			    boot_khz <= data->opp_list[nearest + 1].freq) {
				dev_err(data->dev, "apple_mif_v7: native boot %u not suitable for 13-OPP exponential table\n",
					boot_khz);
				return -EINVAL;
			}
			apple_mif_v7_boot_snap_khz = boot_khz;
			data->opp_list[nearest].freq = boot_khz;
			apple_mif_v5_freq_khz[nearest] = boot_khz;
			/* CAL voltage metadata is only an estimate; do not
			 * pretend this field programs physical DRAM rails.
			 */
			dev_warn(data->dev, "apple_mif_v7: geometric slot %d snapped to native boot=%u kHz; total=13\n",
				 nearest, boot_khz);
		}
	}
'''
one(anchor,anchor+"\n"+insert)

# Existing V6 ALT-DVFS patch appends 3672 above stock. In V7 it must
# instead append 4000 (the 13 OPP ceiling).
one("m->target_load[n] = 3672000;","m->target_load[n] = 4000000;")
one('pr_info("apple_mif_v6: default ALT-DVFS MIF target_load ceiling 3172000 -> 3672000 kHz\\n");',
'pr_info("apple_mif_v7: default ALT-DVFS MIF target_load ceiling 3172000 -> 4000000 kHz\\n");')

# V7 readback: report the *actual* table and an additional direct
# DDRPHY clock-framework reading when present; don't assert that
# ACPM replies alone certify physical DRAM speed.
one('"apple_mif_v5=1\\npoints=13\\n"','"apple_mif_v7=1\\npoints=13\\n"')
one('"stock_ect_fallback=1\\n"','"stock_ect_fvp_internal=1\\n"')
one('"dram_voltage_measured=0\\n"','"dram_voltage_measured=0\\n"')
one('''"acpm_reported_khz=%lu\\n",
                     exynos_acpm_get_rate(DEVFREQ_MIF));''',
''' "acpm_reported_khz=%lu\\n"
                     "ddrphy_clk_khz=%lu\\n"
                     "native_boot_snap_khz=%u\\n",
                     exynos_acpm_get_rate(DEVFREQ_MIF),
                     d->clk ? (unsigned long)(clk_get_rate(d->clk) / 2000) : 0UL,
                     apple_mif_v7_boot_snap_khz);''')

# Expose a V7-specific sysfs alias alongside V5 diagnostic naming.
one("static DEVICE_ATTR_RO(apple_mif_v5_geometric);",
"""static DEVICE_ATTR_RO(apple_mif_v5_geometric);
static DEVICE_ATTR(apple_mif_v7_geometric, 0444,
		   apple_mif_v5_geometric_show, NULL);""")
anchor='''        ret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_v5_geometric);
        if (ret)
            dev_warn(data->dev, "MIF V5 geometric table unavailable: %d\\n", ret);'''
assert s.count(anchor)==0,"tabs normalization required"
import re
tabs=lambda x:re.sub(r"(?m)^( {4})+",lambda m:"\t"*(len(m.group(0))//4),x)
anchor=tabs(anchor)
add=tabs('''        ret = device_create_file(&data->devfreq->dev, &dev_attr_apple_mif_v7_geometric);
        if (ret)
            dev_warn(data->dev, "MIF V7 geometric table unavailable: %d\\n", ret);''')
one(anchor,anchor+"\n"+add)
one(tabs('''        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_v5_geometric);'''),
tabs('''        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_v5_geometric);
        device_remove_file(&data->devfreq->dev, &dev_attr_apple_mif_v7_geometric);'''))

p.write_text(s)
print("Apple Final V7: exactly 13 geometric MIF Linux OPPs, nominal 4GHz, native CAL boot snap if needed, 13 native ECT FVP frequencies preserved.")
