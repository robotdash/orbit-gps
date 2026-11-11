// Orbit GPS Lite: navigation core, ported from the Android app (nav/Route.kt, RouteTracker.kt,
// Navigator.kt, voice/PromptEngine.kt). Pure logic, no browser APIs, so it runs in tests too.

// ------------------------------------------------------------------ geometry

const R = 6371000;
const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;

export const Geo = {
  distance(a, b) {
    const lat = rad((a.lat + b.lat) / 2);
    const x = rad(b.lon - a.lon) * Math.cos(lat);
    const y = rad(b.lat - a.lat);
    return Math.sqrt(x * x + y * y) * R;
  },
  /** Initial bearing a→b, degrees 0..360. */
  bearing(a, b) {
    const lat = rad((a.lat + b.lat) / 2);
    const x = rad(b.lon - a.lon) * Math.cos(lat);
    const y = rad(b.lat - a.lat);
    return (deg(Math.atan2(x, y)) + 360) % 360;
  },
  /** Angle to -180..180. */
  normalize(d) {
    d %= 360;
    if (d > 180) d -= 360;
    if (d < -180) d += 360;
    return d;
  },
  angleDiff(a, b) { return Math.abs(Geo.normalize(a - b)); },
  lerp(a, b, t) { return { lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t }; },
};

