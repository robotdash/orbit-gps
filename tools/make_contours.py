"""Trace contour lines from a Terrarium terrain PMTiles file into a vector PMTiles file.

  python3 make_contours.py terrain.pmtiles contours.pmtiles

Every 50 m, with every 250 m marked major=1 (drawn bolder, labelled). Lines are traced
per tile in the tile's own pixel grid (both are Web Mercator), so no reprojection is
needed: z11 has every line, z10 only the major ones. A one-tile border of neighbours is
used so lines join up across tile edges, and lines are clipped exactly to each tile.

Layer "contours", fields: ele (metres, int), major (0/1). Metadata name "orbit-contours"
(the app recognises the file from that).
Needs: pip install pmtiles numpy scipy scikit-image shapely mapbox-vector-tile pillow
"""
import gzip, io, json, sys
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter
from skimage.measure import find_contours
from shapely.geometry import LineString, box
from shapely import simplify
import mapbox_vector_tile
from pmtiles.reader import Reader, MmapSource
from pmtiles.tile import zxy_to_tileid, tileid_to_zxy, TileType, Compression
from pmtiles.writer import Writer

STEP, MAJOR = 50, 250
EXTENT = 4096


def main():
    src, out = sys.argv[1], sys.argv[2]
    f = open(src, "rb")
    get_bytes = MmapSource(f)
    r = Reader(get_bytes)
    hdr = r.header()
    cache = {}

    def dem(z, x, y):
        # Alaska's Aleutians cross 180°: x wraps around; rows off the top/bottom don't exist.
        n = 1 << z
        if not 0 <= y < n: return None
        x %= n
        k = (z, x, y)
        if k not in cache:
            b = r.get(z, x, y)
            if b is None:
                cache[k] = None
            else:
                a = np.asarray(Image.open(io.BytesIO(b)).convert("RGB"), dtype=np.float32)
                cache[k] = a[:, :, 0] * 256 + a[:, :, 1] + a[:, :, 2] / 256 - 32768
        return cache[k]

    def mosaic(z, x, y):
        """3x3 tiles around (x, y); missing neighbours repeat the edge."""
        c = dem(z, x, y)
        if c is None:
            return None
        rows = []
        for dy in (-1, 0, 1):
            row = []
            for dx in (-1, 0, 1):
                t = dem(z, x + dx, y + dy)
                row.append(t if t is not None else c)
            rows.append(np.hstack(row))
        return np.vstack(rows)

    tiles = {}
    zooms = {10: True, 11: False}  # zoom -> major lines only
    for z, major_only in zooms.items():
        coords = sorted(tileid_to_zxy(t)[1:] for t in all_ids(get_bytes, z))
        print(f"z{z}: {len(coords)} tiles", flush=True)
        for i, (x, y) in enumerate(coords):
            m = mosaic(z, x, y)
            if m is None:
                continue
            m = gaussian_filter(m, 1.2 if z == 11 else 0.8)
            # Centre tile plus a 2 px margin, so lines can be clipped cleanly.
            sub = m[254:514, 254:514]
            lo, hi = float(sub.min()), float(sub.max())
            step = MAJOR if major_only else STEP
            levels = [v for v in range(int(np.ceil(max(lo, 1) / step)) * step, int(hi) + 1, step)]
            feats = []
            clip = box(0, 0, EXTENT, EXTENT)
            for ele in levels:
                for line in find_contours(sub, ele):
                    if len(line) < 3:
                        continue
                    # (row, col) in sub -> tile pixels (sub starts 2 px before the tile) -> MVT units.
                    pts = [((c - 2) * EXTENT / 256, (rr - 2) * EXTENT / 256) for rr, c in line]
                    g = simplify(LineString(pts), 4).intersection(clip)
                    if g.is_empty:
                        continue
                    parts = [g] if g.geom_type == "LineString" else [p for p in getattr(g, "geoms", []) if p.geom_type == "LineString"]
                    for p in parts:
                        if p.length < 24:
                            continue
                        feats.append({"geometry": p.wkt, "properties": {"ele": int(ele), "major": int(ele % MAJOR == 0)}})
            if feats:
                # MVT y grows downward, like image rows.
                mvt = mapbox_vector_tile.encode([{"name": "contours", "features": feats}],
                                                default_options={"y_coord_down": True, "extents": EXTENT})
                tiles[zxy_to_tileid(z, x, y)] = gzip.compress(mvt)
            if i % 500 == 0:
                print(f"  {i}/{len(coords)}", flush=True)
            if len(cache) > 3000:
                cache.clear()

    with open(out, "wb") as fo:
        w = Writer(fo)
        for tid in sorted(tiles):
            w.write_tile(tid, tiles[tid])
        w.finalize({
            "tile_type": TileType.MVT, "tile_compression": Compression.GZIP,
            "min_zoom": 10, "max_zoom": 11,
            "min_lon_e7": hdr["min_lon_e7"], "min_lat_e7": hdr["min_lat_e7"],
            "max_lon_e7": hdr["max_lon_e7"], "max_lat_e7": hdr["max_lat_e7"],
            "center_zoom": 11, "center_lon_e7": hdr["center_lon_e7"], "center_lat_e7": hdr["center_lat_e7"],
        }, {
            "name": "orbit-contours",
            "vector_layers": [{"id": "contours", "fields": {"ele": "Number", "major": "Number"}, "minzoom": 10, "maxzoom": 11}],
            "attribution": "Contours from Mapzen Terrain Tiles / AWS Open Data",
        })
    print("wrote", out, sum(len(b) for b in tiles.values()) // 1_000_000, "MB,", len(tiles), "tiles", flush=True)


def all_ids(get_bytes, z):
    """Tile ids at zoom z present in the archive (walks the directory)."""
    from pmtiles.reader import all_tiles
    return [zxy_to_tileid(*k) for k, _ in all_tiles(get_bytes) if k[0] == z]


if __name__ == "__main__":
    main()
