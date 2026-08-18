#!/usr/bin/env bash
set -euxo pipefail

KERNEL_RELEASE='5.4.242-30958140-abG991BXXSJHZA6'
SOURCE_BRANCH='rebased-11'

rm -rf src out toolchain
mkdir -p out toolchain

# Pull the Samsung/Exynos history, not the generic Android 5.4 branch. We use
# partial clone so history can be inspected cheaply; blobs are fetched only for
# the revision that is finally checked out.
git clone --filter=blob:none --no-checkout --single-branch --branch "$SOURCE_BRANCH" \
  https://github.com/xfwdrev/android_kernel_samsung_ex2100.git src

# Find the newest revision in Samsung's rebased-11 lineage for which BOTH are
# true: kernel SUBLEVEL is exactly 242 and Samsung EMS cpu_select.c exists.
# This avoids the earlier generic 5.4.242 merge, which had no Exynos EMS tree.
SRC_COMMIT=''
while read -r C; do
  [ -n "$C" ] || continue
  SUB="$(git -C src show "$C:Makefile" 2>/dev/null | sed -n 's/^SUBLEVEL[[:space:]]*=[[:space:]]*//p' | head -1 || true)"
  [ "$SUB" = '242' ] || continue
  if git -C src cat-file -e "$C:kernel/sched/ems/cpu_select.c" 2>/dev/null; then
    SRC_COMMIT="$C"
    break
  fi
done < <(git -C src log "$SOURCE_BRANCH" --format='%H' -- Makefile)

if [ -z "$SRC_COMMIT" ]; then
  echo 'No Samsung EMS revision with SUBLEVEL=242 found in rebased-11' >&2
  exit 21
fi

echo "Selected Samsung EMS 5.4.242 source: $SRC_COMMIT"
git -C src checkout --detach "$SRC_COMMIT"

grep -q '^SUBLEVEL[[:space:]]*=[[:space:]]*242$' src/Makefile
[ -f src/kernel/sched/ems/cpu_select.c ]

python3 apple-final/apply_apple_final.py

grep -q 'APPLE FINAL EMS 3x3' src/kernel/sched/ems/cpu_select.c
grep -q '{  0, 18, 45 }' src/kernel/sched/ems/cpu_select.c
grep -q '{ 28,  0, 25 }' src/kernel/sched/ems/cpu_select.c
grep -q '{ 55, 35,  0 }' src/kernel/sched/ems/cpu_select.c

# Android clang r383902 is the exact compiler generation printed by the HZA6
# stock Image. Keep the older GNU cross binutils available for Samsung scripts.
git clone --depth=1 --branch android11-release \
  https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86 \
  toolchain/clang
CLANG="$PWD/toolchain/clang/clang-r383902/bin"
if [ ! -x "$CLANG/clang" ]; then
  echo 'clang-r383902 missing from android11-release' >&2
  find toolchain/clang -maxdepth 3 -type f -name clang -print >&2 || true
  exit 32
fi

git clone --depth=1 --branch android11-release \
  https://android.googlesource.com/platform/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-4.9 \
  toolchain/gcc49

export ARCH=arm64
export SUBARCH=arm64
export PLATFORM_VERSION=11
export ANDROID_MAJOR_VERSION=r
export PATH="$CLANG:$PWD/toolchain/gcc49/bin:$PATH"
export CC=clang
export LD=ld.lld
export AR=llvm-ar
export NM=llvm-nm
export OBJCOPY=llvm-objcopy
export OBJDUMP=llvm-objdump
export STRIP=llvm-strip
export CROSS_COMPILE=aarch64-linux-android-
export CLANG_TRIPLE=aarch64-linux-gnu-
export KBUILD_BUILD_USER=applefinal
export KBUILD_BUILD_HOST=HZA6
export KBUILD_BUILD_TIMESTAMP='Wed Jan 21 13:36:52 KST 2026'

# Reconstruct the exact HZA6 IKCONFIG extracted from the user's stock Image.
cat apple-final/HZA6_exact.config.gz.b64.part* | base64 -d | gzip -dc > out/.config
CONFIG_ORIGIN=IKCONFIG_exact_HZA6

