"""Download terrain (elevation) tiles for a circle and pack them into a PMTiles file.

Tiles: Mapzen/Tilezen "Terrarium" PNGs from AWS Open Data (elevation-tiles-prod),
sources include NRCan CDEM and SRTM. Free to use with attribution.

  python3 fetch_tiles.py dem LAT LON RADIUS_KM MAXZOOM out.pmtiles
  python3 fetch_tiles.py dem-area region.geojson MAXZOOM out.pmtiles   (any polygon, e.g. all of BC)

The app recognises the file as terrain from its PNG tile type, and the Topo style
uses it for hill shading (raster-dem, encoding "terrarium").
Needs: pip install pmtiles
"""
import math, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pmtiles.tile import zxy_to_tileid, TileType, Compression
from pmtiles.writer import Writer

URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"


def tile_bounds(z, x, y):
    n = 2 ** z
    lon0 = x / n * 360 - 180
    lon1 = (x + 1) / n * 360 - 180
    lat0 = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
    lat1 = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    return lon0, lat0, lon1, lat1


def dist_km(lat0, lon0, lat1, lon1):
    p = math.pi / 180
    a = math.sin((lat1 - lat0) * p / 2) ** 2 + math.cos(lat0 * p) * math.cos(lat1 * p) * math.sin((lon1 - lon0) * p / 2) ** 2
    return 12742 * math.asin(math.sqrt(a))


def tiles_in_circle(lat, lon, r_km, z):
    n = 2 ** z
    def tx(lo): return int((lo + 180) / 360 * n)
    def ty(la): return int((1 - math.asinh(math.tan(math.radians(la))) / math.pi) / 2 * n)
    dlat = r_km / 110.54
    dlon = r_km / (111.32 * math.cos(math.radians(lat + dlat)))
    out = []
    for x in range(max(0, tx(lon - dlon)), min(n - 1, tx(lon + dlon)) + 1):
        for y in range(max(0, ty(lat + dlat)), min(n - 1, ty(lat - dlat)) + 1):
            lo0, la0, lo1, la1 = tile_bounds(z, x, y)
            # Nearest point of the tile to the centre.
            cla = min(max(lat, la0), la1); clo = min(max(lon, lo0), lo1)
            if dist_km(lat, lon, cla, clo) <= r_km:
                out.append((z, x, y))
    return out


def tiles_in_area(geom, z):
    """Tiles at zoom z touching a shapely (multi)polygon."""
    from shapely.geometry import box
    from shapely.prepared import prep
    n = 2 ** z
    def tx(lo): return int((lo + 180) / 360 * n)
    def ty(la): return int((1 - math.asinh(math.tan(math.radians(la))) / math.pi) / 2 * n)
    g = prep(geom)
    lo0, la0, lo1, la1 = geom.bounds
    out = []
    for x in range(max(0, tx(lo0)), min(n - 1, tx(lo1)) + 1):
        for y in range(max(0, ty(la1)), min(n - 1, ty(la0)) + 1):
            if g.intersects(box(*tile_bounds(z, x, y))):
                out.append((z, x, y))
    return out


def get(t, tries=5):
    z, x, y = t
    for i in range(tries):
        try:
            with urllib.request.urlopen(URL.format(z=z, x=x, y=y), timeout=60) as r:
                return t, r.read()
        except Exception as e:
            if i == tries - 1:
                raise
            time.sleep(2 * (i + 1))


def main():
    if len(sys.argv) == 5 and sys.argv[1] == "dem-area":
        import json
        from shapely.geometry import shape
        g = json.load(open(sys.argv[2]))
        g = g["features"][0] if "features" in g else g
        geom = shape(g.get("geometry") or g)
        maxz, out = int(sys.argv[3]), sys.argv[4]
        tiles = [t for z in range(0, maxz + 1) for t in tiles_in_area(geom, z)]
        lo0, la0, lo1, la1 = geom.bounds
        bounds = (lo0, la0, lo1, la1); centre = ((la0 + la1) / 2, (lo0 + lo1) / 2)
    elif len(sys.argv) == 7 and sys.argv[1] == "dem":
        lat, lon, r_km, maxz, out = float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), int(sys.argv[5]), sys.argv[6]
        tiles = [t for z in range(0, maxz + 1) for t in tiles_in_circle(lat, lon, r_km, z)]
        dlat = r_km / 110.54
        dlon = r_km / (111.32 * math.cos(math.radians(lat + dlat)))
        bounds = (lon - dlon, lat - dlat, lon + dlon, lat + dlat); centre = (lat, lon)
    else:
        sys.exit(__doc__)
    print(len(tiles), "tiles", flush=True)
    data = {}
    with ThreadPoolExecutor(16) as ex:
        for i, (t, b) in enumerate(ex.map(get, tiles)):
            data[zxy_to_tileid(*t)] = b
            if i % 1000 == 0:
                print(i, "downloaded", flush=True)
    with open(out, "wb") as f:
        w = Writer(f)
        for tid in sorted(data):
            w.write_tile(tid, data[tid])
        w.finalize({
            "tile_type": TileType.PNG, "tile_compression": Compression.NONE,
            "min_zoom": 0, "max_zoom": maxz,
            "min_lon_e7": int(bounds[0] * 1e7), "min_lat_e7": int(bounds[1] * 1e7),
            "max_lon_e7": int(bounds[2] * 1e7), "max_lat_e7": int(bounds[3] * 1e7),
            "center_zoom": 8, "center_lon_e7": int(centre[1] * 1e7), "center_lat_e7": int(centre[0] * 1e7),
        }, {
            "name": "orbit-terrain", "encoding": "terrarium",
            "attribution": "Terrain: Mapzen Terrain Tiles / AWS Open Data (NRCan CDEM, SRTM)",
        })
    print("wrote", out, sum(len(b) for b in data.values()) // 1_000_000, "MB", flush=True)


if __name__ == "__main__":
    main()
