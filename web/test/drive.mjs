// Simulated drive along a saved Valhalla route: prints every prompt with where it was said.
import { readFileSync } from 'fs';
import { parseRoute, Navigator, Geo, iconFor } from '../go/js/nav.js';
const words = JSON.parse(readFileSync(new URL('../go/voice/manifest.json', import.meta.url)));
const route = parseRoute(readFileSync(process.argv[2], 'utf8'));
console.log('points', route.points.length, 'km', (route.length / 1000).toFixed(1), 'announced', route.announced.length);
for (const s of route.announced) console.log('  @', Math.round(s.distanceFromStart), s.maneuver.type, s.maneuver.side || '', s.streetName, iconFor(s), s.lanes ? JSON.stringify(s.lanes.valid) : '');
let t = 0, along = 0;
const said = [];
const nav = new Navigator(route, (clips) => {
  const text = clips.map((c) => words[c] ?? `[${c}]`).join(' ');
  const missing = clips.filter((c) => !(c in words));
  said.push({ along, text, missing });
  console.log(`  ${(along / 1000).toFixed(2)} km: ${text}${missing.length ? '  MISSING ' + missing : ''}`);
}, () => console.log('  OFF ROUTE'), () => t);
// drive: speed by distance to next turn (slow in town, fast between)
const cum = route.cumulative, pts = route.points;
let k = 0;
while (!nav.arrived && t < 6 * 3600 * 1000) {
  const p = nav.lastProgress;
  const kmh = p && p.distanceToNext < 300 ? 35 : 90;
  along = Math.min(along + kmh / 3.6, route.length);
  while (k < cum.length - 2 && cum[k + 1] < along) k++;
  const f = (along - cum[k]) / Math.max(cum[k + 1] - cum[k], 1e-9);
  const pos = Geo.lerp(pts[k], pts[k + 1], Math.min(1, f));
  nav.update(pos, Geo.bearing(pts[k], pts[k + 1]), kmh / 3.6, 5);
  t += 1000;
}
console.log('arrived', nav.arrived, 'after', Math.round(t / 60000), 'min; prompts', said.length, 'missing clips', said.some((s) => s.missing.length));
