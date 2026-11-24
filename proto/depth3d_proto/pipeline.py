"""2D -> stereo pipeline, modelled on what has to run on a Pico 4 (XR2 Gen 1).

Stages, in the order the headset will run them:

1. depth   ZipDepth on a small input (256 short side, like the int8 CPU route),
           output is relative inverse depth: larger = nearer.
2. range   per-frame robust normalisation to [0, 1], range smoothed over time.
3. temporal  per-texel exponential average, reset on a scene cut.
4. upsample  joint bilateral upsample to quarter resolution, guided by colour,
           so depth edges snap to colour edges instead of making a wide halo.
5. offsets   per eye, invert the forward warp: find the source pixel that lands
           here; when several do, the nearest wins (occlusion).
6. warp      sample the full-res colour frame with those offsets.

Every stage is plain numpy / OpenCV so it maps directly onto GLES shaders later.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np
import torch

from zipdepth.model.architecture import create_model
from zipdepth.utils.model_utils import strip_state_dict_prefixes


@dataclass
class StereoParams:
    # Separation in percent of frame width. MoonlightXR found 0.6 by blind test;
    # more gave no extra perceived depth but more strain and edge artefacts.
    separation_pct: float = 0.6
    # Depth value (0 far .. 1 near) that sits on the screen plane. Below it goes
    # behind the screen, above it pops out.
    convergence: float = 0.35
    temporal_alpha: float = 0.35       # weight of the new depth map per frame
    range_alpha: float = 0.1           # weight of the new range per frame
    scene_cut_threshold: float = 0.35  # mean abs colour change that resets history
    sigma_r: float = 0.12              # bilateral colour sigma, rgb in [0, 1]
    edge_sharpen: float = 0.6          # 0..1, turn depth ramps back into steps
    edge_fade_pct: float = 1.5         # shift falls to 0 over this band at L/R edges


class DepthModel:
    def __init__(self, checkpoint: str, input_short_side: int = 256, device: str | None = None):
        if device is None:
            device = "mps" if torch.backends.mps.is_available() else "cpu"
        self.device = device
        self.short = input_short_side
        model = create_model(variant="base", upsample_unfold=False)
        sd = torch.load(checkpoint, map_location="cpu", weights_only=True)
        sd = strip_state_dict_prefixes(sd.get("model_state_dict", sd))
        model.load_state_dict(sd, strict=False)
        model.eval().fuse_for_inference()
        self.model = model.to(device)

    def input_size(self, h: int, w: int) -> tuple[int, int]:
        s = self.short / min(h, w)
        r = lambda v: max(32, int(round(v * s / 32)) * 32)
        return r(h), r(w)

    @torch.no_grad()
    def __call__(self, bgr: np.ndarray) -> np.ndarray:
        h, w = bgr.shape[:2]
        ih, iw = self.input_size(h, w)
        # Area resize, the 4x4 box MoonlightXR uses to stop the map crawling.
        small = cv2.resize(bgr, (iw, ih), interpolation=cv2.INTER_AREA)
        t = torch.from_numpy(small[:, :, ::-1].copy()).to(self.device)
        t = t.permute(2, 0, 1)[None].float().div_(255.0)
        d = self.model(t)
        while d.dim() > 2:
            d = d[0]
        return d.float().cpu().numpy()


@dataclass
class StereoState:
    depth: np.ndarray | None = None
    lo: float | None = None
    hi: float | None = None
    prev_thumb: np.ndarray | None = None
    cuts: int = 0
    extra: dict = field(default_factory=dict)


def _scene_cut(state: StereoState, bgr: np.ndarray, thr: float) -> bool:
    thumb = cv2.resize(bgr, (64, 36), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
    cut = state.prev_thumb is None or float(np.abs(thumb - state.prev_thumb).mean()) * 4 > thr
    state.prev_thumb = thumb
    return cut


def normalise_temporal(raw: np.ndarray, bgr: np.ndarray, st: StereoState, p: StereoParams) -> np.ndarray:
    lo, hi = np.percentile(raw, [2, 98])
    cut = _scene_cut(st, bgr, p.scene_cut_threshold)
    if cut or st.lo is None:
        st.lo, st.hi = float(lo), float(hi)
        st.cuts += int(cut and st.depth is not None)
    else:
        st.lo += p.range_alpha * (lo - st.lo)
        st.hi += p.range_alpha * (hi - st.hi)
    d = np.clip((raw - st.lo) / max(st.hi - st.lo, 1e-6), 0.0, 1.0).astype(np.float32)
    if cut or st.depth is None or st.depth.shape != d.shape:
        st.depth = d
    else:
        st.depth += p.temporal_alpha * (d - st.depth)
    return st.depth


def bilateral_upsample(depth_lo: np.ndarray, guide_bgr: np.ndarray, out_hw: tuple[int, int],
                       sigma_r: float, sharpen: float) -> np.ndarray:
    """Joint bilateral upsample over the 5x5 low-res neighbourhood, then edge snap."""
    oh, ow = out_hw
    lh, lw = depth_lo.shape
    guide_hi = cv2.resize(guide_bgr, (ow, oh), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
    guide_lo = cv2.resize(guide_bgr, (lw, lh), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0

    ys = (np.arange(oh) + 0.5) * lh / oh - 0.5
    xs = (np.arange(ow) + 0.5) * lw / ow - 0.5
    by, bx = np.floor(ys).astype(int), np.floor(xs).astype(int)
    num = np.zeros((oh, ow), np.float32)
    den = np.zeros((oh, ow), np.float32)
    dlo = np.full((oh, ow), 1.0, np.float32)
    dhi = np.zeros((oh, ow), np.float32)
    sig_s2 = 2 * 1.5 ** 2
    sig_r2 = 2 * sigma_r ** 2
    for dy in range(-2, 3):
        qy = np.clip(by + dy, 0, lh - 1)
        oy = (qy - ys)[:, None]
        for dx in range(-2, 3):
            qx = np.clip(bx + dx, 0, lw - 1)
            ox = (qx - xs)[None, :]
            s_d = depth_lo[qy[:, None], qx[None, :]]
            s_c = guide_lo[qy[:, None], qx[None, :]]
            ws = np.exp(-(oy ** 2 + ox ** 2) / sig_s2)
            cd = guide_hi - s_c
            wr = np.exp(-(cd * cd).sum(-1) / sig_r2)
            w = ws * wr
            num += w * s_d
            den += w
            np.minimum(dlo, s_d, out=dlo)
            np.maximum(dhi, s_d, out=dhi)
    d = num / np.maximum(den, 1e-6)
    if sharpen > 0:
        span = dhi - dlo
        u = np.clip((d - dlo) / np.maximum(span, 1e-6), 0, 1)
        snapped = dlo + span / (1 + np.exp(-24 * (u - 0.5)))
        d = np.where(span >= 0.05, d + sharpen * (snapped - d), d)
    return d.astype(np.float32)


def eye_offsets(depth: np.ndarray, disp: float, conv: float) -> np.ndarray:
    """Per destination texel, the source offset t (texels) with
    t + disp * (d(x + t) - conv) == 0; nearest surface wins on ties.

    A source pixel s lands at s + disp * (d(s) - conv).
    """
    h, w = depth.shape
    reach = int(np.ceil(abs(disp) * max(conv, 1 - conv))) + 2
    xs = np.arange(w)
    here = depth
    best_d = np.full((h, w), -1.0, np.float32)
    best_off = (-disp * (here - conv)).astype(np.float32)
    pd = depth[:, np.clip(xs - reach, 0, w - 1)]
    pe = -reach + disp * (pd - conv)
    for t in range(-reach + 1, reach + 1):
        cd = depth[:, np.clip(xs + t, 0, w - 1)]
        ce = t + disp * (cd - conv)
        span = ce - pe
        hit = (pe * ce <= 0) & (np.abs(span) > 1e-6)
        f = np.clip(-pe / np.where(hit, span, 1.0), 0, 1)
        rd = pd + f * (cd - pd)
        better = hit & (rd > best_d)
        best_d = np.where(better, rd, best_d)
        best_off = np.where(better, (t - 1) + f, best_off)
        pd, pe = cd, ce
    return best_off


def synth_eye(bgr: np.ndarray, offsets_q: np.ndarray, edge_fade_pct: float) -> np.ndarray:
    h, w = bgr.shape[:2]
    qh, qw = offsets_q.shape
    off = cv2.resize(offsets_q, (w, h), interpolation=cv2.INTER_LINEAR) * (w / qw)
    if edge_fade_pct > 0:
        band = max(1.0, w * edge_fade_pct / 100)
        x = np.arange(w, dtype=np.float32)
        fade = np.clip(np.minimum(x, w - 1 - x) / band, 0, 1)
        off *= fade[None, :]
    mx = (np.arange(w, dtype=np.float32)[None, :] + off).astype(np.float32)
    my = np.repeat(np.arange(h, dtype=np.float32)[:, None], w, axis=1)
    return cv2.remap(bgr, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def stereo_frame(bgr: np.ndarray, raw_depth: np.ndarray, st: StereoState, p: StereoParams):
    """Returns (left, right, depth_quarter)."""
    h, w = bgr.shape[:2]
    d_lo = normalise_temporal(raw_depth, bgr, st, p)
    qh, qw = max(1, h // 4), max(1, w // 4)
    d_q = bilateral_upsample(d_lo, bgr, (qh, qw), p.sigma_r, p.edge_sharpen)
    disp_q = (p.separation_pct / 100.0) * qw  # quarter-res texels of shift across the depth range
    # Each eye gets half the separation, in opposite directions.
    left = synth_eye(bgr, eye_offsets(d_q, +disp_q / 2, p.convergence), p.edge_fade_pct)
    right = synth_eye(bgr, eye_offsets(d_q, -disp_q / 2, p.convergence), p.edge_fade_pct)
    return left, right, d_q
