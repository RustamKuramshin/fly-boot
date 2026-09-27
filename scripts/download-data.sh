#!/bin/sh
# Downloads the connectomes into data/raw.
#   ./scripts/download-data.sh            FlyWire v783 (female, brain), about 104 MB
#   ./scripts/download-data.sh --malecns  also MaleCNS v1.0 (male, brain and ventral nerve cord), about 560 MB
# FlyWire data: CC BY-NC 4.0 (https://flywire.ai/tos); MaleCNS: CC BY 4.0 (https://male-cns.janelia.org/);
# Shiu et al. 2024 model: MIT.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)/data/raw"
SHIU="https://raw.githubusercontent.com/philshiu/Drosophila_brain_model/91bdd1e7dcf193f3e7ca5a8933497fcef63b7960"
CODEX="https://storage.googleapis.com/flywire-data/codex/data/fafb/783"
MCNS="https://storage.googleapis.com/flyem-male-cns/v1.0/connectome-data/flat-connectome"

fetch() {
    dir="$1"
    mkdir -p "$dir"
    if [ -s "$dir/$3" ]; then
        echo "have:        $3"
    else
        echo "downloading: $3"
        curl -fL --retry 3 -o "$dir/$3.part" "$2/$3"
        mv "$dir/$3.part" "$dir/$3"
    fi
}

fetch "$ROOT" "$SHIU" Connectivity_783.parquet
fetch "$ROOT" "$SHIU" Completeness_783.csv
fetch "$ROOT" "$CODEX" classification.csv.gz
fetch "$ROOT" "$CODEX" consolidated_cell_types.csv.gz
fetch "$ROOT" "$CODEX" neurons.csv.gz
fetch "$ROOT" "$CODEX" coordinates.csv.gz

if [ "${1:-}" = "--malecns" ]; then
    fetch "$ROOT/malecns" "$MCNS" body-annotations-male-cns-v1.0-minconf-0.5.feather
    fetch "$ROOT/malecns" "$MCNS" body-neurotransmitters-male-cns-v1.0.feather
    fetch "$ROOT/malecns" "$MCNS" connectome-weights-male-cns-v1.0-minconf-0.5-significant-only.feather
fi

echo "Done: $ROOT"
