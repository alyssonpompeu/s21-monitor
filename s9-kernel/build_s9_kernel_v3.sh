#!/usr/bin/env bash
# S9 (starqltechn, SDM845 v2.1) - kernel v3.
# CPU com frequencias novas na tensao de fabrica, GPU ate 900 MHz, voto extra de
# memoria, governadores, audio 15->18 dB, toque sensivel e reforco ao tocar.
# Fonte, compilador e config fixos.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$PWD/s9work}"
SRC_URL=https://github.com/klabit87/android_kernel_samsung_sdm845.git
SRC_BRANCH=lineage-19.0
SRC_COMMIT=66ad1f12cf577c346d8bd3580426b98119ecd672
TC_URL=https://github.com/LineageOS/android_prebuilts_gcc_linux-x86_aarch64_aarch64-linux-android-4.9.git
TC_BRANCH=lineage-19.1
V2_PKG="${V2_PKG:-}"   # pasta com o pacote do kernel v2 (traz v2, v1 e original)

mkdir -p "$WORK" && cd "$WORK"
[ -d src ] || git clone --depth 1 -b "$SRC_BRANCH" "$SRC_URL" src
[ "$(git -C src rev-parse HEAD)" = "$SRC_COMMIT" ] || { echo "ERRO: commit do fonte diferente do fixado"; exit 1; }
[ -d tc ] || git clone --depth 1 -b "$TC_BRANCH" "$TC_URL" tc
git -C src checkout -q -- . && git -C src clean -qfd
echo "toolchain commit: $(git -C tc rev-parse HEAD)"

python3 "$HERE/apply_s9_kernel_v3.py" src "$HERE/tables_v3.inc"
git -C src diff --stat
# 15 .dts planos do starqlte-chn + 4 fontes; nada alem disso pode mudar
CHANGED="$(git -C src diff --name-only | sort)"
EXPECTED="$( { ls src/arch/arm64/boot/dts/samsung/sdm845-sec-starqlte-chn-r*.dts | sed 's#^src/##'; printf '%s\n' drivers/clk/qcom/clk-cpu-osm.c drivers/clk/qcom/gpucc-sdm845.c drivers/cpufreq/cpu-boost.c drivers/devfreq/governor_msm_adreno_tz.c drivers/input/touchscreen/sec_ts/sec_ts.c kernel/sched/fair.c techpack/audio/asoc/codecs/max98512.c; } | sort )"
[ "$CHANGED" = "$EXPECTED" ] || { echo "ERRO: arquivos alterados diferentes do esperado"; diff <(echo "$CHANGED") <(echo "$EXPECTED") || true; exit 1; }
[ "$(echo "$CHANGED" | wc -l)" = "22" ] || { echo "ERRO: esperava 22 arquivos alterados"; exit 1; }

echo "--- teste unitario da troca de multiplicador na LUT (funcao extraida do fonte ja alterado)"
mkdir -p ut && cp "$HERE/tests/test_osm_rewrite_v3.c" "$HERE/tests/s9_lut_data.c" ut/
python3 - src/drivers/clk/qcom/clk-cpu-osm.c ut/s9_osm_snip.c <<'PY'
import sys
t = open(sys.argv[1]).read()
a = t.index("/* ---- S9 kernel v3: overclock")
b = t.index("static int clk_osm_read_lut(struct platform_device *pdev, struct clk_osm *c)\n", a)
open(sys.argv[2], "w").write(t[a:b])
PY
gcc -Wall -Wno-unused-parameter -o ut/test_osm ut/test_osm_rewrite_v3.c && ut/test_osm

touch src/.scmversion   # evita sufixo "+" na versao
rm -rf out && mkdir out
zcat "$HERE/rom_kernel.config.gz" > out/.config
export PATH="$WORK/tc/bin:$PATH"
aarch64-linux-android-gcc --version | head -n 1
# scripts/gcc-wrapper.py do fonte e Python 2 e trata aviso como erro (builds
# 43549560415 e 43550443448): Python 2 disponivel e GCC chamado direto.
command -v python2 >/dev/null || { echo "ERRO: python2 ausente"; exit 1; }
MK=(make -C src O="$WORK/out" ARCH=arm64 CROSS_COMPILE=aarch64-linux-android- KCFLAGS=-mno-android
    PYTHON=python2 HOSTCFLAGS="-O2 -fomit-frame-pointer -std=gnu89 -fcommon"
    CC=aarch64-linux-android-gcc)
