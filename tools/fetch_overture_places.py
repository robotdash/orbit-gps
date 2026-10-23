"""Businesses and other places from Overture Maps, for Orbit GPS's search.

OpenStreetMap (what build_search.py reads) has only some businesses: about 250 around Terrace
versus about 1,000 in Overture (Oct 2026: "Spruce Medical Centre" was missing). This adds
Overture places that OpenStreetMap doesn't already have.

Data: Overture Maps Foundation places theme (CDLA-Permissive-2.0, Apache-2.0, CC0).
Output CSV (name, category, detail, lat, lon) for build_search.py's extras.

Usage:
  python3 fetch_overture_places.py RELEASE region.geojson osm_search.db out.csv
    RELEASE        e.g. 2026-10-21.0 (see https://docs.overturemaps.org/release/latest/)
    region.geojson the area (Feature or FeatureCollection with one polygon)
    osm_search.db  a search.db built WITHOUT these extras (to skip places OSM already has)
Needs: pip install duckdb shapely
"""
import csv, json, math, re, sqlite3, sys, urllib.request
from shapely.geometry import shape, Point
from shapely.prepared import prep

BUCKET = "https://overturemaps-us-west-2.s3.us-west-2.amazonaws.com/"
MIN_CONFIDENCE = 0.6

# Natural features and other things the map already shows (or that aren't destinations).
SKIP = {"lake", "river", "mountain", "beach", "geographic_entities", "island", "forest", "waterfall",
        "historic_site", "park", "parking", "farm", "landmark_and_historical_building", "bay", "glacier",
        "canyon", "hiking_trail", "trail", "reservoir", "pond", "creek", "valley", "national_park", "state_park"}

# Overture category → the category names Orbit's place types look for (same words OSM gives).
MAP = {
    "gas_station": "fuel", "truck_gas_station": "fuel",
    "coffee_shop": "cafe", "cafe": "cafe", "coffee_roastery": "cafe", "tea_room": "cafe",
    "fast_food_restaurant": "fast food", "burger_restaurant": "fast food", "sandwich_shop": "fast food",
    "chicken_restaurant": "fast food", "hot_dog_restaurant": "fast food", "food_truck": "fast food",
    "gastropub": "pub", "pub": "pub", "sports_bar": "pub", "brewery": "pub",
    "grocery_store": "supermarket", "organic_grocery_store": "supermarket", "international_grocery_store": "supermarket",
    "asian_grocery_store": "supermarket", "mexican_grocery_store": "supermarket", "supermarket": "supermarket",
    "convenience_store": "convenience", "bakery": "bakery", "butcher_shop": "butcher", "meat_shop": "butcher",
    "hotel": "hotel", "motel": "motel", "bed_and_breakfast": "guest house", "guest_house": "guest house",
    "lodge": "chalet", "resort": "resort", "hostel": "hostel", "cabin": "chalet",
    "campground": "camp site", "rv_park": "caravan site",
    "hospital": "hospital", "general_hospital": "hospital", "emergency_room": "hospital",
    "doctors_office": "doctors", "family_practice": "doctors", "walk_in_clinic": "clinic", "medical_center": "clinic",
    "health_care": "clinic", "outpatient_care_facility": "clinic", "urgent_care_clinic": "clinic", "pediatric_clinic": "clinic",
    "public_health_clinic": "clinic",
    "dental_clinic": "dentist", "general_dentistry": "dentist", "pediatric_dentistry": "dentist", "cosmetic_dentistry": "dentist",
    "pharmacy": "pharmacy", "drugstore": "pharmacy",
    "veterinarian": "veterinary", "animal_hospital": "veterinary", "emergency_pet_hospital": "veterinary",
    "bank": "bank", "bank_or_credit_union": "bank", "credit_union": "bank", "atm": "atm",
    "automotive_repair": "car repair", "auto_body_shop": "car repair", "automotive_service": "car repair",
    "oil_change_station": "car repair", "tire_dealer_and_repair": "tyres", "tire_shop": "tyres",
    "auto_parts_store": "car parts", "ev_charging_station": "charging station", "propane_supplier": "propane",
}


def cat_for(c):
    if c in MAP: return MAP[c]
    if c.endswith("_restaurant") or c in ("restaurant", "diner", "bistro", "steakhouse"): return "restaurant"
    return c.replace("_", " ")


STOP = {"the", "and", "inc", "ltd", "llc", "co", "of", "&", "-", "corp", "limited", "store", "bc"}


def words(name):
    return {w for w in re.sub(r"[^a-z0-9 ]", " ", name.lower().replace("&", " and ")).split() if w not in STOP}


