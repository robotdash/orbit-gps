"""Where to download OpenStreetMap data for a province/state and its neighbours.

  python3 osm_sources.py SOURCE CODE [CODE ...]     prints one URL per line (duplicates removed)
    SOURCE  geofabrik (one file per province/state; used by the GitHub builds)
            osmfr     (openstreetmap.fr; Geofabrik is blocked from Claude's workspace. Only
                       CA, CO, IL and MI have their own US files; other states come in big regional files)
    CODE    CA-AB, US-WA ... (region_area.py's code and "neighbours")
"""
import sys

CANADA = {"AB": "alberta", "BC": "british-columbia", "MB": "manitoba", "NB": "new-brunswick",
          "NL": "newfoundland-and-labrador", "NS": "nova-scotia", "NT": "northwest-territories", "NU": "nunavut",
          "ON": "ontario", "PE": "prince-edward-island", "QC": "quebec", "SK": "saskatchewan", "YT": "yukon"}
US = {"AL": "alabama", "AK": "alaska", "AZ": "arizona", "AR": "arkansas", "CA": "california", "CO": "colorado",
      "CT": "connecticut", "DE": "delaware", "DC": "district-of-columbia", "FL": "florida", "GA": "georgia",
      "HI": "hawaii", "ID": "idaho", "IL": "illinois", "IN": "indiana", "IA": "iowa", "KS": "kansas",
      "KY": "kentucky", "LA": "louisiana", "ME": "maine", "MD": "maryland", "MA": "massachusetts",
      "MI": "michigan", "MN": "minnesota", "MS": "mississippi", "MO": "missouri", "MT": "montana",
      "NE": "nebraska", "NV": "nevada", "NH": "new-hampshire", "NJ": "new-jersey", "NM": "new-mexico",
      "NY": "new-york", "NC": "north-carolina", "ND": "north-dakota", "OH": "ohio", "OK": "oklahoma",
      "OR": "oregon", "PA": "pennsylvania", "RI": "rhode-island", "SC": "south-carolina", "SD": "south-dakota",
      "TN": "tennessee", "TX": "texas", "UT": "utah", "VT": "vermont", "VA": "virginia", "WA": "washington",
      "WV": "west-virginia", "WI": "wisconsin", "WY": "wyoming"}
# openstreetmap.fr's US regions (US Census regions)
FR_US = {"us-west": "AK AZ CA CO HI ID MT NV NM OR UT WA WY",
         "us-midwest": "IL IN IA KS MI MN MO NE ND OH SD WI",
         "us-northeast": "CT ME MA NH NJ NY PA RI VT"}
FR_US_OWN = {"CA", "CO", "IL", "MI"}
NAMES = {**{"CA-" + k: v for k, v in CANADA.items()}, **{"US-" + k: v for k, v in US.items()}}

GF = "https://download.geofabrik.de/north-america/"
FR = "https://download.openstreetmap.fr/extracts/north-america/"


def url(source, code):
    country, sub = code.split("-")
    if country == "MX":
        return (GF if source == "geofabrik" else FR) + "mexico-latest.osm.pbf"
    if source == "geofabrik":
        return GF + ("canada/" if country == "CA" else "us/") + NAMES[code] + "-latest.osm.pbf"
    if country == "CA":
        return FR + "canada/" + NAMES[code].replace("-", "_") + "-latest.osm.pbf"
    region = next((r for r, s in FR_US.items() if sub in s.split()), "us-south")
    if sub in FR_US_OWN:
        return FR + region + "/" + NAMES[code] + "-latest.osm.pbf"
    return FR + region + "-latest.osm.pbf"


if __name__ == "__main__":
    if len(sys.argv) < 3: sys.exit(__doc__)
    seen = []
    for c in sys.argv[2:]:
        u = url(sys.argv[1], c)
        if u not in seen: seen.append(u)
    print("\n".join(seen))
