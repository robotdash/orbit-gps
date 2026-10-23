"""Print the newest Protomaps build and Overture release as shell settings, e.g.
  PROTOMAPS=20261022
  OVERTURE=2026-10-21.0
Only Protomaps builds with basemap version 4.x are used: the app's map styles are written for it,
and a new major version can rename layers (the build stops so the styles can be updated first).
"""
import json, re, sys, urllib.request

def get(url):   # (Cloudflare refuses Python's default user agent)
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "OrbitGPS-build/1"}), timeout=60).read()


builds = json.loads(get("https://build-metadata.protomaps.dev/builds.json"))
v4 = [b for b in builds if str(b.get("version", "")).startswith("4.")]
if not v4: sys.exit("no Protomaps 4.x build found")
newest = max(builds, key=lambda b: b["key"])
if not str(newest.get("version", "")).startswith("4."):
    print(f"# note: newest Protomaps build {newest['key']} is version {newest.get('version')}; staying on 4.x", file=sys.stderr)
print("PROTOMAPS=" + max(b["key"] for b in v4).removesuffix(".pmtiles"))

xml = get("https://overturemaps-us-west-2.s3.us-west-2.amazonaws.com/?list-type=2&prefix=release/&delimiter=/").decode()
releases = re.findall(r"<Prefix>release/(\d{4}-\d\d-\d\d\.\d+)/</Prefix>", xml)
print("OVERTURE=" + max(releases))
