// Trip links shared with the planner (orbitgps.ca/plan) and the app:
// payload = "1" + base64url(UTF-8 text); lines: trip name, then "lat,lon,name" per stop
// (first = start, last = destination).

function b64urlEncode(bytes) {
  let s = '';
  bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function b64urlDecode(s) {
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  const bin = atob(s);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export function encodeTrip(name, stops) {
  const lines = [(name || '').slice(0, 60).replace(/\n/g, ' ')];
  for (const s of stops) lines.push(`${s.lat.toFixed(5)},${s.lon.toFixed(5)},${(s.name || '').slice(0, 50).replace(/\n/g, ' ')}`);
  return '1' + b64urlEncode(new TextEncoder().encode(lines.join('\n')));
}

export function decodeTrip(payload) {
  if (!payload || payload[0] !== '1') return null;
  try {
    const lines = new TextDecoder().decode(b64urlDecode(payload.slice(1))).split('\n');
    const name = lines.shift() || '';
    const stops = lines.map((l) => {
      const m = /^(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),?(.*)$/.exec(l.trim());
      if (!m) return null;
      const lat = +m[1], lon = +m[2];
      if (!(Math.abs(lat) <= 90 && Math.abs(lon) <= 180)) return null;
      return { lat, lon, name: m[3] || '' };
    }).filter(Boolean);
    return stops.length >= 2 ? { name, stops } : null;
  } catch { return null; }
}
