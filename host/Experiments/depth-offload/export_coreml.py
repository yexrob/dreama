#!/usr/bin/env python3
"""Export ZipDepth (base_npu) to Core ML MLProgram (.mlpackage, FP16) and check parity.

Usage:
  uv run python export_coreml.py [--checkpoint <path>] [--out build] [--sizes 256x256 512x288 640x384]
"""

from __future__ import annotations

import argparse
import os
import pathlib
import urllib.request

import coremltools as ct
import numpy as np
import torch
from zipdepth.model.architecture import create_model
from zipdepth.utils.model_utils import strip_state_dict_prefixes

CHECKPOINT_URL = (
    "https://github.com/fabiotosi92/ZipDepth/raw/"
    "91f3fd21e131641f51e8d35736d1958350180e3a/checkpoints/zipdepth_base_npu.pth"
)


class ZipDepthWrapper(torch.nn.Module):
    def __init__(self, model: torch.nn.Module):
        super().__init__()
        self.model = model

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.model(x)


def locate_or_download_checkpoint(explicit: str | None, out_dir: pathlib.Path) -> pathlib.Path:
    if explicit:
        p = pathlib.Path(explicit)
        if p.is_file():
            return p
        raise SystemExit(f"Checkpoint not found: {p}")

    repo_root = pathlib.Path(__file__).resolve().parents[3]
    candidates = [
        repo_root / "proto" / "checkpoints" / "zipdepth_base_npu.pth",
        pathlib.Path.home() / "Episodes/Projects/depth3d/proto/checkpoints/zipdepth_base_npu.pth",
        out_dir / "zipdepth_base_npu.pth",
    ]
    for c in candidates:
        if c.is_file():
            print(f"Using existing checkpoint: {c}")
            return c

    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / "zipdepth_base_npu.pth"
    print(f"Downloading zipdepth_base_npu.pth -> {dst} ...")
    urllib.request.urlretrieve(CHECKPOINT_URL, dst)
    return dst


def load_zipdepth(checkpoint: pathlib.Path) -> torch.nn.Module:
    model = create_model(variant="base", upsample_unfold=False)
    sd = torch.load(checkpoint, map_location="cpu", weights_only=True)
    sd = strip_state_dict_prefixes(sd.get("model_state_dict", sd))
    model.load_state_dict(sd, strict=False)
    model.eval().fuse_for_inference()
    return ZipDepthWrapper(model).eval()


def parse_size(spec: str) -> tuple[int, int]:
    w_str, h_str = spec.lower().split("x")
    w, h = int(w_str), int(h_str)
    if w % 32 != 0 or h % 32 != 0:
        raise ValueError(f"Size {spec} must be multiples of 32")
    return w, h


def export_one(model: torch.nn.Module, width: int, height: int, out_dir: pathlib.Path) -> pathlib.Path:
    sample = torch.rand(1, 3, height, width, dtype=torch.float32)
    with torch.no_grad():
        traced = torch.jit.trace(model, sample, strict=False)
        ref_out = traced(sample).numpy()

    mlmodel = ct.convert(
        traced,
        convert_to="mlprogram",
        inputs=[ct.TensorType(name="rgb", shape=(1, 3, height, width), dtype=np.float32)],
        outputs=[ct.TensorType(name="depth")],
        minimum_deployment_target=ct.target.macOS14,
        compute_precision=ct.precision.FLOAT16,
    )

    pkg_path = out_dir / f"zipdepth_{width}x{height}_fp16.mlpackage"
    mlmodel.save(str(pkg_path))

    # Verify numerical accuracy against PyTorch reference
    pred = mlmodel.predict({"rgb": sample.numpy()})["depth"]
    corr = float(np.corrcoef(ref_out.ravel(), pred.ravel())[0, 1])
    mae = float(np.abs(ref_out - pred).mean())
    print(f"Exported {pkg_path.name}: output_shape={pred.shape}, corr={corr:.6f}, MAE={mae:.5f}")
    return pkg_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=str, default=None)
    parser.add_argument("--out", type=str, default="build")
    parser.add_argument("--sizes", nargs="+", default=["256x256", "512x288", "640x384"])
    args = parser.parse_args()

    out_dir = pathlib.Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    ckpt = locate_or_download_checkpoint(args.checkpoint, out_dir)
    model = load_zipdepth(ckpt)

    for spec in args.sizes:
        w, h = parse_size(spec)
        export_one(model, w, h, out_dir)


if __name__ == "__main__":
    main()
