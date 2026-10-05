#!/bin/sh
# Fetches the Whisper model the app is built with, and refuses it unless it is the exact
# file this repository was tested against. The model is ~57 MB, so it is not committed;
# CI runs this before building, and so should anyone building from a clean clone.
set -eu
cd "$(dirname "$0")"

NAME=ggml-base.en-q5_1.bin
SHA256=4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f
URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$NAME"

if [ ! -f "$NAME" ]; then
    curl -fL --retry 3 -o "$NAME.part" "$URL"
    mv "$NAME.part" "$NAME"
fi
echo "$SHA256  $NAME" | sha256sum -c -
