#!/bin/sh
# Downloads the FlyWire v783 connectome and annotations into data/raw.
# FlyWire data: CC BY-NC 4.0 (https://flywire.ai/tos), Shiu et al. 2024 model: MIT.
set -eu

DIR="$(cd "$(dirname "$0")/.." && pwd)/data/raw"
SHIU="https://raw.githubusercontent.com/philshiu/Drosophila_brain_model/91bdd1e7dcf193f3e7ca5a8933497fcef63b7960"
CODEX="https://storage.googleapis.com/flywire-data/codex/data/fafb/783"

mkdir -p "$DIR"

fetch() {
    if [ -s "$DIR/$2" ]; then
        echo "have:        $2"
    else
        echo "downloading: $2"
        curl -fL --retry 3 -o "$DIR/$2.part" "$1/$2"
        mv "$DIR/$2.part" "$DIR/$2"
    fi
}

fetch "$SHIU" Connectivity_783.parquet
fetch "$SHIU" Completeness_783.csv
fetch "$CODEX" classification.csv.gz
fetch "$CODEX" consolidated_cell_types.csv.gz
fetch "$CODEX" neurons.csv.gz

echo "Done: $DIR"
