"""Cut one big Valhalla road network into region pieces that fit back together on the phone.

  python3 split_graph.py TILES (dir or .tar) GRAPH_ID area.geojson out.tar
Writes out.tar with every graph tile (all three levels) whose square touches the area, plus a
file "graph.json" ({"graph": GRAPH_ID, "tiles": N}) so the app knows which build the tiles came
from: tiles from the same build join up exactly at region borders; tiles from different builds
must not be mixed.

Valhalla tiles: level 0 = 4° squares (motorways), 1 = 1° (main roads), 2 = 0.25° (everything);
tile id = row * columns + column counted from -180°, -90°; file 2/000/751/564.gph = level 2, id 751564.
"""
import io, json, os, re, sys, tarfile
from shapely.geometry import shape, box
from shapely.prepared import prep

SIZE = {0: 4.0, 1: 1.0, 2: 0.25}
PATH = re.compile(r"^(\d)/([\d/]+)\.gph$")


def tile_box(path):
    m = PATH.match(path)
    if not m: return None
    level = int(m.group(1)); tid = int(m.group(2).replace("/", ""))
    s = SIZE.get(level)
    if s is None: return None
    cols = int(360 / s)
    row, col = divmod(tid, cols)
    return box(-180 + col * s, -90 + row * s, -180 + (col + 1) * s, -90 + (row + 1) * s)


def tiles(src):
    """(path, open-file function) for every tile in a directory or tar."""
    if os.path.isdir(src):
        for root, _, files in os.walk(src):
            for f in files:
                if f.endswith(".gph"):
                    full = os.path.join(root, f)
                    yield os.path.relpath(full, src), (lambda p=full: open(p, "rb")), os.path.getsize(full)
    else:
        t = tarfile.open(src)
        for m in t:
            if m.isfile() and m.name.endswith(".gph"):
                yield m.name.lstrip("./"), (lambda m=m: t.extractfile(m)), m.size


def main(src, graph_id, area_path, out):
    g = json.load(open(area_path)); g = g["features"][0] if "features" in g else g
    area = prep(shape(g.get("geometry") or g))
    chosen = sorted((p, opener, size) for p, opener, size in tiles(src) if (b := tile_box(p)) is not None and area.intersects(b))
    with tarfile.open(out + ".tmp", "w", format=tarfile.USTAR_FORMAT) as t:
        meta = json.dumps({"graph": graph_id, "tiles": len(chosen)}).encode()
        info = tarfile.TarInfo("graph.json"); info.size = len(meta); t.addfile(info, io.BytesIO(meta))
        for p, opener, size in chosen:
            info = tarfile.TarInfo(p); info.size = size
            with opener() as f: t.addfile(info, f)
    os.replace(out + ".tmp", out)
    print(f"{out}: {len(chosen)} tiles, {os.path.getsize(out) / 1e6:.0f} MB, graph {graph_id}")


if __name__ == "__main__":
    if len(sys.argv) != 5: sys.exit(__doc__)
    main(*sys.argv[1:])
