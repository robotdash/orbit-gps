"""Publish a built region pack to maps.orbitgps.ca (Cloudflare R2) and add it to regions.json.

  python3 publish_region.py WORKDIR/CODE [--id ID] [--kinds map,routing,...] [--entry-only] [--dry-run]
  python3 publish_region.py --merge entry1.json entry2.json ...
    --entry-only  upload the files and write WORKDIR/CODE/entry.json, but leave regions.json alone
                  (GitHub builds many regions at once; one job merges their entries at the end)
    --merge       put saved entries into the live catalog (and upload it), then delete builds
                  older than the previous one
  python3 publish_region.py --prune ab wa [--dry-run]   only the clean-up
Environment: CF_API_TOKEN (R2 edit), CF_ACCOUNT_ID, R2_BUCKET (default orbitgps-maps).

Follows the publishing rule: every kind published goes, with all its parts, into a new folder
regions/<id>/<YYYY-MM-DD>[b, c...]/; kinds not published keep their old URLs. Files over 200 MiB are
split into .partN pieces (the app joins them). The catalog goes up last, so phones never see a
catalog pointing at files that aren't there yet.
"""
import argparse, datetime, hashlib, json, os, sys, time, urllib.error, urllib.request

PART = 200 * 1024 * 1024
BASE = "https://maps.orbitgps.ca/"
AGENT = "OrbitGPS-build/1"
FILES = {"map": ("map.pmtiles", False), "routing": ("routing.tar", False), "search": ("search.db", False),
         "terrain": ("terrain.pmtiles", True), "contours": ("contours.pmtiles", True)}


def api(path, data=None, ctype="application/octet-stream", method="PUT"):
    acct, tok = os.environ["CF_ACCOUNT_ID"], os.environ["CF_API_TOKEN"]
    bucket = os.environ.get("R2_BUCKET", "orbitgps-maps")
    url = f"https://api.cloudflare.com/client/v4/accounts/{acct}/r2/buckets/{bucket}/objects/{path}"
    for t in range(5):
        try:
            req = urllib.request.Request(url, data=data, method=method,
                                         headers={"Authorization": f"Bearer {tok}", "Content-Type": ctype})
            with urllib.request.urlopen(req, timeout=1800) as r:
                if json.load(r).get("success"): return
        except Exception as e:
            print("  retry", t + 1, path, e, flush=True)
        time.sleep(10 * (t + 1))
    sys.exit(f"upload failed: {path}")


def pieces(path):
    """(name suffix, bytes) for each piece of the file (one piece if it fits)."""
    size = os.path.getsize(path)
    with open(path, "rb") as fh:
        if size <= PART:
            yield "", fh.read(); return
        i = 1
        while True:
            b = fh.read(PART)
            if not b: return
            yield f".part{i}", b; i += 1


# Apps up to 3.3 hold one region at a time and read regions.json (BC only, so nobody replaces their
# maps by accident); apps that hold several regions read regions-v2.json. CATALOG=regions.json to change.
CATALOG = os.environ.get("CATALOG", "regions-v2.json")


def outline(path, tolerance=0.01):
    """A region outline for the catalog: ~1 km detail, coordinates to 3 decimals (the app uses it
    to pick which region routes a trip, so it only needs to be roughly right)."""
    from shapely.geometry import shape, mapping
    g = json.load(open(path)); g = g.get("geometry") or g
    geom = mapping(shape(g).simplify(tolerance, preserve_topology=True))
    def r(c): return [r(x) for x in c] if isinstance(c[0], (list, tuple)) else [round(c[0], 3), round(c[1], 3)]
    return {"type": geom["type"], "coordinates": r(geom["coordinates"])}


def fetch_catalog():
    req = urllib.request.Request(BASE + f"{CATALOG}?t={int(time.time())}", headers={"User-Agent": AGENT})
    try:
        return json.load(urllib.request.urlopen(req, timeout=60))
    except urllib.error.HTTPError as e:
        if e.code != 404: raise
        print(f"{CATALOG} isn't there yet: starting it from regions.json")
        req = urllib.request.Request(BASE + f"regions.json?t={int(time.time())}", headers={"User-Agent": AGENT})
        return json.load(urllib.request.urlopen(req, timeout=60))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("region_dir", nargs="?"); ap.add_argument("--id"); ap.add_argument("--kinds")
    ap.add_argument("--description"); ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--entry-only", action="store_true"); ap.add_argument("--merge", nargs="+")
    ap.add_argument("--prune", nargs="+", metavar="ID", help="only delete old builds of these regions")
    a = ap.parse_args()
    if a.prune: return prune(a.prune, [fetch_catalog()], a.dry_run)
    if a.merge: return merge([json.load(open(p)) for p in a.merge], a.dry_run)
    area = json.load(open(os.path.join(a.region_dir, "area.json")))
    rid = a.id or area["code"].lower()
    out = os.path.join(a.region_dir, "out")
    kinds = a.kinds.split(",") if a.kinds else [k for k, (f, _) in FILES.items() if os.path.exists(os.path.join(out, f))]
    cat = fetch_catalog()
    regions = cat["regions"]
    old = next((r for r in regions if r["id"] == rid), None)
    used = {f["url"].split("/")[2] for r in regions if r["id"] == rid for f in r["files"]}
    day = datetime.date.today().isoformat()
    folder = next(day + s for s in [""] + list("bcdefghijklmnopqrstuvwxyz") if day + s not in used)
    files = [f for f in (old["files"] if old else []) if f["kind"] not in kinds]
    for kind in kinds:
        fname, optional = FILES[kind]
        for suffix, data in pieces(os.path.join(out, fname)):
            name = f"{rid}-{fname}{suffix}"
            url = f"regions/{rid}/{folder}/{name}"
            print(f"{url}  {len(data) / 1e6:.0f} MB", flush=True)
            if not a.dry_run: api(url, data)
            files.append({"kind": kind, "name": name, "url": url, "size": len(data),
                          "sha256": hashlib.sha256(data).hexdigest(), "optional": optional})
    order = list(FILES)
    files.sort(key=lambda f: (order.index(f["kind"]), f["name"]))
    w, s, e, n = area["bbox50"]
    outlines = {k: outline(os.path.join(a.region_dir, f)) for k, f in (("area", "buf50.geojson"), ("core", "boundary.geojson"))}
    entry = {"id": rid, "name": area["name"],
             "description": a.description or (old or {}).get("description") or
                            f"All of {area['name']}, plus about 50 km around it so routes can cross the border.",
             "built": day, "center": [round((s + n) / 2, 2), round((w + e) / 2, 2)],
             "radiusKm": round(max(n - s, (e - w) * 0.6) * 111 / 2), "files": files, **outlines}
    json.dump(dict(entry, published=kinds), open(os.path.join(a.region_dir, "entry.json"), "w"), indent=1)
    if a.entry_only: print(f"files up; entry saved for --merge"); return
    merge([dict(entry, published=kinds)], a.dry_run)


