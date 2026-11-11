// Routes from a Valhalla server (the same engine as the app's offline routing, same OSRM-format
// answer). The server's address comes from config.json so it can be changed without an update.
// The FOSSGIS server asks for at most one request a second: requests are spaced out here.
import { parseRoute, Geo } from './nav.js';

let config = null;
export async function getConfig() {
  if (config) return config;
  try { config = await (await fetch(new URL('../config.json', import.meta.url), { cache: 'no-cache' })).json(); }
  catch { config = {}; }
  config.routing = config.routing || 'https://valhalla1.openstreetmap.de/route';
  config.maps = config.maps || 'https://maps.orbitgps.ca/';
  return config;
}

let lastRequest = 0;
async function spaced() {
  const wait = lastRequest + 1100 - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  lastRequest = Date.now();
}

export class RouteError extends Error {}

async function request(stops, heading, options, signal) {
  const cfg = await getConfig();
  const locations = stops.map((p, i) => {
    const l = { lat: +p.lat.toFixed(6), lon: +p.lon.toFixed(6) };
    if (i > 0 && i < stops.length - 1) l.type = 'break';
    return l;
  });
  if (heading != null) { locations[0].heading = Math.round(heading); locations[0].heading_tolerance = 60; }
  const auto = {};
  if (options.avoidHighways) auto.use_highways = 0;
  if (options.avoidFerries) auto.use_ferry = 0;
  if (options.avoidUnpaved) auto.exclude_unpaved = true;
  if (options.avoidTolls) auto.use_tolls = 0;
  const body = { locations, costing: 'auto', costing_options: { auto }, format: 'osrm', directions_options: { units: 'kilometers' } };
  await spaced();
  let res;
  try {
    res = await fetch(cfg.routing, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new RouteError(navigator.onLine === false ? 'No data connection. Routes need a connection; the map and a route already planned keep working.' : 'The route server didn’t answer. Try again in a moment.');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.routes) {
    const msg = String(data.error || data.message || res.status);
    if (/no path|no suitable edges|unable to find a route/i.test(msg)) throw new RouteError('Couldn’t find a road route there.');
    if (res.status === 429) throw new RouteError('The route server is busy. Try again in a few seconds.');
    throw new RouteError('Couldn’t make a route: ' + msg);
  }
  return parseRoute(data);
}

/** Route through [stops] (first = start). [heading] keeps it from starting with a U-turn. */
export async function route(stops, heading, options = {}, signal) {
  let r = null, err = null;
  try { r = await request(stops, heading, options, signal); } catch (e) { if (e.name === 'AbortError') throw e; err = e; }
  if (heading != null) {
    const gap = r ? Geo.distance(r.points[0], stops[0]) : null;
    if (gap == null || gap > 60) {
      try {
        const plain = await request(stops, null, options, signal);
        const pg = Geo.distance(plain.points[0], stops[0]);
        if (gap == null || pg < gap) r = plain;
      } catch (e) { if (e.name === 'AbortError') throw e; if (!r) err = e; }
    }
  }
  if (!r) throw err;
  return r;
}
