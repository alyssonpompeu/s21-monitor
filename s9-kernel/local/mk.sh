#!/bin/bash
# build local do kernel S9 com clang 18 do sistema (sem GCC/binutils GNU de aarch64)
export PATH=/home/claude/tc/bin:$PATH
cd /home/claude/s9src
exec make -j"$(nproc)" O=/home/claude/kout ARCH=arm64 SUBARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- CLANG_TRIPLE=aarch64-linux-gnu- \
  CC=clang LD=ld.lld AR=llvm-ar NM=llvm-nm OBJCOPY=llvm-objcopy OBJDUMP=llvm-objdump \
  STRIP=llvm-strip HOSTCC=gcc HOSTCFLAGS="-O2 -std=gnu89 -fcommon" \
  LOCALVERSION= "$@"
