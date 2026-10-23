# Building Orbit GPS region packs

## Any province or state (Oct 2026 on): one command

```
OSM_SOURCE=geofabrik tools/build_region.sh CA-AB work        # or US-WA, CA-ON, US-CA ...
CF_API_TOKEN=... CF_ACCOUNT_ID=... python3 tools/publish_region.py work/CA-AB --id ab
```

`build_region.sh` does every step and skips the ones already done (rerun it after a restart):

1. `region_area.py` — the province/state outline from Overture Maps, + 50 km (map, roads, search)
   and + 10 km (addresses, terrain), and which neighbours the 50 km strip reaches.
2. Map: `pmtiles extract` from the Protomaps daily build.
3. Roads: the region's and its neighbours' OpenStreetMap files (`osm_sources.py`: Geofabrik, or
   openstreetmap.fr from Claude's workspace where Geofabrik is blocked; `fetch_pbf.sh` downloads in
   checked 32 MB pieces), each filtered and clipped to the 50 km area, then merged.
4. Routing: Valhalla 3.6.3 tiles → `routing.tar`.
5. Search: OSM places + Overture addresses (`fetch_overture_addresses.py`, region + 10 km) +
   Overture businesses OSM lacks (`fetch_overture_places.py`) → `search.db`. Towns are labelled with
   their own province/state (`areas.geojson`).
6. Topo: Terrarium terrain z0–11 + contours over the region + 10 km.

`publish_region.py` splits files over 200 MiB, uploads them to `regions/<id>/<date>/`, adds the
region (with its outlines, used by the app to pick which region routes a trip) to
**regions-v2.json** (read by app 3.4+, which holds several regions), and checks the live files.
`regions.json` (apps up to 3.3, one region at a time) keeps only BC so older apps can't swap
their BC maps for another region by accident.

GitHub: `.github/workflows/regions.yml` runs this monthly for `tools/regions.txt`
(`latest_sources.py` picks the newest Protomaps and Overture releases).

Sizes (Oct 2026): Alberta map 541 MB, routing 278 MB, search 242 MB, terrain 186 MB, contours 11 MB
(~20 min to build). Washington: map 358 MB, routing 855 MB (dense roads; the strip reaches
Vancouver and Portland), search ~400 MB.

BC is still built by hand (BC Address Geocoder addresses, BC rec sites): see below.

---

## The original Terrace pack (by hand)

A region pack is five files the app imports with ☰ → Offline maps → Choose file. All of
them can be picked at once; each file's type is recognised from its contents, and files
split into `.part1`, `.part2` are joined back together on import.

| File | What it is | Size (Terrace, Oct 2026) |
|---|---|---|
| `<name>-map.pmtiles.part1/2` | Map display (Protomaps vector tiles, z0–14) | 498 MB |
| `<name>-routing.tar` | Valhalla 3.6.3 road network | 25 MB |
| `<name>-search.db` | Search: towns, streets, businesses, BC civic addresses | 18 MB |
| `<name>-terrain.pmtiles.part1/2` | Elevation for Topo hill shading (optional) | 564 MB |
| `<name>-contours.pmtiles` | 50 m contour lines for Topo (optional) | 22 MB |

The Terrace pack is a 483 km (300 mile) circle around Terrace (54.5163, -128.6035),
`terrace-circle.geojson` / `terrace-circle-feature.geojson` here; terrain and contours
cover 330 km.

## Tools

```
pip install --break-system-packages osmium pyvalhalla==3.6.3 pmtiles numpy scipy scikit-image shapely mapbox-vector-tile pillow
apt-get install osmium-tool
# go-pmtiles: https://github.com/protomaps/go-pmtiles/releases (pmtiles binary)
```

## Steps (about 30 minutes)

```
# 1. Map (minutes; streams only the needed part of the planet file)
pmtiles extract https://build.protomaps.com/YYYYMMDD.pmtiles map.pmtiles --region=terrace-circle.geojson --maxzoom=14

# 2. OSM data for BC. openstreetmap.fr is slow per connection: fetch it in 8 byte ranges and join them.
#    https://download.openstreetmap.fr/extracts/north-america/canada/british_columbia-latest.osm.pbf
osmium tags-filter bc.osm.pbf w/highway w/route=ferry r/type=restriction nwr/addr:housenumber nwr/name nwr/place \
  nwr/amenity nwr/shop nwr/tourism nwr/leisure nwr/office nwr/craft nwr/healthcare nwr/aeroway nwr/railway \
  nwr/historic nwr/natural nwr/emergency nwr/sport nwr/public_transport nwr/man_made nwr/brand nwr/operator \
  -o filtered.osm.pbf
osmium extract -p terrace-circle.geojson -s complete_ways filtered.osm.pbf -o region.osm.pbf

# 3. Routing
python3 -m valhalla.valhalla_build_config --mjolnir-tile-dir $PWD/tiles --mjolnir-admin $PWD/admins.sqlite > valhalla.json
python3 -m valhalla valhalla_build_admins -c valhalla.json region.osm.pbf
python3 -m valhalla valhalla_build_tiles -c valhalla.json region.osm.pbf
cd tiles && find . -name "*.gph" | sed 's#^\./##' | sort > ../list.txt && tar -cf ../routing.tar -T ../list.txt

# 4. Search (BC addresses: ~1,500 requests to the BC Address Geocoder, a few minutes)
python3 fetch_bc_addresses.py terrace-circle-feature.geojson bc_addresses.csv
python3 build_search.py region.osm.pbf search.db bc_addresses.csv

# 5. Terrain and contours (optional, for the Topo style)
python3 fetch_tiles.py dem 54.5163 -128.6035 330 12 terrain.pmtiles
python3 make_contours.py terrain.pmtiles contours.pmtiles

# 6. Name and split (send limit ~400 MB per file)
split -b 260m -d -a 1 --numeric-suffixes=1 map.pmtiles NAME-map.pmtiles.part
split -b 300m -d -a 1 --numeric-suffixes=1 terrain.pmtiles NAME-terrain.pmtiles.part
```

## Checks

- `./gradlew testDebugUnitTest --tests '*RegionFilesTest*' -Porbit.region=/path/to/pack` — every file is recognised.
- Copy the search file to `app/src/test/resources/search_terrace.db` and run `AddressSearchTest` and
  `NearbyTest` (5230 Centennial Drive must come first however it's typed).
- In the app, Offline maps shows each file's size, and the search file's build date and address count.
