#!/usr/bin/env bash
set -euo pipefail

# Apple Final rebased onto FloppyKernel. This builds a complete matched
# kernel+modules+DTBO+vendor_boot; it does NOT reuse HZA6 old kernel Image.
FLOP_REPO="https://github.com/FlopKernel-Series/flop_exynos2100_kernel.git"
FLOP_BRANCH="floppy-main"
FLOP_COMMIT="4553117a5014928dff55595f46627e0d77bcf133"
OLD_KERNEL="5.4.242-30958140-abG991BXXSJHZA6"

echo "== APPLE FINAL ON FLOPPYKERNEL EXYNOS2100 =="
echo "Pinned Floppy: $FLOP_COMMIT"
echo "Original Apple release (not reused): $OLD_KERNEL"
rm -rf src
git clone --no-checkout --depth=1 --filter=blob:none --branch "$FLOP_BRANCH" "$FLOP_REPO" src
git -C src checkout --detach "$FLOP_COMMIT"
test "$(git -C src rev-parse HEAD)" = "$FLOP_COMMIT" || {
  echo "ERROR: wrong Floppy tree" >&2; exit 20;
}
grep -q '^SUBLEVEL = 304$' src/Makefile || {
  echo "ERROR: expected 5.4.304" >&2; exit 21;
}

# Avoid fetching enormous NetHunter firmware submodule for vanilla build.
git -C src submodule update --init --depth=1 build/mkbootimg
python3 apple-final/apply_apple_final_floppy.py
python3 apple-final/apply_apple_uv_floppy.py
python3 apple-final/apply_apple_gpu949_default.py
python3 apple-final/apply_apple_mif_lab.py
python3 apple-final/apply_apple_mif_v3.py
python3 apple-final/apply_apple_mif_3672_v4.py
python3 apple-final/apply_apple_mif_v5_geometric.py
python3 apple-final/apply_apple_mif_v6_alt_dvfs.py
python3 apple-final/apply_apple_mif_v7_13exponential.py
grep -q 'APPLE FINAL EMS 3x3' src/kernel/sched/ems/cpu_select.c
grep -q 'apple_final_keep_prev(env, best_cpu)' src/kernel/sched/ems/cpu_select.c
grep -q 'gpu_unlock' src/drivers/gpu/arm/exynos/frontend/gpex_clock_sysfs.c
grep -q 'gpu_stock_max_clock' src/drivers/gpu/arm/exynos/frontend/gpex_clock.c
grep -q '^CONFIG_SOC_EXYNOS2100_CL0_UV=9$' src/arch/arm64/configs/exynos2100-unified_defconfig
grep -q '^CONFIG_SOC_EXYNOS2100_CL1_UV=10$' src/arch/arm64/configs/exynos2100-unified_defconfig
grep -q '^CONFIG_SOC_EXYNOS2100_CL2_UV=8$' src/arch/arm64/configs/exynos2100-unified_defconfig
grep -q '^CONFIG_SOC_EXYNOS2100_GPU_UV=7$' src/arch/arm64/configs/exynos2100-unified_defconfig
grep -q '^static int margin_mif = -6;' src/drivers/soc/samsung/cal-if/fvmap.c
grep -q '^static int margin_dsu = -6;' src/drivers/soc/samsung/cal-if/fvmap.c
grep -q '^static int margin_int = -6;' src/drivers/soc/samsung/cal-if/fvmap.c
grep -q 'Apple Final: 949 MHz GPU unlock is default' src/drivers/gpu/arm/exynos/frontend/gpex_clock.c
grep -q 'APPLE FINAL MIF LAB' src/drivers/devfreq/exynos/exynos-devfreq.c
grep -q 'DEVICE_ATTR(apple_mif_3672' src/drivers/devfreq/exynos/exynos-devfreq.c
grep -q 'DEVICE_ATTR_RO(apple_mif_cal_opps)' src/drivers/devfreq/exynos/exynos-devfreq.c
grep -q 'APPLE FINAL V5: 13-point GEOMETRIC candidate curve' src/drivers/devfreq/exynos/exynos-devfreq.c
grep -q 'DEVICE_ATTR_RO(apple_mif_v5_geometric)' src/drivers/devfreq/exynos/exynos-devfreq.c
grep -q 'apple_mif_v7: default ALT-DVFS MIF target_load ceiling' src/drivers/devfreq/exynos/exynos-devfreq.c
grep -q 'DEVICE_ATTR(apple_mif_v7_geometric' src/drivers/devfreq/exynos/exynos-devfreq.c
grep -q 'apple_mif_v7: 13 exponential MIF OPPs' src/drivers/devfreq/exynos/exynos-devfreq.c

