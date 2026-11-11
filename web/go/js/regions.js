// Map data straight from the region packs the Android app downloads (maps.orbitgps.ca):
// regions-v3.json lists each province/state's map.pmtiles as 200 MiB ".partN" pieces, read here
// with HTTP range requests that may span two pieces. Ranges already read are kept in the Cache API
// so the map works where there's no signal, and the corridor along a route can be fetched ahead.

const pmtiles = window.pmtiles;
const TILE_CACHE = 'orbit-map-ranges-v1';
const MAX_CACHED = 12000;          // ranges (~60 KB each on average → well under 1 GB)

let tileCache = null;
async function openCache() {
  if (tileCache !== null) return tileCache;
  try { tileCache = await caches.open(TILE_CACHE); } catch { tileCache = false; }
  return tileCache;
}
let putsSinceTrim = 0;
async function trimCache(c) {
  try {
    const keys = await c.keys();
    if (keys.length <= MAX_CACHED) return;
    for (const k of keys.slice(0, keys.length - MAX_CACHED + 1000)) await c.delete(k);
  } catch {}
}

/** One region's map file, made of pieces, read like a single file. */
class PartsSource {
  constructor(key, base, parts) {
    this.key = key;
    this.parts = [];
    let off = 0;
    for (const p of parts) { this.parts.push({ url: new URL(p.url, base).href, start: off, size: p.size }); off += p.size; }
    this.total = off;
  }
  getKey() { return this.key; }

  async getBytes(offset, length, signal) {
    const end = Math.min(offset + length, this.total);
    const key = `https://cache.orbitgps.ca/${this.key}/${offset}-${end}`;
    const c = await openCache();
    if (c) {
      try {
        const hit = await c.match(key);
        if (hit) return { data: await hit.arrayBuffer() };
      } catch {}
    }
    const chunks = [];
    for (const p of this.parts) {
      const a = Math.max(offset, p.start), b = Math.min(end, p.start + p.size);
      if (a >= b) continue;
      const res = await fetch(p.url, { headers: { Range: `bytes=${a - p.start}-${b - p.start - 1}` }, signal });
      if (res.status !== 206 && res.status !== 200) throw new Error(`map ${res.status}`);
      let buf = await res.arrayBuffer();
      if (res.status === 200) buf = buf.slice(a - p.start, b - p.start);  // server ignored the range
      chunks.push(new Uint8Array(buf));
    }
    let data;
    if (chunks.length === 1) data = chunks[0].buffer.byteLength === chunks[0].byteLength ? chunks[0].buffer : chunks[0].slice().buffer;
    else {
      const out = new Uint8Array(end - offset);
      let o = 0;
      for (const ch of chunks) { out.set(ch, o); o += ch.byteLength; }
      data = out.buffer;
    }
    if (c) {
      try {
        await c.put(key, new Response(data.slice(0), { headers: { 'Content-Type': 'application/octet-stream' } }));
        if (++putsSinceTrim > 500) { putsSinceTrim = 0; trimCache(c); }
      } catch {}
    }
    return { data };
  }
}

// ------------------------------------------------------------------ catalog

function bboxOf(geom) {
  let w = 180, s = 90, e = -180, n = -90;
  const walk = (c) => {
    if (typeof c[0] === 'number') {
      w = Math.min(w, c[0]); e = Math.max(e, c[0]); s = Math.min(s, c[1]); n = Math.max(n, c[1]);
    } else c.forEach(walk);
  };
  walk(geom.coordinates);
  // Alaska (and its 50 km) crosses 180°: use the western side only.
  if (e - w > 180) e = Math.max(...flatLons(geom).filter((x) => x < 0)), w = Math.min(...flatLons(geom).filter((x) => x < 0));
  return [w, s, e, n];
}
function flatLons(geom) { const out = []; const walk = (c) => (typeof c[0] === 'number' ? out.push(c[0]) : c.forEach(walk)); walk(geom.coordinates); return out; }

