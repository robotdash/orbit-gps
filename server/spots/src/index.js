// Orbit GPS spots service (Cloudflare Worker + D1) — spots.orbitgps.ca
//
//   POST /report      {type, lat, lon, note, flags[]}   → stored as "pending"
//   POST /vote        {id, good}                        → still good / gone or changed
//   GET  /spots.json  approved spots, for the app (cached 10 min)
//   GET  /admin       review page (password: ADMIN_PASSWORD secret)
//
// Privacy: no accounts, names or phone IDs are received or kept. To stop flooding, a
// one-way hash of (address + day + secret) counts requests for a day, then is deleted.

export const TYPES = {
  overnight_ok:  { emoji: "✅", label: "Overnight parking OK" },
  no_overnight:  { emoji: "🚫", label: "No overnight parking" },
  free_camping:  { emoji: "🌲", label: "Free camping" },
  rest_area:     { emoji: "🅿️", label: "Rest area / pull-off" },
  sani_dump:     { emoji: "🚐", label: "Sani dump" },
  potable_water: { emoji: "💧", label: "Fresh potable water" },
  propane:       { emoji: "🔥", label: "Propane" },
};
export const FLAGS = ["bigrig", "toilets", "cell", "fee"];
const FLAG_LABELS = { bigrig: "Big rigs OK", toilets: "Toilets", cell: "Cell signal", fee: "Costs money" };
const MAX_REPORTS_PER_DAY = 30;
const NOTE_MAX = 300;

const json = (o, status = 200, extra = {}) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", ...extra } });

const now = () => new Date().toISOString();
const today = () => now().slice(0, 10);

async function sha(s) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

/** Count one request; false if over the day's limit. */
async function allow(env, req, what, max) {
  const ip = req.headers.get("cf-connecting-ip") || "local";
  const who = await sha(ip + "|" + today() + "|" + (env.HASH_SECRET || "orbit"));
  const day = today();
  await env.DB.prepare("DELETE FROM hits WHERE day < ?").bind(new Date(Date.now() - 2 * 86400e3).toISOString().slice(0, 10)).run();
  const row = await env.DB.prepare("SELECT n FROM hits WHERE who=? AND day=? AND what=?").bind(who, day, what).first();
  if (row && row.n >= max) return false;
  await env.DB.prepare("INSERT INTO hits(who,day,what,n) VALUES(?,?,?,1) ON CONFLICT(who,day,what) DO UPDATE SET n=n+1")
    .bind(who, day, what).run();
  return true;
}

/** Check and tidy a report. Returns [spot, null] or [null, error]. */
export function clean(b) {
  if (!b || typeof b !== "object") return [null, "bad request"];
  const type = String(b.type || "");
  if (!TYPES[type]) return [null, "unknown type"];
  const lat = Number(b.lat), lon = Number(b.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat === 0 && lon === 0))
    return [null, "bad location"];
  const note = String(b.note || "").replace(/[\u0000-\u001f\u007f]+/g, " ").replace(/\s+/g, " ").trim().slice(0, NOTE_MAX);
  const flags = (Array.isArray(b.flags) ? b.flags : []).map(String).filter((f) => FLAGS.includes(f));
  return [{ type, lat: Math.round(lat * 1e6) / 1e6, lon: Math.round(lon * 1e6) / 1e6, note, flags: [...new Set(flags)].join(",") }, null];
}

async function report(req, env) {
  const [s, err] = clean(await req.json().catch(() => null));
  if (err) return json({ error: err }, 400);
  if (!(await allow(env, req, "report", MAX_REPORTS_PER_DAY))) return json({ error: "too many reports today" }, 429);
  const t = now();
  // Google Play's pre-launch test phones all sit at Google's campus in Mountain View and tap
  // through the app; their "reports" go straight to rejected (deleted after 30 days).
  const robot = Math.abs(s.lat - 37.422) < 0.01 && Math.abs(s.lon + 122.084) < 0.01;
  const r = await env.DB.prepare(
    "INSERT INTO spots(created,updated,status,type,lat,lon,note,flags) VALUES(?,?,?,?,?,?,?,?) RETURNING id")
    .bind(t, t, robot ? "rejected" : "pending", s.type, s.lat, s.lon, s.note, s.flags).first();
  return json({ ok: true, id: r.id, status: "pending" });
}

