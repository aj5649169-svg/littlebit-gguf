#!/usr/bin/env bash
# Apply patches/*.diff to the third_party/llama.cpp submodule (idempotently).
# Used by CI before the Gradle build; the app CMakeLists also applies them at
# configure time, so this is mostly for explicit/standalone use.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
llama=third_party/llama.cpp

git submodule update --init -- "$llama"

applied=0
skipped=0
for patch in "$PWD"/patches/*.diff; do
    if git -C "$llama" apply --reverse --check "$patch" 2>/dev/null; then
        skipped=$((skipped + 1))
        continue
    fi
    git -C "$llama" apply --check "$patch"
    git -C "$llama" apply "$patch"
    applied=$((applied + 1))
done

echo "patches: $applied applied, $skipped already applied"
