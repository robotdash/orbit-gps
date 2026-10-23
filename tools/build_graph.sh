#!/bin/bash
# Build ONE Valhalla road network for a big area (all of Canada + US by default) and cut it into a
# routing.tar per region. Pieces of one build join up exactly on the phone, so a trip can run
# across every region someone has downloaded.
#
#   build_graph.sh WORKDIR GRAPH_ID CODE [CODE ...]
#     GRAPH_ID   name of this build, e.g. na-20261102 (the app only joins pieces with the same id)
#     CODE       regions to cut pieces for (CA-BC, CA-AB, US-WA ...); each gets
#                WORKDIR/CODE/out/routing.tar (and the area files, via region_area.py)
#   Settings: OSM_URLS (space-separated .osm.pbf URLs or local files; default Geofabrik Canada + US),
#             OVERTURE (release for region outlines), KEEP_TILES=1 to keep the whole network.
# Needs: osmium-tool, pyvalhalla==3.6.3, shapely, duckdb, pyproj. Disk: ~60 GB for Canada + US.
set -euo pipefail
W=$(realpath -m "$1"); GRAPH=$2; shift 2; CODES="$*"; T=$(dirname "$(realpath "$0")")
OVERTURE=${OVERTURE:-2026-10-21.0}
OSM_URLS=${OSM_URLS:-"https://download.geofabrik.de/north-america/canada-latest.osm.pbf https://download.geofabrik.de/north-america/us-latest.osm.pbf"}
G=$W/graph; mkdir -p "$G"
log() { echo "[$(date +%H:%M:%S)] graph: $*"; }

# 1. Download and keep only what routing needs (roads, ferries, turn restrictions, boundaries for
#    driving side / country rules), deleting each download once filtered to save disk.
if [ ! -s "$G/roads.osm.pbf" ]; then
  parts=()
  for u in $OSM_URLS; do
    f=$G/$(basename "$u"); r=$G/roads-$(basename "$u"); own=0
    [ -f "$u" ] && { f=$u; own=1; }      # a file already on disk (kept)
    if [ ! -s "$r" ]; then
      [ -s "$f" ] || { log "download $u"; "$T/fetch_pbf.sh" "$u" "$f"; }
      log "filter $(basename "$u")"
      osmium tags-filter "$f" w/highway w/route=ferry r/type=restriction r/boundary=administrative \
        --overwrite -o "$r.tmp.pbf" && mv "$r.tmp.pbf" "$r"
      [ $own = 1 ] || rm -f "$f"
    fi
    parts+=("$r")
  done
  log "merge"
  if [ ${#parts[@]} -gt 1 ]; then osmium merge "${parts[@]}" --overwrite -o "$G/roads.tmp.pbf" && mv "$G/roads.tmp.pbf" "$G/roads.osm.pbf"
  else mv "${parts[0]}" "$G/roads.osm.pbf"; fi
  rm -f "${parts[@]}"
  ls -la "$G/roads.osm.pbf"
fi

# 2. Valhalla
if [ ! -s "$G/done" ]; then
  log "routing network ($(nproc) cores)"
  rm -rf "$G/tiles"; mkdir -p "$G/tiles"
  python3 -m valhalla.valhalla_build_config --mjolnir-tile-dir "$G/tiles" --mjolnir-admin "$G/admins.sqlite" \
    --mjolnir-concurrency "$(nproc)" > "$G/valhalla.json"
  python3 -m valhalla valhalla_build_admins -c "$G/valhalla.json" "$G/roads.osm.pbf"
  python3 -m valhalla valhalla_build_tiles -c "$G/valhalla.json" "$G/roads.osm.pbf"
  rm -f "$G/roads.osm.pbf"
  echo "$GRAPH" > "$G/done"
  log "network: $(find "$G/tiles" -name '*.gph' | wc -l) tiles, $(du -sh "$G/tiles" | cut -f1)"
fi

# 3. One piece per region (region + 50 km)
for c in $CODES; do
  R=$W/$c; mkdir -p "$R/out"
  [ -s "$R/area.json" ] || python3 "$T/region_area.py" "$c" "$R" "$OVERTURE" >/dev/null
  python3 "$T/split_graph.py" "$G/tiles" "$GRAPH" "$R/buf50.geojson" "$R/out/routing.tar"
done
[ "${KEEP_TILES:-0}" = 1 ] || rm -rf "$G/tiles"
log "done"
