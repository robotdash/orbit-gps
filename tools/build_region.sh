#!/bin/bash
# Build one Orbit GPS region pack (a province or state + 50 km) from scratch.
#
#   build_region.sh CODE WORKDIR
#     CODE     CA-AB, US-WA ... (ISO 3166-2)
#   Settings (environment, with defaults):
#     OSM_SOURCE=geofabrik        or osmfr (openstreetmap.fr)
#     OVERTURE=2026-10-21.0       Overture Maps release (boundaries, addresses, businesses)
#     PROTOMAPS=20261021          Protomaps daily build (map display)
#     OSM_CACHE=$WORKDIR/osm      downloaded OSM files (shared between regions)
#     TOPO=1                      also build terrain + contours (0 to skip)
#     ROUTING=1                   0: skip routing (GitHub makes it from the joint network, build_graph.sh)
#
# Every step is skipped if its output exists, so a rerun picks up where it stopped.
# Output in WORKDIR/CODE/out: map.pmtiles, routing.tar, search.db, terrain.pmtiles, contours.pmtiles
# (then publish_region.py splits, uploads and adds them to the catalog).
#
# Needs: python3 with duckdb shapely pyproj numpy osmium pyvalhalla==3.6.3 pmtiles scipy scikit-image
#        mapbox-vector-tile pillow; osmium-tool; go-pmtiles as `pmtiles` on PATH (or $PMTILES).
set -euo pipefail
CODE=$1; W=$(realpath -m "$2"); T=$(dirname "$(realpath "$0")")
OSM_SOURCE=${OSM_SOURCE:-geofabrik}; OVERTURE=${OVERTURE:-2026-10-21.0}; PROTOMAPS=${PROTOMAPS:-20261021}
OSM_CACHE=${OSM_CACHE:-$W/osm}; TOPO=${TOPO:-1}; PMTILES=${PMTILES:-pmtiles}
R=$W/$CODE; O=$R/out; mkdir -p "$R" "$O" "$OSM_CACHE"
log() { echo "[$(date +%H:%M:%S)] $CODE: $*"; }

# 1. Area: outline, +50 km, +10 km, neighbours
[ -s "$R/area.json" ] && [ -s "$R/areas.geojson" ] || { log "area"; python3 "$T/region_area.py" "$CODE" "$R" "$OVERTURE"; }
NAME=$(python3 -c "import json;print(json.load(open('$R/area.json'))['name'])")
NEIGH=$(python3 -c "import json;print(' '.join(json.load(open('$R/area.json'))['neighbours']))")

# 2. Map display
[ -s "$O/map.pmtiles" ] || { log "map"; "$PMTILES" extract "https://build.protomaps.com/$PROTOMAPS.pmtiles" "$O/tmp-map.pmtiles" \
    --region="$R/buf50.geojson" --maxzoom=14 --download-threads=4 && mv "$O/tmp-map.pmtiles" "$O/map.pmtiles"; }

# 3. OpenStreetMap: the region and its neighbours, each filtered and clipped to the 50 km area, merged
if [ ! -s "$R/region.osm.pbf" ]; then
  parts=()
  for u in $(python3 "$T/osm_sources.py" "$OSM_SOURCE" "$CODE" $NEIGH); do
    f=$OSM_CACHE/$(basename "$u")
    [ -s "$f" ] || { log "download $u"; "$T/fetch_pbf.sh" "$u" "$f"; }
    c=$R/clip-$(basename "$u"); parts+=("$c")
    [ -s "$c" ] && continue
    log "filter + clip $(basename "$u")"
    osmium tags-filter "$f" w/highway w/route=ferry r/type=restriction nwr/addr:housenumber nwr/name nwr/place \
      nwr/amenity nwr/shop nwr/tourism nwr/leisure nwr/office nwr/craft nwr/healthcare nwr/aeroway nwr/railway \
      nwr/historic nwr/natural nwr/emergency nwr/sport nwr/public_transport nwr/man_made nwr/brand nwr/operator \
      --overwrite -o "$R/filtered.osm.pbf"
    osmium extract -p "$R/buf50.geojson" -s complete_ways --overwrite "$R/filtered.osm.pbf" -o "$c.tmp.pbf"
    mv "$c.tmp.pbf" "$c"; rm -f "$R/filtered.osm.pbf"
  done
  log "merge ${#parts[@]} parts"
  osmium merge "${parts[@]}" --overwrite -o "$R/region.osm.pbf.tmp.pbf" && mv "$R/region.osm.pbf.tmp.pbf" "$R/region.osm.pbf"
  rm -f "${parts[@]}"
