// Search: Photon (OpenStreetMap places and addresses) everywhere, plus BC's own Address Geocoder
// for street addresses in BC (it has addresses OpenStreetMap lacks). Nearby categories (gas, food…)
// come from OpenStreetMap through Overpass.

const PHOTON = 'https://photon.komoot.io/api/';
const PHOTON_REVERSE = 'https://photon.komoot.io/reverse';
const BC_GEOCODER = 'https://geocoder.api.gov.bc.ca/addresses.json';
const OVERPASS = ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'];

const inBC = (lon, lat) => lat > 48.2 && lat < 60.1 && lon > -139.1 && lon < -114.0;

function dist(a, b) {
  const R = 6371, dl = ((b.lat - a.lat) * Math.PI) / 180, dn = ((b.lon - a.lon) * Math.PI) / 180;
  const x = Math.sin(dl / 2) ** 2 + Math.cos((a.lat * Math.PI) / 180) * Math.cos((b.lat * Math.PI) / 180) * Math.sin(dn / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

function photonLabel(p) {
  const street = [p.housenumber, p.street].filter(Boolean).join(' ');
  const place = p.city || p.town || p.village || p.county || '';
  const name = p.name || street || place || 'Place';
  const parts = [];
  if (street && street !== name) parts.push(street);
  if (place && place !== name) parts.push(place);
  if (p.state && p.state !== name) parts.push(p.state);
  return { name, detail: parts.join(', ') };
}

const KIND = {
  amenity: { fuel: 'Gas', restaurant: 'Restaurant', fast_food: 'Fast food', cafe: 'Café', hospital: 'Hospital', pharmacy: 'Pharmacy', bank: 'Bank', parking: 'Parking', toilets: 'Toilets', police: 'Police' },
  shop: { supermarket: 'Grocery', convenience: 'Convenience', hardware: 'Hardware' },
  tourism: { hotel: 'Hotel', motel: 'Motel', camp_site: 'Campground', caravan_site: 'RV park' },
  place: { city: 'City', town: 'Town', village: 'Village', hamlet: 'Hamlet' },
};

export async function search(q, near, signal) {
  q = q.trim();
  if (!q) return [];
  const tasks = [];
  const u = new URL(PHOTON);
  u.searchParams.set('q', q);
  u.searchParams.set('limit', '12');
  u.searchParams.set('lang', 'en');
  if (near) { u.searchParams.set('lat', near.lat.toFixed(4)); u.searchParams.set('lon', near.lon.toFixed(4)); u.searchParams.set('location_bias_scale', '0.3'); }
  tasks.push(fetch(u, { signal }).then((r) => r.json()).then((d) => d.features.map((f) => {
    const p = f.properties;
    const { name, detail } = photonLabel(p);
    return { name, detail, kind: (KIND[p.osm_key] || {})[p.osm_value] || '', lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0], src: 'osm' };
  })).catch((e) => { if (e.name === 'AbortError') throw e; return []; }));

  // Street addresses in BC: the province's geocoder.
  const bc = /^\d/.test(q) && (!near || inBC(near.lon, near.lat));
  if (bc) {
    const g = new URL(BC_GEOCODER);
    g.searchParams.set('addressString', q);
    g.searchParams.set('maxResults', '5');
    g.searchParams.set('minScore', '65');
    g.searchParams.set('autoComplete', 'true');
    tasks.push(fetch(g, { signal }).then((r) => r.json()).then((d) => d.features
      .filter((f) => f.properties.matchPrecision === 'CIVIC_NUMBER' || f.properties.matchPrecision === 'BLOCK')
      .map((f) => {
        const full = f.properties.fullAddress;
        const i = full.indexOf(',');
        return { name: i > 0 ? full.slice(0, i) : full, detail: i > 0 ? full.slice(i + 2) : '', kind: 'Address', lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0], src: 'bc', score: f.properties.score };
      })).catch((e) => { if (e.name === 'AbortError') throw e; return []; }));
  }
  const [osm, bcList = []] = await Promise.all(tasks);
  // BC addresses first; then OSM results, dropping ones within 60 m of an address already listed.
  const out = [...bcList];
  for (const r of osm) if (!out.some((o) => dist(o, r) < 0.06 && o.name.toLowerCase() === r.name.toLowerCase())) out.push(r);
  if (near) {
    for (const r of out) r.km = dist(near, r);
    // Drop far-off namesakes (another "Centennial Drive" in Georgia) when there are matches nearby.
    if (out.some((r) => r.km < 500)) return out.filter((r) => r.km < 2000).slice(0, 15);
  }
  return out.slice(0, 15);
}