# reforco ao tocar: o driver existe no fonte mas vinha desligado na config da ROM
echo "CONFIG_CPU_BOOST=y" >> out/.config
"${MK[@]}" olddefconfig
grep -q '^CONFIG_CPU_BOOST=y$' out/.config || { echo "ERRO: CONFIG_CPU_BOOST nao ficou ativo"; exit 1; }
grep -q '^CONFIG_LOCALVERSION="-klabitV6.5-ZRAM"$' out/.config || { echo "ERRO: LOCALVERSION mudou"; exit 1; }
"${MK[@]}" -j"$(nproc)"

IMG=out/arch/arm64/boot/Image.gz-dtb
[ -f "$IMG" ] || { echo "ERRO: $IMG nao foi gerado"; ls -la out/arch/arm64/boot; exit 1; }
python3 "$HERE/check_kernel_image.py" "$IMG" "$HERE/boot_original.img"
python3 "$HERE/check_v3_image.py" "$IMG"

python3 "$HERE/repack_bootimg.py" "$HERE/boot_original.img" "$IMG" "$WORK/S9_kernel_v3_boot.img"
mkdir -p "$WORK/pacote" "$WORK/rb"
python3 "$HERE/make_twrp_zip.py" "$WORK/S9_kernel_v3_boot.img" "$WORK/pacote/S9_kernel_v3_TWRP.zip" \
  "S9 kernel v3: CPU A75 2995,2 / A55 1996,8 MHz na tensao de fabrica, GPU ate 900 MHz, audio 18 dB, toque sensivel, reforco ao tocar. EXPERIMENTAL."
python3 "$HERE/make_twrp_zip.py" "$HERE/boot_original.img" "$WORK/pacote/S9_ROLLBACK_kernel_original_TWRP.zip" \
  "S9 rollback: kernel original da PixelExperience Plus 12.1 (build 20221101)"
bash "$HERE/tests/test_installer.sh" "$WORK/pacote/S9_kernel_v3_TWRP.zip" "$WORK/S9_kernel_v3_boot.img"
bash "$HERE/tests/test_installer.sh" "$WORK/pacote/S9_ROLLBACK_kernel_original_TWRP.zip" "$HERE/boot_original.img"
for v in v1 v2; do
  case $v in v1) Z="S9_ROLLBACK_kernel_v1_TWRP.zip"; D="S9 rollback: kernel v1 (GPU undervolt etapa 1 + telemetria), validado em 14/11/2026";;
             v2) Z="S9_kernel_v2_TWRP.zip"; D="S9 rollback: kernel v2 (GPU tabela B, governadores; GPU estavel ate 798 MHz)";; esac
  SRCZ=$(find "${V2_PKG:-/nonexistent}" -name "$Z" 2>/dev/null | head -n 1 || true)
  if [ -n "$SRCZ" ]; then
    rm -f "$WORK/rb/boot.img"; unzip -o -q "$SRCZ" boot.img -d "$WORK/rb"
    mv "$WORK/rb/boot.img" "$WORK/rb/$v.img"
    python3 "$HERE/make_twrp_zip.py" "$WORK/rb/$v.img" "$WORK/pacote/S9_ROLLBACK_kernel_${v}_TWRP.zip" "$D"
    bash "$HERE/tests/test_installer.sh" "$WORK/pacote/S9_ROLLBACK_kernel_${v}_TWRP.zip" "$WORK/rb/$v.img"
  else
    echo "AVISO: pacote do kernel $v nao disponivel; rollback $v nao incluido"
  fi
done
cp "$HERE/S9_clocks_kernel_v3.txt" "$WORK/pacote/"
( cd "$WORK/pacote" && sha256sum * > S9_SHA256SUMS.txt && cat S9_SHA256SUMS.txt )
echo "BUILD v3 CONCLUIDO (compilado; NAO testado em aparelho)"
