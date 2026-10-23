"""Download every civic address inside a region from the BC Address Geocoder.

The geocoder's /sites/within endpoint returns at most 500 sites per box, so
boxes that come back full are split in four until each is under the limit.

It also only returns a site when the site lies *entirely* inside the box (a site is
more than one point: rooftop, parcel, access point), so sites near a box edge were
silently missing from both neighbours (Oct 21: 3700 22 St, Vernon). Every request
therefore asks for its box grown by a quarter of its width on all sides (at most MARGIN);
overlaps are de-duplicated. A fixed 200 m border made dense areas split endlessly (every
small box plus border held 500+ sites); a quarter of the width found every address in a
Vernon test area (9,652) with ~20% more requests.

Data: BC Address Geocoder, Province of British Columbia,
Open Government Licence – British Columbia.

Usage: python3 fetch_bc_addresses.py region.geojson out.csv
"""
import csv, json, math, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

API = "https://geocoder.api.gov.bc.ca/sites/within.json?bbox={},{},{},{}&maxResults=500&outputSRS=4326"
LIMIT = 500
MARGIN = 0.002   # degrees (~150-220 m): the most added around a box
MARGIN_FRAC = 0.25


def inside(poly, lon, lat):
    c = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]; xj, yj = poly[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            c = not c
        j = i
    return c


def box_touches(poly, b):
    x0, y0, x1, y1 = b
    if any(inside(poly, x, y) for x, y in [(x0, y0), (x1, y0), (x0, y1), (x1, y1), ((x0+x1)/2, (y0+y1)/2)]):
        return True
    return any(x0 <= x <= x1 and y0 <= y <= y1 for x, y in poly)


def fetch(b, tries=4):
    m = min(MARGIN, (b[2] - b[0]) * MARGIN_FRAC)
    url = API.format(b[0] - m, b[1] - m, b[2] + m, b[3] + m)
    for t in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return json.load(r)["features"]
        except Exception as e:
            if t == tries - 1:
                print("FAILED", b, e, file=sys.stderr); return []
            time.sleep(2 * (t + 1))


def main(region, out):
    g = json.load(open(region))
    poly = (g.get("geometry") or g)["coordinates"][0]
    xs = [p[0] for p in poly]; ys = [p[1] for p in poly]
    step = 0.5
    boxes = []
    x = math.floor(min(xs));
    while x < max(xs):
        y = math.floor(min(ys))
        while y < max(ys):
            b = (round(x, 6), round(y, 6), round(x + step, 6), round(y + step, 6))
            if box_touches(poly, b): boxes.append(b)
            y += step
        x += step
    sites = {}
    requests = 0
    # Progress is saved to OUT.progress so a stopped run picks up where it left off.
    ckpt = out + ".progress"
    if os.path.exists(ckpt):
        with open(ckpt) as fh:
            st = json.load(fh)
        sites = {tuple(k.split("\t")): tuple(v) for k, v in st["sites"].items()}
        boxes = [tuple(b) for b in st["boxes"]]; requests = st["requests"]
        print(f"resuming: {len(sites)} addresses, {len(boxes)} boxes to go", flush=True)

    def save(todo):
        tmp = ckpt + ".tmp"
        with open(tmp, "w") as fh:
            json.dump({"sites": {"\t".join(k): v for k, v in sites.items()}, "boxes": todo, "requests": requests}, fh)
        os.replace(tmp, ckpt)

    with ThreadPoolExecutor(4) as pool:
        while boxes:
            nxt = []
            # Handle each reply as it comes (a round can be thousands of boxes; keeping
            # them all at once ran out of memory on Oct 21).
            for i, (b, feats) in enumerate(zip(boxes, pool.map(fetch, boxes))):
                requests += 1
                if len(feats) >= LIMIT and (b[2] - b[0]) > 0.0005:
                    mx, my = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
                    for q in [(b[0], b[1], mx, my), (mx, b[1], b[2], my), (b[0], my, mx, b[3]), (mx, my, b[2], b[3])]:
                        nxt.append(tuple(round(v, 7) for v in q))
                else:
                    for f in feats:
                        p = f["properties"]
                        lon, lat = f["geometry"]["coordinates"]
                        if p.get("unitNumber") or not p.get("civicNumber"): continue
                        if not inside(poly, lon, lat): continue
                        key = (p["streetAddress"], p["localityName"])
                        sites[key] = (p["streetAddress"], p["localityName"], lat, lon,
                                      p.get("streetName", ""), p.get("streetType", ""), p.get("civicNumber"))
                if (i + 1) % 2000 == 0:
                    save(list(boxes[i + 1:]) + nxt)
            print(f"requests {requests}, addresses {len(sites)}, next round {len(nxt)} boxes", flush=True)
            boxes = nxt
            save(boxes)
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["street_address", "locality", "lat", "lon", "street_name", "street_type", "civic"])
        for row in sorted(sites.values()):
            w.writerow(row)
    print("done:", len(sites), "addresses")
    if os.path.exists(ckpt): os.remove(ckpt)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