async function vote(req, env) {
  const b = await req.json().catch(() => null);
  const id = Number(b && b.id);
  if (!Number.isInteger(id) || id <= 0) return json({ error: "bad id" }, 400);
  if (!(await allow(env, req, "vote:" + id, 1))) return json({ ok: true, counted: false }); // one vote per spot per day
  const t = now();
  const sql = b.good
    ? "UPDATE spots SET up=up+1, confirmed=?, updated=? WHERE id=? AND status='approved'"
    : "UPDATE spots SET down=down+1, updated=? WHERE id=? AND status='approved'";
  const r = b.good ? await env.DB.prepare(sql).bind(t, t, id).run() : await env.DB.prepare(sql).bind(t, id).run();
  return json({ ok: true, counted: r.meta.changes > 0 });
}

async function spots(env) {
  const { results } = await env.DB.prepare(
    "SELECT id,type,lat,lon,note,flags,up,down,confirmed,updated FROM spots WHERE status='approved' ORDER BY id").all();
  const version = results.reduce((m, r) => (r.updated > m ? r.updated : m), "");
  return json({
    version, count: results.length,
    spots: results.map((r) => ({ id: r.id, t: r.type, lat: r.lat, lon: r.lon, n: r.note, f: r.flags, up: r.up, down: r.down, c: r.confirmed })),
  }, 200, { "cache-control": "public, max-age=600", "access-control-allow-origin": "*" });
}

// ------------------------------------------------------------------ review page

function authed(req, env) {
  const h = req.headers.get("authorization") || "";
  if (!h.startsWith("Basic ") || !env.ADMIN_PASSWORD) return false;
  const pass = atob(h.slice(6)).split(":").slice(1).join(":");
  return pass === env.ADMIN_PASSWORD;
}
const askLogin = () => new Response("Orbit spots review: please sign in", {
  status: 401, headers: { "www-authenticate": 'Basic realm="Orbit spots review"' } });

async function adminApi(req, env, id) {
  const b = await req.json().catch(() => ({}));
  const t = now();
  if (b.action === "approve") {
    const row = await env.DB.prepare("SELECT * FROM spots WHERE id=?").bind(id).first();
    if (!row) return json({ error: "no such spot" }, 404);
    // Start from what was reported; the review page may have changed type, note, flags or moved the pin.
    const merged = { type: row.type, lat: row.lat, lon: row.lon, note: row.note, flags: row.flags ? row.flags.split(",") : [], ...(b.edit || {}) };
    const [s, err] = clean(merged);
    if (err) return json({ error: err }, 400);
    await env.DB.prepare("UPDATE spots SET status='approved', type=?, lat=?, lon=?, note=?, flags=?, updated=?, confirmed=COALESCE(confirmed,?), down=0 WHERE id=?")
      .bind(s.type, s.lat, s.lon, s.note, s.flags, t, t, id).run();
  } else if (b.action === "reject") {
    await env.DB.prepare("UPDATE spots SET status='rejected', updated=? WHERE id=?").bind(t, id).run();
  } else return json({ error: "unknown action" }, 400);
  return json({ ok: true });
}

async function adminList(env) {
  // Rejected reports are never shown; they're deleted after 30 days (privacy policy).
  await env.DB.prepare("DELETE FROM spots WHERE status='rejected' AND updated < ?")
    .bind(new Date(Date.now() - 30 * 86400e3).toISOString()).run();
  const { results } = await env.DB.prepare(
    "SELECT * FROM spots WHERE status='pending' OR (status='approved' AND down >= 2 AND down > up) ORDER BY id").all();
  const counts = await env.DB.prepare("SELECT status, COUNT(*) n FROM spots GROUP BY status").all();
  return json({ todo: results, counts: counts.results, types: TYPES, flags: FLAGS, flagLabels: FLAG_LABELS });
}

