"""Convert a 2D video into side-by-side 3D, using the headset-shaped pipeline.

    uv run python -m depth3d_proto.convert input.mp4 -o out_LR.mp4 --seconds 30

Output is full SBS (left | right, each at the source size) with the source's
audio, which the Pico 4 video player plays as 3D "Left-Right".
"""

from __future__ import annotations

import argparse
import subprocess
import time
from pathlib import Path

import cv2
import numpy as np
from tqdm import tqdm

from .pipeline import DepthModel, StereoParams, StereoState, stereo_frame

HERE = Path(__file__).resolve().parent.parent


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("-o", "--output")
    ap.add_argument("--checkpoint", default=str(HERE / "checkpoints/zipdepth_base_npu.pth"))
    ap.add_argument("--input-size", type=int, default=256, help="model input short side (Pico 4 route: 256)")
    ap.add_argument("--depth-rate", type=float, default=12.0,
                    help="depth maps per second; Pico 4 budget is ~12, 0 = every frame")
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--seconds", type=float, default=0.0, help="0 = whole video")
    ap.add_argument("--max-height", type=int, default=1080)
    ap.add_argument("--separation", type=float, default=StereoParams.separation_pct)
    ap.add_argument("--convergence", type=float, default=StereoParams.convergence)
    ap.add_argument("--layout", choices=["pico", "full", "half"], default="pico",
                    help="pico: L|R at full width per eye, height doubled so a half-SBS player "
                         "shows the right aspect without halving resolution (PICO 4 built-in player); "
                         "full: L|R at source size; half: L|R squeezed to source width")
    ap.add_argument("--debug", action="store_true", help="output [left | depth] instead, to inspect the map")
    args = ap.parse_args()

    src = Path(args.input)
    out = Path(args.output) if args.output else src.with_name(src.stem + "_3D_LR.mp4")
    cap = cv2.VideoCapture(str(src))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    if args.start:
        cap.set(cv2.CAP_PROP_POS_MSEC, args.start * 1000)
    n = int(args.seconds * fps) if args.seconds else max(0, total - int(args.start * fps))

    ok, frame = cap.read()
    if not ok:
        raise SystemExit(f"cannot read {src}")
    h0, w0 = frame.shape[:2]
    scale = min(1.0, args.max_height / h0)
    w, h = (int(round(w0 * scale / 2)) * 2, int(round(h0 * scale / 2)) * 2)

    model = DepthModel(args.checkpoint, args.input_size)
    params = StereoParams(separation_pct=args.separation, convergence=args.convergence)
    state = StereoState()
    print(f"{src.name}: {w0}x{h0} @ {fps:.2f} -> {w*2}x{h} SBS, model input {model.input_size(h, w)}")

    vf = {"pico": f"scale={w*2}:{h*2}:flags=bicubic", "full": "null", "half": f"scale={w}:{h}:flags=area"}[args.layout]
    enc = subprocess.Popen([
        "ffmpeg", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{w*2}x{h}", "-r", f"{fps}", "-i", "-",
        "-ss", f"{args.start}", "-i", str(src),
        "-map", "0:v", "-map", "1:a?", "-shortest",
        "-vf", f"{vf},setsar=1",
        "-c:v", "libx265", "-preset", "medium", "-crf", "18", "-tag:v", "hvc1", "-pix_fmt", "yuv420p",
        "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
        "-c:a", "aac", "-b:a", "192k", str(out),
    ], stdin=subprocess.PIPE)

    every = 1 if args.depth_rate <= 0 else max(1, round(fps / args.depth_rate))
    raw = None
    t_model = t_stereo = 0.0
    n_model = 0
    for i in tqdm(range(n), unit="f"):
        if i > 0:
            ok, frame = cap.read()
            if not ok:
                break
        if scale != 1.0:
            frame = cv2.resize(frame, (w, h), interpolation=cv2.INTER_AREA)
        if raw is None or i % every == 0:
            t0 = time.perf_counter()
            raw = model(frame)
            t_model += time.perf_counter() - t0
            n_model += 1
        t0 = time.perf_counter()
        left, right, d_q = stereo_frame(frame, raw, state, params)
        t_stereo += time.perf_counter() - t0
        if args.debug:
            vis = cv2.applyColorMap((cv2.resize(d_q, (w, h)) * 255).astype(np.uint8), cv2.COLORMAP_INFERNO)
            right = vis
        enc.stdin.write(np.ascontiguousarray(np.hstack([left, right])).tobytes())
    enc.stdin.close()
    enc.wait()
    print(f"wrote {out}")
    print(f"model {1000*t_model/max(n_model,1):.1f} ms/map ({n_model} maps, every {every} frames), "
          f"stereo (numpy) {1000*t_stereo/max(i+1,1):.1f} ms/frame, scene cuts {state.cuts}")


if __name__ == "__main__":
    main()
