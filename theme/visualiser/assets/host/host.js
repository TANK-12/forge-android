/* Forge Visualiser's page (Relay task 1293). The desktop visualiser's own
   looks and presets (shared/visualiser, task 1294, copied in at build time)
   run here unchanged: full screen with controls, or with ?mode=wallpaper as
   the live wallpaper with none. The sound is read on the Android side
   (Sound.java: this tablet's own output or the microphone) and handed over a
   frame at a time through window.ForgeAndroid, already in the shape the
   looks draw from, so no raw audio reaches the page. */
(() => {
  "use strict";
  const A = window.ForgeAndroid || null;
  const wall = new URLSearchParams(location.search).get("mode") === "wallpaper";
  const $ = (id) => document.getElementById(id);
  const NB = ForgeViz.NB, WAVE = ForgeViz.WAVE, SIZE = NB + WAVE + 6;
  const WEIGHT = { light: "Light", heavy: "Showpiece" };
  const GROUPS = [
    ["light", "Light", "Smooth on any tablet and easy on the battery."],
    ["heavy", "Showpiece", "Rich 3D looks that want strong graphics."],
  ];
  const presetKey = wall ? "wall" : "preset";
  const fpsKey = wall ? "wallFps" : "fps";
  const scaleKey = wall ? "wallScale" : "scale";

  // Linear up to 0.72, then smooth rolloff toward 1 so turning sensitivity up
  // keeps musical contrast instead of pinning every value flat against the ceiling.
  const softCap = (v) => (v <= 0 ? 0 : v <= 0.72 ? v : 0.72 + 0.28 * Math.tanh((v - 0.72) / 0.28));
  const softWave = (v) => Math.sign(v) * softCap(Math.abs(v));

  // The analyser viz_core's Player asks for a frame each time it draws; this
  // one reads the Android side's bytes and applies the user's sensitivity and
  // the preset's gain and smoothing the way viz_core's own analyser does.
  class PhoneSound {
    constructor() {
      this.sensitivity = 1;
      this.out = { t: 0, dt: 0, bands: new Float32Array(NB), wave: new Float32Array(WAVE),
        level: 0, bass: 0, mid: 0, treble: 0, beat: 0, beats: 0, silent: true };
    }
    feedPcm() {}
    feedBands() {}
    reset() {}
    read() {
      try {
        const s = A ? A.frame() : "";
        return s ? atob(s) : "";
      } catch (e) {
        return "";
      }
    }
    frame(t, dt, audio = {}) {
      const o = this.out, raw = this.read();
      const sens = Math.max(0.25, Math.min(2.5, Number(this.sensitivity) || 1));
      const gain = (Number(audio.gain) || 1) * sens;
      // Sound.java already smooths moderately; blend lightly so presets still shape decay.
      const smooth = Math.min(0.92, Math.max(0, (audio.smoothing == null ? 0.6 : Number(audio.smoothing)) * 0.85));
      const fall = Math.pow(smooth, dt * 60), rise = 1 - Math.pow(0.25, dt * 60);
      o.t = t; o.dt = dt;
      if (raw.length < SIZE) {
        for (let b = 0; b < NB; b++) o.bands[b] *= fall;
        const ebb = Math.pow(0.9, dt * 60); // by time: the same fade at 30 or 120 frames a second
        for (let i = 0; i < WAVE; i++) o.wave[i] *= ebb;
        o.level *= fall; o.bass *= fall; o.mid *= fall; o.treble *= fall;
        o.beat = 0; o.silent = true;
        return o;
      }
      for (let b = 0; b < NB; b++) {
        const v = softCap((raw.charCodeAt(b) / 255) * gain), p = o.bands[b];
        o.bands[b] = v > p ? p + (v - p) * rise : Math.max(v, p * fall);
      }
      const wg = Math.min(1.5, gain);
      for (let i = 0; i < WAVE; i++) o.wave[i] = softWave(((raw.charCodeAt(NB + i) - 128) / 127) * wg);
      const k = NB + WAVE, u = (i) => softCap((raw.charCodeAt(k + i) / 255) * gain);
      const ease = 1 - Math.pow(0.75, dt * 60);
      o.level += (u(0) - o.level) * ease;
      o.bass += (u(1) - o.bass) * ease;
      o.mid += (u(2) - o.mid) * ease;
      o.treble += (u(3) - o.treble) * ease;
      const beat = raw.charCodeAt(k + 4) / 255;
      if (beat > 0.9 && o.beat <= 0.9) o.beats++;
      o.beat = beat;
      o.silent = raw.charCodeAt(k + 5) === 1;
      return o;
    }
  }

  const phoneSound = new PhoneSound();
  const player = new ForgeViz.Player($("stage"), { analyser: phoneSound, fps: 30, onError: broke });
  let presets = [], prefs = {}, current = null, still = false, paused = document.visibilityState === "hidden";
  let engines = {}; // look id -> "2d" | "webgl", from looks.json
  let shares = {}; // look id -> share of screen pixels it draws at (looks.json scale)

  // Showpiece looks draw with WebGL 2; ask the screen once whether it has it
  const GL2 = (() => {
    try {
      const g = document.createElement("canvas").getContext("webgl2");
      const lose = g && g.getExtension("WEBGL_lose_context");
      if (lose) lose.loseContext();
      return !!g;
    } catch (e) {
      return false;
    }
  })();
  const runs = (p) => GL2 || engines[p.look] !== "webgl";
  let pacing = 0, fadeTimer = 0, choosing = 0;
  // the screen's refresh rate (0 until known), measured while nothing draws,
  // as the player starts and each time it comes back on screen
  let screenHz = 0, measuring = null;
  const measure = () => {
    if (wall) return Promise.resolve();
    if (!measuring) measuring = ForgeViz.refreshRate(24, 1500).then((hz) => { if (hz) screenHz = hz; measuring = null; });
    return measuring;
  };

  const readPrefs = () => {
    try { return JSON.parse(A ? A.prefs() : "{}") || {}; } catch (e) { return {}; }
  };
  const save = (k, v) => { prefs[k] = String(v); if (A) A.set(k, String(v)); };
  // the user's sharpness times the look's own share, as the desktop app does,
  // so a heavy look (Northern lights, Chrome) draws fewer pixels on a phone
  const scale = () => {
    const share = Number(current && shares[current.look]) || 1;
    player.setScale(share * (Number(prefs[scaleKey]) || (wall ? 0.75 : 1)));
  };
  const applySensitivity = () => {
    const v = Math.max(0.3, Math.min(2, Number(prefs.sensitivity) || 1));
    phoneSound.sensitivity = v;
    const sl = $("sensSlider"), out = $("sensVal");
    if (sl) sl.value = String(v);
    if (out) out.textContent = `${Math.round(v * 100)}%`;
  };

  function note(text) {
    const n = $("note");
    n.textContent = text || "";
    n.hidden = !text;
    if (text) { clearTimeout(note.t); note.t = setTimeout(() => { n.hidden = true; }, 6000); }
  }

  // Looks change one at a time and only the newest one asked for runs, so a
  // quick swipe past a look still loading cannot bring it back afterwards.
  let changing = Promise.resolve();
  function show(id, picked) {
    const ask = ++choosing;
    changing = changing.then(() => (ask === choosing ? change(id, picked, ask) : null)).catch((e) => console.warn("[visualiser]", e));
    return changing;
  }

  // show a preset; when this screen cannot run it, the look already showing
  // stays (with none yet, a Light one) and a note says why
  async function change(id, picked, ask) {
    const want = presets.find((p) => p.id === id) || presets.find((p) => p.weight !== "heavy" && runs(p)) || presets[0];
    if (!want) return;
    let got = want;
    try {
      await player.use(want);
    } catch (e) {
      console.warn("[visualiser]", want.id, e.message);
      note(`${want.name} ${e.message}`);
      got = (current && current.id !== want.id && current)
        || presets.find((p) => p.weight !== "heavy" && p.id !== want.id && runs(p));
      if (!got) return;
      try {
        await player.use(got);
      } catch (e2) {
        console.warn("[visualiser]", got.id, e2.message);
        return;
      }
    }
    if (ask !== choosing) return;
    current = got;
    scale();
    if (picked && !wall && got === want) save(presetKey, got.id);
    label();
    if (still) drawStill();
  }

  function broke(e) {
    note(`${current ? current.name : "This look"} stopped: ${e.message}`);
    const light = presets.find((p) => p.weight !== "heavy" && runs(p) && (!current || p.id !== current.id));
    if (light) show(light.id).then(() => { if (!paused && !still) player.start(); });
  }

  function step(by) {
    if (!presets.length) return;
    const at = Math.max(0, presets.findIndex((p) => current && p.id === current.id));
    show(presets[(at + by + presets.length) % presets.length].id, true);
  }

  function drawStill() {
    player.draw((performance.now() - player.t0) / 1000, 1 / 30);
  }

  // the frame rate allowed now (battery rules on the Android side); 0 = a still picture
  function pace() {
    const fps = A ? A.fps() : 30;
    if ((fps || 30) !== player.maxFps) player.setFps(fps || 30);
    const nowStill = fps === 0;
    if (nowStill !== still) {
      still = nowStill;
      if (still) { player.stop(); drawStill(); } else if (!paused) player.start();
    }
    if (!wall) { $("why").textContent = A ? A.why() : ""; status(); rateNote(); }
  }

  // what the frame rate is really doing, and plainly when the screen is slower
  // than the rate picked (the battery rules have their own line, #why)
  function rateNote() {
    const el = $("rateNote");
    if (!el) return;
    el.textContent = player.running ? ForgeViz.rateNote(player.maxFps, Math.max(screenHz, player.tickHz()), player.drawnFps()) : "";
  }

  // the full-screen frame-rate chips, from the shared list (shared/visualiser
  // RATES; "as fast as the screen" is left out to spare the battery)
  function rateChips() {
    const box = $("fpsChips");
    if (!box) return;
    box.textContent = "";
    for (const r of ForgeViz.RATES.filter((x) => x.fps > 0)) {
      const b = document.createElement("button");
      b.dataset.v = String(r.fps);
      b.textContent = box.childElementCount ? String(r.fps) : `${r.fps} frames a second`;
      box.appendChild(b);
    }
  }

  function pause() {
    paused = true;
    player.stop();
    clearInterval(pacing);
    pacing = 0;
    clearTimeout(fadeTimer);
    fadeTimer = 0;
  }

  function resume() {
    paused = false;
    pace();
    if (still) drawStill();
    // the screen is measured once, before the first frame; after that a
    // return draws at once (a phone keeps its screen)
    else if (screenHz || wall) player.start();
    else measure().then(() => { if (!paused && !still) { player.start(); rateNote(); } });
    if (!pacing) pacing = setInterval(pace, 3000);
    if (!wall) scheduleFade();
  }

  // ---- the player's controls ---------------------------------------------------
  function label() {
    if (wall || !current) return;
    $("name").textContent = current.name;
    const w = $("weight");
    w.textContent = WEIGHT[current.weight] || "Light";
    w.className = "tag " + (current.weight === "heavy" ? "heavy" : "light");
    for (const t of document.querySelectorAll(".tile")) t.classList.toggle("on", t.dataset.id === current.id);
    const onWall = presets.find((p) => p.id === prefs.wall);
    const eng = prefs.wallStatus ? ` (${prefs.wallStatus})` : "";
    $("wallNow").textContent = onWall ? `The wallpaper shows ${onWall.name}.${eng}` : (prefs.wallStatus || "");
  }

  function status() {
    if (!A) { $("status").textContent = "No sound here (not on Android)"; return; }
    $("status").textContent = A.status();
    const listens = prefs.source === "phone" || prefs.source === "mic";
    $("allow").hidden = !(listens && prefs.mic === false);
    if (!$("allow").hidden) {
      $("ui").classList.remove("faded");
      clearTimeout(fadeTimer);
      fadeTimer = 0;
    }
  }

  function syncChips() {
    for (const box of document.querySelectorAll(".chips")) {
      const k = box.dataset.pref;
      const v = prefs[k];
      for (const b of box.querySelectorAll("button")) {
        const match = k === "sensitivity"
          ? Math.abs((Number(v) || 1) - Number(b.dataset.v)) < 0.015
          : b.dataset.v === v;
        b.classList.toggle("on", match);
      }
    }
    applySensitivity();
  }

  function buildLooks() {
    const list = $("lookList");
    list.textContent = "";
    for (const [weight, title, about] of GROUPS) {
      const rows = presets.filter((p) => (p.weight === "heavy" ? "heavy" : "light") === weight);
      if (!rows.length) continue;
      const g = document.createElement("div");
      g.className = "group";
      const h = document.createElement("h3");
      h.textContent = title;
      const p = document.createElement("p");
      const cannot = rows.filter((r) => !runs(r)).length;
      p.textContent = cannot ? `${about} This screen has no WebGL 2, so they cannot run here.` : about;
      const tiles = document.createElement("div");
      tiles.className = "tiles";
      for (const row of rows) {
        const b = document.createElement("button");
        b.className = runs(row) ? "tile" : "tile off";
        b.dataset.id = row.id;
        const sw = document.createElement("span");
        sw.className = "sw";
        const c = row.colours || {};
        for (const k of ["background", "primary", "secondary", "accent"]) {
          const i = document.createElement("i");
          i.style.background = c[k] || "#000";
          sw.appendChild(i);
        }
        const name = document.createElement("span");
        name.textContent = row.name;
        b.append(sw, name);
        b.addEventListener("click", () => show(row.id, true));
        tiles.appendChild(b);
      }
      g.append(h, p, tiles);
      list.appendChild(g);
    }
  }

  function scheduleFade() {
    clearTimeout(fadeTimer);
    fadeTimer = 0;
    if (paused || wall) return;
    const open = !$("looks").hidden || !$("settings").hidden;
    const needAllow = $("allow") && !$("allow").hidden;
    if (open || needAllow) return;
    fadeTimer = setTimeout(() => {
      fadeTimer = 0;
      const sheetOpen = !$("looks").hidden || !$("settings").hidden;
      const stillNeed = $("allow") && !$("allow").hidden;
      if (!paused && !sheetOpen && !stillNeed) $("ui").classList.add("faded");
    }, 5000);
  }

  function sheet(id) {
    for (const s of ["looks", "settings"]) $(s).hidden = s !== id || !$(s).hidden;
    if (id === "settings") rateNote();
    scheduleFade();
  }

  function wake() {
    $("ui").classList.remove("faded");
    scheduleFade();
  }

  function buildUi() {
    $("ui").hidden = false;
    buildLooks();
    rateChips();
    syncChips();
    $("looksBtn").addEventListener("click", () => sheet("looks"));
    $("setBtn").addEventListener("click", () => sheet("settings"));
    $("closeBtn").addEventListener("click", () => (A ? A.close() : history.back()));
    $("prevLook").addEventListener("click", () => step(-1));
    $("nextLook").addEventListener("click", () => step(1));
    $("allow").addEventListener("click", () => A && A.source(prefs.source));
    $("wallBtn").addEventListener("click", () => {
      if (!current) return;
      save("wall", current.id);
      label();
      if (A) A.wallpaper(current.id);
    });
    for (const b of document.querySelectorAll("[data-key]")) b.addEventListener("click", () => A && A.key(b.dataset.key));
    for (const box of document.querySelectorAll(".chips")) {
      box.addEventListener("click", (e) => {
        const b = e.target.closest("button");
        if (!b) return;
        const k = box.dataset.pref;
        if (k === "source") { prefs.source = b.dataset.v; if (A) A.source(b.dataset.v); }
        else save(k, b.dataset.v);
        if (k === scaleKey) scale();
        if (k === fpsKey) pace();
        if (k === "sensitivity") applySensitivity();
        syncChips();
        status();
      });
    }
    const sensSlider = $("sensSlider");
    if (sensSlider) {
      const onSens = (persist) => {
        const v = String(Number(sensSlider.value) || 1);
        prefs.sensitivity = v;
        if (persist) save("sensitivity", v);
        syncChips();
      };
      sensSlider.addEventListener("input", () => onSens(false));
      sensSlider.addEventListener("change", () => onSens(true));
    }
    // a tap on the look shows the controls or hides them; a swipe changes look
    let down = null;
    $("stage").addEventListener("pointerdown", (e) => { down = { x: e.clientX, y: e.clientY, t: performance.now() }; });
    $("stage").addEventListener("pointerup", (e) => {
      if (!down) return;
      const dx = e.clientX - down.x, dy = e.clientY - down.y;
      down = null;
      if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.5) { step(dx < 0 ? 1 : -1); wake(); return; }
      if (Math.abs(dx) < 12 && Math.abs(dy) < 12) {
        const ui = $("ui");
        if (ui.classList.contains("faded")) wake();
        else { ui.classList.add("faded"); sheet(null); }
      }
    });
    for (const el of [$("ui")]) el.addEventListener("pointerdown", wake, true);
    wake();
  }

  window.host = {
    pause,
    resume,
    note,
    prefsChanged() {
      const old = prefs;
      prefs = readPrefs();
      scale();
      applySensitivity();
      if (presets.length && prefs[presetKey] && prefs[presetKey] !== old[presetKey]) show(prefs[presetKey]);
      if (!wall) { syncChips(); label(); status(); }
    },
    sourceChanged() {
      prefs = readPrefs();
      if (!wall) { syncChips(); status(); }
    },
  };

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") pause();
    else if (paused) resume();
  });

  async function boot() {
    document.body.classList.add(wall ? "wallpaper" : "player");
    prefs = readPrefs();
    scale();
    applySensitivity();
    try {
      for (const look of await ForgeViz.catalog()) {
        engines[look.id] = look.engine;
        shares[look.id] = look.scale;
      }
      presets = await ForgeViz.presets();
    } catch (e) {
      if (!wall) { $("ui").hidden = false; note(`The visualiser looks are missing from this app: ${e.message}`); }
      return;
    }
    if (!wall) buildUi();
    await show(prefs[presetKey]);
    if (!paused) resume();
  }
  boot();
})();