export const CATEGORIES = [
  { id: 'gas', label: 'Gas', q: 'nwr["amenity"="fuel"]' },
  { id: 'food', label: 'Food', q: 'nwr["amenity"~"^(restaurant|fast_food)$"]' },
  { id: 'coffee', label: 'Coffee', q: 'nwr["amenity"="cafe"]' },
  { id: 'grocery', label: 'Groceries', q: 'nwr["shop"~"^(supermarket|convenience)$"]' },
  { id: 'rest', label: 'Rest areas', q: 'nwr["highway"="rest_area"];nwr["amenity"="toilets"]' },
  { id: 'camp', label: 'Camping', q: 'nwr["tourism"~"^(camp_site|caravan_site)$"]' },
  { id: 'hotel', label: 'Hotels', q: 'nwr["tourism"~"^(hotel|motel)$"]' },
  { id: 'ev', label: 'EV charging', q: 'nwr["amenity"="charging_station"]' },
];

const CAT_LABEL = { fuel: 'Gas', restaurant: 'Restaurant', fast_food: 'Fast food', cafe: 'Café', supermarket: 'Grocery', convenience: 'Convenience', rest_area: 'Rest area', toilets: 'Toilets', camp_site: 'Campground', caravan_site: 'RV park', hotel: 'Hotel', motel: 'Motel', charging_station: 'EV charging' };

/** Places of a category around [near], nearest first; searches wider if nothing is close. */
export async function nearby(cat, near, signal) {
  for (const radius of [8000, 30000, 80000]) {
    const parts = cat.q.split(';').map((s) => `${s}(around:${radius},${near.lat.toFixed(5)},${near.lon.toFixed(5)});`).join('');
    const body = 'data=' + encodeURIComponent(`[out:json][timeout:15];(${parts});out center tags 60;`);
    let data = null;
    for (const url of OVERPASS) {
      try {
        const r = await fetch(url, { method: 'POST', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, signal });
        if (r.ok) { data = await r.json(); break; }
      } catch (e) { if (e.name === 'AbortError') throw e; }
    }
    if (!data) throw new Error('Nearby search is unavailable right now.');
    const out = data.elements.map((e) => {
      const t = e.tags || {};
      const lat = e.lat ?? (e.center && e.center.lat), lon = e.lon ?? (e.center && e.center.lon);
      if (lat == null) return null;
      const kind = CAT_LABEL[t.amenity] || CAT_LABEL[t.shop] || CAT_LABEL[t.tourism] || CAT_LABEL[t.highway] || '';
      const street = [t['addr:housenumber'], t['addr:street']].filter(Boolean).join(' ');
      const name = t.name || t.brand || t.operator || kind || cat.label;
      return { name, detail: [street, t['addr:city']].filter(Boolean).join(', '), kind, lat, lon, km: dist(near, { lat, lon }), src: 'osm' };
    }).filter(Boolean).sort((a, b) => a.km - b.km);
    if (out.length >= 3 || radius === 80000) return out.slice(0, 25);
  }
  return [];
}

/** A short name for a spot on the map ("5230 Centennial Dr, Terrace" or a place name). */
export async function reverse(p, signal) {
  try {
    if (inBC(p.lon, p.lat)) {
      const r = await fetch(`https://geocoder.api.gov.bc.ca/sites/nearest.json?point=${p.lon},${p.lat}&maxDistance=150&outputSRS=4326`, { signal });
      if (r.ok) { const d = await r.json(); if (d.properties && d.properties.fullAddress) return d.properties.fullAddress.replace(/, BC$/, ''); }
    }
  } catch (e) { if (e.name === 'AbortError') throw e; }
  try {
    const r = await fetch(`${PHOTON_REVERSE}?lat=${p.lat}&lon=${p.lon}&lang=en&limit=1`, { signal });
    const d = await r.json();
    const f = d.features && d.features[0];
    if (f) { const l = photonLabel(f.properties); return l.detail ? `${l.name}, ${l.detail.split(', ')[0]}` : l.name; }
  } catch (e) { if (e.name === 'AbortError') throw e; }
  return `${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}`;
}
