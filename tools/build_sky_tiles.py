#!/usr/bin/env python3
"""
Genera le tile del cielo per AstroScout dal World Atlas of Artificial Night Sky Brightness 2015.

Input : GeoTIFF del World Atlas (Falchi et al. 2016, doi:10.5880/GFZ.1.4.2016.001),
        griglia 30" in EPSG:4326, valori = brillanza ARTIFICIALE allo zenit in mcd/m².
Output: <out>/manifest.json + <out>/<ID>.bin.gz  (es. N45E005.bin.gz)

Per ogni tile tileDeg×tileDeg:
  1. legge SOLO la finestra necessaria (lettura a finestre: non carica mai i ~3 GB in RAM);
  2. SQM = -2.5·log10((artificiale + 0.171168) / 1.08e8)     [mag/arcsec²]
  3. quantizza: q = round((SQM - 16.0)/0.025) in 0..254, 255 = nodata;
  4. scrive header big-endian + dati u8, compressi gzip (mtime=0 → file deterministici).
Le tile in cui OGNI pixel è Bortle 1 (SQM >= --dark-threshold = 21.99) NON vengono scritte: l'app
le tratta come "cielo naturale" grazie alla lista `tiles` del manifest.

Il formato binario è documentato in app/.../data/sky/atlas/SkyTile.kt e deve restare identico.

Esempi:
  pip install rasterio numpy
  # tutto il mondo (≈ qualche centinaio di tile)
  python tools/build_sky_tiles.py World_Atlas_2015.tif --out sky-tiles
  # solo Europa
  python tools/build_sky_tiles.py World_Atlas_2015.tif --out sky-tiles --bbox -25 34 45 72
"""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np
import rasterio
from rasterio.enums import Resampling
from rasterio.windows import from_bounds

MAGIC = b"ASQT"
FORMAT_VERSION = 1
SQM_MIN = 16.0
SQM_STEP = 0.025
NODATA = 255
NATURAL_MCD = 0.171168
HEADER = struct.Struct(">4sBHHhhHff")  # 23 byte, vedi SkyTile.kt
GITHUB_RELEASE_ASSET_LIMIT = 1000


def artificial_to_sqm(art: np.ndarray) -> np.ndarray:
    return -2.5 * np.log10((np.clip(art, 0.0, None) + NATURAL_MCD) / 1.08e8)


def quantize(sqm: np.ndarray, valid: np.ndarray) -> np.ndarray:
    q = np.rint((sqm - SQM_MIN) / SQM_STEP)
    q = np.clip(np.nan_to_num(q, nan=0.0), 0, 254).astype(np.uint8)
    q[~valid] = NODATA
    return q


def tile_id(lat_s: int, lon_w: int) -> str:
    return f"{'N' if lat_s >= 0 else 'S'}{abs(lat_s):02d}{'E' if lon_w >= 0 else 'W'}{abs(lon_w):03d}"


