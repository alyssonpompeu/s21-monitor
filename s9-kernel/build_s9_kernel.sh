#!/usr/bin/env bash
# S9 (starqltechn, SDM845) - kernel v1: GPU undervolt etapa 1 + telemetria OSM (leitura).
# Reproduzivel: fonte e compilador fixos, config extraida do kernel da ROM.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$PWD/s9work}"
SRC_URL=https://github.com/klabit87/android_kernel_samsung_sdm845.git
SRC_BRANCH=lineage-19.0
SRC_COMMIT=66ad1f12cf577c346d8bd3580426b98119ecd672
TC_URL=https://github.com/LineageOS/android_prebuilts_gcc_linux-x86_aarch64_aarch64-linux-android-4.9.git
TC_BRANCH=lineage-19.1
OUTNAME=S9_kernel_v1_gpu_uv_step1_boot.img

mkdir -p "$WORK" && cd "$WORK"
[ -d src ] || git clone --depth 1 -b "$SRC_BRANCH" "$SRC_URL" src
[ "$(git -C src rev-parse HEAD)" = "$SRC_COMMIT" ] || { echo "ERRO: commit do fonte diferente do fixado"; exit 1; }
[ -d tc ] || git clone --depth 1 -b "$TC_BRANCH" "$TC_URL" tc
git -C src checkout -q -- . && git -C src clean -qfd
echo "toolchain commit: $(git -C tc rev-parse HEAD)"

python3 "$HERE/apply_s9_gpu_uv_step1.py" src
python3 "$HERE/apply_s9_cpu_volt_telemetry.py" src
git -C src diff --stat
[ "$(git -C src diff --name-only | sort | tr '\n' ' ')" = "drivers/clk/qcom/clk-cpu-osm.c drivers/clk/qcom/gpucc-sdm845.c " ] \
  || { echo "ERRO: arquivos alterados diferentes do esperado"; exit 1; }
touch src/.scmversion   # evita sufixo "+" na versao

rm -rf out && mkdir out
zcat "$HERE/rom_kernel.config.gz" > out/.config
export PATH="$WORK/tc/bin:$PATH"
aarch64-linux-android-gcc --version | head -n 1
# scripts/gcc-wrapper.py do fonte e Python 2: com Python 3 ele quebra ao repassar
# qualquer aviso do compilador (falha vista no build 43549560415).
command -v python2 >/dev/null || { echo "ERRO: python2 ausente (necessario para scripts/gcc-wrapper.py)"; exit 1; }
MK=(make -C src O="$WORK/out" ARCH=arm64 CROSS_COMPILE=aarch64-linux-android- KCFLAGS=-mno-android
    PYTHON=python2 HOSTCFLAGS="-O2 -fomit-frame-pointer -std=gnu89 -fcommon"
    CC=aarch64-linux-android-gcc)
# CC direto: o gcc-wrapper.py transforma qualquer aviso em erro e parava em
# fs/proc/cmdline.c:40 (frame de 2064 bytes, codigo original do klabit; build
# 43550443448). O wrapper so filtra avisos; nao muda o codigo gerado.
"${MK[@]}" olddefconfig
grep -q '^CONFIG_LOCALVERSION="-klabitV6.5-ZRAM"$' out/.config || { echo "ERRO: LOCALVERSION mudou"; exit 1; }
echo "--- diferencas de config em relacao ao kernel da ROM:"
diff <(zcat "$HERE/rom_kernel.config.gz" | grep -E '^(CONFIG_|# CONFIG_)' | sort) \
     <(grep -E '^(CONFIG_|# CONFIG_)' out/.config | sort) || true
"${MK[@]}" -j"$(nproc)"

IMG=out/arch/arm64/boot/Image.gz-dtb
[ -f "$IMG" ] || { echo "ERRO: $IMG nao foi gerado"; ls -la out/arch/arm64/boot; exit 1; }
python3 "$HERE/check_kernel_image.py" "$IMG" "$HERE/boot_original.img"
python3 "$HERE/repack_bootimg.py" "$HERE/boot_original.img" "$IMG" "$WORK/$OUTNAME"
cp "$HERE/boot_original.img" "$WORK/S9_ROLLBACK_boot_original.img"
( cd "$WORK" && sha256sum "$OUTNAME" S9_ROLLBACK_boot_original.img > S9_SHA256SUMS.txt && cat S9_SHA256SUMS.txt )
echo "BUILD CONCLUIDO (compilado; NAO testado em aparelho)"