export function decodePolyline(s, precision = 6) {
  const factor = Math.pow(10, precision);
  const out = [];
  let i = 0, lat = 0, lon = 0;
  while (i < s.length) {
    let shift = 0, result = 0, b;
    do { b = s.charCodeAt(i++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
    lat += result & 1 ? ~(result >> 1) : result >> 1;
    shift = 0; result = 0;
    do { b = s.charCodeAt(i++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
    lon += result & 1 ? ~(result >> 1) : result >> 1;
    out.push({ lat: lat / factor, lon: lon / factor });
  }
  return out;
}

// ------------------------------------------------------------------ route

export const T = {
  TURN: 'TURN', BEAR: 'BEAR', SHARP: 'SHARP', KEEP: 'KEEP', STAY: 'STAY', EXIT: 'EXIT', RAMP: 'RAMP',
  MERGE: 'MERGE', MERGE_HIGHWAY: 'MERGE_HIGHWAY', ROUNDABOUT: 'ROUNDABOUT', UTURN: 'UTURN',
  STRAIGHT: 'STRAIGHT', END_OF_ROAD: 'END_OF_ROAD', FERRY: 'FERRY', ARRIVE: 'ARRIVE',
};

/**
 * A Valhalla route returned in OSRM format → { points, cumulative, steps, announced, duration, length }.
 * Each step: { maneuver|null, instruction, streetName, ref, pointIndex, distanceFromStart, icon }.
 */
export function parseRoute(json) {
  const root = typeof json === 'string' ? JSON.parse(json) : json;
  const r = root.routes[0];
  const points = [];
  const stepStart = [];
  const raw = [];
  const lastLeg = [];
  r.legs.forEach((leg, l) => {
    for (const step of leg.steps) {
      const g = decodePolyline(step.geometry || '', 6);
      stepStart.push(points.length ? points.length - 1 : 0);
      raw.push(step);
      lastLeg.push(l === r.legs.length - 1);
      const skipFirst = points.length && g.length && Geo.distance(points[points.length - 1], g[0]) < 0.5;
      for (let k = skipFirst ? 1 : 0; k < g.length; k++) points.push(g[k]);
    }
  });
  if (points.length < 2) points.push(...decodePolyline(r.geometry, 6));
  const cum = new Float64Array(points.length);
  for (let i = 1; i < points.length; i++) cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i]);

  const steps = raw.map((step, i) => {
    const s = parseStep(step, Math.min(stepStart[i], points.length - 1), cum);
    if (!lastLeg[i] && s.maneuver && s.maneuver.type === T.ARRIVE) s.maneuver.waypoint = true;
    return s;
  });
  const length = cum[cum.length - 1];
  return {
    points, cumulative: cum, steps, length,
    announced: steps.filter((s) => s.maneuver),
    duration: r.duration || 0,
  };
}

function parseStep(step, idx, cum) {
  const m = step.maneuver || {};
  const type = m.type || '';
  const mod = m.modifier || '';
  const side = mod.includes('right') ? 'RIGHT' : mod.includes('left') ? 'LEFT' : null;
  const lanes = lanesAt(step.intersections);
  const mode = step.mode || 'driving';
  let icon = 'STRAIGHT';
  let man = null;
  const M = (t, extra = {}) => ({ type: t, side, ...extra });
  if (mode === 'ferry' && type !== 'arrive') { icon = 'FERRY'; man = M(T.FERRY); }
  else if (type === 'depart') man = null;
  else if (type === 'arrive') { icon = 'ARRIVE'; man = M(T.ARRIVE); }
  else if (type === 'roundabout' || type === 'rotary' || type === 'roundabout turn') {
    icon = 'ROUNDABOUT'; man = M(T.ROUNDABOUT, { exitNumber: m.exit > 0 ? m.exit : null });
  } else if (type === 'exit roundabout' || type === 'exit rotary') man = null;
  else if (type === 'fork') { icon = 'FORK'; man = M(T.KEEP, { lanes }); }
  else if (type === 'off ramp') { icon = 'EXIT'; man = M(T.EXIT, { lanes }); }
  else if (type === 'on ramp') {
    icon = 'SLIGHT';
    man = (mod.includes('slight') || mod === 'straight' || !side) ? M(T.MERGE_HIGHWAY) : M(T.RAMP, { lanes });
  } else if (type === 'merge') { icon = 'MERGE'; man = M(side ? T.MERGE : T.MERGE_HIGHWAY); }
  else if (type === 'end of road' && side) { icon = 'TURN'; man = M(T.END_OF_ROAD, { lanes }); }
  else if (mod === 'uturn') { icon = 'UTURN'; man = M(T.UTURN); }
  else if (mod === 'straight' || !mod) man = null;
  else if ((type === 'continue' || type === 'new name') && mod.startsWith('slight')) { icon = 'FORK'; man = M(T.STAY, { lanes }); }
  else if (mod.startsWith('slight')) { icon = 'SLIGHT'; man = M(T.BEAR, { lanes }); }
  else if (mod.startsWith('sharp')) { icon = 'SHARP'; man = M(T.SHARP, { lanes }); }
  else if (side) { icon = 'TURN'; man = M(T.TURN, { lanes }); }
  return {
    maneuver: man,
    instruction: m.instruction || '',
    streetName: step.name || '',
    ref: step.ref || '',
    destinations: step.destinations || '',
    exits: step.exits || '',
    pointIndex: idx,
    distanceFromStart: cum[Math.max(0, Math.min(idx, cum.length - 1))],
    icon,
    lanes,
  };
}

function lanesAt(inters) {
  const arr = inters && inters[0] && inters[0].lanes;
  if (!arr) return null;
  const valid = arr.map((l) => !!l.valid);
  return valid.length >= 2 ? { valid, indications: arr.map((l) => l.indications || []) } : null;
}

/** Icon name (icons.js) for a step, with the left-hand version where there is one. */
export function iconFor(step) {
  const left = step && step.maneuver && step.maneuver.side === 'LEFT';
  const base = {
    STRAIGHT: 'turn_straight', TURN: 'turn_right', SLIGHT: 'turn_slight', SHARP: 'turn_sharp', UTURN: 'turn_uturn',
    FORK: 'turn_fork', ROUNDABOUT: 'turn_roundabout', MERGE: 'turn_merge', EXIT: 'turn_exit', ARRIVE: 'turn_arrive', FERRY: 'turn_ferry',
  }[step ? step.icon : 'STRAIGHT'];
  const sided = ['turn_right', 'turn_slight', 'turn_sharp', 'turn_fork', 'turn_merge', 'turn_exit'];
  return left && sided.includes(base) ? base + '_left' : base;
}

// ------------------------------------------------------------------ route tracker

export class RouteTracker {
  constructor(route) {
    this.route = route;
    this.segIndex = 0;
    this.offCount = 0;
    this.lastAlong = 0;
  }

  update(p, heading, speedMs, accuracy) {
    const pts = this.route.points, cum = this.route.cumulative;
    let best = this.segIndex, bestD = Infinity, bestT = 0;
    let i = this.segIndex;
    while (i > 0 && cum[this.segIndex] - cum[i] < 50) i--;
    while (i < pts.length - 1 && (cum[i] < this.lastAlong + 600 || i <= this.segIndex + 3)) {
      const [d, t] = segDistance(p, pts[i], pts[i + 1]);
      const segBearing = Geo.bearing(pts[i], pts[i + 1]);
      const penalty = speedMs > 3 && heading != null && Geo.angleDiff(segBearing, heading) > 100 ? 30 : 0;
      if (d + penalty < bestD) { bestD = d + penalty; best = i; bestT = t; }
      i++;
    }
    if (bestD > 200) {
      for (let j = 0; j < pts.length - 1; j++) {
        const [d, t] = segDistance(p, pts[j], pts[j + 1]);
        if (d < bestD) { bestD = d; best = j; bestT = t; }
      }
    }
    this.segIndex = best;
    const a = pts[best], b = pts[best + 1];
    const snapped = Geo.lerp(a, b, bestT);
    const offset = Geo.distance(p, snapped);
    const along = Math.max(cum[best] + (cum[best + 1] - cum[best]) * bestT, this.lastAlong - 30);
    this.lastAlong = along;
    const routeBearing = Geo.bearing(a, b);
    const ann = this.route.announced;
    let nextIndex = ann.findIndex((s) => s.distanceFromStart > along + 3);
    const distanceToNext = nextIndex >= 0 ? ann[nextIndex].distanceFromStart - along : 0;
    const remaining = Math.max(this.route.length - along, 0);
    const limit = 35 + Math.min(accuracy || 0, 40);
    const wrongWay = speedMs > 4 && heading != null && Geo.angleDiff(routeBearing, heading) > 120;
    this.offCount = offset > limit || wrongWay ? this.offCount + 1 : 0;
    const offRoute = this.offCount >= 3;
    const arrived = remaining < 25 || (nextIndex < 0 && remaining < 60);
    if (arrived) nextIndex = -1;
    return { snapped, routeBearing, offset, along, nextIndex, distanceToNext, remaining, offRoute, arrived };
  }
}

function segDistance(p, a, b) {
  const kx = 111320 * Math.cos(rad(p.lat)), ky = 110540;
  const ax = (a.lon - p.lon) * kx, ay = (a.lat - p.lat) * ky;
  const bx = (b.lon - p.lon) * kx, by = (b.lat - p.lat) * ky;
  const dx = bx - ax, dy = by - ay;
  const len2 = dx * dx + dy * dy;
  const t = len2 === 0 ? 0 : Math.min(1, Math.max(0, -(ax * dx + ay * dy) / len2));
  const x = ax + t * dx, y = ay + t * dy;
  return [Math.sqrt(x * x + y * y), t];
}

// ------------------------------------------------------------------ prompts (Garmin-style)

const DISTANCE_CLIPS = [[3000, 'in_3_km'], [2000, 'in_2_km'], [1500, 'in_1_5_km'], [1000, 'in_1_km'], [800, 'in_800_m'],
  [600, 'in_600_m'], [500, 'in_500_m'], [400, 'in_400_m'], [300, 'in_300_m'], [200, 'in_200_m'], [150, 'in_150_m'],
  [100, 'in_100_m'], [50, 'in_50_m']];
const CONTINUE_KM = [1, 1.5, 2, 2.5, 3, 3.5, 4, 5, 6, 7, 8, 9, 10, 12, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100, 120, 150, 200];

export const Prompts = {
  warningScale: 1.0,
  thenThreshold(kmh) { return kmh < 60 ? 150 : kmh < 90 ? 250 : 400; },
  stageDistances(kmh) {
    const b = kmh < 40 ? [300, 100, 25] : kmh < 60 ? [500, 200, 40] : kmh < 90 ? [1000, 400, 80] : [2000, 800, 200];
    return { early: b[0] * this.warningScale, prepare: b[1] * this.warningScale, action: b[2] };
  },
  distanceClip(m) {
    let best = DISTANCE_CLIPS[0];
    for (const c of DISTANCE_CLIPS) if (Math.abs(c[0] - m) < Math.abs(best[0] - m)) best = c;
    return best[1];
  },
  continueClip(metres) {
    const km = metres / 1000;
    if (km < 0.95) return null;
    if (km > 230) return 'follow_road';
    let n = CONTINUE_KM[0];
    for (const c of CONTINUE_KM) {
      const dc = Math.abs(c - km), dn = Math.abs(n - km);
      if (dc < dn || (dc === dn && c < n)) n = c;
    }
    return `continue_for_${String(n).replace('.', '_')}_km`;
  },
  actionClips(m) {
    const s = (m.side || 'RIGHT').toLowerCase();
    switch (m.type) {
      case T.TURN: return [`turn_${s}`];
      case T.BEAR: return [`bear_${s}`];
      case T.SHARP: return [`sharp_${s}`];
      case T.KEEP: return m.staysOnHighway ? [`keep_${s}`, 'to_stay_on_highway'] : [`fork_${s}`];
      case T.STAY: return [`stay_${s}`];
      case T.EXIT: return [m.side ? `exit_${s}` : 'take_exit'];
      case T.RAMP: return [`ramp_${s}`];
      case T.MERGE: return [`merge_${s}`];
      case T.MERGE_HIGHWAY: return ['merge_highway'];
      case T.ROUNDABOUT: return m.exitNumber >= 1 && m.exitNumber <= 5 ? [`roundabout_exit_${m.exitNumber}`] : ['enter_roundabout'];
      case T.UTURN: return ['u_turn'];
      case T.STRAIGHT: return ['straight_through'];
      case T.END_OF_ROAD: return [`end_of_road_${s}`];
      case T.FERRY: return ['board_ferry'];
      case T.ARRIVE: return [m.side === 'LEFT' ? 'dest_ahead_left' : m.side === 'RIGHT' ? 'dest_ahead_right' : 'dest_ahead'];
    }
    return [];
  },
  laneClip(lanes) {
    const v = lanes && lanes.valid;
    if (!v || !v.length || v.every((x) => x) || !v.some((x) => x)) return null;
    const n = v.length, first = v.indexOf(true), last = v.lastIndexOf(true), count = last - first + 1;
    if (v.slice(first, last + 1).some((x) => !x)) return null;
    if (last === n - 1 && count === 1) return 'use_right_lane';
    if (last === n - 1 && count === 2) return 'use_right_2_lanes';
    if (first === 0 && count === 1) return 'use_left_lane';
    if (first === 0 && count === 2) return 'use_left_2_lanes';
    if (first > 0 && last < n - 1) return count === 1 ? 'use_middle_lane' : 'use_middle_lanes';
    return null;
  },
  laneTail(m) {
    const s = m.side && m.side.toLowerCase();
    if (m.type === T.TURN || m.type === T.END_OF_ROAD) return s ? [`to_turn_${s}`] : null;
    if (m.type === T.EXIT) return ['to_take_exit'];
    if (m.type === T.KEEP || m.type === T.STAY) return m.staysOnHighway ? ['to_stay_on_highway'] : null;
    return null;
  },
  build(stage, m, distance, kmh, next, gap) {
    const out = [];
    if (stage === 'ACTION') { if (m.type !== T.ARRIVE) out.push('now'); }
    else out.push(this.distanceClip(distance));
    if (m.type === T.ARRIVE && stage === 'ACTION') {
      out.push(m.waypoint ? 'stop_reached' : m.side === 'LEFT' ? 'dest_on_left' : m.side === 'RIGHT' ? 'dest_on_right' : 'arriving');
      return out;
    }
    const lane = this.laneClip(m.lanes), tail = this.laneTail(m);
    const giveLanes = lane && tail && (stage === 'PREPARE' || (stage === 'EARLY' && kmh >= 90));
    if (giveLanes) out.push(lane, ...tail); else out.push(...this.actionClips(m));
    const close = next && gap != null && gap <= this.thenThreshold(kmh);
    if (stage !== 'EARLY' && close && next.type !== T.ARRIVE) out.push('then', ...this.actionClips(next));
    else if (stage === 'ACTION' && close && next.type === T.ARRIVE) out.push('then', ...this.actionClips(next));
    return out;
  },
};

/** Which stages have been spoken for the current manoeuvre (voice/PromptEngine.kt GuidanceTracker). */
export class GuidanceTracker {
  constructor(speak) { this.speak = speak; this.currentId = -1; this.spoken = new Set(); this.distanceAtStart = 0; this.preAnnouncedId = null; }
  update(id, m, distance, kmh, next, gap) {
    if (id !== this.currentId) {
      this.currentId = id;
      this.spoken.clear();
      this.distanceAtStart = distance;
      if (this.preAnnouncedId === id) { this.spoken.add('EARLY'); this.spoken.add('PREPARE'); }
      this.preAnnouncedId = null;
      if (id > 0) {
        const c = Prompts.continueClip(distance);
        if (c && distance > Prompts.stageDistances(kmh).early * 1.5) this.speak([c]);
      }
    }
    const d = Prompts.stageDistances(kmh);
    const stage = distance <= d.action ? 'ACTION' : distance <= d.prepare ? 'PREPARE' : distance <= d.early ? 'EARLY' : null;
    if (!stage || this.spoken.has(stage)) return;
    if (stage === 'EARLY' && (this.distanceAtStart <= d.prepare + 150 || distance <= d.prepare + 150)) return;
    this.spoken.add(stage);
    if (stage === 'PREPARE') this.spoken.add('EARLY');
    if (stage === 'ACTION') { this.spoken.add('EARLY'); this.spoken.add('PREPARE'); }
    const sentence = Prompts.build(stage, m, distance, kmh, next, gap);
    if (sentence.includes('then')) this.preAnnouncedId = id + 1;
    this.speak(sentence);
  }
}

// ------------------------------------------------------------------ navigator

export class Navigator {
  /** speak(clips), onOffRoute(pt, heading), clock() ms. */
  constructor(route, speak, onOffRoute, clock = () => Date.now()) {
    this.route = route;
    this.speak = speak;
    this.onOffRoute = onOffRoute;
    this.clock = clock;
    this.tracker = new RouteTracker(route);
    this.guidance = new GuidanceTracker(speak);
    this.generation = 0;
    this.startedSpeaking = false;
    this.rerouteAt = 0;
    this.rerouteTries = 0;
    this.arrived = false;
    this.lastProgress = null;
  }

  replaceRoute(route, announce = true) {
    this.route = route;
    this.tracker = new RouteTracker(route);
    this.generation++;
    this.rerouteAt = this.clock();
    if (announce && this.rerouteTries <= 1) this.speak(['route_recalculated']);
  }

  update(p, heading, speedMs, accuracy) {
    if (this.arrived) return this.lastProgress;
    const prog = this.tracker.update(p, heading, speedMs, accuracy);
    this.lastProgress = prog;
    const kmh = speedMs * 3.6;
    if (!this.startedSpeaking) {
      this.startedSpeaking = true;
      this.speak([prog.offset > 60 ? 'drive_to_route' : 'route_start']);
    }
    if (prog.arrived) {
      this.arrived = true;
      const last = this.route.announced[this.route.announced.length - 1];
      const side = last && last.maneuver && last.maneuver.type === T.ARRIVE ? last.maneuver.side : null;
      this.speak([side === 'LEFT' ? 'dest_on_left' : side === 'RIGHT' ? 'dest_on_right' : 'arrived']);
      return prog;
    }
    if (prog.offRoute) {
      if (speedMs < 2) return prog;
      const now = this.clock();
      const wait = Math.min(8000 * Math.pow(2, Math.min(Math.max(this.rerouteTries - 1, 0), 3)), 60000);
      if (this.rerouteTries === 0 || now - this.rerouteAt > wait) {
        this.rerouteAt = now;
        if (this.rerouteTries === 0) this.speak(['recalculating']);
        this.rerouteTries++;
        this.onOffRoute(p, heading);
      }
      return prog;
    }
    this.rerouteTries = 0;
    const ann = this.route.announced;
    const i = prog.nextIndex;
    if (i < 0) return prog;
    const step = ann[i];
    const m = step.maneuver;
    if (!m) return prog;
    if (m.type === T.ARRIVE && !m.waypoint && prog.distanceToNext <= Prompts.stageDistances(kmh).action + 20) return prog;
    const next = ann[i + 1];
    const gap = next ? next.distanceFromStart - step.distanceFromStart : null;
    this.guidance.update(this.generation * 10000 + i, m, prog.distanceToNext, kmh, next && next.maneuver, gap);
    return prog;
  }
}

// ------------------------------------------------------------------ formatting

export function fmtDistance(m) {
  if (m >= 10000) return `${Math.round(m / 1000)} km`;
  if (m >= 1000) return `${(m / 1000).toFixed(1)} km`;
  if (m >= 100) return `${Math.round(m / 10) * 10} m`;
  return `${Math.max(0, Math.round(m / 5) * 5)} m`;
}

export function fmtDuration(sec) {
  const min = Math.round(sec / 60);
  if (min < 60) return `${Math.max(1, min)} min`;
  const h = Math.floor(min / 60), mm = min % 60;
  return mm ? `${h} h ${mm} min` : `${h} h`;
}
