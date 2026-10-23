"""Build Orbit GPS's offline search database from an OSM extract.

Output: SQLite with an FTS4 table `places`:
  name    – what the user types to find it ("Save-On-Foods", "4741 Lakelse Avenue", "Kenney Street", "Smithers")
  kind    – city/town/village/hamlet/locality/neighbourhood/poi/address/street
  category– for POIs: "supermarket", "fuel", "restaurant"...
  detail  – second line shown in results (street + nearest town)
  lat, lon, rank (lower = more important)

Usage: python3 build_search.py region.osm.pbf search.db [addresses.csv | -] [extras.csv ...]
  addresses.csv: from fetch_bc_addresses.py or fetch_overture_addresses.py (replaces OSM's addresses)
  ORBIT_AREA=Alberta (environment): the province/state name shown under towns (default British Columbia)
  extras.csv: name,category,detail,lat,lon rows to add (e.g. from fetch_bc_rv.py)
"""
import functools, math, os, sqlite3, sys
import osmium

PLACE_RANK = {"city": 0, "town": 1, "village": 2, "hamlet": 3, "suburb": 4,
              "neighbourhood": 5, "locality": 6, "island": 6, "isolated_dwelling": 7}
POI_KEYS = ["amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare",
            "aeroway", "railway", "historic", "man_made", "public_transport", "emergency", "sport"]
SKIP_POI = {"bench", "waste_basket", "parking_space", "bicycle_parking", "vending_machine",
            "post_box", "telephone", "toilets", "drinking_water", "fire_hydrant", "recycling",
            "hunting_stand", "grit_bin", "waste_disposal", "clock", "shelter", "bbq",
            "picnic_table", "loading_dock", "power", "utility_pole", "street_lamp", "switch",
            "level_crossing", "crossing", "buffer_stop", "milestone", "signal", "survey_point",
            "mast", "pole", "manhole", "pipeline", "tower", "storage_tank", "silo", "chimney",
            "flagpole", "water_tap", "monitoring_station", "pumping_station", "tree", "track"}
ROAD_TYPES = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
              "residential", "living_street", "service", "motorway_link", "trunk_link",
              "primary_link", "secondary_link", "tertiary_link", "road"}


def centroid(nodes):
    lat = lon = n = 0.0
    for nd in nodes:
        if nd.location.valid():
            lat += nd.location.lat; lon += nd.location.lon; n += 1
    return (lat / n, lon / n) if n else None


