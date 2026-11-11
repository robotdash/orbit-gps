// Speaks Orbit's prompts with the recorded voice clips (voice/<id>.mp3), played back to back
// through Web Audio. If a clip can't be loaded, the sentence is spoken by the phone's own voice
// (speechSynthesis) from the clip's words, so directions are never silent. (voice/VoicePlayer.kt)

const BASE = new URL('../voice/', import.meta.url);

export class Voice {
  constructor() {
    this.ctx = null;
    this.buffers = new Map();
    this.words = {};
    this.queue = [];
    this.busy = false;
    this.muted = false;
    this.volume = 1;
    this.unlocked = false;
    this.loading = null;
    this.onState = () => {};
  }

  /** Call from a tap (iPhones only allow sound that starts from a tap). */
  unlock() {
    try {
      if (!this.ctx) {
        const AC = window.AudioContext || window.webkitAudioContext;
        this.ctx = new AC();
        this.gain = this.ctx.createGain();
        this.gain.connect(this.ctx.destination);
      }
      // Navigation prompts duck other audio (music, podcasts) instead of stopping it,
      // and still play with the ring/silent switch on (Safari 16.4+).
      if (navigator.audioSession) { try { navigator.audioSession.type = 'transient'; } catch {} }
      if (this.ctx.state !== 'running') this.ctx.resume();
      // A silent blip in the tap itself unlocks Web Audio on older iPhones.
      const b = this.ctx.createBuffer(1, 1, 22050);
      const s = this.ctx.createBufferSource();
      s.buffer = b; s.connect(this.ctx.destination); s.start(0);
      if (window.speechSynthesis && !this.unlocked) {
        const u = new SpeechSynthesisUtterance(' ');
        u.volume = 0; speechSynthesis.speak(u);
      }
      this.unlocked = true;
    } catch (e) { console.warn('audio unlock', e); }
    return this.load();
  }

  /** Fetch and decode every clip once (about 1.5 MB, cached for offline by the service worker). */
  load() {
    if (this.loading) return this.loading;
    this.loading = (async () => {
      try { this.words = await (await fetch(new URL('manifest.json', BASE))).json(); } catch { this.words = {}; }
      const ids = Object.keys(this.words);
      let i = 0;
      const worker = async () => {
        while (i < ids.length) {
          const id = ids[i++];
          try {
            const data = await (await fetch(new URL(id + '.mp3', BASE))).arrayBuffer();
            const buf = await new Promise((ok, bad) => this.ctx.decodeAudioData(data, ok, bad));
            this.buffers.set(id, buf);
          } catch { /* phone voice covers it */ }
        }
      };
      if (this.ctx) await Promise.all([worker(), worker(), worker(), worker()]);
    })();
    return this.loading;
  }

  speak(clips) {
    if (this.muted || !clips || !clips.length) return;
    if (this.busy) { this.queue.push('_pause', ...clips); return; }
    this.queue = [...clips];
    this.busy = true;
    this.onState(true);
    this.next();
  }

  stop() {
    this.queue = [];
    try { this.source && this.source.stop(); } catch {}
    try { window.speechSynthesis && speechSynthesis.cancel(); } catch {}
    this.busy = false;
    this.onState(false);
  }

  next() {
    const id = this.queue.shift();
    if (id == null) { this.busy = false; this.onState(false); return; }
    if (id === '_pause') { setTimeout(() => this.next(), 400); return; }
    const buf = this.buffers.get(id);
    if (this.ctx && buf) {
      if (this.ctx.state !== 'running') this.ctx.resume();
      const s = this.ctx.createBufferSource();
      s.buffer = buf;
      this.gain.gain.value = this.volume;
      s.connect(this.gain);
      s.onended = () => { if (this.source === s) this.source = null; this.next(); };
      this.source = s;
      s.start();
      return;
    }
    // No clip: say this one and the rest of the sentence with the phone's voice.
    const rest = [id];
    while (this.queue.length && this.queue[0] !== '_pause') rest.push(this.queue.shift());
    const text = rest.map((c) => this.words[c] || c.replace(/_/g, ' ')).join(' ');
    if (!window.speechSynthesis) { this.next(); return; }
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'en-CA';
    u.volume = this.volume;
    let done = false;
    const finish = () => { if (!done) { done = true; this.next(); } };
    u.onend = finish; u.onerror = finish;
    setTimeout(finish, 2500 + text.length * 120);
    speechSynthesis.speak(u);
  }

  /** The words for a sentence (for the screen and for tests). */
  text(clips) { return clips.map((c) => this.words[c] || c).join(' '); }
}
