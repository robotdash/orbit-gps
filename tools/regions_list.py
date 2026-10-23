"""Read tools/regions.txt for the GitHub workflow.

Lines: CODE [id=ID] [only=routing]
  id=     the catalog id, if not the province/state letters in lower case (CA-AB -> ab)
  only=routing   GitHub builds just this region's piece of the joint road network
                 (the rest of the region is built by hand, e.g. BC with the BC Address Geocoder)

  python3 regions_list.py [regions.txt] [CODE ...]   prints {"build": [...], "graph": [...]} as JSON
    build: regions whose map/search/Topo GitHub builds; graph: every region that gets a routing piece.
    CODEs given: just those (any code works, listed or not).
"""
import json, os, sys

def short(code):
    """Catalog id: the province/state letters ("CA-SK" -> "sk"; Canadian and US codes never clash)."""
    return code.split("-")[-1].lower()


path = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1].endswith(".txt") else os.path.join(os.path.dirname(__file__), "regions.txt")
want = [a for a in sys.argv[1:] if not a.endswith(".txt")]
rows = {}
for line in open(path):
    line = line.split("#")[0].split()
    if not line: continue
    opts = dict(o.split("=", 1) for o in line[1:] if "=" in o)
    rows[line[0]] = {"code": line[0], "id": opts.get("id", short(line[0])), "only": opts.get("only", "")}
if want:
    rows = {c: rows.get(c, {"code": c, "id": short(c), "only": ""}) for c in want}
print(json.dumps({
    "build": [{"code": r["code"], "id": r["id"]} for r in rows.values() if r["only"] != "routing"],
    "graph": [{"code": r["code"], "id": r["id"]} for r in rows.values()],
}))