# HZA6 modules use this exact release string. Disable automatic git suffixes so
# vermagic can be compared directly with the live/audio vendor modules.
if grep -q '^CONFIG_LOCALVERSION=' out/.config; then
  sed -i 's/^CONFIG_LOCALVERSION=.*/CONFIG_LOCALVERSION="-30958140-abG991BXXSJHZA6"/' out/.config
else
  echo 'CONFIG_LOCALVERSION="-30958140-abG991BXXSJHZA6"' >> out/.config
fi
if grep -q '^CONFIG_LOCALVERSION_AUTO=y' out/.config; then
  sed -i 's/^CONFIG_LOCALVERSION_AUTO=y/# CONFIG_LOCALVERSION_AUTO is not set/' out/.config
fi

cp out/.config out/config.before
make -C src O="$PWD/out" ARCH=arm64 olddefconfig
cp out/.config out/config.after
diff -u out/config.before out/config.after > out/config.diff || true

# Configuration gates for the exact design/ABI we depend on.
grep -q '^CONFIG_SCHED_EMS=y' out/.config
grep -q '^CONFIG_SCHED_EMS_TUNE=y' out/.config
grep -q '^CONFIG_MODVERSIONS=y' out/.config
grep -q '^CONFIG_MODULES=y' out/.config
grep -q '^CONFIG_LTO_CLANG=y' out/.config
grep -q '^CONFIG_CFI_CLANG=y' out/.config

# No frequency table/PLL OC patch is applied anywhere in APPLE FINAL.
if git -C src diff -- kernel/sched/ems/cpu_select.c | grep -Ei 'mali|g3d|pll|1001000|1000000|936000|975000'; then
  echo 'Unexpected GPU/PLL material in APPLE FINAL diff' >&2
  exit 41
fi

make -C src O="$PWD/out" ARCH=arm64 -j"$(nproc)" Image modules

IMG=out/arch/arm64/boot/Image
[ -s "$IMG" ]
strings "$IMG" | grep -m1 'Linux version' | tee out/linux-version.txt
sha256sum "$IMG" | tee out/Image.sha256
[ -s out/Module.symvers ]

# Exact HZA6 ABI/KMI gate. Any CRC conflict against symbols imported by the
# active Mali/CAL/final-audio modules rejects the build before packaging.
python3 apple-final/check_kmi.py \
  apple-final/HZA6_KMI_CRITICAL.txt \
  out/Module.symvers \
  out/APPLE_FINAL_KMI_REPORT.txt

grep -q '^gate=PASS$' out/APPLE_FINAL_KMI_REPORT.txt

cp out/Module.symvers out/APPLE_FINAL_Module.symvers
cp out/.config out/APPLE_FINAL.config
cp "$IMG" out/APPLE_FINAL.Image
git -C src diff > out/APPLE_FINAL_source.patch

git -C src show -s --format='%H%n%P%n%cd%n%s' "$SRC_COMMIT" > out/source-commit.txt

cat > out/APPLE_FINAL_BUILD_INFO.txt <<EOF
name=APPLE FINAL
source_repo=xfwdrev/android_kernel_samsung_ex2100
source_branch=$SOURCE_BRANCH
source_commit=$SRC_COMMIT
target_release=$KERNEL_RELEASE
config_origin=$CONFIG_ORIGIN
ems_3x3=YES
matrix_A55=0,18,45
matrix_A78=28,0,25
matrix_X1=55,35,0
cache_locality=YES
cross_cluster_hysteresis=YES
x1_sprint_policy=YES
gpu_oc=NO
gpu_max_target_kHz=858000
cpu_oc=NO
mif_oc=NO
thermal_bypass=NO
kmi_gate=PASS
EOF

tar -C out -czf APPLE_FINAL_BUILD.tar.gz \
  APPLE_FINAL.Image APPLE_FINAL_Module.symvers APPLE_FINAL.config \
  APPLE_FINAL_BUILD_INFO.txt APPLE_FINAL_source.patch APPLE_FINAL_KMI_REPORT.txt \
  Image.sha256 linux-version.txt config.diff source-commit.txt
