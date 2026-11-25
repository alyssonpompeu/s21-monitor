#!/usr/bin/env bash
# =============================================================================
#  build-s9-android16.sh
#  LineageOS 23.2 (Android 16) para Samsung Galaxy S9 Snapdragon SM-G9600
#  (codinome: starqltechn, SoC: SDM845, kernel 4.9)
#
#  ONDE RODAR: Ubuntu 22.04/24.04 (PC, WSL2 ou VM na nuvem)
#    Minimo: 8 nucleos, 32 GB RAM (ou 16 GB + swap), 400 GB livres em SSD
#
#  USO:
#    bash build-s9-android16.sh            # tudo (deps, sync, build)
#    bash build-s9-android16.sh sync       # so baixa/atualiza o codigo
#    bash build-s9-android16.sh build      # so compila
#    bash build-s9-android16.sh report     # so gera o relatorio
#
#  Opcional: VENDOR_DUMP=/caminho/da/dump bash build-s9-android16.sh
#    (pasta com system/ e vendor/ extraidos do firmware stock Android 10
#     do G9600, para extrair os blobs proprietarios)
#
#  AO FINAL: mande o conteudo de ~/s9-build/RELATORIO.txt no chat.
#
#  STATUS HONESTO: os device trees publicos param no Android 11/12
#  (lineage-19.1). Este script monta o ambiente e TENTA o build do
#  Android 16; o primeiro build quase certamente falha. O relatorio gerado
#  serve para eu corrigir o port iterativamente.
# =============================================================================
set -uo pipefail

WORK="${WORK:-$HOME/s9-build}"
SRC="$WORK/lineage"
LOG="$WORK/build.log"
REPORT="$WORK/RELATORIO.txt"
BRANCH="${BRANCH:-lineage-23.2}"   # Android 16 (QPR2). 23.0/23.1 tambem sao Android 16
DEVICE="starqltechn"
JOBS="${JOBS:-$(nproc)}"
STEP="${1:-all}"

