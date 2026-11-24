#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
llama=third_party/llama.cpp
build_dir="$PWD/build-android-arm64"
ndk=

for candidate in \
    "${ANDROID_NDK_HOME:-}" \
    "${ANDROID_NDK_ROOT:-}" \
    "${ANDROID_NDK:-}" \
    "${ANDROID_NDK_LATEST_HOME:-}"
do
    if [[ -n "$candidate" && -f "$candidate/build/cmake/android.toolchain.cmake" ]]; then
        ndk=$candidate
        break
    fi
done

if [[ -z "$ndk" ]]; then
    echo "Android NDK not found; set ANDROID_NDK_HOME, ANDROID_NDK_ROOT, ANDROID_NDK, or ANDROID_NDK_LATEST_HOME" >&2
    exit 1
fi

git submodule update --init -- "$llama"

for patch in "$PWD"/patches/*.diff; do
    if git -C "$llama" apply --reverse --check "$patch" 2>/dev/null; then
        continue
    fi
    git -C "$llama" apply --check "$patch"
    git -C "$llama" apply "$patch"
done

generator=()
if command -v ninja >/dev/null 2>&1; then
    generator=(-G Ninja)
fi

cmake -S "$llama" -B "$build_dir" "${generator[@]}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_CPU_KLEIDIAI=ON \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=ON \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_APP=OFF
cmake --build "$build_dir" --parallel "${LITTLEBIT_JOBS:-2}" --target llama-completion
cmake --build "$build_dir" --parallel "${LITTLEBIT_JOBS:-2}" --target llama-bench

dist_dir="$PWD/dist/littlebit-gguf-android-arm64"
lib_dir="$dist_dir/lib"
rm -rf "$dist_dir"
mkdir -p "$dist_dir/bin" "$lib_dir"

install_output() {
    local source=$1
    local name=$2

    if [[ ! -f "$source" ]]; then
        return 1
    fi
    install -m 755 "$source" "$dist_dir/bin/$name"
}

if ! install_output "$build_dir/bin/llama-completion" llama-completion; then
    echo "Missing built binary: $build_dir/bin/llama-completion" >&2
    exit 1
fi
if ! install_output "$build_dir/bin/llama-bench" llama-bench; then
    echo "Missing built binary: $build_dir/bin/llama-bench" >&2
    exit 1
fi

found_library=false
while IFS= read -r -d '' library; do
    cp -a "$library" "$lib_dir/"
    found_library=true
done < <(find "$build_dir/bin" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -print0)
if [[ "$found_library" != true ]]; then
    echo "No shared libraries found in $build_dir/bin" >&2
    exit 1
fi

if ! command -v readelf >/dev/null 2>&1; then
    echo "readelf is required to inspect Android runtime dependencies" >&2
    exit 1
fi
needs_cxx_shared=false
for artifact in "$dist_dir/bin/"* "$lib_dir/"*.so*; do
    if [[ -f "$artifact" ]] && readelf -d "$artifact" 2>/dev/null | grep -Fq 'libc++_shared.so'; then
        needs_cxx_shared=true
        break
    fi
done
if [[ "$needs_cxx_shared" == true ]]; then
    cxx_shared=$(find "$ndk/toolchains/llvm/prebuilt" -type f \
        -path '*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so' -print -quit)
    if [[ -z "$cxx_shared" ]]; then
        echo "Android binaries require libc++_shared.so, but it was not found in $ndk" >&2
        exit 1
    fi
    cp -a "$cxx_shared" "$lib_dir/"
fi

# Launcher for interactive use. Defaults to -t 4 (the 4 big Cortex-A715 cores
# on Dimensity 8350 Ultra) unless the user already passed -t/--threads.
cat > "$dist_dir/run-android.sh" <<'WRAPPER'
#!/system/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export LD_LIBRARY_PATH="$script_dir/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

threads_set=0
for arg in "$@"; do
    case "$arg" in
        -t|--threads) threads_set=1 ;;
    esac
done
if [ "$threads_set" -eq 0 ]; then
    set -- "$@" -t 4
fi

exec "$script_dir/bin/llama-completion" "$@"
WRAPPER
chmod 755 "$dist_dir/run-android.sh"

# Phone-side benchmark sweep: llama-bench at -ngl 0 across -t 2/4/6/8.
cat > "$dist_dir/bench-android.sh" <<'WRAPPER'
#!/system/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

if [ "$#" -lt 1 ]; then
    echo "Usage: sh bench-android.sh <model.gguf> [extra llama-bench flags]" >&2
    echo "Example: sh bench-android.sh /sdcard/Download/littlebit-qwen3-8b-q4_k.gguf" >&2
    exit 1
fi
model=$1
shift

export LD_LIBRARY_PATH="$script_dir/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

echo "=== Device ==="
echo "SoC model  : $(getprop ro.soc.model 2>/dev/null || echo unknown)"
echo "Android    : $(getprop ro.build.version.release 2>/dev/null || echo unknown) (SDK $(getprop ro.build.version.sdk 2>/dev/null || echo unknown))"
echo "CPU features (check for asimddp / i8mm):"
grep -m1 '^Features' /proc/cpuinfo || echo "  (Features line not found)"
echo
echo "Model : $model"
echo "Bench : bin/llama-bench -ngl 0 -p 128 -n 32 -r 3 [extra flags: $*]"
echo

for threads in 2 4 6 8; do
    if [ "$threads" = "4" ]; then
        echo "########## -t 4  (4x big Cortex-A715 cores - recommended) ##########"
    else
        echo "########## -t $threads ##########"
    fi
    ./bin/llama-bench -m "$model" -ngl 0 -t "$threads" -p 128 -n 32 -r 3 "$@"
    echo
done
WRAPPER
chmod 755 "$dist_dir/bench-android.sh"

mkdir -p "$PWD/dist"
tar -czf "$PWD/dist/littlebit-gguf-android-arm64.tar.gz" \
    -C "$PWD/dist" littlebit-gguf-android-arm64
    
