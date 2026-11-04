#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
p=Path("apple-final/build_apple_final_resume.sh")
s=p.read_text()
repls={
"python3 apple-final/apply_apple_mif_v7_13exponential.py\n":
"python3 apple-final/apply_apple_mif_v7_13exponential.py\npython3 apple-final/apply_apple_v8_dsu2392_gpu_exp4.py\n",
"grep -q '^CONFIG_SOC_EXYNOS2100_GPU_UV=7$'":
"grep -q '^CONFIG_SOC_EXYNOS2100_GPU_UV=4$'",
"CPU A55 -9%; CPU A78 -10%; CPU X1 -8%; GPU G3D -7%.":
"CPU A55 -9%; CPU A78 -10%; CPU X1 -8%; GPU G3D max -4% with exponential frequency curve.",
"V7 is an experimental firmware-dependent kernel; retain V6 TWRP and original backups for rollback.":
"V8 is an experimental firmware-dependent kernel; retain V7 TWRP and original backups for rollback.",
"MIF -6%; DSU -6%; INT -6%.":
"MIF -6%; DSU -6%; INT -6%.\nV8 DSU exact PLL 2392000kHz, CAL readback and fallback. GPU G3D exponential -4..-1 percent ACPM margin.",
}
for a,b in repls.items():
    assert s.count(a)==1,(a,s.count(a))
    s=s.replace(a,b,1)
p.write_text(s)
PY
bash apple-final/build_apple_final_resume.sh
