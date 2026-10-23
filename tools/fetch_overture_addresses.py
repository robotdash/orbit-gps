"""Street addresses for any area, from Overture Maps (Canada: Statistics Canada's open address
database and provincial lists; US: the National Address Database, OpenAddresses, counties).

  python3 fetch_overture_addresses.py RELEASE area.geojson out.csv
Writes the same CSV as fetch_bc_addresses.py (street_address, locality, lat, lon, street_name,
street_type, civic), one row per building address (units folded together). Addresses with no
town (only a state) get an empty locality; build_search.py fills in the nearest town.
Needs: pip install duckdb shapely
"""
import csv, json, re, sys, urllib.request
import numpy as np
import shapely
from shapely.geometry import shape

BUCKET = "https://overturemaps-us-west-2.s3.us-west-2.amazonaws.com/"
DIRS = {"N", "S", "E", "W", "NE", "NW", "SE", "SW"}
KEEP_UPPER = DIRS | {"II", "III", "IV", "FSR", "RR", "SR", "US", "BC", "AB", "PO"}
TYPES = {"St", "Ave", "Rd", "Dr", "Blvd", "Cres", "Crt", "Ct", "Pl", "Way", "Lane", "Ln", "Hwy", "Trl", "Pkwy",
         "Cir", "Terr", "Sq", "Gate", "Grove", "Hts", "Hill", "Loop", "Run", "Close", "Bend", "Pass", "Rdge",
         "Esp", "Mews", "Pt", "Lp", "Walk", "Street", "Avenue", "Road", "Drive", "Boulevard", "Crescent", "Court",
         "Place", "Highway", "Trail", "Parkway", "Circle", "Terrace", "Square", "Ridge", "Point"}
ORD = re.compile(r"^(\d+)(ST|ND|RD|TH)$", re.I)


def files(release):
    xml = urllib.request.urlopen(f"{BUCKET}?list-type=2&prefix=release/{release}/theme=addresses/type=address/").read().decode()
    return [BUCKET + k for k in re.findall(r"<Key>([^<]+\.parquet)</Key>", xml)]


def word_case(w):
    if w.upper() in KEEP_UPPER: return w.upper()
    m = ORD.match(w)
    if m: return m.group(1) + m.group(2).lower()
    if w[:1].isdigit(): return w.lower()
    if w.upper().startswith("MC") and len(w) > 3: return "Mc" + w[2:].capitalize()
    return "-".join(p.capitalize() for p in w.split("-"))


def nice(text):
    """'104 ST NW' -> '104 St NW', 'Hawkcliff WAY NW' -> 'Hawkcliff Way NW' (words in one case only;
    'McLeod' and other mixed-case words are left alone)."""
    return " ".join(w if w != w.upper() and w != w.lower() else word_case(w) for w in (text or "").split())


def main(release, area, out):
    import duckdb
    g = json.load(open(area)); g = g["features"][0] if "features" in g else g
    poly = shape(g.get("geometry") or g); shapely.prepare(poly)
    x0, y0, x1, y1 = poly.bounds
    con = duckdb.connect(); con.sql("INSTALL httpfs; LOAD httpfs; SET threads=4;")
    cur = con.execute(f"""
        SELECT number, street, postal_city, CASE WHEN len(address_levels) > 1 THEN address_levels[-1].value END, (bbox.ymin+bbox.ymax)/2, (bbox.xmin+bbox.xmax)/2
        FROM read_parquet(?)
        WHERE bbox.xmin BETWEEN {x0} AND {x1} AND bbox.ymin BETWEEN {y0} AND {y1}
          AND number IS NOT NULL AND street IS NOT NULL""", [files(release)])
    seen, n_in, n_all = set(), 0, 0
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["street_address", "locality", "lat", "lon", "street_name", "street_type", "civic"])
        while True:
            batch = cur.fetchmany(200_000)
            if not batch: break
            n_all += len(batch)
            lat = np.array([b[4] for b in batch]); lon = np.array([b[5] for b in batch])
            inside = shapely.contains_xy(poly, lon, lat)
            for b, ok in zip(batch, inside):
                if not ok: continue
                num, street = b[0].strip(), nice(b[1])
                town = nice(b[2] or b[3] or "")
                key = (num, street.lower(), town.lower())
                if key in seen: continue            # one per building (units share the address)
                seen.add(key)
                parts = street.split()
                stype = next((p for p in reversed(parts) if p in TYPES), "")
                w.writerow([f"{num} {street}", town, round(b[4], 7), round(b[5], 7),
                            " ".join(p for p in parts if p != stype and p not in DIRS), stype, num])
                n_in += 1
            print(f"{n_all} read, {n_in} kept", flush=True)
    print(f"done: {n_in} addresses")


if __name__ == "__main__":
    if len(sys.argv) != 4: sys.exit(__doc__)
    main(*sys.argv[1:])