def encode_tile(q: np.ndarray, lat_s: int, lon_w: int, deg: int) -> bytes:
    h, w = q.shape
    raw = HEADER.pack(MAGIC, FORMAT_VERSION, w, h, lat_s, lon_w, deg, SQM_MIN, SQM_STEP) + q.tobytes(order="C")
    return gzip.compress(raw, compresslevel=9, mtime=0)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", type=Path, help="GeoTIFF World Atlas 2015")
    ap.add_argument("--out", type=Path, default=Path("sky-tiles"))
    ap.add_argument("--tile-deg", type=int, default=5)
    ap.add_argument("--px-per-deg", type=int, default=120, help="120 = 30 arcsec (risoluzione nativa)")
    ap.add_argument("--bbox", type=float, nargs=4, metavar=("LON_MIN", "LAT_MIN", "LON_MAX", "LAT_MAX"),
                    help="limita a un'area (default: tutto il raster)")
    ap.add_argument("--version", default="wa2015-1", help="cambiala se rigeneri: le app riscaricano le tile")
    ap.add_argument("--dark-threshold", type=float, default=21.99,
                    help="tile con SQM minimo >= soglia (21.99 = limite Bortle 1) non vengono scritte")
    args = ap.parse_args()

    deg, px = args.tile_deg, args.tile_deg * args.px_per_deg
    args.out.mkdir(parents=True, exist_ok=True)

    with rasterio.open(args.input) as src:
        if src.crs is None or src.crs.to_epsg() != 4326:
            print(f"ATTENZIONE: CRS {src.crs} (atteso EPSG:4326)", file=sys.stderr)
        b = src.bounds
        lon_min, lat_min, lon_max, lat_max = args.bbox or (b.left, b.bottom, b.right, b.top)
        # copertura = intersezione bbox/raster, allineata alla griglia delle tile
        cov_lat_min = max(math.floor(max(lat_min, b.bottom) / deg) * deg, -90)
        cov_lat_max = min(math.ceil(min(lat_max, b.top) / deg) * deg, 90)
        cov_lon_min = math.floor(max(lon_min, b.left) / deg) * deg
        cov_lon_max = math.ceil(min(lon_max, b.right) / deg) * deg
        nodata = src.nodata
        print(f"Raster {src.width}x{src.height}, bounds {tuple(round(v, 3) for v in b)}, nodata={nodata}")
        print(f"Tile {deg}°: lat {cov_lat_min}..{cov_lat_max}, lon {cov_lon_min}..{cov_lon_max}")

        written, dark, empty, total_bytes = [], 0, 0, 0
        for lat_s in range(cov_lat_min, cov_lat_max, deg):
            for lon_w in range(cov_lon_min, cov_lon_max, deg):
                win = from_bounds(lon_w, lat_s, lon_w + deg, lat_s + deg, src.transform)
                arr = src.read(1, window=win, out_shape=(px, px), boundless=True, masked=True,
                               resampling=Resampling.nearest)
                data = arr.astype(np.float64).filled(np.nan)
                valid = np.isfinite(data) & (data >= 0)
                if nodata is not None and not (isinstance(nodata, float) and math.isnan(nodata)):
                    valid &= data != nodata
                if not valid.any():
                    empty += 1
                    continue
                sqm = artificial_to_sqm(np.where(valid, data, 0.0))
                if sqm[valid].min() >= args.dark_threshold:
                    dark += 1
                    continue
                tid = tile_id(lat_s, lon_w)
                blob = encode_tile(quantize(sqm, valid), lat_s, lon_w, deg)
                (args.out / f"{tid}.bin.gz").write_bytes(blob)
                written.append(tid)
                total_bytes += len(blob)
            print(f"  fascia lat {lat_s:+d}: {len(written)} tile scritte finora", flush=True)

    manifest = {
        "format": FORMAT_VERSION,
        "version": args.version,
        "dataset": "World Atlas of Artificial Night Sky Brightness 2015 (Falchi et al. 2016)",
        "license": "CC BY-NC 4.0 - doi:10.5880/GFZ.1.4.2016.001",
        "tileDeg": deg,
        "latMin": cov_lat_min,
        "latMax": cov_lat_max,
        "lonMin": cov_lon_min,
        "lonMax": cov_lon_max,
        "darkSqm": round((args.dark_threshold + 22.0) / 2, 3),
        "tiles": sorted(written),
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
    }
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=1))

    n = len(written) + 1
    print(f"\nFatto: {len(written)} tile ({total_bytes / 1e6:.1f} MB), {dark} buie omesse, {empty} vuote.")
    print(f"Output: {args.out.resolve()}  ({n} file incluso manifest.json)")
    if n > GITHUB_RELEASE_ASSET_LIMIT:
        print(f"ATTENZIONE: {n} file > {GITHUB_RELEASE_ASSET_LIMIT} (limite asset di una GitHub Release). "
              f"Usa --bbox o --tile-deg 10.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
