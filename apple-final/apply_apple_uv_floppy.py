#!/usr/bin/env python3
"""Apple Final 1.4 UV profile on pinned FloppyKernel 5.4.304 source.

This is a 7-domain ACM/CAL percentage-margin request, NOT a raw-voltage
write, an OPP table edit, or proof of physical PMIC voltage. Values were
previously read back on Samsung HZA6, not tested on Floppy 5.4.304.
"""
from pathlib import Path

cfg = Path("src/arch/arm64/configs/exynos2100-unified_defconfig")
data = cfg.read_text()
assert "CONFIG_SOC_EXYNOS2100_UNDERVOLT=y" in data, "Floppy UV Kconfig unavailable"
values = {
    "CONFIG_SOC_EXYNOS2100_CL0_UV": 9,
    "CONFIG_SOC_EXYNOS2100_CL1_UV": 10,
    "CONFIG_SOC_EXYNOS2100_CL2_UV": 8,
    "CONFIG_SOC_EXYNOS2100_GPU_UV": 7,
}
for name, percent in values.items():
    old = f"{name}=1\n"
    assert data.count(old) == 1, f"Unexpected config default for {name}"
    data = data.replace(old, f"{name}={percent}\n", 1)
cfg.write_text(data)

fvmap = Path("src/drivers/soc/samsung/cal-if/fvmap.c")
s = fvmap.read_text()
for domain in ("mif", "int", "dsu"):
    old = f"static int margin_{domain};\n"
    assert s.count(old) == 1, f"Unexpected Floppy margin declaration: {domain}"
    s = s.replace(old, f"static int margin_{domain} = -6;\n", 1)

# The original Floppy kernel writes initial margins to CAL without storing
# them in percent_margin_table. Keep driver readback synchronized with
# startup requests (still not an independent ACPM acknowledgment).
old = """\t\tmargin = init_margin_table[vclk->margin_id];
\t\tif (margin)
\t\t\tcal_dfs_set_volt_margin(i | ACPM_VCLK_TYPE, margin);
"""
new = """\t\tmargin = init_margin_table[vclk->margin_id];
\t\tif (margin) {
\t\t\tcal_dfs_set_volt_margin(i | ACPM_VCLK_TYPE, margin);
\t\t\tpercent_margin_table[vclk->margin_id] = margin;
\t\t}
"""
assert s.count(old) == 1, "Floppy initial margin application changed"
s=s.replace(old,new,1)

# The native CL0/CL1/CL2/G3D undervolt driver is used for the remaining
# domains, and each boot default is explicitly exposed by readback.
domain_to_config = {
    "MARGIN_CPUCL0":"CONFIG_SOC_EXYNOS2100_CL0_UV",
    "MARGIN_CPUCL1":"CONFIG_SOC_EXYNOS2100_CL1_UV",
    "MARGIN_CPUCL2":"CONFIG_SOC_EXYNOS2100_CL2_UV",
    "MARGIN_G3D":"CONFIG_SOC_EXYNOS2100_GPU_UV",
}
for margin_id, config in domain_to_config.items():
    old = (f"\t\tif (vclk->margin_id == {margin_id})\n"
           f"\t\t\tcal_dfs_set_volt_margin(i | ACPM_VCLK_TYPE,\n"
           f"\t\t\t\t\t-{config});")
    new = (f"\t\tif (vclk->margin_id == {margin_id}) {{\n"
           f"\t\t\tcal_dfs_set_volt_margin(i | ACPM_VCLK_TYPE,\n"
           f"\t\t\t\t\t-{config});\n"
           f"\t\t\tpercent_margin_table[vclk->margin_id] = -{config};\n"
           f"\t\t}}")
    assert s.count(old)==1, f"Missing startup native undervolt call {margin_id}"
    s=s.replace(old,new,1)
fvmap.write_text(s)
print("APPLE FINAL 1.4 UV (Floppy): A55 -9, A78 -10, X1 -8, GPU -7, MIF/DSU/INT -6")
print("Startup percent_margin_table readback synchronized; ACPM physical voltage not measured")
