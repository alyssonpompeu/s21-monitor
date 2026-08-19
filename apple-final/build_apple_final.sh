#!/usr/bin/env bash
set -euxo pipefail

KERNEL_RELEASE='5.4.242-30958140-abG991BXXSJHZA6'
SOURCE_BRANCH='rebased-11'
SRC_COMMIT='d26be7c85bf2e84881ffefa46daddcf9e0e0aa1f'

rm -rf src out toolchain
mkdir -p out toolchain src

# The validated Samsung/Exynos 5.4.242 + EMS revision is pinned. Fetch only
# this exact commit so retries are deterministic and avoid scanning history.
git -C src init
git -C src remote add origin https://github.com/xfwdrev/android_kernel_samsung_ex2100.git
git -C src fetch --depth=1 origin "$SRC_COMMIT"
git -C src checkout --detach FETCH_HEAD

echo "Pinned Samsung EMS 5.4.242 source: $SRC_COMMIT"
grep -q '^SUBLEVEL[[:space:]]*=[[:space:]]*242$' src/Makefile
[ -f src/kernel/sched/ems/cpu_select.c ]

python3 apple-final/apply_apple_final.py

grep -q 'APPLE FINAL EMS 3x3' src/kernel/sched/ems/cpu_select.c
grep -q '{  0, 18, 45 }' src/kernel/sched/ems/cpu_select.c
grep -q '{ 28,  0, 25 }' src/kernel/sched/ems/cpu_select.c
grep -q '{ 55, 35,  0 }' src/kernel/sched/ems/cpu_select.c

# Samsung's FMP integrity helper predates the Clang ELF layout used by the
# exact HZA6 compiler. Its parser incorrectly searches for a literal
# '.shstrtab' string, but Clang may use the section at e_shstrndx while naming
# it '.strtab'. Use the ELF header's authoritative e_shstrndx instead. This is
# a build-tool compatibility fix only; it does not change generated kernel
# scheduler code, FMP policy, crypto algorithms, or runtime behavior.
python3 - <<'PY'
from pathlib import Path
p = Path('src/scripts/fmp/ELF.py')
s = p.read_text()
old = '''\tdef find_section_names(self):
\t\t__str_offset_sechdr_lst = []
\t\tfor __shdr in self.shdr:
\t\t\tif __shdr.sh_type == SHT_STRTAB:
\t\t\t\t__start_bin_idx = __shdr.sh_offset
\t\t\t\t__end_bin_idx = __shdr.sh_offset + __shdr.sh_size
\t\t\t\t__temp_lst = find_pattern(self.binary_img[__start_bin_idx: __end_bin_idx],
\t\t\t\t\t\t\t\t\t\t  string_to_bytearray(DEFAULT_NAME_SECTION_SHSTRTAB))
\t\t\t\tif len(__temp_lst) > 0:
\t\t\t\t\t__str_offset_sechdr_lst.append([__shdr, __temp_lst])

\t\tif len(__str_offset_sechdr_lst) != 1 or len(__str_offset_sechdr_lst[0][1]) != 1:
\t\t\tlog_e(" ERROR: something wrong with section name string search ")
\t\t\traise RuntimeError

\t\t__sh_strtab = __str_offset_sechdr_lst[0][0]
\t\tfor __shdr in self.shdr:
\t\t\t__shdr.sh_set_name(self.binary_img[__sh_strtab.sh_offset:
\t\t\t\t\t\t\t\t\t__sh_strtab.sh_offset + __sh_strtab.sh_size ])
'''
new = '''\tdef find_section_names(self):
\t\t# ELF64_Ehdr.e_shstrndx is the authoritative section-name string table.
\t\t# Clang 11 can use a table named .strtab here, so searching for the
\t\t# literal .shstrtab string is not portable.
\t\t__idx = self.ehdr.e_shstrndx
\t\tif __idx <= 0 or __idx >= len(self.shdr):
\t\t\tlog_e(" ERROR: invalid ELF section-name string-table index ")
\t\t\traise RuntimeError
\t\t__sh_strtab = self.shdr[__idx]
\t\tif __sh_strtab.sh_type != SHT_STRTAB:
\t\t\tlog_e(" ERROR: ELF section-name table is not SHT_STRTAB ")
\t\t\traise RuntimeError
\t\tfor __shdr in self.shdr:
\t\t\t__shdr.sh_set_name(self.binary_img[__sh_strtab.sh_offset:
\t\t\t\t\t\t\t\t\t__sh_strtab.sh_offset + __sh_strtab.sh_size ])
'''
if old not in s:
    raise SystemExit('FMP ELF parser block not found; refusing blind patch')
