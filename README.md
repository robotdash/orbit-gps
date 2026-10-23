# Orbit GPS

A free, offline, Garmin-style street GPS for Android and Android Auto, by robotdash studios
(Terrace, British Columbia). No account, no ads, no tracking. Website: https://orbitgps.ca

- Offline maps, routing (Valhalla on the phone) and search, downloaded per province or state
- Early, clear turn warnings with lane guidance and a natural voice
- RV and van-life places, with spots reported by Orbit users

## What's here

| Folder | |
|---|---|
| `app/` | the Android app (Kotlin, MapLibre, Valhalla, Android Auto) |
| `tools/` | building region packs: `build_region.sh CODE` makes the map, routing, search and Topo files for any province or state; see `tools/README-region-build.md` |
| `.github/workflows/regions.yml` | rebuilds and publishes the region packs every month |
| `server/spots/` | the spot-reporting service (Cloudflare Worker) |

## Data

Map and road data © OpenStreetMap contributors (ODbL). Map tiles: Protomaps. Addresses and
businesses: Overture Maps Foundation (CDLA-Permissive-2.0); BC addresses: BC Address Geocoder,
Province of British Columbia (Open Government Licence – BC). Terrain: Mapzen Terrain Tiles / AWS
Open Data. Routing: Valhalla. Voice: Kokoro.
