#!/usr/bin/env bash
# Builds Termux's full PRoot (fake root -0, --link2symlink, seccomp acceleration) for the
# in-app Linux environment. Adapted from DroidDeck's tools/proot (GPL-3.0).
# Usage: NDK=/path/to/android-ndk tools/linux-proot/build.sh app/src/modern/jniLibs/arm64-v8a
# Output: libprootlinux.so + libprootlinux-loader.so (distinct names from Winlator's reduced proot).
set -euo pipefail
OUTDIR=$(mkdir -p "$1" && cd "$1" && pwd)
: "${NDK:?set NDK to the Android NDK root}"
API=26
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/source.env"
case "$(uname -s):$(uname -m)" in
  Darwin:*) NDK_HOST=darwin-x86_64 ;;
  Linux:x86_64) NDK_HOST=linux-x86_64 ;;
  Linux:aarch64|Linux:arm64) NDK_HOST=linux-aarch64 ;;
  *) echo "Unsupported build host: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
if [[ ! -d "$TOOLCHAIN" && -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi
CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
if [[ ! -x "$CC" ]]; then
  echo "Android NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
fetch() {
  curl -fsSL --retry 3 -o "$WORK/$1" "$2"
  echo "$3  $WORK/$1" | (command -v sha256sum >/dev/null && sha256sum -c - || shasum -a 256 -c -)
  mkdir -p "$WORK/${1%%.*}"
  tar -xzf "$WORK/$1" -C "$WORK/${1%%.*}" --strip-components=1
}
fetch proot.tar.gz "https://github.com/termux/proot/archive/${PROOT_COMMIT}.tar.gz" "$PROOT_SHA256"
fetch talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz" "$TALLOC_SHA256"
for patch in "$HERE"/patches/*.patch; do
  patch -d "$WORK/proot" -p1 --forward --quiet < "$patch"
done
mkdir -p "$WORK/talloc/config"
cp "$HERE/talloc-config.h" "$WORK/talloc/config/config.h"
"$CC" -c -O2 -fPIC -ffile-prefix-map="$WORK"=. -D__STDC_WANT_LIB_EXT1__=1 -DHAVE_CONFIG_H \
    -I"$WORK/talloc/config" -I"$WORK/talloc" -I"$WORK/talloc/lib/replace" \
    -o "$WORK/talloc.o" "$WORK/talloc/talloc.c"
"$TOOLCHAIN/llvm-ar" rcs "$WORK/libtalloc.a" "$WORK/talloc.o"
mkdir -p "$WORK/bin"
ln -s "$TOOLCHAIN/llvm-readelf" "$WORK/bin/readelf"
PATH="$WORK/bin:$PATH" make -C "$WORK/proot/src" -j"$(getconf _NPROCESSORS_ONLN)" \
    CC="$CC" STRIP="$TOOLCHAIN/llvm-strip" OBJCOPY="$TOOLCHAIN/llvm-objcopy" OBJDUMP="$TOOLCHAIN/llvm-objdump" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK/talloc -DARG_MAX=131072 -Wno-error=implicit-function-declaration" \
    LDFLAGS="$WORK/libtalloc.a -Wl,-z,noexecstack" \
    PROOT_UNBUNDLE_LOADER=/nonexistent proot loader
install -m755 "$WORK/proot/src/proot" "$OUTDIR/libprootlinux.so"
install -m755 "$WORK/proot/src/loader/loader" "$OUTDIR/libprootlinux-loader.so"
"$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUTDIR/libprootlinux.so"
grep -q '^#define HAVE_SECCOMP_FILTER' "$WORK/proot/src/build.h" || { echo "ERROR: proot was built without seccomp acceleration"; exit 1; }
grep -q '^#define HAVE_PROCESS_VM' "$WORK/proot/src/build.h" || { echo "ERROR: proot was built without process_vm"; exit 1; }
NEEDED=$("$TOOLCHAIN/llvm-readelf" -d "$OUTDIR/libprootlinux.so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | tr '\n' ' ')
echo "libprootlinux.so NEEDED: $NEEDED"
for lib in $NEEDED; do
  case $lib in libc.so|libdl.so|libm.so) ;;
  *) echo "ERROR: unexpected dependency $lib"; exit 1 ;;
  esac
done
ls -l "$OUTDIR/libprootlinux.so" "$OUTDIR/libprootlinux-loader.so"
