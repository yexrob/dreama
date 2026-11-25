#!/usr/bin/env python3
"""Generate depth3d Host macOS AppIcon assets (pure Python, zero third-party deps).

Design follows design-opus/ux-spec.md:
  - Restrained theater dark canvas (#0E0F12) with subtle rounded squircle frame
  - Three offset translucent layered frames evoking depth/parallax (ux-spec §3.7)
  - Warm amber (#E3A857) focal edge highlight on the front frame
"""

from __future__ import annotations

import json
import math
import os
import struct
import sys
import zlib


def write_png(path: str, width: int, height: int, rgba: bytearray) -> None:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw.extend(rgba[y * stride : (y + 1) * stride])

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), level=6))
        + chunk(b"IEND", b"")
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def sd_round_box(px: float, py: float, cx: float, cy: float, hx: float, hy: float, r: float) -> float:
    qx = abs(px - cx) - hx + r
    qy = abs(py - cy) - hy + r
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r


def blend(dst: tuple[float, float, float, float], src: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
    sa = src[3]
    da = dst[3]
    out_a = sa + da * (1.0 - sa)
    if out_a <= 1e-6:
        return (0.0, 0.0, 0.0, 0.0)
    r = (src[0] * sa + dst[0] * da * (1.0 - sa)) / out_a
    g = (src[1] * sa + dst[1] * da * (1.0 - sa)) / out_a
    b = (src[2] * sa + dst[2] * da * (1.0 - sa)) / out_a
    return (r, g, b, out_a)


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def render_icon(size: int) -> bytearray:
    buf = bytearray(size * size * 4)
    aa = 1.5 / size

    for y in range(size):
        ny = (y + 0.5) / size
        for x in range(size):
            nx = (x + 0.5) / size

            # macOS icon tile inset (10% margin, rounded superellipse-like box)
            tile_d = sd_round_box(nx, ny, 0.5, 0.5, 0.42, 0.42, 0.105)
            tile_a = 1.0 - smoothstep(-aa, aa, tile_d)
            if tile_a <= 0.001:
                continue

            # Subtle dark vertical gradient (#14161B -> #0B0C0F)
            bg_t = (ny - 0.08) / 0.84
            bg_t = max(0.0, min(1.0, bg_t))
            col = (
                (0x16 + (0x0C - 0x16) * bg_t) / 255.0,
                (0x18 + (0x0D - 0x18) * bg_t) / 255.0,
                (0x1E + (0x10 - 0x1E) * bg_t) / 255.0,
                tile_a,
            )

            # Subtle 1px inner border on tile
            border_band = (1.0 - smoothstep(0.0, aa * 1.5, abs(tile_d + 0.005))) * tile_a
            if border_band > 0.001:
                col = blend(col, (1.0, 1.0, 1.0, 0.09 * border_band))

            # Three offset layered frames suggesting 2D->3D depth
            # Back layer (top-left, dim cool slate)
            d1 = sd_round_box(nx, ny, 0.41, 0.43, 0.19, 0.135, 0.028)
            a1 = (1.0 - smoothstep(-aa, aa, d1)) * tile_a
            if a1 > 0.001:
                col = blend(col, (0x24 / 255.0, 0x28 / 255.0, 0x31 / 255.0, 0.90 * a1))
            s1 = (1.0 - smoothstep(0.0, aa * 1.6, abs(d1) - 0.006)) * tile_a
            if s1 > 0.001:
                col = blend(col, (0x7D / 255.0, 0x81 / 255.0, 0x8A / 255.0, 0.45 * s1))

            # Middle layer (center, elevated surface)
            d2 = sd_round_box(nx, ny, 0.50, 0.50, 0.195, 0.14, 0.030)
            a2 = (1.0 - smoothstep(-aa, aa, d2)) * tile_a
            if a2 > 0.001:
                col = blend(col, (0x1E / 255.0, 0x21 / 255.0, 0x28 / 255.0, 0.96 * a2))
            s2 = (1.0 - smoothstep(0.0, aa * 1.6, abs(d2) - 0.007)) * tile_a
            if s2 > 0.001:
                col = blend(col, (0xC0 / 255.0, 0xC4 / 255.0, 0xCE / 255.0, 0.55 * s2))

            # Front layer (bottom-right, crisp frame with warm amber #E3A857 accent)
            d3 = sd_round_box(nx, ny, 0.59, 0.57, 0.195, 0.14, 0.030)
            a3 = (1.0 - smoothstep(-aa, aa, d3)) * tile_a
            if a3 > 0.001:
                col = blend(col, (0x14 / 255.0, 0x16 / 255.0, 0x1B / 255.0, 0.98 * a3))
                # Soft warm theater glow inside front screen
                gx = (nx - 0.59) / 0.20
                gy = (ny - 0.57) / 0.15
                glow = max(0.0, 1.0 - (gx * gx + gy * gy)) ** 2
                col = blend(col, (0xE3 / 255.0, 0xA8 / 255.0, 0x57 / 255.0, 0.22 * glow * a3))

            s3 = (1.0 - smoothstep(0.0, aa * 1.8, abs(d3) - 0.010)) * tile_a
            if s3 > 0.001:
                col = blend(col, (0xE3 / 255.0, 0xA8 / 255.0, 0x57 / 255.0, 0.98 * s3))

            # Small amber play/horizon bar inside front layer
            bar_d = sd_round_box(nx, ny, 0.59, 0.57, 0.075, 0.012, 0.010)
            bar_a = (1.0 - smoothstep(-aa, aa, bar_d)) * tile_a
            if bar_a > 0.001:
                col = blend(col, (0xEC / 255.0, 0xEC / 255.0, 0xEE / 255.0, 0.92 * bar_a))

            idx = (y * size + x) * 4
            buf[idx] = max(0, min(255, int(round(col[0] * 255))))
            buf[idx + 1] = max(0, min(255, int(round(col[1] * 255))))
            buf[idx + 2] = max(0, min(255, int(round(col[2] * 255))))
            buf[idx + 3] = max(0, min(255, int(round(col[3] * 255))))

    return buf


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} <AppIcon.appiconset dir>")
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)

    specs = [
        (16, 1), (16, 2),
        (32, 1), (32, 2),
        (128, 1), (128, 2),
        (256, 1), (256, 2),
        (512, 1), (512, 2),
    ]
    rendered: dict[int, bytearray] = {}
    images = []
    for pt, scale in specs:
        px = pt * scale
        if px not in rendered:
            rendered[px] = render_icon(px)
        filename = f"icon_{pt}x{pt}{'@2x' if scale == 2 else ''}.png"
        write_png(os.path.join(out_dir, filename), px, px, rendered[px])
        images.append({
            "filename": filename,
            "idiom": "mac",
            "scale": f"{scale}x",
            "size": f"{pt}x{pt}",
        })

    with open(os.path.join(out_dir, "Contents.json"), "w", encoding="utf-8") as f:
        json.dump({"images": images, "info": {"author": "xcode", "version": 1}}, f, indent=2)

    assets_dir = os.path.dirname(out_dir)
    assets_contents = os.path.join(assets_dir, "Contents.json")
    if not os.path.exists(assets_contents):
        with open(assets_contents, "w", encoding="utf-8") as f:
            json.dump({"info": {"author": "xcode", "version": 1}}, f, indent=2)


if __name__ == "__main__":
    main()
