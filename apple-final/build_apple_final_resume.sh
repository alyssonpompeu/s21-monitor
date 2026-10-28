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
grep -q 'APPLE FINAL EMS 3x3' src/kernel/sched/ems/cpu_select.c
grep -q 'apple_final_keep_prev(env, best_cpu)' src/kernel/sched/ems/cpu_select.c
grep -q 'gpu_unlock' src/drivers/gpu/arm/exynos/frontend/gpex_clock_sysfs.c
grep -q 'gpu_stock_max_clock' src/drivers/gpu/arm/exynos/frontend/gpex_clock.c
grep -q '^CONFIG_SOC_EXYNOS2100_CL0_UV=9
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
bash do_build.sh q
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
APPLE 1.4 UV: native Floppy CAL/ACPM request at boot.
CPU A55 -9%; CPU A78 -10%; CPU X1 -8%; GPU G3D -7%.
MIF -6%; DSU -6%; INT -6%.
Only previously confirmed via readback on Samsung HZA6.
NOT yet device-validated on FloppyKernel 5.4.304.
Sysfs readback indicates kernel-requested margin, NOT physical PMIC voltage.
Original HZA6 Image reused: NO
GPU driver: native FloppyKernel, GPU unlock and CAL/ACPM
Artificial 910-1001 MHz OPPs added: NO
Old direct-PLL GPU OC patch: NOT APPLIED
Build variant: Vanilla
Hardware boot / GPU overclock validation: NOT PERFORMED
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
 src/arch/arm64/configs/exynos2100-unified_defconfig
grep -q '^CONFIG_SOC_EXYNOS2100_CL1_UV=10
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
bash do_build.sh q
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
Original HZA6 Image reused: NO
GPU driver: native FloppyKernel, GPU unlock and CAL/ACPM
Artificial 910-1001 MHz OPPs added: NO
Old direct-PLL GPU OC patch: NOT APPLIED
Build variant: Vanilla
Hardware boot / GPU overclock validation: NOT PERFORMED
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
 src/arch/arm64/configs/exynos2100-unified_defconfig
grep -q '^CONFIG_SOC_EXYNOS2100_CL2_UV=8
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
bash do_build.sh q
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
Original HZA6 Image reused: NO
GPU driver: native FloppyKernel, GPU unlock and CAL/ACPM
Artificial 910-1001 MHz OPPs added: NO
Old direct-PLL GPU OC patch: NOT APPLIED
Build variant: Vanilla
Hardware boot / GPU overclock validation: NOT PERFORMED
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
 src/arch/arm64/configs/exynos2100-unified_defconfig
grep -q '^CONFIG_SOC_EXYNOS2100_GPU_UV=7
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
bash do_build.sh q
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
Original HZA6 Image reused: NO
GPU driver: native FloppyKernel, GPU unlock and CAL/ACPM
Artificial 910-1001 MHz OPPs added: NO
Old direct-PLL GPU OC patch: NOT APPLIED
Build variant: Vanilla
Hardware boot / GPU overclock validation: NOT PERFORMED
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
 src/arch/arm64/configs/exynos2100-unified_defconfig
grep -q '^static int margin_mif = -6;' src/drivers/soc/samsung/cal-if/fvmap.c
grep -q '^static int margin_dsu = -6;' src/drivers/soc/samsung/cal-if/fvmap.c
grep -q '^static int margin_int = -6;' src/drivers/soc/samsung/cal-if/fvmap.c

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
bash do_build.sh q
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
Original HZA6 Image reused: NO
GPU driver: native FloppyKernel, GPU unlock and CAL/ACPM
Artificial 910-1001 MHz OPPs added: NO
Old direct-PLL GPU OC patch: NOT APPLIED
Build variant: Vanilla
Hardware boot / GPU overclock validation: NOT PERFORMED
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
