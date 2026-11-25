#!/usr/bin/env bash
# Reproduce the Mac-side Core ML ZipDepth export and M-series latency benchmark.
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p build

echo "=== 1. Export ZipDepth (base_npu) to Core ML FP16 .mlpackage ==="
uv run --python 3.12 python export_coreml.py --out build --sizes 256x256 512x288 640x384

echo "=== 2. Compile native Swift Core ML + VideoToolbox benchmark ==="
swiftc -O BenchCoreML.swift -o build/bench-coreml \
  -framework CoreML \
  -framework CoreVideo \
  -framework CoreMedia \
  -framework VideoToolbox \
  -framework CoreImage \
  -framework Metal \
  -framework Accelerate

echo "=== 3. Run benchmark on this Mac ==="
sysctl -n machdep.cpu.brand_string
sw_vers
./build/bench-coreml \
  build/zipdepth_256x256_fp16.mlpackage \
  build/zipdepth_512x288_fp16.mlpackage \
  build/zipdepth_640x384_fp16.mlpackage
