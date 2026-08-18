#!/usr/bin/env bash
set -euxo pipefail

SRC_COMMIT=3b9196f720169d810b42b79928e13d4a60450cd8
KERNEL_RELEASE='5.4.242-30958140-abG991BXXSJHZA6'

rm -rf src out toolchain
mkdir -p src out toolchain

git -C src init
git -C src remote add origin https://github.com/xfwdrev/android_kernel_samsung_ex2100.git
git -C src fetch --depth=1 origin "$SRC_COMMIT"
git -C src checkout FETCH_HEAD

# Exact configuration extracted from the user's HZA6 stock Image via IKCONFIG.
cat apple-final/HZA6_exact.config.gz.b64.part* | base64 -d | gzip -dc > src/.config
# Match the exact HZA6 module vermagic/release string while avoiding a git hash suffix.
sed -i 's/^CONFIG_LOCALVERSION=.*/CONFIG_LOCALVERSION="-30958140-abG991BXXSJHZA6"/' src/.config
sed -i 's/^CONFIG_LOCALVERSION_AUTO=y/# CONFIG_LOCALVERSION_AUTO is not set/' src/.config

python3 apple-final/apply_apple_final.py

# Android 11 clang-r383902 (same toolchain family recorded in the HZA6 IKCONFIG).
git clone --depth=1 --branch android11-release \
  https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86 \
  toolchain/clang

CLANG="$PWD/toolchain/clang/clang-r383902/bin"
if [ ! -x "$CLANG/clang" ]; then
  echo 'clang-r383902 not found on android11-release; probing repository' >&2
  find toolchain/clang -maxdepth 2 -type f -name clang -print >&2 || true
  exit 32
fi

# GNU 4.9 cross tools are still referenced by portions of this Samsung tree.
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

# Preserve exact .config; olddefconfig only resolves symbols the candidate source requires.
cp src/.config out/config.before
make -C src O="$PWD/out" ARCH=arm64 olddefconfig
cp out/.config out/config.after

diff -u out/config.before out/config.after > out/config.diff || true

grep -q '^CONFIG_SCHED_EMS=y' out/.config
grep -q '^CONFIG_MODVERSIONS=y' out/.config
grep -q '^CONFIG_LTO_CLANG=y' out/.config
grep -q '^CONFIG_CFI_CLANG=y' out/.config

make -C src O="$PWD/out" ARCH=arm64 -j"$(nproc)" Image modules

IMG=out/arch/arm64/boot/Image
[ -s "$IMG" ]
strings "$IMG" | grep -m1 'Linux version' | tee out/linux-version.txt
sha256sum "$IMG" | tee out/Image.sha256

# KMI evidence for offline comparison with exact HZA6 vendor modules.
cp out/Module.symvers out/APPLE_FINAL_Module.symvers
cp out/.config out/APPLE_FINAL.config
cp "$IMG" out/APPLE_FINAL.Image

cat > out/APPLE_FINAL_BUILD_INFO.txt <<EOF
name=APPLE FINAL
source_repo=xfwdrev/android_kernel_samsung_ex2100
source_commit=$SRC_COMMIT
target_release=$KERNEL_RELEASE
ems_3x3=YES
matrix_A55=0,18,45
matrix_A78=28,0,25
matrix_X1=55,35,0
cache_locality=YES
cross_cluster_hysteresis=YES
gpu_oc=NO
gpu_max_target_kHz=858000
cpu_oc=NO
mif_oc=NO
thermal_bypass=NO
config_origin=IKCONFIG_from_exact_HZA6_Image
EOF

tar -C out -czf APPLE_FINAL_BUILD.tar.gz \
  APPLE_FINAL.Image APPLE_FINAL_Module.symvers APPLE_FINAL.config \
  APPLE_FINAL_BUILD_INFO.txt Image.sha256 linux-version.txt config.diff