p.write_text(s.replace(old, new, 1))
PY

grep -q 'authoritative section-name string table' src/scripts/fmp/ELF.py

# Exact compiler generation printed by HZA6 stock Image.
git clone --filter=blob:none --no-checkout --depth=1 --branch android11-release \
  https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86 \
  toolchain/clang-repo
git -C toolchain/clang-repo sparse-checkout init --cone
git -C toolchain/clang-repo sparse-checkout set clang-r383902
git -C toolchain/clang-repo checkout
CLANG="$PWD/toolchain/clang-repo/clang-r383902/bin"
if [ ! -x "$CLANG/clang" ]; then
  echo 'clang-r383902 missing from official Android android11-release' >&2
  exit 32
fi
"$CLANG/clang" --version | tee out/clang-version.txt

git clone --depth=1 --branch android11-release \
  https://android.googlesource.com/platform/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-4.9 \
  toolchain/gcc49

export ARCH=arm64
export SUBARCH=arm64
export PLATFORM_VERSION=11
export ANDROID_MAJOR_VERSION=r
export PATH="$CLANG:$PWD/toolchain/gcc49/bin:$PATH"
export LLVM=1
export LLVM_IAS=1
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

# Reconstruct exact HZA6 IKCONFIG extracted from stock Image.
cat apple-final/HZA6_exact.config.gz.b64.part* | base64 -d | gzip -dc > out/.config
CONFIG_ORIGIN=IKCONFIG_exact_HZA6

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

# Samsung's production config references an internal build-server-only GKI
# whitelist. It is not part of the released source. Do not substitute a partial
# whitelist because that could trim exports needed by vendor modules. The KMI
# CRC gate below remains mandatory before any successful artifact is accepted.
sed -i 's/^CONFIG_TRIM_UNUSED_KSYMS=y/# CONFIG_TRIM_UNUSED_KSYMS is not set/' out/.config
sed -i 's|^CONFIG_UNUSED_KSYMS_WHITELIST=.*|CONFIG_UNUSED_KSYMS_WHITELIST=""|' out/.config
make -C src O="$PWD/out" ARCH=arm64 olddefconfig

cp out/.config out/config.after
diff -u out/config.before out/config.after > out/config.diff || true

grep -q '^CONFIG_SCHED_EMS=y' out/.config
grep -q '^CONFIG_SCHED_EMS_TUNE=y' out/.config
grep -q '^CONFIG_MODVERSIONS=y' out/.config
grep -q '^CONFIG_MODULES=y' out/.config
grep -q '^CONFIG_LTO_CLANG=y' out/.config
grep -q '^CONFIG_CFI_CLANG=y' out/.config
grep -q '^# CONFIG_TRIM_UNUSED_KSYMS is not set' out/.config

# This Samsung tree hardcodes CONFIG_IKCONFIG's source to a proprietary
# exynos2100-p3sxxx_defconfig that is absent from the public source release.
# Supply the exact post-olddefconfig HZA6 configuration we already recovered
# from the stock Image. This preserves IKCONFIG instead of disabling it.
mkdir -p src/arch/arm64/configs
cp out/.config src/arch/arm64/configs/exynos2100-p3sxxx_defconfig
cmp -s out/.config src/arch/arm64/configs/exynos2100-p3sxxx_defconfig

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
trim_unused_ksyms=NO
kmi_gate=PASS
EOF

tar -C out -czf APPLE_FINAL_BUILD.tar.gz \
  APPLE_FINAL.Image APPLE_FINAL_Module.symvers APPLE_FINAL.config \
  APPLE_FINAL_BUILD_INFO.txt APPLE_FINAL_source.patch APPLE_FINAL_KMI_REPORT.txt \
  Image.sha256 linux-version.txt clang-version.txt config.diff source-commit.txt