echo "Compiling full Floppy kernel and matched modules, DTB/DTBO, native AnyKernel3"
export WP="$PWD"
cd src
export USE_CCACHE=0
export DO_TAR=0
export DO_ZIP=1
export AK3_BRANCH=floppy-unity
export DEFAULT_DEFCONFIG=exynos2100-unified_defconfig
export KBUILD_BUILD_USER=applefinal
export KBUILD_BUILD_HOST=Floppy-G991B
# q: Quiet output, vanilla root variant; root remains external to the kernel.
if ! bash do_build.sh q; then
  echo '=== KERNEL COMPILER ERRORS: src/log.txt ===' >&2
  echo '=== COMPILER ERROR LINES ===' >&2
  grep -nEi '(^|[[:space:]])(fatal )?error:|undefined (reference|symbol)|ld.lld:|failed to|make\[[0-9]+\]: \*\*\*' log.txt | tail -n 80 >&2 || true
  echo '=== FIRST 160 COMPILER LINES ===' >&2
  head -n 160 log.txt >&2 || true
  echo '=== TAIL 70 COMPILER LINES ===' >&2
  tail -n 70 log.txt >&2 || true
  exit 42
fi
cd ..

ZIP="$(find src/build -maxdepth 1 -type f -name 'Floppy_*Vanilla*.zip' | head -n 1)"
test -n "$ZIP" && test -s "$ZIP" || {
  echo "ERROR: missing complete Floppy AnyKernel ZIP" >&2; exit 34;
}
IMG=src/out/arch/arm64/boot/Image
test -s "$IMG"
test -s src/out/Module.symvers
sha256sum "$IMG" "$ZIP" | tee APPLE_FINAL_FLOPPY_SHA256SUMS.txt

cat > APPLE_FINAL_FLOPPY_README.txt <<EOF
APPLE FINAL x FLOPPYKERNEL - DEVELOPER BUILD
Kernel source: $FLOP_REPO
Base commit: $FLOP_COMMIT
Linux version: $(make -s -C src kernelversion)
Apple EMS 3x3 task migration layer: APPLIED
APPLE 1.4 UV: native Floppy CAL/ACPM margins at boot.
GPU 949 MHz: enabled at startup only when valid CAL/ASV+DT OPP; DVFS active.
MIF LAB V7: EXACTLY 13 descending exponential Linux MIF OPPs, nominal 4000 MHz max to 421 MHz min.
MIF rate is mathematical exp(-0.1876180672 * level); rounded to nearest kHz, NOT hardware-validated quantization.
An original native CAL boot frequency may replace the closest internal geometric slot at boot, retaining 13 entries and logging the modification.
Samsung's 13 native ECT frequencies remain independently available to firmware ACPM/FVP/constraint mapping, not as extra Linux OPPs.
ALT-DVFS default-mode governor target_load ceiling extended from 3172000 to 4000000 kHz at build time.
User ALT target_load table and other ALT modes retain their original behavior. Overclock is workload-triggered, not boot-forced.
V7 sysfs: /sys/class/devfreq/17000010.devfreq_mif/apple_mif_v7_geometric
MIF 4000 nominal: ACPM FREQ_GET acknowledgement and separate DDRPHY clock-framework readback; physical LPDDR5 speed still requires verification.
New geometric CAL/ASV voltages are only Linux OPP model estimates; they are NOT PMIC/DRAM voltage writes.
ACPM firmware and clock/PHY timing tables are NOT extended here; requested 4000000 may be unsupported even if echoed.
Unsupported candidate responses are logged and blocked again during this boot.
DRAM voltage/PLL/PHY timings are NOT changed; no proof firmware supports any new candidate.
MIF ECT stock boot/resume clocks unchanged, no LPDDR5 timing/training edits.
Not validated on hardware; kernel's CAL/OPP checks do not establish DRAM/PHY timing safety; rollback TWRP.
/sys/kernel/gpu/gpu_unlock=0 returns to original Floppy stock max.
CPU A55 -9%; CPU A78 -10%; CPU X1 -8%; GPU G3D -7%.
MIF -6%; DSU -6%; INT -6%.
Validated previously via readback on Samsung HZA6; NOT on Floppy 5.4.304.
Sysfs readback indicates kernel margin request, NOT physical PMIC voltage.
Original HZA6 Image reused: NO
GPU driver: native FloppyKernel, GPU unlock and CAL/ACPM
Artificial 910-1001 MHz OPPs added: NO
Old direct-PLL GPU OC patch: NOT APPLIED
Build variant: Vanilla
Hardware boot / GPU overclock / MIF 4000 validation: NOT PERFORMED
V7 is an experimental firmware-dependent kernel; retain V6 TWRP and original backups for rollback.
Different from old HZA6 5.4.242 ABI: full Image+vendor_boot+DTBO required
Back up boot, vendor_boot and dtbo before hardware testing.
EOF

mkdir -p apple_floppy_artifact
cp "$ZIP" apple_floppy_artifact/
cp "$IMG" apple_floppy_artifact/Image
cp src/out/Module.symvers apple_floppy_artifact/
cp APPLE_FINAL_FLOPPY_README.txt APPLE_FINAL_FLOPPY_SHA256SUMS.txt apple_floppy_artifact/
git -C src diff -- kernel/sched/ems/cpu_select.c > apple_floppy_artifact/APPLE_FINAL_EMS_PORT.patch
tar -czf APPLE_FINAL_BUILD.tar.gz -C apple_floppy_artifact .
echo "Floppy-based Apple Final full kernel build completed."