def mid(nodes):
    good = [nd.location for nd in nodes if nd.location.valid()]
    if not good: return None
    m = good[len(good) // 2]
    return (m.lat, m.lon)


class H(osmium.SimpleHandler):
    def __init__(self, osm_addresses=True):
        super().__init__()
        self.places, self.pois, self.addrs, self.streets = [], [], [], {}
        self.osm_addresses = osm_addresses       # off when an address list replaces OSM's (saves memory)

    def _entry(self, tags, loc):
        if loc is None: return
        name = tags.get("name")
        place = tags.get("place")
        if place in PLACE_RANK and name:
            self.places.append((name, place, loc, PLACE_RANK[place]))
            return
        cat = None
        # Things drivers look for, kept even without a name.
        hw = tags.get("highway")
        am = tags.get("amenity")
        nat = tags.get("natural") or ("waterfall" if tags.get("waterway") == "waterfall" else None)
        if hw in ("rest_area", "services"):
            label = "rest area" if hw == "rest_area" else "service area"
            self.pois.append((name or label.capitalize(), label, loc, None, None))
            return
        if am in ("fuel", "charging_station") and not name:
            name = tags.get("brand") or tags.get("operator") or ("Gas station" if am == "fuel" else "EV charging")
        # RV / van life: worth finding even without a name.
        label = name or tags.get("operator") or tags.get("brand")
        if am == "sanitary_dump_station":
            self.pois.append((label or "Sani dump", "sanitary dump station", loc, None, None)); return
        if am == "water_point":
            self.pois.append((label or "RV water fill", "water fill", loc, None, None)); return
        if tags.get("sanitary_dump_station") in ("yes", "customers", "public") and label:
            self.pois.append((f"{label} (sani dump)", "sanitary dump station", loc, None, None))
        if (tags.get("shop") == "gas" or tags.get("fuel:lpg") == "yes") and label:
            self.pois.append((f"{label}" if tags.get("shop") == "gas" else f"{label} (propane)", "propane", loc, None, None))
        tour = tags.get("tourism")
        if tour in ("camp_site", "caravan_site") and tags.get("fee") == "no":
            self.pois.append((label or "Free campsite", "free camping", loc, None, None)); return
        if am == "parking" and tags.get("overnight") in ("yes", "customers"):
            self.pois.append((label or "Overnight parking", "free camping", loc, None, None)); return
        if nat in ("peak", "waterfall", "hot_spring", "beach", "glacier", "volcano", "cave_entrance", "arch") and name:
            self.pois.append((name, nat.replace("_", " "), loc, None, None))
            return
        for k in POI_KEYS:
            v = tags.get(k)
            if v and v not in ("yes", "no") and v not in SKIP_POI:
                cat = v; break
        hn, st = tags.get("addr:housenumber"), tags.get("addr:street")
        street_line = f"{hn} {st}" if hn and st else None
        if name and not cat:
            for k in ("building", "landuse", "natural", "waterway", "boundary"):
                v = tags.get(k)
                if v and k in ("building", "landuse"):
                    cat = "building" if k == "building" and v == "yes" else v
                    break
        if name and cat:
            self.pois.append((name, cat, loc, street_line, tags.get("addr:city")))
        elif street_line:
            if self.osm_addresses: self.addrs.append((street_line, loc, tags.get("addr:city")))

    def node(self, n):
        if not n.tags: return
        self._entry(n.tags, (n.location.lat, n.location.lon))

    def way(self, w):
        t = w.tags
        hw = t.get("highway")
        if hw in ROAD_TYPES and t.get("name"):
            key = t.get("name")
            loc = mid(w.nodes)
            if loc:
                self.streets.setdefault(key, []).append(loc)
            return
        if "name" in t or "addr:housenumber" in t or t.get("amenity") in ("fuel", "charging_station", "sanitary_dump_station", "water_point") \
                or hw in ("rest_area", "services") or t.get("overnight") in ("yes", "customers") \
                or (t.get("tourism") in ("camp_site", "caravan_site") and t.get("fee") == "no"):
            self._entry(t, centroid(w.nodes))


def fold(text):
    import unicodedata
    t = unicodedata.normalize("NFKD", text)
    t = "".join(c for c in t if not unicodedata.combining(c)).lower()
    return t.replace("'", "").replace("’", "")


def dist_km(a, b):
    la1, lo1 = map(math.radians, a); la2, lo2 = map(math.radians, b)
    x = (lo2 - lo1) * math.cos((la1 + la2) / 2); y = la2 - la1
    return 6371 * math.hypot(x, y)


# BC street-type abbreviations → full words, so "Drive" and "Dr" both find "5230 Centennial Dr".
STREET_TYPES = {"Dr": "Drive", "Ave": "Avenue", "St": "Street", "Rd": "Road", "Cres": "Crescent", "Pl": "Place",
                "Blvd": "Boulevard", "Hwy": "Highway", "Crt": "Court", "Ct": "Court", "Ln": "Lane", "Trail": "Trail",
                "Pkwy": "Parkway", "Cir": "Circle", "Terr": "Terrace", "Sq": "Square", "Way": "Way", "Gate": "Gate",
                "Grove": "Grove", "Hts": "Heights", "Hill": "Hill", "Loop": "Loop", "Run": "Run", "Close": "Close",
                "Bend": "Bend", "Pass": "Pass", "Rdge": "Ridge", "Esp": "Esplanade", "Mews": "Mews", "Pt": "Point",
                "Frontage Rd": "Frontage Road", "Fsr": "Forest Service Road", "Lp": "Loop", "Walk": "Walk"}
DIRS = {"N": "North", "S": "South", "E": "East", "W": "West", "NE": "Northeast", "NW": "Northwest", "SE": "Southeast", "SW": "Southwest"}


# Shown under towns and cities in results ("Terrace · British Columbia"). ORBIT_AREA=Alberta etc.
AREA = os.environ.get("ORBIT_AREA", "British Columbia")
# ORBIT_AREAS=areas.geojson (from region_area.py): towns outside the region get their own province/state.
AREAS = os.environ.get("ORBIT_AREAS")


def area_of():
    if not AREAS: return lambda lat, lon: AREA
    import json
    from shapely.geometry import shape, Point
    from shapely.prepared import prep
    fs = [(prep(shape(f["geometry"])), f["properties"]["name"]) for f in json.load(open(AREAS))["features"]]
    def find(lat, lon):
        p = Point(lon, lat)
        return next((n for g, n in fs if g.contains(p)), fs[0][1])
    return find


def bc_address_rows(csv_path):
    """Addresses from fetch_bc_addresses.py / fetch_overture_addresses.py as search rows, one at a
    time (California has ~13 million; a list of them all would need ~10 GB)."""
    import csv
    for r in csv.DictReader(open(csv_path)):
        short = r["street_address"]
        words = short.split()
        full = " ".join(STREET_TYPES.get(w, DIRS.get(w, w)) if i > 0 else w for i, w in enumerate(words))
        yield full, "address", "", r["locality"], float(r["lat"]), float(r["lon"]), 20, short


def main(pbf, out, bc_csv=None, extras=()):
    h = H(osm_addresses=not bc_csv)
    h.apply_file(pbf, locations=True, idx="flex_mem")
    towns = [(n, loc) for n, p, loc, r in h.places if p in ("city", "town", "village", "hamlet")]

    def near_town(loc):
        return _near_town(round(loc[0], 2), round(loc[1], 2))      # same answer within ~1 km

    @functools.lru_cache(maxsize=None)
    def _near_town(lat, lon):
        loc = (lat, lon)
        best = min(towns, key=lambda t: dist_km(loc, t[1]), default=None)
        return best[0] if best and dist_km(loc, best[1]) < 40 else ""

    import os
    if os.path.exists(out): os.remove(out)
    db = sqlite3.connect(out)
    db.execute("DROP TABLE IF EXISTS places")
    # 'search' is the only indexed column: name + category, lower-cased, accents removed,
    # so the basic tokenizer every Android phone has works.
    db.execute("CREATE VIRTUAL TABLE places USING fts4(search, name, kind, category, detail, lat, lon, rank, "
               "notindexed=name, notindexed=kind, notindexed=category, notindexed=detail, "
               "notindexed=lat, notindexed=lon, notindexed=rank)")
    rows = []
    where = area_of()
    for name, place, loc, rank in h.places:
        rows.append((name, place, "", where(loc[0], loc[1]), loc[0], loc[1], rank))
    for name, cat, loc, street, city in h.pois:
        detail = ", ".join(x for x in (street, city or near_town(loc)) if x)
        rows.append((name, "poi", cat.replace("_", " "), detail, loc[0], loc[1], 10))
    import csv
    extra_rows = [(r["name"], "poi", r["category"], r["detail"], float(r["lat"]), float(r["lon"]), 10)
                  for path in extras for r in csv.DictReader(open(path))]
    if extra_rows:
        # The official lists are better than OpenStreetMap's copies of the same places: drop OSM
        # rows of the same kind within 300 m of one.
        group = {"rest area": "rest", "service area": "rest", "camp site": "camp", "caravan site": "camp", "free camping": "camp"}
        cell = {}
        for e in extra_rows:
            g = group.get(e[2])
            if g: cell.setdefault((g, round(e[4], 2), round(e[5], 2)), []).append(e)
        def dup(r):
            g = group.get(r[2])
            if not g or r[1] != "poi": return False
            for dla in (-0.01, 0, 0.01):
                for dlo in (-0.01, 0, 0.01):
                    for e in cell.get((g, round(r[4] + dla, 2), round(r[5] + dlo, 2)), ()):
                        if dist_km((r[4], r[5]), (e[4], e[5])) < 0.3: return True
            return False
        before = len(rows)
        rows = [r for r in rows if not dup(r)] + extra_rows
        print(f"extras: +{len(extra_rows)}, replaced {before - (len(rows) - len(extra_rows))} OSM duplicates")
    # One entry per street per town (long highways get one per town they pass).
    for name, locs in h.streets.items():
        seen = set()
        for loc in locs:
            town = near_town(loc)
            if town in seen: continue
            seen.add(town)
            rows.append((name, "street", "", town, loc[0], loc[1], 15))
    area_names = {r[3] for r in rows if r[1] not in ("poi", "address", "street")} | {AREA}
    def town_of(detail):
        return detail.split(",")[-1].strip() if detail and detail not in area_names else ""
    # Searchable text: name, category, town (so "… terrace" works), plus any short form ("Dr").
    def text(r, short=""):
        return fold(" ".join(x for x in (r[0], r[2], town_of(r[3]), short) if x))
    db.executemany("INSERT INTO places VALUES (?,?,?,?,?,?,?,?)", ((text(r),) + r for r in rows))
    # Addresses go straight into the database, a batch at a time.
    if bc_csv:
        # Official addresses replace OpenStreetMap's (which are very sparse in northern BC).
        addresses = ((full, kind, cat, town or near_town((lat, lon)), lat, lon, rank, short)
                     for full, kind, cat, town, lat, lon, rank, short in bc_address_rows(bc_csv))
    else:
        addresses = ((line, "address", "", city or near_town(loc), loc[0], loc[1], 20, "") for line, loc, city in h.addrs)
    n_addr = 0
    import itertools
    while True:
        batch = [(text(a[:7], a[7]),) + a[:7] for a in itertools.islice(addresses, 200_000)]
        if not batch: break
        db.executemany("INSERT INTO places VALUES (?,?,?,?,?,?,?,?)", batch)
        n_addr += len(batch)
    db.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    db.execute("INSERT INTO meta VALUES ('version','2')")
    import datetime
    db.execute("INSERT INTO meta VALUES ('built', ?)", (datetime.date.today().isoformat(),))
    db.execute("INSERT INTO meta VALUES ('addresses', ?)", (str(n_addr),))
    db.execute("INSERT INTO meta VALUES ('rows', ?)", (str(len(rows) + n_addr),))
    db.commit()
    db.execute("INSERT INTO places(places) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    print(f"places={len(h.places)} pois={len(h.pois)} addresses={n_addr} streets={len(h.streets)} rows={len(rows) + n_addr}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != "-" else None, sys.argv[4:])
