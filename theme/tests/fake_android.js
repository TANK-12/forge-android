// Test only (tests/check_page.py): a pretend Android side for the visualiser
// page, a synthetic beat and prefs from the query; never shipped in an APK.
(() => {
  const q = new URLSearchParams(location.search);
  const prefs = { preset: q.get("preset") || "", wall: q.get("preset") || "", source: "phone", fps: "60", scale: "1", wallFps: "30", wallScale: "0.75", mic: q.get("mic") !== "0" };
  const errs = [];
  // ?nogl=1: a screen without WebGL 2 (as the emulator without a GPU)
  if (q.get("nogl")) {
    const get = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (kind, ...rest) {
      return kind === "webgl2" ? null : get.call(this, kind, ...rest);
    };
  }
  window.addEventListener("error", (e) => errs.push(String(e.message)));
  const oe = console.error; console.error = (...a) => { errs.push(a.join(" ")); oe(...a); };
  const ow = console.warn; console.warn = (...a) => { errs.push("warn " + a.join(" ")); ow(...a); };
  let t0 = performance.now(), calls = 0;
  window.ForgeAndroid = {
    frame() {
      calls++;
      const t = (performance.now() - t0) / 1000, b = new Uint8Array(582);
      const ph = (t % 0.5) / 0.5;
      for (let i = 0; i < 64; i++) b[i] = Math.max(0, Math.min(255, 200 * Math.exp(-i / 22) * (0.6 + 0.4 * Math.sin(t * 3 + i * 0.3)) + (i < 8 ? 55 * (1 - ph) : 0)));
      for (let i = 0; i < 512; i++) b[64 + i] = 128 + Math.round(90 * Math.sin(i / 512 * Math.PI * 8 + t * 5) * Math.sin(i / 512 * Math.PI));
      b[576] = 150; b[577] = 200; b[578] = 120; b[579] = 70; b[580] = Math.round(255 * Math.max(0, 1 - ph / 0.6)); b[581] = 0;
      let s = ""; for (const x of b) s += String.fromCharCode(x);
      return btoa(s);
    },
    // ?fps=120: the rate the Android side allows (its Pacer), else 30
    fps() { return Number(q.get("fps")) || 30; }, why() { return ""; }, status() { return "Listening to this device's own sound (test)"; },
    prefs() { return JSON.stringify(prefs); }, set(k, v) { prefs[k] = v; }, key() {}, source(m) { prefs.source = m; }, wallpaper() {}, close() {},
  };
  window.__check = () => ({ errs, calls, canvas: !!document.querySelector("canvas"), name: (document.getElementById("name") || {}).textContent, tiles: document.querySelectorAll(".tile").length, off: document.querySelectorAll(".tile.off").length, note: (document.getElementById("note") || {}).textContent || "", rates: Array.from(document.querySelectorAll("#fpsChips button"), (b) => b.dataset.v).join(","), rateNote: (document.getElementById("rateNote") || {}).textContent || "", px: (() => { const c = document.querySelector("canvas"); return c && c.clientWidth ? +(c.width / (c.clientWidth * (window.devicePixelRatio || 1))).toFixed(3) : 0; })() });
  addEventListener("load", () => {
    const open = q.get("sheet");
    if (open) setTimeout(() => document.getElementById(open + "Btn").click(), 800);
    setTimeout(() => { const d = document.createElement("pre"); d.id = "check"; d.style.display = "none"; d.textContent = JSON.stringify(window.__check()); document.body.appendChild(d); }, 2500);
  });
})();