mkdir -p "$WORK"
say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[AVISO] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[ERRO] %s\033[0m\n' "$*"; exit 1; }

check_host() {
  say "Verificando maquina"
  local ram_gb disk_gb
  ram_gb=$(awk '/MemTotal/{printf "%d", $2/1024/1024}' /proc/meminfo)
  disk_gb=$(df -BG --output=avail "$WORK" | tail -1 | tr -dc '0-9')
  echo "CPU: $(nproc) nucleos | RAM: ${ram_gb} GB | Disco livre: ${disk_gb} GB"
  [ "$disk_gb" -lt 300 ] && die "Precisa de pelo menos 300 GB livres (tem ${disk_gb} GB)."
  if [ "$ram_gb" -lt 30 ]; then
    warn "RAM < 32 GB. Criando swap de 32 GB para evitar falta de memoria."
    if ! swapon --show | grep -q "$WORK/swapfile"; then
      sudo fallocate -l 32G "$WORK/swapfile" && sudo chmod 600 "$WORK/swapfile" \
        && sudo mkswap "$WORK/swapfile" && sudo swapon "$WORK/swapfile" \
        || warn "Nao consegui criar swap; o build pode falhar por memoria."
    fi
    [ "$ram_gb" -lt 20 ] && JOBS=$(( JOBS > 4 ? 4 : JOBS ))
  fi
}

install_deps() {
  say "Instalando dependencias"
  sudo apt-get update
  sudo apt-get install -y bc bison build-essential ccache curl flex g++-multilib \
    gcc-multilib git git-lfs gnupg gperf imagemagick lib32readline-dev lib32z1-dev \
    libelf-dev liblz4-tool lz4 libsdl1.2-dev libssl-dev libxml2 libxml2-utils lzop \
    pngcrush rsync schedtool squashfs-tools xsltproc zip zlib1g-dev python3 \
    python-is-python3 protobuf-compiler python3-protobuf unzip openjdk-17-jdk-headless \
    || die "Falha ao instalar dependencias."
  mkdir -p "$HOME/bin"
  if [ ! -x "$HOME/bin/repo" ]; then
    curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o "$HOME/bin/repo"
    chmod a+x "$HOME/bin/repo"
  fi
  git config --global user.name  >/dev/null || git config --global user.name  "builder"
  git config --global user.email >/dev/null || git config --global user.email "builder@localhost"
  git lfs install
}

write_local_manifest() {
  mkdir -p "$SRC/.repo/local_manifests"
  cat > "$SRC/.repo/local_manifests/starqltechn.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <remote name="gh" fetch="https://github.com/" />
  <!-- Device tree S9 Snapdragon chines (base Android 12 - sera portado) -->
  <project name="matebuteler/android_device_samsung_starqltechn"
           path="device/samsung/starqltechn" remote="gh" revision="lineage-19.1" />
  <!-- Common SDM845 Samsung (base Android 11 - sera portado) -->
  <project name="dotOS-Devices/android_device_samsung_sdm845-common"
           path="device/samsung/sdm845-common" remote="gh" revision="eleven" />
  <!-- Kernel 4.9 SDM845 Samsung -->
  <project name="matebuteler/android_kernel_samsung_sdm845"
           path="kernel/samsung/sdm845" remote="gh" revision="Clang" />
</manifest>
EOF
}

do_sync() {
  export PATH="$HOME/bin:$PATH"
  say "Baixando codigo LineageOS $BRANCH (demora horas)"
  mkdir -p "$SRC" && cd "$SRC" || die "sem acesso a $SRC"
  [ -d .repo ] || repo init -u https://github.com/LineageOS/android.git -b "$BRANCH" \
      --git-lfs --depth=1 --no-clone-bundle || die "repo init falhou"
  write_local_manifest
  repo sync -c -j"$JOBS" --force-sync --no-tags --no-clone-bundle --optimized-fetch \
    || repo sync -c -j4 --force-sync --no-tags --fail-fast \
    || die "repo sync falhou (rode de novo; costuma ser rede)"
}

extract_blobs() {
  cd "$SRC" || return
  local ex
  ex=$(ls device/samsung/sdm845-common/extract-files.{sh,py} device/samsung/starqltechn/extract-files.{sh,py} 2>/dev/null | head -1)
  if [ -n "${VENDOR_DUMP:-}" ] && [ -n "$ex" ]; then
    say "Extraindo blobs proprietarios de $VENDOR_DUMP"
    ( cd "$(dirname "$ex")" && bash "./$(basename "$ex")" "$VENDOR_DUMP" ) 2>&1 | tee -a "$LOG"
  else
    warn "Sem blobs proprietarios (VENDOR_DUMP nao definido ou sem extract-files)."
    warn "O build vai falhar ao procurar vendor/samsung/... - isso e esperado no 1o ciclo."
  fi
}

do_build() {
  export PATH="$HOME/bin:$PATH"
  export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache CCACHE_DIR="$WORK/ccache"
  ccache -M 50G >/dev/null 2>&1
  cd "$SRC" || die "Codigo nao baixado. Rode: bash $0 sync"
  extract_blobs
  say "Compilando ($JOBS jobs). Log: $LOG"
  : > "$LOG"
  (
    set +u
    source build/envsetup.sh
    breakfast "$DEVICE" userdebug && m bacon -j"$JOBS"
  ) 2>&1 | tee -a "$LOG"
  local rc=${PIPESTATUS[0]}
  if [ "$rc" -eq 0 ]; then
    say "BUILD OK"
    ls -lh "$SRC"/out/target/product/$DEVICE/lineage-*.zip 2>/dev/null
  else
    warn "Build falhou (codigo $rc). Gerando relatorio."
  fi
}

do_report() {
  say "Gerando relatorio: $REPORT"
  {
    echo "### RELATORIO BUILD S9 SM-G9600 ($DEVICE) - $(date -u +%FT%TZ)"
    echo "branch=$BRANCH  nproc=$(nproc)  ram=$(free -g | awk '/Mem/{print $2}')G  disco_livre=$(df -h --output=avail "$WORK" | tail -1)"
    echo "os=$(. /etc/os-release; echo "$PRETTY_NAME")"
    echo
    echo "### Commits dos trees"
    for p in device/samsung/starqltechn device/samsung/sdm845-common kernel/samsung/sdm845; do
      [ -d "$SRC/$p" ] && echo "$p: $(git -C "$SRC/$p" log -1 --format='%h %ci %s' 2>/dev/null)"
    done
    echo
    echo "### Artefato"
    ls -lh "$SRC"/out/target/product/$DEVICE/lineage-*.zip 2>/dev/null || echo "nenhum zip gerado"
    echo
    echo "### Primeiros erros (unicos)"
    grep -nE '(error:|ERROR:|FAILED:|fatal:|No rule to make target|not defined|cannot find)' "$LOG" 2>/dev/null \
      | awk '!seen[substr($0, index($0,":")+1)]++' | head -60
    echo
    echo "### Ultimas 80 linhas do log"
    tail -n 80 "$LOG" 2>/dev/null
  } > "$REPORT"
  echo "Pronto. Mande o conteudo de: $REPORT"
  echo "(cat $REPORT  -> copie e cole no chat)"
}

case "$STEP" in
  all)    check_host; install_deps; do_sync; do_build; do_report ;;
  deps)   check_host; install_deps ;;
  sync)   check_host; do_sync ;;
  build)  do_build; do_report ;;
  report) do_report ;;
  *)      die "Passo desconhecido: $STEP (use all|deps|sync|build|report)" ;;
esac
