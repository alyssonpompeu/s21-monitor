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

python3 apple-final/apply_apple_final.py

# Android 11 clang-r383902: exact toolchain family recorded by the HZA6 Image.
git clone --depth=1 --branch android11-release \
  https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86 \
  toolchain/clang
CLANG="$PWD/toolchain/clang/clang-r383902/bin"
[ -x "$CLANG/clang" ] || { find toolchain/clang -maxdepth 2 -type f -name clang -print; exit 32; }

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

# Prefer the exact HZA6 IKCONFIG when its split payload is present. The first
# CI pass can fall back to Samsung's o1s defconfig to validate source/toolchain.
if compgen -G 'apple-final/HZA6_exact.config.gz.b64.part*' > /dev/null; then
  cat apple-final/HZA6_exact.config.gz.b64.part* | base64 -d | gzip -dc > out/.config
  CONFIG_ORIGIN=IKCONFIG_exact_HZA6
else
  make -C src O="$PWD/out" ARCH=arm64 exynos2100-o1sxxx_defconfig
  CONFIG_ORIGIN=Samsung_o1s_defconfig_candidate
fi

sed -i 's/^CONFIG_LOCALVERSION=.*/CONFIG_LOCALVERSION="-30958140-abG991BXXSJHZA6"/' out/.config
sed -i 's/^CONFIG_LOCALVERSION_AUTO=y/# CONFIG_LOCALVERSION_AUTO is not set/' out/.config
cp out/.config out/config.before
make -C src O="$PWD/out" ARCH=arm64 olddefconfig
cp out/.config out/config.after
diff -u out/config.before out/config.after > out/config.diff || true

grep -q '^CONFIG_SCHED_EMS=y' out/.config
grep -q '^CONFIG_MODVERSIONS=y' out/.config

make -C src O="$PWD/out" ARCH=arm64 -j"$(nproc)" Image modules

IMG=out/arch/arm64/boot/Image
[ -s "$IMG" ]
strings "$IMG" | grep -m1 'Linux version' | tee out/linux-version.txt
sha256sum "$IMG" | tee out/Image.sha256
cp out/Module.symvers out/APPLE_FINAL_Module.symvers
cp out/.config out/APPLE_FINAL.config
cp "$IMG" out/APPLE_FINAL.Image

cat > out/APPLE_FINAL_BUILD_INFO.txt <<EOF
name=APPLE FINAL
source_repo=xfwdrev/android_kernel_samsung_ex2100
source_commit=$SRC_COMMIT
target_release=$KERNEL_RELEASE
config_origin=$CONFIG_ORIGIN
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
EOF

tar -C out -czf APPLE_FINAL_BUILD.tar.gz \
  APPLE_FINAL.Image APPLE_FINAL_Module.symvers APPLE_FINAL.config \
  APPLE_FINAL_BUILD_INFO.txt Image.sha256 linux-version.txt config.diff
