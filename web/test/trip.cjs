const { chromium, devices } = require('playwright');
const OUT = '/tmp/claude-0/shots';
const b64 = (s) => Buffer.from(s, 'utf8').toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const payload = '1' + b64(['Kitimat run', '54.51630,-128.59700,Home', '54.53870,-128.55100,Thornhill gas', '54.05230,-128.65340,Kitimat City Centre'].join('\n'));
(async () => {
  const browser = await chromium.launch({ args: ['--proxy-server=https=' + process.env.HTTPS_PROXY.replace('http://', ''), '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required'] });
  const ctx = await browser.newContext({ ...devices['iPhone 13'], ignoreHTTPSErrors: true, geolocation: { latitude: 54.5163, longitude: -128.597, accuracy: 8 }, permissions: ['geolocation'] });
  const page = await ctx.newPage();
  const spoken = [];
  const routeCalls = [];
  page.on('console', (m) => { const t = m.text(); if (t.startsWith('SAY ')) spoken.push(t.slice(4)); else if (m.type() === 'error') console.log('console', t.slice(0, 160)); });
  page.on('pageerror', (e) => console.log('PAGEERROR', e.message));
  page.on('request', (r) => { if (r.url().includes('valhalla')) routeCalls.push(r.postData()); });
  await page.route('https://maps.orbitgps.ca/**', async (route) => {
    const r = await route.fetch();
    await route.fulfill({ response: r, headers: { ...r.headers(), 'access-control-allow-origin': '*' } });
  });
  // Overpass can't be reached from here: answer with two gas stations.
  await page.route('https://overpass-api.de/**', (route) => route.fulfill({ contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ elements: [
    { type: 'node', lat: 54.5175, lon: -128.5905, tags: { amenity: 'fuel', name: 'Petro-Canada', 'addr:street': 'Lakelse Avenue', 'addr:housenumber': '4402' } },
    { type: 'node', lat: 54.5402, lon: -128.5480, tags: { amenity: 'fuel', brand: 'Shell' } },
    { type: 'way', center: { lat: 54.51, lon: -128.6 }, tags: { amenity: 'fuel', name: 'Husky' } }] }) }));
  await page.addInitScript(() => localStorage.setItem('orbit.settings', JSON.stringify({ night: 'day' })));
  await page.goto('http://127.0.0.1:8765/go/#d=' + payload);
  await page.waitForFunction(() => window.orbit && window.orbit.voice, null, { timeout: 60000 });
  await page.evaluate(() => { const v = window.orbit.voice; const s = v.speak.bind(v); v.speak = (c) => { console.log('SAY ' + v.text(c)); s(c); }; });
  await page.waitForFunction(() => !document.getElementById('goBtn').disabled, null, { timeout: 40000 });
  await page.waitForTimeout(3000);
  await page.screenshot({ path: OUT + '/t1-trip.png' });
  console.log('trip preview:', await page.textContent('#pvName'), '|', await page.textContent('#pvInfo'), '|', await page.$$eval('#pvStops li .nm', (e) => e.map((x) => x.textContent)));
  await page.click('#goBtn');
  await page.waitForTimeout(500);
  if (await page.isVisible('#dialog')) await page.click('#dialogOk');
  // Drive the first 2 km of the route with real (emulated) GPS updates, 25 m/s.
  const pts = await page.evaluate(() => window.orbit.state.route.points.map((p) => [p.lat, p.lon]));
  const cum = [0];
  const d = (a, b) => { const R = 6371000, la = (a[0] + b[0]) / 2 * Math.PI / 180; const x = (b[1] - a[1]) * Math.PI / 180 * Math.cos(la), y = (b[0] - a[0]) * Math.PI / 180; return Math.hypot(x, y) * R; };
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + d(pts[i - 1], pts[i]));
  const at = (s) => { let k = 0; while (k < cum.length - 2 && cum[k + 1] < s) k++; const f = Math.min(1, (s - cum[k]) / Math.max(cum[k + 1] - cum[k], 1e-9)); return [pts[k][0] + (pts[k + 1][0] - pts[k][0]) * f, pts[k][1] + (pts[k + 1][1] - pts[k][1]) * f]; };
  let s = 0;
  for (let i = 0; i < 70; i++) {
    s += 20;
    const [lat, lon] = at(s);
    await ctx.setGeolocation({ latitude: lat, longitude: lon, accuracy: 8 });
    await page.waitForTimeout(350);
    if (i % 10 === 0) { const t0 = Date.now(); const info = await page.evaluate(() => [window.orbit.state.progress && Math.round(window.orbit.state.progress.along), document.getElementById('toast').textContent]); console.log('tick', i, 'eval ms', Date.now() - t0, info); }
    if (i === 40) {
      console.log('fps', await page.evaluate(() => new Promise((ok) => { let n = 0; const t0 = performance.now(); const f = () => { n++; if (performance.now() - t0 < 2000) requestAnimationFrame(f); else ok(n / 2); }; requestAnimationFrame(f); })));
      console.log('renders/s', await page.evaluate(() => new Promise((ok) => { let n = 0; const m = window.orbit.map; const h = () => n++; m.on('render', h); setTimeout(() => { m.off('render', h); ok(n / 2); }, 2000); })));
      const fps = () => page.evaluate(() => new Promise((ok) => { let n = 0; const t0 = performance.now(); const f = () => { n++; if (performance.now() - t0 < 2000) requestAnimationFrame(f); else ok(n / 2); }; requestAnimationFrame(f); }));
      await page.waitForTimeout(4000); console.log('fps after 4 s no gps', await fps());
      console.log('layers', await page.evaluate(() => [window.orbit.map.getStyle().layers.length, Object.keys(window.orbit.map.getStyle().sources).join(','), window.orbit.map.getZoom(), window.orbit.map.getPitch(), window.orbit.map.transform.padding && JSON.stringify(window.orbit.map.transform.padding)]));
      await page.evaluate(() => { window.orbit.settings.view = 'flat'; }); await page.waitForTimeout(2000); console.log('fps flat', await fps());
      await page.evaluate(() => { document.querySelectorAll('.pin').forEach((e) => e.remove()); }); console.log('fps no pins', await fps());
      console.log('cache entries', await page.evaluate(async () => (await (await caches.open('orbit-map-ranges-v1')).keys()).length));
      await page.screenshot({ path: OUT + '/t2-drive.png', timeout: 60000 }).catch((e) => console.log('shot failed'));
    }
  }
  console.log('after 1.4 km:', await page.evaluate(() => [document.getElementById('turnDist').textContent, document.getElementById('turnRoad').textContent, document.getElementById('remain').textContent].join(' | ')));
  // Leave the route: head off 90° to the side.
  const [lat0, lon0] = at(s);
  const calls = routeCalls.length;
  for (let i = 1; i <= 8; i++) {
    await ctx.setGeolocation({ latitude: lat0 + i * 0.0006, longitude: lon0 - i * 0.0009, accuracy: 8 });
    await page.waitForTimeout(700);
  }
  await page.screenshot({ path: OUT + '/t3-offroute.png' });
  await page.waitForTimeout(5000);
  console.log('reroute requests:', routeCalls.length - calls, '| banner:', await page.evaluate(() => [document.getElementById('turnDist').textContent, document.getElementById('turnRoad').textContent].join(' | ')));
  console.log('stops left after reroute:', await page.evaluate(() => window.orbit.state.stops.map((x) => x.name)));
  const last = routeCalls[routeCalls.length - 1];
  console.log('last request locations:', last && JSON.stringify(JSON.parse(last).locations));
  console.log('SPOKEN:\n  ' + spoken.join('\n  '));
  // Stop; nearby gas
  await page.click('#stopBtn');
  await page.click('#whereBtn');
  await page.click('.chip >> nth=0');
  await page.waitForSelector('.res');
  console.log('gas:', await page.$$eval('.res', (e) => e.map((x) => x.innerText.replace(/\n/g, ' | '))));
  await page.screenshot({ path: OUT + '/t4-gas.png' });
  // Offline: the service worker should bring the app back up.
  await page.goto('http://127.0.0.1:8765/go/');
  await page.waitForTimeout(3000);
  const sw = await page.evaluate(() => navigator.serviceWorker.controller ? 'controlled' : 'no sw');
  await ctx.setOffline(true);
  await page.reload();
  await page.waitForFunction(() => window.orbit && window.orbit.map, null, { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(5000);
  await page.screenshot({ path: OUT + '/t5-offline.png' });
  console.log('offline:', sw, await page.evaluate(() => [!!window.orbit, window.orbit && window.orbit.regions.list.length, window.orbit && window.orbit.state.regionsKey].join(' ')));
  await browser.close();
})();
