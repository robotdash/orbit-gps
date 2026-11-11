// Orbit GPS (web version, for iPhone): map, search, routes and spoken turn-by-turn directions.
import { Navigator, Geo, iconFor, fmtDistance, fmtDuration, Prompts } from './nav.js';
import { Voice } from './voice.js';
import { Regions, buildStyle } from './regions.js';
import { search, nearby, reverse, CATEGORIES } from './search.js';
import { route as planRoute, getConfig, RouteError } from './router.js';
import { decodeTrip } from './trip.js';
import { ICONS } from './icons.js';

const $ = (id) => document.getElementById(id);
const maplibregl = window.maplibregl;
const TERRACE = { lon: -128.6, lat: 54.5163 };
const isIOS = /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const standalone = window.navigator.standalone === true || matchMedia('(display-mode: standalone)').matches;
const SIM = /[#&?]sim\b/.test(location.href);

// ------------------------------------------------------------------ settings

const store = {
  get(k, d) { try { const v = localStorage.getItem('orbit.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('orbit.' + k, JSON.stringify(v)); } catch {} },
};
const settings = Object.assign({ voice: true, early: false, avoidHighways: false, avoidFerries: false, avoidUnpaved: false, avoidTolls: false, night: 'auto', view: '3d' }, store.get('settings', {}));
const saveSettings = () => store.set('settings', settings);
Prompts.warningScale = settings.early ? 1.3 : 1.0;

// ------------------------------------------------------------------ icons

function icon(el, name) { el.innerHTML = ICONS[name] || ''; }
document.querySelectorAll('[data-icon]').forEach((el) => icon(el, el.dataset.icon));
icon($('menuBtn'), 'ic_menu');
icon($('locBtn'), 'ic_recenter');
icon($('pvClose'), 'ic_close');
icon($('plClose'), 'ic_close');
icon($('searchBack'), 'ic_back');
icon($('installClose'), 'ic_close');

// ------------------------------------------------------------------ state

const state = {
  mode: 'browse',          // browse | search | place | preview | drive
  fix: null,               // last GPS fix {lon, lat, heading, speed, accuracy, t}
  follow: true,
  stops: [],               // destination stops (not the start): {lat, lon, name}
  start: null,             // explicit start for a trip link
  route: null,
  nav: null,
  progress: null,
  routeAbort: null,
  regionsKey: '',
  night: false,
  lastFixAt: 0,
  gpsLostSaid: false,
  reached: 0,              // waypoints reached on this drive
  routeName: '',
};
const voice = new Voice();
voice.muted = !settings.voice;
let config = null;
let regions = null;
let map = null;

// ------------------------------------------------------------------ helpers

let toastTimer = 0;
function toast(text, ms = 3500) {
  const t = $('toast');
  t.textContent = text;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  if (ms) toastTimer = setTimeout(() => t.classList.add('hidden'), ms);
}
function dialog(html) {
  $('dialogBody').innerHTML = html;
  $('dialog').classList.remove('hidden');
}
$('dialogOk').onclick = () => $('dialog').classList.add('hidden');
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clockTime = (d) => d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

function setMode(m) {
  state.mode = m;
  document.body.classList.toggle('driving', m === 'drive');
  document.body.classList.toggle('sheet-open', m === 'preview' || m === 'place');
  $('top').classList.toggle('hidden', m === 'drive' || m === 'search');
  $('banner').classList.toggle('hidden', m !== 'drive');
  $('driveBar').classList.toggle('hidden', m !== 'drive');
  $('voiceBtn').classList.toggle('hidden', m !== 'drive');
  $('preview').classList.toggle('hidden', m !== 'preview');
  $('place').classList.toggle('hidden', m !== 'place');
  $('search').classList.toggle('hidden', m !== 'search');
  if (m !== 'drive') $('thenBox').classList.add('hidden');
  $('installTip').classList.toggle('hidden', !(m === 'browse' && installTipWanted()));
  updateRecenter();
  if (map) applyPadding();
}

// ------------------------------------------------------------------ sun (day/night colours)

function sunAltitude(date, lat, lon) {
  const rad = Math.PI / 180;
  const d = date / 86400000 - 10957.5;           // days since J2000
  const g = rad * (357.529 + 0.98560028 * d);
  const q = 280.459 + 0.98564736 * d;
  const L = rad * (q + 1.915 * Math.sin(g) + 0.02 * Math.sin(2 * g));
  const e = rad * (23.439 - 0.00000036 * d);
  const ra = Math.atan2(Math.cos(e) * Math.sin(L), Math.cos(L));
  const dec = Math.asin(Math.sin(e) * Math.sin(L));
  const gmst = (18.697374558 + 24.06570982441908 * d) % 24;
  const ha = rad * (gmst * 15 + lon) - ra;
  return Math.asin(Math.sin(rad * lat) * Math.sin(dec) + Math.cos(rad * lat) * Math.cos(dec) * Math.cos(ha)) / rad;
}
function wantNight() {
  if (settings.night === 'day') return false;
  if (settings.night === 'night') return true;
  const c = state.fix || (map ? { lat: map.getCenter().lat, lon: map.getCenter().lng } : TERRACE);
  return sunAltitude(Date.now(), c.lat, c.lon) < -4;
}

// ------------------------------------------------------------------ map

function routeLayers(night) {
  return [
    { id: 'orbit-route-casing', type: 'line', source: 'orbit-route', layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': '#0D47A1', 'line-width': ['interpolate', ['linear'], ['zoom'], 6, 5, 12, 9, 16, 15] } },
    { id: 'orbit-route', type: 'line', source: 'orbit-route', layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': night ? '#3D96FF' : '#2F8CFF', 'line-width': ['interpolate', ['linear'], ['zoom'], 6, 3, 12, 6, 16, 10] } },
  ];
}
const emptyLine = { type: 'FeatureCollection', features: [] };
function routeGeoJSON() {
  if (!state.route) return emptyLine;
  return { type: 'Feature', geometry: { type: 'LineString', coordinates: state.route.points.map((p) => [p.lon, p.lat]) }, properties: {} };
}

async function refreshStyle(force = false) {
  if (!map || !regions || !regions.list.length) return;
  const b = map.getBounds();
  const c = map.getCenter();
  const pick = regions.forView([b.getWest(), b.getSouth(), b.getEast(), b.getNorth()], [c.lng, c.lat]);
  const night = wantNight();
  const key = pick.map((r) => r.id).join(',') + (night ? ':n' : ':d');
  if (!force && key === state.regionsKey) return;
  state.regionsKey = key;
  state.night = night;
  const style = await buildStyle(pick.map((r) => regions.urlFor(r)), night);
  style.sources['orbit-route'] = { type: 'geojson', data: routeGeoJSON() };
  style.layers.push(...routeLayers(night));
  if (!pick.length) style.layers.unshift({ id: 'nomap', type: 'background', paint: { 'background-color': night ? '#1a2333' : '#e9eef2' } });
  map.setStyle(style, { diff: true });
  document.querySelector('meta[name=theme-color]').content = night ? '#0C1430' : '#0C1430';
}

function setRouteLine() {
  const src = map && map.getSource('orbit-route');
  if (src) src.setData(routeGeoJSON());
}

function initMap() {
  maplibregl.addProtocol('pmtiles', regions.protocol.tile);
  const last = store.get('view', null);
  map = new maplibregl.Map({
    container: 'map',
    style: { version: 8, sources: {}, layers: [{ id: 'bg', type: 'background', paint: { 'background-color': '#e9eef2' } }] },
    center: last ? [last.lon, last.lat] : [TERRACE.lon, TERRACE.lat],
    zoom: last ? last.zoom : 12,
    attributionControl: false,
    pitchWithRotate: true,
    maxPitch: 70,
    fadeDuration: 0,
  });
  map.addControl(new maplibregl.AttributionControl({
    compact: true,
    customAttribution: '<a href="https://www.openstreetmap.org/copyright" target="_blank">© OpenStreetMap contributors</a> · <a href="https://protomaps.com" target="_blank">Protomaps</a> · Routing: <a href="https://valhalla1.openstreetmap.de" target="_blank">FOSSGIS Valhalla</a> · <a href="https://www.openstreetmap.org/fixthemap" target="_blank">Report a map error</a>',
  }), 'bottom-left');
  map.on('load', () => {
    refreshStyle(true);
    // Credits start folded into the (i) button so they don't cover the map.
    document.querySelectorAll('.maplibregl-ctrl-attrib').forEach((el) => el.classList.remove('maplibregl-compact-show'));
  });
  let t = 0;
  map.on('moveend', () => {
    clearTimeout(t);
    t = setTimeout(() => {
      refreshStyle();
      const c = map.getCenter();
      store.set('view', { lon: c.lng, lat: c.lat, zoom: map.getZoom() });
    }, 300);
  });
  // The person moved the map: stop following until Re-centre.
  const stopFollow = (e) => { if (e.originalEvent && state.follow) { state.follow = false; updateRecenter(); } };
  map.on('dragstart', stopFollow);
  map.on('zoomstart', (e) => { if (e.originalEvent && state.mode !== 'drive') stopFollow(e); });
  map.on('rotatestart', stopFollow);
  map.on('pitchstart', stopFollow);
  // Long-press on the map: that spot as a destination.
  let pressTimer = 0, pressAt = null;
  const cancelPress = () => { clearTimeout(pressTimer); pressAt = null; };
  map.on('touchstart', (e) => {
    if (e.originalEvent.touches.length !== 1 || state.mode === 'drive') return;
    pressAt = e.point;
    const ll = e.lngLat;
    pressTimer = setTimeout(() => showPlace({ lat: ll.lat, lon: ll.lng, name: '' }), 600);
  });
  map.on('touchmove', (e) => { if (pressAt && Math.hypot(e.point.x - pressAt.x, e.point.y - pressAt.y) > 10) cancelPress(); });
  map.on('touchend', cancelPress);
  map.on('touchcancel', cancelPress);
  map.on('contextmenu', (e) => { if (state.mode !== 'drive') showPlace({ lat: e.lngLat.lat, lon: e.lngLat.lng, name: '' }); });

  const el = document.createElement('div');
  el.className = 'car';
  el.innerHTML = ICONS.ic_car_arrow;
  carMarker = new maplibregl.Marker({ element: el, rotationAlignment: 'map', pitchAlignment: 'map' });
  setInterval(() => { if (wantNight() !== state.night) refreshStyle(true); }, 120000);
}

let carMarker = null;
const stopMarkers = [];
function drawStops() {
  stopMarkers.splice(0).forEach((m) => m.remove());
  if (!map) return;
  const all = state.stops;
  all.forEach((s, i) => {
    const el = document.createElement('div');
    const last = i === all.length - 1;
    el.className = 'pin' + (last ? '' : ' via');
    el.innerHTML = last ? '' : `<span>${i + 1}</span>`;
    stopMarkers.push(new maplibregl.Marker({ element: el, anchor: 'bottom-left', offset: [-4, 4] }).setLngLat([s.lon, s.lat]).addTo(map));
  });
  if (state.start) {
    const el = document.createElement('div');
    el.className = 'pin start';
    stopMarkers.push(new maplibregl.Marker({ element: el, anchor: 'bottom-left', offset: [-4, 4] }).setLngLat([state.start.lon, state.start.lat]).addTo(map));
  }
}

// ------------------------------------------------------------------ camera

// The view button cycles 3D (tilted, heading up) → flat heading up → north up, like the app.
const VIEWS = ['3d', 'flat', 'north'];
function viewIcon() {
  icon($('viewBtn'), settings.view === 'north' ? 'ic_north_up' : 'ic_heading_up');
  const svg = $('viewBtn').querySelector('svg');
  if (svg && settings.view === '3d') svg.style.transform = 'perspective(40px) rotateX(30deg)';
}
viewIcon();
$('viewBtn').onclick = () => {
  settings.view = VIEWS[(VIEWS.indexOf(settings.view) + 1) % VIEWS.length];
  saveSettings();
  viewIcon();
  toast(settings.view === '3d' ? '3D view' : settings.view === 'flat' ? 'Heading up, flat' : 'North up', 1500);
  state.follow = true;
  updateRecenter();
  if (!state.fix) { map.easeTo({ pitch: settings.view === '3d' ? 55 : 0, bearing: settings.view === 'north' ? 0 : map.getBearing(), duration: 500 }); }
  applyPadding();
};

function applyPadding() {
  const h = window.innerHeight;
  const drive = state.mode === 'drive';
  const headingUp = settings.view !== 'north';
  let top = 0;
  if (drive && headingUp) top = h * (settings.view === '3d' ? 0.5 : 0.36);
  else if (drive) top = h * 0.12;
  const bottom = drive ? 90 : 0;
  pose.padding = { top, bottom, left: 0, right: 0 };
}

function zoomFor(kmh) {
  if (state.mode !== 'drive') return null;
  // Same as the app (MainActivity.zoomFor).
  return kmh < 30 ? 17.0 : kmh < 60 ? 16.3 : kmh < 90 ? 15.5 : 14.8;
}

// Smoothly glide the car and camera from one GPS fix to the next.
const pose = { from: null, to: null, t0: 0, dur: 1000, shown: null, padding: { top: 0, bottom: 0, left: 0, right: 0 }, zoom: null };
function setPose(p) {
  const now = performance.now();
  pose.from = pose.shown || p;
  pose.to = p;
  pose.t0 = now;
  pose.dur = pose.shown ? Math.min(Math.max(p.dt || 1000, 300), 1500) : 0;
}
function frame() {
  requestAnimationFrame(frame);
  if (!map || !pose.to) return;
  const f = pose.dur ? Math.min(1, (performance.now() - pose.t0) / pose.dur) : 1;
  // Nothing moving (parked, or between fixes after the glide): don't redraw — saves battery.
  const sig = `${f}|${pose.to.lon}|${pose.to.lat}|${pose.to.heading}|${state.follow}|${state.mode}|${settings.view}|${pose.padding.top}|${pose.zoom && pose.zoom.toFixed(3)}`;
  if (f === 1 && sig === pose.lastSig && !(state.mode === 'drive' && pose.zoomMoving)) return;
  pose.lastSig = sig;
  const a = pose.from, b = pose.to;
  const shown = {
    lon: a.lon + (b.lon - a.lon) * f,
    lat: a.lat + (b.lat - a.lat) * f,
    heading: a.heading + Geo.normalize(b.heading - a.heading) * f,
  };
  pose.shown = shown;
  carMarker.setLngLat([shown.lon, shown.lat]).setRotation(shown.heading);
  if (!carMarker._map) carMarker.addTo(map);
  if (!state.follow || state.mode === 'preview' || state.mode === 'place' || state.mode === 'search') return;
  const drive = state.mode === 'drive';
  const headingUp = settings.view !== 'north' && drive;
  const opts = { center: [shown.lon, shown.lat], padding: pose.padding };
  if (drive) {
    opts.bearing = headingUp ? shown.heading : 0;
    opts.pitch = settings.view === '3d' ? 60 : 0;
    const z = zoomFor((b.speed || 0) * 3.6);
    pose.zoom = pose.zoom == null ? z : pose.zoom + (z - pose.zoom) * 0.02;
    pose.zoomMoving = Math.abs(z - pose.zoom) > 0.005;
    opts.zoom = pose.zoom;
  }
  map.jumpTo(opts);
}
requestAnimationFrame(frame);

function updateRecenter() {
  const show = !state.follow && (state.mode === 'drive' || state.mode === 'browse') && !!state.fix;
  $('recenter').classList.toggle('hidden', !show);
}
$('recenterBtn').onclick = () => {
  state.follow = true;
  pose.zoom = null;
  updateRecenter();
  if (state.mode !== 'drive' && state.fix) map.easeTo({ center: [state.fix.lon, state.fix.lat], zoom: Math.max(map.getZoom(), 15), duration: 600 });
};
$('locBtn').onclick = () => {
  startGps();
  if (state.fix) {
    state.follow = true;
    updateRecenter();
    map.easeTo({ center: [state.fix.lon, state.fix.lat], zoom: Math.max(map.getZoom(), 15), padding: pose.padding, duration: 600 });
  } else toast('Finding your location…');
};

// ------------------------------------------------------------------ GPS

let watchId = null;
let lastPos = null;
function startGps() {
  if (SIM || watchId != null) return;
  if (!navigator.geolocation) { toast('This browser has no location access.'); return; }
  watchId = navigator.geolocation.watchPosition(onPosition, (err) => {
    if (err.code === 1) {
      dialog(`<h2>Location is off for Orbit</h2><p>To guide you, Orbit needs your location.</p>
        <p>On an iPhone: <b>Settings → Privacy &amp; Security → Location Services → Safari Websites</b>
        (or <b>Orbit GPS</b> once installed) → <b>While Using the App</b>, then reopen Orbit.</p>`);
      watchId = null;
    }
  }, { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 });
}

function onPosition(pos) {
  const c = pos.coords;
  feedFix({ lon: c.longitude, lat: c.latitude, heading: c.heading, speed: c.speed, accuracy: c.accuracy, t: pos.timestamp || Date.now() });
}

function feedFix(raw) {
  const p = { lon: raw.lon, lat: raw.lat };
  const prev = lastPos;
  let speed = raw.speed != null && !isNaN(raw.speed) && raw.speed >= 0 ? raw.speed : null;
  let heading = raw.heading != null && !isNaN(raw.heading) ? raw.heading : null;
  if (prev) {
    const d = Geo.distance(prev, p), dt = Math.max((raw.t - prev.t) / 1000, 0.2);
    const moved = d / dt;
    // Some phones report 0 (or nothing) while moving: trust the movement when it's clear.
    if (speed == null || (speed < 1 && moved > 4 && d > 2 * (raw.accuracy || 10))) {
      speed = moved;
      if (d > 3) heading = Geo.bearing(prev, p);
    }
    if (heading == null && d > 3) heading = Geo.bearing(prev, p);
  }
  if (heading == null || (speed != null && speed < 1.2)) heading = state.fix ? state.fix.heading : 0;  // hold still when parked
  const fix = { ...p, heading, speed: speed || 0, accuracy: raw.accuracy || 10, t: raw.t, dt: prev ? raw.t - prev.t : 1000 };
  lastPos = { ...p, t: raw.t };
  const first = !state.fix;
  state.fix = fix;
  state.lastFixAt = Date.now();
  if (state.gpsLostSaid) { state.gpsLostSaid = false; if (state.mode === 'drive') voice.speak(['gps_restored']); }

  // Snap the shown car onto the route while on it.
  let shown = { ...fix };
  if (state.nav && state.mode === 'drive') {
    const prog = state.nav.update(p, fix.speed > 1.2 ? fix.heading : null, fix.speed, fix.accuracy);
    state.progress = prog;
    if (prog && !prog.offRoute && prog.offset < 30) {
      shown = { ...shown, lon: prog.snapped.lon, lat: prog.snapped.lat };
      if (fix.speed > 2) shown.heading = prog.routeBearing;
    }
    updateDriveUI();
  }
  setPose(shown);
  if (first && state.mode === 'browse') { state.follow = true; map.jumpTo({ center: [fix.lon, fix.lat], zoom: Math.max(map.getZoom(), 14) }); updateRecenter(); }
  $('speed').innerHTML = `${Math.round(fix.speed * 3.6)}<small> km/h</small>`;
}

// GPS gone quiet while driving (tunnel, or the iPhone paused it).
setInterval(() => {
  if (state.mode !== 'drive' || SIM) return;
  if (Date.now() - state.lastFixAt > 15000 && !state.gpsLostSaid && document.visibilityState === 'visible') {
    state.gpsLostSaid = true;
    voice.speak(['gps_lost']);
    toast('Waiting for GPS…', 0);
  }
  if (Date.now() - state.lastFixAt < 3000) { if ($('toast').textContent === 'Waiting for GPS…') $('toast').classList.add('hidden'); }
}, 3000);

// ------------------------------------------------------------------ keep the screen on while driving

let wakeLock = null;
async function keepAwake(on) {
  try {
    if (on && 'wakeLock' in navigator && document.visibilityState === 'visible') {
      if (!wakeLock || wakeLock.released) wakeLock = await navigator.wakeLock.request('screen');
    } else if (!on && wakeLock) { await wakeLock.release(); wakeLock = null; }
  } catch {}
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') {
    if (state.mode === 'drive') { keepAwake(true); voice.ctx && voice.ctx.resume(); }
  }
});

// ------------------------------------------------------------------ search screen

let searchAbort = null;
let activeCat = null;
let searchFor = 'dest';      // dest | stop
function openSearch(forWhat = 'dest') {
  searchFor = forWhat;
  setMode('search');
  activeCat = null;
  renderChips();
  $('q').value = '';
  showRecent();
  setTimeout(() => $('q').focus(), 50);
  startGps();
}
$('whereBtn').onclick = () => openSearch('dest');
$('searchBack').onclick = () => { $('q').blur(); setMode(state.route ? 'preview' : 'browse'); };

function renderChips() {
  $('chips').innerHTML = '';
  for (const c of CATEGORIES) {
    const b = document.createElement('button');
    b.className = 'chip' + (activeCat === c ? ' on' : '');
    b.textContent = c.label;
    b.onclick = () => runCategory(c);
    $('chips').appendChild(b);
  }
}

function near() {
  if (state.fix) return { lat: state.fix.lat, lon: state.fix.lon };
  const c = map.getCenter();
  return { lat: c.lat, lon: c.lng };
}

function renderResults(list, emptyText) {
  const box = $('results');
  box.innerHTML = '';
  if (!list.length) { box.innerHTML = `<div class="note">${esc(emptyText || 'Nothing found.')}</div>`; return; }
  for (const r of list) {
    const b = document.createElement('button');
    b.className = 'res';
    const km = r.km != null ? (r.km < 1 ? `${Math.round(r.km * 1000)} m` : r.km < 10 ? `${r.km.toFixed(1)} km` : `${Math.round(r.km)} km`) : '';
    b.innerHTML = `<div class="t"><div class="n">${esc(r.name)}</div><div class="d">${esc(r.detail || '')}</div></div>
      <div class="k">${km ? `<b>${km}</b>` : ''}${esc(r.kind || '')}</div>`;
    b.onclick = () => chooseResult(r);
    box.appendChild(b);
  }
}

function showRecent() {
  const recent = store.get('recent', []);
  if (!recent.length) { $('results').innerHTML = '<div class="note">Type a place, business or street address.</div>'; return; }
  const n = near();
  recent.forEach((r) => (r.km = Geo.distance(n, r) / 1000));
  renderResults(recent);
  $('results').insertAdjacentHTML('afterbegin', '<div class="sectionHead">Recent</div>');
}

let typing = 0;
$('q').addEventListener('input', () => {
  clearTimeout(typing);
  activeCat = null; renderChips();
  const q = $('q').value;
  if (!q.trim()) { showRecent(); return; }
  typing = setTimeout(() => runSearch(q), 350);
});
$('q').addEventListener('keydown', (e) => { if (e.key === 'Enter') { clearTimeout(typing); runSearch($('q').value); $('q').blur(); } });

async function runSearch(q) {
  if (searchAbort) searchAbort.abort();
  searchAbort = new AbortController();
  $('results').innerHTML = '<div class="note">Searching…</div>';
  try {
    const list = await search(q, near(), searchAbort.signal);
    renderResults(list, navigator.onLine === false ? 'Search needs a data connection.' : 'Nothing found. Try adding the town name.');
  } catch (e) { if (e.name !== 'AbortError') renderResults([], 'Search isn’t working right now. Check your connection.'); }
}

async function runCategory(c) {
  if (searchAbort) searchAbort.abort();
  searchAbort = new AbortController();
  activeCat = c; renderChips();
  $('q').blur();
  $('results').innerHTML = `<div class="note">Looking for ${esc(c.label.toLowerCase())} nearby…</div>`;
  try {
    const list = await nearby(c, near(), searchAbort.signal);
    renderResults(list, `No ${c.label.toLowerCase()} found within 80 km.`);
  } catch (e) { if (e.name !== 'AbortError') renderResults([], e.message || 'Nearby search isn’t working right now.'); }
}

function remember(r) {
  const recent = store.get('recent', []).filter((x) => Geo.distance(x, r) > 30);
  recent.unshift({ name: r.name, detail: r.detail || '', kind: r.kind || '', lat: r.lat, lon: r.lon });
  store.set('recent', recent.slice(0, 15));
}

function chooseResult(r) {
  $('q').blur();
  remember(r);
  if (searchFor === 'stop' && state.stops.length) {
    insertStop({ lat: r.lat, lon: r.lon, name: r.name });
    makeRoute();
  } else {
    state.stops = [{ lat: r.lat, lon: r.lon, name: r.name, detail: r.detail }];
    state.start = null;
    state.routeName = r.name;
    makeRoute();
  }
}

/** Put a stop where it adds the least extra distance (never after the destination). */
function insertStop(s) {
  const from = state.start || state.fix || state.stops[0];
  const pts = [from, ...state.stops];
  let best = 0, bestExtra = Infinity;
  for (let i = 0; i < pts.length - 1; i++) {
    const extra = Geo.distance(pts[i], s) + Geo.distance(s, pts[i + 1]) - Geo.distance(pts[i], pts[i + 1]);
    if (extra < bestExtra) { bestExtra = extra; best = i; }
  }
  state.stops.splice(best, 0, s);
}

// ------------------------------------------------------------------ place card (long-press)

let placeAbort = null;
let placeSpot = null;
async function showPlace(p) {
  placeSpot = p;
  setMode('place');
  $('plName').textContent = p.name || 'Dropped pin';
  $('plInfo').textContent = `${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}`;
  $('plStop').classList.toggle('hidden', !state.stops.length);
  if (placeAbort) placeAbort.abort();
  placeAbort = new AbortController();
  if (!p.name) {
    try { const name = await reverse(p, placeAbort.signal); if (placeSpot === p) { p.name = name; $('plName').textContent = name; } } catch {}
  }
  startGps();
}
$('plClose').onclick = () => setMode(state.route ? 'preview' : 'browse');
$('plGo').onclick = () => {
  const p = placeSpot;
  state.stops = [{ lat: p.lat, lon: p.lon, name: p.name || 'Dropped pin' }];
  state.start = null;
  state.routeName = p.name || 'Dropped pin';
  remember(state.stops[0]);
  makeRoute();
};
$('plStop').onclick = () => { insertStop({ lat: placeSpot.lat, lon: placeSpot.lon, name: placeSpot.name || 'Stop' }); makeRoute(); };

// ------------------------------------------------------------------ route preview

function routeOptions() {
  return { avoidHighways: settings.avoidHighways, avoidFerries: settings.avoidFerries, avoidUnpaved: settings.avoidUnpaved, avoidTolls: settings.avoidTolls };
}

async function waitForFix(ms) {
  const t0 = Date.now();
  while (!state.fix && Date.now() - t0 < ms) await new Promise((r) => setTimeout(r, 250));
  return state.fix;
}

async function makeRoute() {
  setMode('preview');
  $('goBtn').disabled = true;
  $('pvMsg').classList.add('hidden');
  renderStops();
  $('pvName').textContent = state.routeName || state.stops[state.stops.length - 1].name;
  $('pvInfo').textContent = 'Planning the route…';
  drawStops();
  startGps();
  const from = state.start || (await waitForFix(SIM ? 0 : 12000)) || (SIM ? simStart() : null);
  if (!from) {
    $('pvInfo').textContent = '';
    showMsg('Orbit doesn’t know where you are yet. Allow location for Orbit, or wait a moment outdoors, then try again.');
    return;
  }
  if (state.routeAbort) state.routeAbort.abort();
  state.routeAbort = new AbortController();
  try {
    const heading = !state.start && state.fix && state.fix.speed > 2 ? state.fix.heading : null;
    const r = await planRoute([from, ...state.stops], heading, routeOptions(), state.routeAbort.signal);
    state.route = r;
    setRouteLine();
    $('pvInfo').textContent = `${fmtDistance(r.length)} · ${fmtDuration(r.duration)} · arrive ${clockTime(new Date(Date.now() + r.duration * 1000))}`;
    $('goBtn').disabled = false;
    fitRoute();
  } catch (e) {
    if (e.name === 'AbortError') return;
    $('pvInfo').textContent = '';
    showMsg(e instanceof RouteError ? e.message : 'Couldn’t make a route. Check your connection and try again.');
  }
}
function simStart() { const c = map.getCenter(); return { lat: c.lat, lon: c.lng }; }
function showMsg(t) { $('pvMsg').textContent = t; $('pvMsg').classList.remove('hidden'); }

function fitRoute() {
  if (!state.route) return;
  const b = new maplibregl.LngLatBounds();
  state.route.points.forEach((p) => b.extend([p.lon, p.lat]));
  const sheet = $('preview').getBoundingClientRect().height || 220;
  state.follow = false;
  map.fitBounds(b, { padding: { top: 90, bottom: sheet + 30, left: 40, right: 40 }, bearing: 0, pitch: 0, duration: 700, maxZoom: 16 });
}

function renderStops() {
  const ol = $('pvStops');
  ol.innerHTML = '';
  const rows = [];
  rows.push({ label: state.start ? state.start.name || 'Start' : 'Your location', cls: 'start', idx: -1 });
  state.stops.forEach((s, i) => rows.push({ label: s.name || 'Stop', cls: i === state.stops.length - 1 ? 'end' : '', idx: i }));
  if (rows.length <= 2 && !state.start) { ol.classList.add('hidden'); return; }
  ol.classList.remove('hidden');
  rows.forEach((r, k) => {
    const li = document.createElement('li');
    li.innerHTML = `<span class="dot ${r.cls}">${r.cls === 'start' ? '' : r.cls === 'end' ? '' : k}</span><span class="nm">${esc(r.label)}</span>`;
    if (r.idx >= 0 && state.stops.length > 1) {
      const b = document.createElement('button');
      b.textContent = 'Remove';
      b.onclick = () => { state.stops.splice(r.idx, 1); makeRoute(); };
      li.appendChild(b);
    }
    if (r.idx === -1 && state.start) {
      const b = document.createElement('button');
      b.textContent = 'From here instead';
      b.onclick = () => { state.start = null; makeRoute(); };
      li.appendChild(b);
    }
    ol.appendChild(li);
  });
}

$('pvClose').onclick = cancelRoute;
$('pvAdd').onclick = () => openSearch('stop');
function cancelRoute() {
  if (state.routeAbort) state.routeAbort.abort();
  state.route = null; state.stops = []; state.start = null; state.nav = null;
  setRouteLine();
  drawStops();
  state.follow = true;
  setMode('browse');
  if (state.fix) map.easeTo({ center: [state.fix.lon, state.fix.lat], pitch: 0, bearing: 0, duration: 500 });
}

// ------------------------------------------------------------------ driving

$('goBtn').onclick = startDrive;
async function startDrive() {
  if (!state.route) return;
  voice.unlock();
  keepAwake(true);
  state.reached = 0;
  state.nav = new Navigator(state.route, (clips) => voice.speak(clips), onOffRoute);
  state.follow = true;
  pose.zoom = null;
  setMode('drive');
  applyPadding();
  icon($('voiceBtn'), voice.muted ? 'ic_voice_off' : 'ic_voice');
  updateDriveUI();
  if (!store.get('driveTipShown', false) && isIOS) {
    store.set('driveTipShown', true);
    dialog(`<h2>Keep Orbit on the screen</h2><p>iPhones pause GPS for web apps when the screen locks or another app is open.
      Mount the phone, keep it plugged in, and leave Orbit open while you drive.</p>
      <p>The full Orbit GPS app for iPhone (with CarPlay) is on the way.</p>`);
  }
  if (SIM) startSim();
  // Fetch the map along the route now, while there's signal.
  regions.prefetchCorridor(state.route.points, (done, total) => {
    if (state.mode !== 'drive') return;
    if (done < total) toast(`Saving the map along your route… ${Math.round((done / total) * 100)}%`, 0);
    else toast('Map saved along your route', 2500);
  }).catch(() => {});
}

let rerouting = false;
async function onOffRoute(p, heading) {
  if (rerouting) return;
  rerouting = true;
  updateDriveUI();
  try {
    const left = state.stops.slice(state.reached);
    const r = await planRoute([p, ...left], heading, routeOptions());
    if (state.mode !== 'drive') return;
    state.route = r;
    state.stops = left;
    state.reached = 0;
    state.nav.replaceRoute(r);
    setRouteLine();
  } catch (e) {
    if (state.mode === 'drive') toast(e.message || 'Couldn’t recalculate.', 4000);
  } finally { rerouting = false; updateDriveUI(); }
}

function updateDriveUI() {
  const nav = state.nav, prog = state.progress, route = nav ? nav.route : null;
  if (!route) return;
  // Count stops along the way that are behind us (for rerouting with the rest).
  if (prog) state.reached = route.announced.filter((s) => s.maneuver.waypoint && s.distanceFromStart < prog.along).length;
  const ann = route.announced;
  const step = prog && prog.nextIndex >= 0 ? ann[prog.nextIndex] : null;
  $('banner').classList.toggle('off', !!(prog && prog.offRoute));
  if (nav.arrived) {
    icon($('turnIcon'), 'turn_arrive');
    $('turnDist').textContent = 'Arrived';
    $('turnRoad').textContent = state.stops[state.stops.length - 1] ? state.stops[state.stops.length - 1].name : '';
    $('lanes').classList.add('hidden');
    $('thenBox').classList.add('hidden');
    if (!state.arrivedTimer) state.arrivedTimer = setTimeout(() => { state.arrivedTimer = 0; if (state.mode === 'drive') stopDrive(); }, 12000);
  } else if (prog && prog.offRoute) {
    icon($('turnIcon'), 'turn_uturn');
    $('turnDist').textContent = 'Recalculating…';
    $('turnRoad').textContent = navigator.onLine === false ? 'No data connection: drive back to the blue line' : 'Finding a new route';
    $('lanes').classList.add('hidden');
  } else if (step) {
    icon($('turnIcon'), iconFor(step));
    $('turnDist').textContent = fmtDistance(prog.distanceToNext);
    $('turnRoad').textContent = bannerText(step);
    showLanes(step, prog);
    const next = ann[prog.nextIndex + 1];
    const gap = next ? next.distanceFromStart - step.distanceFromStart : null;
    const tb = $('thenBox');
    if (next && gap < 400 && prog.distanceToNext < 1500) {
      icon($('thenIcon'), iconFor(next));
      tb.style.top = $('banner').getBoundingClientRect().bottom + 'px';
      tb.classList.remove('hidden');
    } else tb.classList.add('hidden');
  } else {
    icon($('turnIcon'), 'turn_straight');
    $('turnDist').textContent = '';
    $('turnRoad').textContent = 'Starting…';
  }
  if (prog) {
    const frac = route.length ? prog.remaining / route.length : 0;
    const secs = route.duration * frac;
    const t = clockTime(new Date(Date.now() + secs * 1000));
    const ampm = /\s?([AaPp])\.?\s?[Mm]\.?$/.exec(t);
    $('eta').innerHTML = '<small>Arrive</small>' + esc(ampm ? t.slice(0, ampm.index) : t) + (ampm ? `<i>${ampm[1].toLowerCase()}m</i>` : '');
    $('remain').textContent = `${fmtDistance(prog.remaining)} · ${fmtDuration(secs)}`;
  }
}

function bannerText(step) {
  const m = step.maneuver;
  if (m.type === 'ARRIVE') return m.waypoint ? 'Your stop' : (state.stops[state.stops.length - 1] || {}).name || 'Destination';
  // First sentence only ("Turn left onto YH 16/Keith Avenue. Continue on YH 16." → the turn).
  let t = (step.instruction || '').split(/\.\s/)[0].replace(/\.$/, '');
  if (!t) t = step.streetName || step.ref || '';
  return t;
}

const LANE_ICON = { left: 'turn_right_left', 'slight left': 'turn_slight_left', 'sharp left': 'turn_sharp_left', right: 'turn_right',
  'slight right': 'turn_slight', 'sharp right': 'turn_sharp', straight: 'turn_straight', uturn: 'turn_uturn', none: 'turn_straight' };
function showLanes(step, prog) {
  const box = $('lanes');
  const lanes = step.lanes;
  const d = Prompts.stageDistances((state.fix ? state.fix.speed : 0) * 3.6);
  if (!lanes || prog.distanceToNext > d.prepare * 1.2 || lanes.valid.every((v) => v)) { box.classList.add('hidden'); return; }
  box.innerHTML = '';
  lanes.valid.forEach((v, i) => {
    const ind = (lanes.indications[i] || [])[0] || 'straight';
    const el = document.createElement('div');
    el.className = 'lane' + (v ? ' on' : '');
    el.innerHTML = ICONS[LANE_ICON[ind] || 'turn_straight'];
    box.appendChild(el);
  });
  box.classList.remove('hidden');
}

$('stopBtn').onclick = stopDrive;
function stopDrive() {
  voice.stop();
  keepAwake(false);
  stopSim();
  state.nav = null;
  state.progress = null;
  clearTimeout(state.arrivedTimer); state.arrivedTimer = 0;
  $('toast').classList.add('hidden');
  cancelRoute();
}

$('voiceBtn').onclick = () => {
  voice.muted = !voice.muted;
  if (voice.muted) voice.stop();
  settings.voice = !voice.muted; saveSettings();
  icon($('voiceBtn'), voice.muted ? 'ic_voice_off' : 'ic_voice');
  toast(voice.muted ? 'Voice off' : 'Voice on', 1200);
  $('optVoice').checked = settings.voice;
};

// ------------------------------------------------------------------ demo drive (#sim): follows the route by itself

let simTimer = 0;
function startSim() {
  stopSim();
  const r = state.route;
  let along = 0, t = Date.now();
  const cum = r.cumulative, pts = r.points;
  let k = 0;
  const speedUp = /[#&?]sim=(\d+)/.exec(location.href);
  const factor = speedUp ? +speedUp[1] : 1;
  simTimer = setInterval(() => {
    if (!state.nav || state.nav.route !== r && state.nav.route) { /* rerouted */ }
    const route = state.nav ? state.nav.route : r;
    const c = route.cumulative, p = route.points;
    const prog = state.progress;
    const kmh = prog && prog.distanceToNext < 250 ? 35 : 80;
    along = Math.min(along + (kmh / 3.6) * factor, route.length);
    if (route !== r) { k = 0; }
    while (k < c.length - 2 && c[k + 1] < along) k++;
    const f = Math.min(1, (along - c[k]) / Math.max(c[k + 1] - c[k], 1e-9));
    const pos = Geo.lerp(p[k], p[k + 1], f);
    t += 1000;
    feedFix({ lon: pos.lon, lat: pos.lat, heading: Geo.bearing(p[k], p[k + 1]), speed: kmh / 3.6, accuracy: 5, t });
    if (along >= route.length) stopSim();
  }, 1000);
}
function stopSim() { clearInterval(simTimer); simTimer = 0; }

// ------------------------------------------------------------------ menu

function openMenu() {
  $('optVoice').checked = settings.voice;
  $('optEarly').checked = settings.early;
  $('optHwy').checked = settings.avoidHighways;
  $('optFerry').checked = settings.avoidFerries;
  $('optUnpaved').checked = settings.avoidUnpaved;
  $('optTolls').checked = settings.avoidTolls;
  $('optNight').value = settings.night;
  $('menu').classList.remove('hidden');
}
$('menuBtn').onclick = openMenu;
$('menu').onclick = (e) => { if (e.target === $('menu')) $('menu').classList.add('hidden'); };
const bind = (id, key, after) => { $(id).onchange = (e) => { settings[key] = e.target.type === 'checkbox' ? e.target.checked : e.target.value; saveSettings(); after && after(); }; };
bind('optVoice', 'voice', () => { voice.muted = !settings.voice; });
bind('optEarly', 'early', () => { Prompts.warningScale = settings.early ? 1.3 : 1.0; });
bind('optHwy', 'avoidHighways');
bind('optFerry', 'avoidFerries');
bind('optUnpaved', 'avoidUnpaved');
bind('optTolls', 'avoidTolls');
bind('optNight', 'night', () => refreshStyle(true));

$('mInstall').onclick = () => { $('menu').classList.add('hidden'); installHelp(); };
$('mHelp').onclick = () => {
  $('menu').classList.add('hidden');
  dialog(`<h2>Tips for driving</h2><ul>
    <li><b>Keep Orbit on the screen.</b> iPhones pause GPS for web apps when the screen locks or you switch apps. Mount the phone and keep it plugged in.</li>
    <li><b>Sound:</b> Orbit lowers your music while she speaks. Turn the volume up before you leave.</li>
    <li><b>No signal?</b> The map along your route is saved when you press Go, and a planned route keeps guiding you. New routes and search need a connection.</li>
    <li><b>The view button</b> switches between 3D, flat heading-up and north-up.</li>
    <li><b>Hold your finger on the map</b> to go to that spot, or to add it as a stop.</li></ul>`);
};
$('mClear').onclick = async () => {
  $('menu').classList.add('hidden');
  try { await caches.delete('orbit-map-ranges-v1'); } catch {}
  toast('Saved map cleared');
};
$('mAbout').onclick = () => {
  $('menu').classList.add('hidden');
  const contact = config && config.contact ? `<p>Questions or problems: <a href="mailto:${esc(config.contact)}">${esc(config.contact)}</a></p>` : '';
  dialog(`<h2>Orbit GPS</h2><p>Free GPS from robotdash studios, Terrace BC. No ads, no account, nothing tracked.
    This is the web version for iPhone; there's also an <a href="https://orbitgps.ca" target="_blank">Android app</a> with offline routing and Android Auto.</p>
    ${contact}
    <p><b>Credits</b><br>Map data © <a href="https://www.openstreetmap.org/copyright" target="_blank">OpenStreetMap contributors</a> (ODbL) ·
    map tiles <a href="https://protomaps.com" target="_blank">Protomaps</a> · addresses and places include <a href="https://overturemaps.org" target="_blank">Overture Maps</a> and the
    <a href="https://www2.gov.bc.ca/gov/content/data/geographic-data-services/location-services/geocoder" target="_blank">BC Address Geocoder</a> ·
    search <a href="https://photon.komoot.io" target="_blank">Photon</a> · routing <a href="https://valhalla1.openstreetmap.de" target="_blank">FOSSGIS Valhalla</a> ·
    map display <a href="https://maplibre.org" target="_blank">MapLibre</a>.</p>
    <p>See a mistake on the map? <a href="https://www.openstreetmap.org/fixthemap" target="_blank">Report or fix it</a>.</p>`);
};

// ------------------------------------------------------------------ install help (iPhone)

function installHelp() {
  dialog(`<h2>Install Orbit GPS on your iPhone</h2><ol>
    <li>Open <b>orbitgps.ca/go</b> in <b>Safari</b>.</li>
    <li>Tap the <b>Share</b> button <span class="share" style="display:inline-block;width:16px;height:20px;vertical-align:-3px;background:no-repeat center/contain url(&quot;data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 18 24' fill='none' stroke='%232FD0FF' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M9 15V2M4.5 6.5 9 2l4.5 4.5'/%3E%3Cpath d='M6 10H3v12h12V10h-3'/%3E%3C/svg%3E&quot;)"></span> (bottom of the screen, or next to the address).</li>
    <li>Scroll down and tap <b>Add to Home Screen</b>, then <b>Add</b>.</li></ol>
    <p>Orbit then opens full-screen from its own icon, like any app.</p>`);
}
function installTipWanted() { return isIOS && !standalone && !store.get('installTipClosed', false); }
$('installClose').onclick = () => { $('installTip').classList.add('hidden'); store.set('installTipClosed', true); };
$('installTip').onclick = (e) => { if (e.target !== $('installClose') && !$('installClose').contains(e.target)) installHelp(); };

// ------------------------------------------------------------------ start

async function boot() {
  config = await getConfig();
  regions = new Regions(config.maps);
  initMap();
  try { await regions.load(); refreshStyle(true); }
  catch { toast('The map list couldn’t load. Check your connection.', 6000); }
  setMode('browse');
  // Start GPS straight away if location was allowed before (no prompt).
  try {
    const p = navigator.permissions && (await navigator.permissions.query({ name: 'geolocation' }));
    if (p && p.state === 'granted') startGps();
  } catch {}
  // A trip from the planner's QR code: orbitgps.ca/go/#d=<payload>
  const m = /[#&]d=([A-Za-z0-9_-]+)/.exec(location.hash);
  if (m) {
    const trip = decodeTrip(m[1]);
    history.replaceState(null, '', location.pathname + location.search + (SIM ? '#sim' : ''));
    if (trip) {
      startGps();
      const fix = await waitForFix(SIM ? 0 : 8000);
      let stops = trip.stops;
      // Already at the trip's start (or no GPS): route from here; otherwise drive to the start first.
      if (fix && Geo.distance(fix, stops[0]) < 1500) stops = stops.slice(1);
      else if (!fix && !SIM) { state.start = stops[0]; stops = stops.slice(1); }
      else if (SIM) { state.start = stops[0]; stops = stops.slice(1); }
      state.stops = stops;
      state.routeName = trip.name || stops[stops.length - 1].name;
      makeRoute();
    }
  }
  if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === '127.0.0.1')) navigator.serviceWorker.register('sw.js').catch(() => {});
  window.orbit = { state, voice, map, regions, feedFix, settings };   // for testing
}
boot();
