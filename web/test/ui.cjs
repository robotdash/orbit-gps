const { chromium, devices } = require('playwright');
const OUT = process.env.OUT || '/tmp/claude-0/shots';
require('fs').mkdirSync(OUT, { recursive: true });
(async () => {
  const browser = await chromium.launch({ args: ['--proxy-server=https=' + (process.env.HTTPS_PROXY || '').replace('http://', ''), '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required'] });
  const ctx = await browser.newContext({ ...devices[process.env.LAND ? 'iPhone 13 landscape' : 'iPhone 13'], ignoreHTTPSErrors: true, geolocation: { latitude: 54.5163, longitude: -128.597, accuracy: 10 }, permissions: ['geolocation'] });
  const page = await ctx.newPage();
  const spoken = [];
  page.on('console', (m) => { const t = m.text(); if (t.startsWith('SAY ')) spoken.push(t.slice(4)); else if (m.type() === 'error' || m.type() === 'warning') console.log('console', m.type(), t.slice(0, 200)); });
  page.on('response', (r) => { if (r.status() >= 400) console.log('HTTP', r.status(), r.request().method(), r.url().slice(0, 120)); });
  page.on('requestfailed', (r) => console.log('FAILED', r.url().slice(0, 120), r.failure() && r.failure().errorText));
  page.on('pageerror', (e) => console.log('PAGEERROR', e.message));
  await page.route('https://maps.orbitgps.ca/**', async (route) => {
    const r = await route.fetch();
    await route.fulfill({ response: r, headers: { ...r.headers(), 'access-control-allow-origin': '*', 'access-control-expose-headers': 'content-range,content-length,etag' } });
  });
  if (process.env.DAY) await page.addInitScript(() => localStorage.setItem('orbit.settings', JSON.stringify({ night: 'day' })));
  const url = 'http://127.0.0.1:8765/go/' + (process.env.HASH || '');
  await page.goto(url);
  await page.waitForFunction(() => window.orbit && window.orbit.map && window.orbit.map.loaded() && window.orbit.regions.list.length, null, { timeout: 60000 });
  await page.evaluate(() => { const v = window.orbit.voice; const s = v.speak.bind(v); v.speak = (c) => { console.log('SAY ' + v.text(c)); s(c); }; });
  await page.waitForTimeout(6000);
  await page.screenshot({ path: OUT + '/1-browse.png' });
  console.log('regions', await page.evaluate(() => window.orbit.state.regionsKey));
  // search
  await page.click('#whereBtn');
  await page.fill('#q', '5230 Centennial Drive');
  await page.waitForSelector('.res', { timeout: 20000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: OUT + '/2-search.png' });
  console.log('results', await page.$$eval('.res .n', (e) => e.slice(0, 5).map((x) => x.textContent)));
  await page.fill('#q', 'Kitimat');
  await page.waitForTimeout(2500);
  console.log('results2', await page.$$eval('.res', (e) => e.slice(0, 4).map((x) => x.innerText.replace(/\n/g, ' | '))));
  await page.click('.res >> nth=0');
  await page.waitForFunction(() => !document.getElementById('goBtn').disabled || !document.getElementById('pvMsg').classList.contains('hidden'), null, { timeout: 30000 });
  await page.waitForTimeout(4000);
  await page.screenshot({ path: OUT + '/3-preview.png' });
  console.log('preview', await page.textContent('#pvInfo'), await page.textContent('#pvMsg'));
  if (process.env.DRIVE) {
    await page.click('#goBtn');
    await page.waitForTimeout(1500);
    if (await page.isVisible('#dialog')) { await page.click('#dialogOk'); }
    const shots = (process.env.DRIVE || '').split(',').map(Number);
    let t0 = Date.now();
    for (const [i, at] of shots.entries()) {
      await page.waitForTimeout(Math.max(0, at * 1000 - (Date.now() - t0)));
      await page.screenshot({ path: OUT + `/5-drive-${i}.png`, timeout: 150000 });
      console.log('cam', await page.evaluate(() => [window.orbit.map.getPitch().toFixed(1), window.orbit.map.getBearing().toFixed(0), window.orbit.map.getZoom().toFixed(1), window.orbit.settings.view, window.orbit.state.follow, window.orbit.state.mode].join(' ')));
      console.log('fps', await page.evaluate(() => new Promise((ok) => { let n = 0; const t0 = performance.now(); const f = () => { n++; if (performance.now() - t0 < 2000) requestAnimationFrame(f); else ok(n / 2); }; requestAnimationFrame(f); })), await page.evaluate(() => window.orbit.map.getPitch()));
      if (i === 0) { await page.evaluate(() => navigator.geolocation.watchPosition(() => {}, () => {}, { enableHighAccuracy: true })); await page.waitForTimeout(1500); console.log('fps with watchPosition', await page.evaluate(() => new Promise((ok) => { let n = 0; const t0 = performance.now(); const f = () => { n++; if (performance.now() - t0 < 2000) requestAnimationFrame(f); else ok(n / 2); }; requestAnimationFrame(f); }))); }
      console.log('then', await page.evaluate(() => document.getElementById('thenBox').outerHTML.slice(0, 300)));
      console.log('routeLayer', await page.evaluate(() => { const m = window.orbit.map; const src = m.getSource('orbit-route'); return [!!m.getLayer('orbit-route'), src && JSON.stringify(src.serialize().data).length, m.getStyle().layers.slice(-3).map((l) => l.id).join(','), window.orbit.state.regionsKey, m.queryRenderedFeatures({ layers: ['orbit-route'] }).length]; }));
      console.log('banner', i, await page.evaluate(() => [document.getElementById('turnDist').textContent, document.getElementById('turnRoad').textContent, document.getElementById('eta').textContent, document.getElementById('remain').textContent].join(' | ')));
    }
    console.log('SPOKEN:\n  ' + spoken.join('\n  '));
  }
  await browser.close();
})();
