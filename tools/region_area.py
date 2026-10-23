"""The area files for a province or state, from Overture Maps' boundaries.

  python3 region_area.py CODE OUTDIR [RELEASE]
    CODE     ISO 3166-2 code: CA-BC, CA-AB, US-WA, US-CA ...
    RELEASE  Overture release (default 2026-10-21.0)

Writes in OUTDIR:
  boundary.geojson  the province/state (land)
  buf50.geojson     plus 50 km — map, routing and search cover this, so routes can cross the border
  buf10.geojson     plus 10 km — terrain and contours (big files, so a smaller margin)
  area.json         {"code", "name", "bbox50": [w, s, e, n], "km2", "neighbours": [codes the 50 km strip reaches]}
  areas.geojson     this region and its neighbours (FeatureCollection, properties name + code), so search
                    can say "Golden · British Columbia" for a town just over the border
Each .geojson is a single Feature (what pmtiles, osmium and the fetch tools take).
Needs: pip install duckdb shapely pyproj
"""
import json, os, re, sys, urllib.request
from shapely import wkb
from shapely.geometry import mapping
import warnings
from shapely.ops import transform, unary_union
warnings.filterwarnings("ignore", category=DeprecationWarning)
import pyproj

BUCKET = "https://overturemaps-us-west-2.s3.us-west-2.amazonaws.com/"


def files(release, theme, typ):
    xml = urllib.request.urlopen(f"{BUCKET}?list-type=2&prefix=release/{release}/theme={theme}/type={typ}/").read().decode()
    return [BUCKET + k for k in re.findall(r"<Key>([^<]+\.parquet)</Key>", xml)]


def feature(geom, name):
    return {"type": "Feature", "properties": {"name": name}, "geometry": mapping(geom)}


def main(code, outdir, release="2026-10-21.0"):
    import duckdb
    con = duckdb.connect(); con.sql("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial;")
    rows = con.execute("""SELECT names."primary", ST_AsWKB(geometry) FROM read_parquet(?)
                          WHERE subtype = 'region' AND region = ? AND is_land""",
                       [files(release, "divisions", "division_area"), code]).fetchall()
    if not rows: sys.exit(f"no region {code} in Overture {release}")
    name = rows[0][0]
    land = unary_union([wkb.loads(bytes(g)) for _, g in rows])
    c = land.centroid
    # Buffer in a local equal-area projection so 50 km is 50 km everywhere.
    laea = pyproj.CRS.from_proj4(f"+proj=laea +lat_0={c.y} +lon_0={c.x} +units=m")
    to = pyproj.Transformer.from_crs("EPSG:4326", laea, always_xy=True).transform
    back = pyproj.Transformer.from_crs(laea, "EPSG:4326", always_xy=True).transform
    m = transform(to, land)

    def buf(km):
        # simplify the grown outline (small islands and fjords make huge polygons; 1 km detail is plenty)
        return transform(back, m.buffer(km * 1000, resolution=8).simplify(1000))

    os.makedirs(outdir, exist_ok=True)
    out = {"boundary": transform(back, m.simplify(200)), "buf50": buf(50), "buf10": buf(10)}
    for k, g in out.items():
        json.dump(feature(g, name), open(os.path.join(outdir, k + ".geojson"), "w"))
    # Which other provinces/states the 50 km strip reaches (their road data is needed too).
    b = out["buf50"]; bx0, by0, bx1, by1 = b.bounds
    near = con.execute(f"""SELECT DISTINCT region, ST_AsWKB(geometry), names."primary" FROM read_parquet(?)
        WHERE subtype = 'region' AND is_land AND region <> ? AND country IN ('CA', 'US', 'MX')
          AND bbox.xmax >= {bx0} AND bbox.xmin <= {bx1} AND bbox.ymax >= {by0} AND bbox.ymin <= {by1}""",
        [files(release, "divisions", "division_area"), code]).fetchall()
    shapes = {}
    for r, g, nm in near:
        g = wkb.loads(bytes(g))
        if g.intersects(b): shapes.setdefault((r, nm), []).append(g)
    neighbours = sorted({r for r, _ in shapes})
    feats = [dict(feature(transform(back, m.simplify(500)), name), properties={"name": name, "code": code})]
    for (r, nm), gs in sorted(shapes.items()):
        feats.append(dict(feature(unary_union(gs).simplify(0.005), nm), properties={"name": nm, "code": r}))
    json.dump({"type": "FeatureCollection", "features": feats}, open(os.path.join(outdir, "areas.geojson"), "w"))
    json.dump({"code": code, "name": name, "bbox50": list(out["buf50"].bounds), "km2": round(m.area / 1e6),
               "neighbours": neighbours},
              open(os.path.join(outdir, "area.json"), "w"))
    print(name, round(m.area / 1e6), "km²; 50 km box", [round(x, 3) for x in out["buf50"].bounds], "neighbours", neighbours)


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4): sys.exit(__doc__)
    main(*sys.argv[1:])