def same_name(a, b):
    """Close enough that two places 150 m apart are one place."""
    wa, wb = words(a), words(b)
    if not wa or not wb: return False
    if wa <= wb or wb <= wa: return True
    return len(wa & wb) / len(wa | wb) >= 0.5


def km(a, b):
    p = math.pi / 180
    return 12742 * math.asin(math.sqrt(math.sin((b[0] - a[0]) * p / 2) ** 2 +
        math.cos(a[0] * p) * math.cos(b[0] * p) * math.sin((b[1] - a[1]) * p / 2) ** 2))


def files(release):
    prefix = f"release/{release}/theme=places/type=place/"
    xml = urllib.request.urlopen(f"{BUCKET}?list-type=2&prefix={prefix}").read().decode()
    return [BUCKET + k for k in re.findall(r"<Key>([^<]+\.parquet)</Key>", xml)]


def main(release, region, osm_db, out):
    import duckdb
    g = json.load(open(region))
    g = g["features"][0] if "features" in g else g
    poly = shape(g.get("geometry") or g)
    inside = prep(poly)
    x0, y0, x1, y1 = poly.bounds
    con = duckdb.connect(); con.sql("INSTALL httpfs; LOAD httpfs;")
    rows = con.execute(f"""
        SELECT names."primary", taxonomy."primary", confidence, operating_status,
               addresses[1].freeform, addresses[1].locality, (bbox.ymin+bbox.ymax)/2, (bbox.xmin+bbox.xmax)/2
        FROM read_parquet(?)
        WHERE bbox.xmin BETWEEN {x0} AND {x1} AND bbox.ymin BETWEEN {y0} AND {y1}
          AND confidence >= {MIN_CONFIDENCE} AND names."primary" IS NOT NULL AND taxonomy."primary" IS NOT NULL
          AND coalesce(operating_status, 'open') NOT IN ('permanently_closed', 'temporarily_closed')
    """, [files(release)]).fetchall()
    print(len(rows), "Overture places in the box", flush=True)

    # OpenStreetMap's places (and camps/rest areas already added) on a ~200 m grid.
    grid = {}
    db = sqlite3.connect(osm_db)
    for name, cat, lat, lon in db.execute("SELECT name, category, lat, lon FROM places WHERE kind='poi'"):
        grid.setdefault((round(lat, 3), round(lon, 3)), []).append((name, cat, lat, lon))

    def near(lat, lon, r_km):
        # grid cells are 0.001° (~110 m north-south, ~65 m east-west at BC latitudes)
        nla = int(r_km / 0.11) + 1; nlo = int(r_km / 0.06) + 1
        for dla in (i * 0.001 for i in range(-nla, nla + 1)):
            for dlo in (j * 0.001 for j in range(-nlo, nlo + 1)):
                for o in grid.get((round(lat + dla, 3), round(lon + dlo, 3)), ()):
                    if km((lat, lon), (o[2], o[3])) <= r_km: yield o

    kept, seen, skipped_osm, skipped_kind, outside = [], {}, 0, 0, 0
    for name, ocat, conf, status, addr, town, lat, lon in rows:
        if ocat in SKIP: skipped_kind += 1; continue
        if not inside.contains(Point(lon, lat)): outside += 1; continue
        cat = cat_for(ocat)
        # OSM already has it (same name within 150 m, or any campsite within 300 m for campgrounds)
        # (the two sources can put one shop a few hundred metres apart: an exact name match counts to 400 m)
        if any(same_name(name, o[0]) for o in near(lat, lon, 0.15)) or \
                any(words(name) == words(o[0]) for o in near(lat, lon, 0.4)) or \
                (cat in ("camp site", "caravan site") and any(o[1] in ("camp site", "caravan site", "free camping") for o in near(lat, lon, 0.3))):
            skipped_osm += 1; continue
        key = (round(lat, 3), round(lon, 3))
        if any(same_name(name, n) for n in seen.get(key, ())): continue      # Overture's own duplicates
        seen.setdefault(key, []).append(name)
        detail = ", ".join(p for p in (addr, town) if p)
        kept.append((name.strip(), cat, detail, round(lat, 6), round(lon, 6)))
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(["name", "category", "detail", "lat", "lon"]); w.writerows(kept)
    print(f"kept {len(kept)}; already in OSM {skipped_osm}; natural/other skipped {skipped_kind}; outside area {outside}")


if __name__ == "__main__":
    if len(sys.argv) != 5: sys.exit(__doc__)
    main(*sys.argv[1:])