const PAGE = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Orbit spots review</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<style>
 :root{--bg:#0c1430;--card:#1b2a4a;--text:#fff;--sub:#b8c4d6;--ok:#2e7d32;--no:#7a1f27;--accent:#2fd0ff}
 body{margin:0;background:var(--bg);color:var(--text);font:16px/1.4 system-ui,sans-serif}
 header{padding:16px;font-size:20px;font-weight:700} header small{color:var(--sub);font-weight:400;font-size:14px;margin-left:8px}
 .card{background:var(--card);border-radius:16px;margin:0 12px 16px;overflow:hidden}
 .map{height:220px} .body{padding:12px 14px}
 .t{font-size:19px;font-weight:700} .sub{color:var(--sub);font-size:14px;margin:4px 0 8px}
 textarea,select{width:100%;box-sizing:border-box;background:#0c1430;color:#fff;border:1px solid #3a4a6a;border-radius:10px;padding:8px;font:inherit}
 label{display:inline-block;margin:6px 12px 0 0;color:var(--sub)}
 .row{display:flex;gap:10px;margin-top:12px} button{flex:1;border:0;border-radius:12px;padding:14px;font:700 17px system-ui;color:#fff;cursor:pointer}
 .approve{background:var(--ok)} .reject{background:var(--no)} .empty{color:var(--sub);padding:24px 16px}
 .flagged{border:2px solid #ff9100}
</style></head><body>
<header>Orbit spots review<small id="counts"></small></header><div id="list"></div>
<script>
const esc=s=>String(s??"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
async function load(){
  const r=await fetch("/admin/api/list"); const d=await r.json();
  document.getElementById("counts").textContent=d.counts.map(c=>c.status+" "+c.n).join(" · ");
  const list=document.getElementById("list"); list.innerHTML="";
  if(!d.todo.length){list.innerHTML='<div class="empty">Nothing to review. 🎉</div>';return}
  for(const s of d.todo){
    const flagged=s.status==="approved";
    const el=document.createElement("div"); el.className="card"+(flagged?" flagged":"");
    const opts=Object.entries(d.types).map(([k,v])=>'<option value="'+k+'"'+(k===s.type?" selected":"")+'>'+v.emoji+" "+esc(v.label)+'</option>').join("");
    const fl=d.flags.map(f=>'<label><input type="checkbox" value="'+f+'"'+((","+s.flags+",").includes(","+f+",")?" checked":"")+'> '+esc(d.flagLabels[f]||f)+'</label>').join("");
    el.innerHTML='<div class="map"></div><div class="body"><div class="t">'+(d.types[s.type]||{}).emoji+" "+esc((d.types[s.type]||{}).label||s.type)+'</div>'+
      '<div class="sub">#'+s.id+" · "+s.created.slice(0,16).replace("T"," ")+" UTC · "+s.lat.toFixed(5)+", "+s.lon.toFixed(5)+
      (flagged?' · <b style="color:#ff9100">reported gone: 👍 '+s.up+" 👎 "+s.down+"</b>":"")+
      ' · <a style="color:var(--accent)" target="_blank" href="https://www.google.com/maps/search/?api=1&query='+s.lat+","+s.lon+'">street view</a></div>'+
      '<select class="type">'+opts+'</select><textarea class="note" rows="2" maxlength="300">'+esc(s.note)+'</textarea><div>'+fl+'</div>'+
      '<div class="row"><button class="approve">'+(flagged?"Keep":"Approve")+'</button><button class="reject">'+(flagged?"Remove":"Reject")+'</button></div></div>';
    list.appendChild(el);
    const m=L.map(el.querySelector(".map"),{attributionControl:false}).setView([s.lat,s.lon],16);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png",{maxZoom:19}).addTo(m);
    const mk=L.marker([s.lat,s.lon],{draggable:true}).addTo(m);
    const act=async a=>{
      const p=mk.getLatLng();
      const edit={type:el.querySelector(".type").value,note:el.querySelector(".note").value,lat:p.lat,lon:p.lng,
        flags:[...el.querySelectorAll("input:checked")].map(i=>i.value)};
      const r=await fetch("/admin/api/spot/"+s.id,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({action:a,edit})});
      if(r.ok) load(); else alert((await r.json()).error||"failed");
    };
    el.querySelector(".approve").onclick=()=>act("approve");
    el.querySelector(".reject").onclick=()=>act("reject");
  }
}
load();
</script></body></html>`;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;
    try {
      if (req.method === "POST" && p === "/report") return report(req, env);
      if (req.method === "POST" && p === "/vote") return vote(req, env);
      if (req.method === "GET" && p === "/spots.json") return spots(env);
      if (p === "/admin" || p.startsWith("/admin/")) {
        if (!authed(req, env)) return askLogin();
        if (p === "/admin") return new Response(PAGE, { headers: { "content-type": "text/html; charset=utf-8" } });
        if (p === "/admin/api/list") return adminList(env);
        const m = p.match(/^\/admin\/api\/spot\/(\d+)$/);
        if (m && req.method === "POST") return adminApi(req, env, Number(m[1]));
      }
      if (p === "/") return new Response("Orbit GPS spots service", { headers: { "content-type": "text/plain" } });
      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: "server error" }, 500);
    }
  },
};