def merge(entries, dry_run=False):
    cat = fetch_catalog()
    before = json.loads(json.dumps(cat))          # the catalog phones had until now
    regions = cat["regions"]
    for e in entries:
        e = {k: v for k, v in e.items() if k != "published"}
        old = next((r for r in regions if r["id"] == e["id"]), None)
        if old:
            # keep kinds this build didn't publish at their old URLs
            done = {f["kind"] for f in e["files"]}
            e["files"] = [f for f in old["files"] if f["kind"] not in done] + e["files"]
            regions[regions.index(old)] = e
        else:
            regions.append(e)
    regions.sort(key=lambda r: (r["id"] != "bc", r["name"]))      # BC first, then A-Z
    cat["updated"] = datetime.date.today().isoformat()
    text = json.dumps(cat, indent=1).encode()
    open(CATALOG, "wb").write(text)
    if dry_run: print(f"dry run: {CATALOG} written locally only"); return
    api(CATALOG, text, "application/json")
    verify(entries)
    prune([e["id"] for e in entries], [before, cat])


def r2_list(prefix):
    """Keys and sizes under prefix in the bucket."""
    acct, tok = os.environ["CF_ACCOUNT_ID"], os.environ["CF_API_TOKEN"]
    bucket = os.environ.get("R2_BUCKET", "orbitgps-maps")
    out, cursor = [], None
    while True:
        q = f"prefix={prefix}&per_page=1000" + (f"&cursor={cursor}" if cursor else "")
        req = urllib.request.Request(f"https://api.cloudflare.com/client/v4/accounts/{acct}/r2/buckets/{bucket}/objects?{q}",
                                     headers={"Authorization": f"Bearer {tok}"})
        r = json.load(urllib.request.urlopen(req, timeout=60))
        out += [(o["key"], o.get("size", 0)) for o in r.get("result", [])]
        cursor = (r.get("result_info") or {}).get("cursor")
        if not cursor or not (r.get("result_info") or {}).get("is_truncated"): return out


def prune(region_ids, catalogs, dry_run=False):
    """
    Delete old builds of these regions: files that neither the new catalog nor the one before it
    (phones may be partway through downloading it) nor the old regions.json (apps up to 3.3)
    point at. Keeps storage at about two builds' worth.
    """
    keep = {f["url"] for c in catalogs for r in c["regions"] for f in r["files"]}
    try:
        req = urllib.request.Request(BASE + f"regions.json?t={int(time.time())}", headers={"User-Agent": AGENT})
        keep |= {f["url"] for r in json.load(urllib.request.urlopen(req, timeout=60))["regions"] for f in r["files"]}
    except Exception as e:
        print("prune skipped: couldn't read regions.json:", e); return
    freed = 0
    for rid in region_ids:
        for key, size in r2_list(f"regions/{rid}/"):
            if key in keep: continue
            print(("would delete " if dry_run else "delete ") + key, flush=True)
            if not dry_run: api(key, method="DELETE")
            freed += size
    print(f"old builds removed: {freed / 1e9:.1f} GB")


def verify(entries):
    """Check what phones will get: every newly published file, from the live site."""
    live = {r["id"]: r for r in fetch_catalog()["regions"]}
    for e in entries:
        for f in live[e["id"]]["files"]:
            if f["kind"] not in e.get("published", []): continue
            for t in range(3):     # a long download can be cut short; only a full copy counts
                req = urllib.request.Request(BASE + f["url"], headers={"User-Agent": AGENT})
                h, n = hashlib.sha256(), 0
                try:
                    with urllib.request.urlopen(req, timeout=1800) as r:
                        for b in iter(lambda: r.read(1 << 20), b""): h.update(b); n += len(b)
                except Exception as e:
                    print("  re-checking", f["url"], e)
                if n == f["size"]: break
            assert n == f["size"] and h.hexdigest() == f["sha256"], f"live file differs: {f['url']}"
        print(f"published {e['id']} ({', '.join(e.get('published', []))}); live files verified")


if __name__ == "__main__":
    main()