fi

# 4. Routing (Valhalla 3.6.3, what the app's valhalla-mobile reads)
if [ "${ROUTING:-1}" = 1 ] && [ ! -s "$O/routing.tar" ]; then
  log "routing"; V=$R/valhalla; rm -rf "$V"; mkdir -p "$V"
  python3 -m valhalla.valhalla_build_config --mjolnir-tile-dir "$V/tiles" --mjolnir-admin "$V/admins.sqlite" > "$V/valhalla.json"
  python3 -m valhalla valhalla_build_admins -c "$V/valhalla.json" "$R/region.osm.pbf"
  python3 -m valhalla valhalla_build_tiles -c "$V/valhalla.json" "$R/region.osm.pbf"
  (cd "$V/tiles" && find . -name "*.gph" | sed 's#^\./##' | sort > ../list.txt && tar -cf "$O/routing.tar.tmp" -T ../list.txt)
  mv "$O/routing.tar.tmp" "$O/routing.tar"; rm -rf "$V"
fi

# 5. Search: OSM places + Overture addresses (region + 10 km) + Overture businesses OSM lacks
if [ ! -s "$O/search.db" ]; then
  EXTRAS=()
  if [ "$CODE" = CA-BC ]; then
    # BC: the province's own address list (Overture has only ~85% of it, under 60% in places like
    # Stewart and Burns Lake) and BC rec sites + rest areas (most of them free camping).
    [ -s "$R/addresses.csv" ] || { log "addresses (BC Address Geocoder)"; python3 "$T/fetch_bc_addresses.py" "$R/buf10.geojson" "$R/addresses.csv.tmp" && mv "$R/addresses.csv.tmp" "$R/addresses.csv"; }
    [ -s "$R/rv_extras.csv" ] || { log "BC rec sites and rest areas"; python3 "$T/fetch_bc_rv.py" "$R/buf50.geojson" "$R/rv_extras.csv.tmp" && mv "$R/rv_extras.csv.tmp" "$R/rv_extras.csv"; }
    EXTRAS+=("$R/rv_extras.csv")
  fi
  [ -s "$R/addresses.csv" ] || { log "addresses"; python3 "$T/fetch_overture_addresses.py" "$OVERTURE" "$R/buf10.geojson" "$R/addresses.csv.tmp" && mv "$R/addresses.csv.tmp" "$R/addresses.csv"; }
  [ -s "$R/osm_search.db" ] || { log "search (OSM only, to compare businesses)"; ORBIT_AREA="$NAME" ORBIT_AREAS="$R/areas.geojson" python3 "$T/build_search.py" "$R/region.osm.pbf" "$R/osm_search.db" -; }
  [ -s "$R/overture_places.csv" ] || { log "businesses"; python3 "$T/fetch_overture_places.py" "$OVERTURE" "$R/buf50.geojson" "$R/osm_search.db" "$R/overture_places.csv.tmp" && mv "$R/overture_places.csv.tmp" "$R/overture_places.csv"; }
  log "search"
  ORBIT_AREA="$NAME" ORBIT_AREAS="$R/areas.geojson" python3 "$T/build_search.py" "$R/region.osm.pbf" "$O/search.db.tmp" "$R/addresses.csv" "${EXTRAS[@]}" "$R/overture_places.csv" \
    && mv "$O/search.db.tmp" "$O/search.db"
fi

# 6. Topo: terrain (z0-11) and contours over the region + 10 km
if [ "$TOPO" = 1 ]; then
  # Huge regions (over 1.4 million km²: Nunavut, Alaska, Quebec) one zoom less: z11 made Nunavut's Topo 8.4 GB.
  TZ=$(python3 -c "import json;print(10 if json.load(open('$R/area.json'))['km2'] > 1_400_000 else 11)")
  [ -s "$O/terrain.pmtiles" ] || { log "terrain (z0-$TZ)"; python3 "$T/fetch_tiles.py" dem-area "$R/buf10.geojson" "$TZ" "$O/tmp-terrain.pmtiles" && mv "$O/tmp-terrain.pmtiles" "$O/terrain.pmtiles"; }
  [ -s "$O/contours.pmtiles" ] || { log "contours"; python3 "$T/make_contours.py" "$O/terrain.pmtiles" "$O/tmp-contours.pmtiles" && mv "$O/tmp-contours.pmtiles" "$O/contours.pmtiles"; }
fi
log "done: $(cd "$O" && ls -s --block-size=M | tr '\n' ' ')"