function rings(geom) {
  return geom.type === 'Polygon' ? [geom.coordinates] : geom.type === 'MultiPolygon' ? geom.coordinates : [];
}
function inRing(lon, lat, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i], [xj, yj] = ring[j];
    if ((yi > lat) !== (yj > lat) && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
export function inside(geom, lon, lat) {
  if (!geom) return false;
  return rings(geom).some((poly) => inRing(lon, lat, poly[0]) && !poly.slice(1).some((h) => inRing(lon, lat, h)));
}

export class Regions {
  constructor(base) {
    this.base = base;
    this.list = [];
    this.protocol = new pmtiles.Protocol();
    this.opened = new Map();
  }

  async load() {
    const url = new URL('regions-v3.json', this.base).href;
    let cat = null;
    // Fresh copy when online; the last one when not.
    try {
      const res = await fetch(url, { cache: 'no-cache' });
      if (!res.ok) throw new Error(res.status);
      cat = await res.json();
      try { localStorage.setItem('orbit.catalog', JSON.stringify(cat)); } catch {}
    } catch {
      try { cat = JSON.parse(localStorage.getItem('orbit.catalog')); } catch {}
    }
    if (!cat) throw new Error('Map list unavailable');
    this.list = cat.regions
      .map((r) => {
        const map = r.files.filter((f) => f.kind === 'map').sort((a, b) => partNo(a.name) - partNo(b.name));
        if (!map.length || !r.area) return null;
        return { id: r.id, name: r.name, country: r.country, built: r.built, area: r.area, core: r.core, bbox: bboxOf(r.area), map };
      })
      .filter(Boolean);
    return this.list;
  }

  /** The source url for a region, opening it on first use. */
  urlFor(r) {
    const key = `${r.id}-${r.map[0].url.split('/').slice(-2, -1)[0]}`;
    if (!this.opened.has(key)) {
      const p = new pmtiles.PMTiles(new PartsSource(key, this.base, r.map));
      this.protocol.add(p);
      this.opened.set(key, p);
    }
    return 'pmtiles://' + key;
  }

  /** Regions to draw for a view: the ones whose area overlaps it, nearest first (at most [max]). */
  forView(bounds, center, max = 3) {
    const [w, s, e, n] = bounds;
    const hits = this.list.filter((r) => r.bbox[0] <= e && r.bbox[2] >= w && r.bbox[1] <= n && r.bbox[3] >= s);
    const score = (r) => (inside(r.core, center[0], center[1]) ? -1e9 : 0) +
      Math.hypot((r.bbox[0] + r.bbox[2]) / 2 - center[0], (r.bbox[1] + r.bbox[3]) / 2 - center[1]);
    return hits.sort((a, b) => score(a) - score(b)).slice(0, max);
  }

  regionAt(lon, lat) { return this.list.find((r) => inside(r.core, lon, lat)) || this.list.find((r) => inside(r.area, lon, lat)); }

  /**
   * Fetch the map pieces along a route ahead of time (for stretches with no signal):
   * z8–z14 tiles touching the line, [maxKm] from the start at the top zoom.
   */
  async prefetchCorridor(points, onProgress, maxKm = 250) {
    const tiles = new Map();
    let dist = 0;
    for (let i = 0; i < points.length; i++) {
      if (i) dist += hav(points[i - 1], points[i]);
      const p = points[i];
      for (const z of [8, 10, 12, 13, 14]) {
        if (z >= 13 && dist > maxKm * 1000) continue;
        const [x, y] = tileXY(p.lon, p.lat, z);
        for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
          if (z < 14 && (dx || dy)) continue;
          tiles.set(`${z}/${x + dx}/${y + dy}`, { z, x: x + dx, y: y + dy, lon: p.lon, lat: p.lat });
        }
      }
    }
    const list = [...tiles.values()];
    let done = 0;
    let i = 0;
    const worker = async () => {
      while (i < list.length) {
        const t = list[i++];
        const r = this.regionAt(t.lon, t.lat);
        if (r) {
          this.urlFor(r);
          const key = `${r.id}-${r.map[0].url.split('/').slice(-2, -1)[0]}`;
          try { await this.opened.get(key).getZxy(t.z, t.x, t.y); } catch {}
        }
        done++;
        if (onProgress && done % 20 === 0) onProgress(done, list.length);
      }
    };
    await Promise.all([worker(), worker(), worker(), worker()]);
    if (onProgress) onProgress(list.length, list.length);
    return list.length;
  }
}

function partNo(name) { const m = /\.part(\d+)$/.exec(name); return m ? +m[1] : 0; }
function tileXY(lon, lat, z) {
  const n = 2 ** z;
  const x = Math.floor(((lon + 180) / 360) * n);
  const r = (lat * Math.PI) / 180;
  const y = Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * n);
  return [x, y];
}
function hav(a, b) {
  const R = 6371000, dl = ((b.lat - a.lat) * Math.PI) / 180, dn = ((b.lon - a.lon) * Math.PI) / 180;
  const x = Math.sin(dl / 2) ** 2 + Math.cos((a.lat * Math.PI) / 180) * Math.cos((b.lat * Math.PI) / 180) * Math.sin(dn / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

// ------------------------------------------------------------------ style

const styleCache = {};
/** The app's own map style (assets/offline_style*.json), pointed at [urls] (one copy per region). */
export async function buildStyle(urls, night) {
  const name = night ? 'offline_style_night' : 'offline_style';
  if (!styleCache[name]) styleCache[name] = await (await fetch(new URL(`../style/${name}.json`, import.meta.url))).json();
  const s = JSON.parse(JSON.stringify(styleCache[name]));
  const root = new URL('../', import.meta.url).href;
  s.glyphs = root + 'glyphs/{fontstack}/{range}.pbf';
  s.sprite = root + 'sprites/' + (night ? 'dark' : 'light');
  const src = s.sources.protomaps;
  delete s.sources.protomaps;
  urls.forEach((u, i) => { s.sources[i ? `protomaps@${i}` : 'protomaps'] = { ...src, url: u }; });
  const layers = [];
  for (const l of s.layers) {
    if (l.source !== 'protomaps') { layers.push(l); continue; }
    urls.forEach((_, k) => layers.push(k ? { ...l, id: `${l.id}@${k}`, source: `protomaps@${k}` } : l));
  }
  s.layers = layers;
  return s;
}
