"""Extra places for drivers and RV / van-life travellers from BC open data.

  - BC Recreation Sites (Recreation Sites and Trails BC): rustic campsites, most of
    them free ("free camping"), some with a fee ("camp site").
  - Ministry of Transportation rest areas ("rest area"), with toilets/tables noted.

Data: Province of British Columbia, Open Government Licence – British Columbia.
Output CSV (name, category, detail, lat, lon) for build_search.py --extra.

Usage: python3 fetch_bc_rv.py region.geojson extras.csv
"""
import csv, json, re, sys, urllib.request

WFS = ("https://openmaps.gov.bc.ca/geo/pub/wfs?service=WFS&version=2.0.0&request=GetFeature"
       "&typeName={layer}&outputFormat=application/json&srsName=EPSG:4326&count=10000")


def inside(poly, lon, lat):
    c = False; j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]; xj, yj = poly[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            c = not c
        j = i
    return c


def fetch(layer):
    with urllib.request.urlopen(WFS.format(layer=layer), timeout=180) as r:
        return json.load(r)["features"]


def clean(html):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html or "")).strip()


def paid(text):
    """Does the site description say camping costs money?"""
    t = text.lower()
    if re.search(r"\bno (camping |overnight )?fees?\b|\bfree of charge\b|\bno charge\b|\bfree (camping|to use)\b", t):
        return False
    return bool(re.search(
        r"\bfees? for (camping|service|overnight)|\bcamping fees?\b|\bfees? (will )?appl|\bfee sites?\b|\bfee'?s (are|is) (collected|in effect)"
        r"|\bfees? (is|are) (in effect|collected|charged)|\bfee for overnight|\bwith fees\b|\bsmall fee\b|\$\s?\d+(\.\d\d)?\s*(/|per)\s*(night|site|vehicle|party)", t))


def title(s):
    s = s.title()
    return re.sub(r"\b(Fsr|Bc|Rv)\b", lambda m: m.group(1).upper(), s)


def main(region, out):
    g = json.load(open(region))
    poly = (g.get("geometry") or g)["coordinates"][0]
    rows = []
    for f in fetch("WHSE_FOREST_TENURE.FTEN_REC_SITE_POINTS_SVW"):
        lon, lat = f["geometry"]["coordinates"][:2]
        if not inside(poly, lon, lat): continue
        p = f["properties"]
        name = (p.get("PROJECT_NAME") or "").strip()
        sites = p.get("NUM_CAMP_SITES") or 0
        if not name or sites <= 0: continue
        if clean(p.get("CLOSURE_DESCRIPTION")): continue
        desc = clean(p.get("PROJECT_DESCRIPTION"))
        access = [p.get(f"SUB_ACCESS_DESC{i}") for i in (1, 2, 3)] + [p.get(f"ACCESS_DESC{i}") for i in (1, 2, 3)]
        rv = any(a and ("Motorhome" in a or "Trailer" in a) for a in access)
        cat = "camp site" if paid(desc) else "free camping"
        bits = ["Rec site", f"{sites} site{'s' if sites != 1 else ''}"]
        if rv: bits.append("RVs OK")
        if cat == "camp site": bits.append("fee")
        nm = name if "rec" in name.lower() else f"{title(name)} Rec Site"
        rows.append((nm, cat, " · ".join(bits), lat, lon))
    n_rec = len(rows)
    for f in fetch("WHSE_IMAGERY_AND_BASE_MAPS.MOT_REST_AREAS_SP"):
        geom = f["geometry"]
        if not geom: continue
        lon, lat = geom["coordinates"][:2]
        if not inside(poly, lon, lat): continue
        p = f["properties"]
        name = title((p.get("REST_AREA_NAME") or "Rest area").strip())
        bits = []
        if p.get("HIGHWAY_NUMBER"): bits.append(f"Hwy {p['HIGHWAY_NUMBER']}")
        if p.get("DIRECTION_OF_TRAFFIC") and p["DIRECTION_OF_TRAFFIC"] not in ("Both", "Not Applicable"):
            bits.append(p["DIRECTION_OF_TRAFFIC"].lower())
        if (p.get("NUMBER_OF_TOILETS") or 0) > 0: bits.append("toilets")
        if p.get("OPEN_YEAR_ROUND_IND") == "No": bits.append("seasonal")
        if p.get("DISTANCE_FROM_MUNICIPALITY"): bits.append(p["DISTANCE_FROM_MUNICIPALITY"].lower())
        rows.append((f"{name} Rest Area", "rest area", " · ".join(bits), lat, lon))
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(["name", "category", "detail", "lat", "lon"]); w.writerows(rows)
    print(f"{n_rec} recreation sites, {len(rows) - n_rec} rest areas")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
