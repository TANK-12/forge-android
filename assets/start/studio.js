// Forge for Android 2 — Hardware & Battery Auto-Adaptation, Widget Maker,
// App Customiser, Media Quick-Shelf, and Event-Driven Lifecycle (Relay task 1592).
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);

  const APP_CATEGORIES = {
    media: ["media", "music", "youtube", "deck"],
    create: ["midi", "loops", "palimpsest", "prompts"],
    study: ["bible", "devotion", "kotoba", "recall", "reader", "shelf", "atlas"],
    tools: ["ai", "focus", "mind", "mint", "notes", "pdf", "reps", "tokens"],
  };

  const SHORTCUT_CHOICES = [
    ["music", "🎵 Music"],
    ["youtube", "▶️ YouTube"],
    ["media", "🎧 Media suite"],
    ["deck", "🕰️ Desk Deck"],
    ["visualiser", "🌈 Visualiser"],
    ["relay", "⚡ Relay"],
    ["notes", "📝 Notes"],
    ["focus", "⏱ Focus"],
    ["ai", "✨ Local AI"],
    ["studio", "🎛 Studio"],
  ];

  const PALETTE_SWATCHES = [
    { label: "Mint", bg: "#121826", accent: "#7cc8a4", text: "#eef1f8" },
    { label: "Ember", bg: "#1a1315", accent: "#f28c6b", text: "#f9ece8" },
    { label: "Cobalt", bg: "#101729", accent: "#6ea8fe", text: "#ebf1ff" },
    { label: "Violet", bg: "#161226", accent: "#a78bfa", text: "#f1ecff" },
    { label: "Amber", bg: "#1b1710", accent: "#f0b45b", text: "#fbf3e4" },
    { label: "Rose", bg: "#1e121a", accent: "#f472b6", text: "#fdf0f7" },
    { label: "Slate", bg: "#141820", accent: "#94a3b8", text: "#f1f5f9" },
    { label: "OLED", bg: "#000000", accent: "#7cc8a4", text: "#ffffff" },
  ];

  const HOME_SECTION_LABELS = [
    ["hero", "Clock & greeting"],
    ["media", "Now playing & queue"],
    ["media_shelf", "Media, YouTube & Music shelf"],
    ["relay", "Relay & computers"],
    ["pins", "Pinned apps"],
    ["features", "Visualiser, Studio & Wallpaper"],
    ["hardware", "Device & battery glance"],
  ];

  const DEFAULT_DESIGN = {
    id: "cw-media-queue",
    name: "Media + Up Next",
    size: "card",
    layout: "split",
    primary: "media",
    secondary: "next_up",
    data_source: "auto",
    show_header: true,
    show_controls: true,
    theme_mode: "auto",
    bg_color: "#131826",
    accent_color: "#7cc8a4",
    text_color: "#eef1f8",
    opacity: 92,
    radius_dp: 20,
    text_scale: "normal",
    shortcuts: ["music", "youtube", "media", "visualiser"],
    note_title: "Today",
    note_body: "Ship clean work\nStay light on battery",
  };

  let studioData = {
    designs: [structuredClone(DEFAULT_DESIGN)],
    presets: [],
    placed_custom: [],
    app_custom: {
      quality_mode: "auto",
      surface_style: "auto",
      font_scale: "normal",
      density: "comfortable",
      corners: "rounded",
      nav_mode: "auto",
      app_cols: "auto",
      accent_override: "",
      bg_override: "",
      pause_below_pct: 15,
      home_sections: ["hero", "media", "media_shelf", "relay", "pins", "features", "hardware"],
    },
    media: { title: "", sub: "", source: "", playing: false, queue: [] },
    pin_supported: true,
  };

  let currentDesign = structuredClone(DEFAULT_DESIGN);
  let editingWidgetId = -1;
  let lastHardware = null;
  let activeAppCategory = "all";

  function detectWebGlDetails() {
    const out = { webgl: false, webgl2: false, renderer: "Unknown GPU" };
    try {
      const c2 = document.createElement("canvas");
      const g2 = c2.getContext("webgl2");
      if (g2) {
        out.webgl2 = true;
        out.webgl = true;
        const dbg = g2.getExtension("WEBGL_debug_renderer_info");
        if (dbg) out.renderer = String(g2.getParameter(dbg.UNMASKED_RENDERER_WEBGL) || out.renderer);
        g2.getExtension("WEBGL_lose_context")?.loseContext();
        return out;
      }
      const g1 = c2.getContext("webgl");
      if (g1) {
        out.webgl = true;
        const dbg = g1.getExtension("WEBGL_debug_renderer_info");
        if (dbg) out.renderer = String(g1.getParameter(dbg.UNMASKED_RENDERER_WEBGL) || out.renderer);
        g1.getExtension("WEBGL_lose_context")?.loseContext();
      }
    } catch { /* headless fallback */ }
    return out;
  }
  const glFacts = detectWebGlDetails();

  // Apply App Customisation + Hardware/Battery Adaptive Rules to the live DOM
  function applyCustomAndHardware() {
    const root = document.documentElement;
    const cl = root.classList;
    const cfg = studioData.app_custom || {};
    const hw = lastHardware || {};
    const ad = hw.adaptive || {};

    for (const f of ["compact", "normal", "large", "xl"]) {
      cl.toggle(`font-${f}`, (cfg.font_scale || "normal") === f);
    }
    cl.toggle("density-compact", cfg.density === "compact");
    for (const c of ["sharp", "rounded", "pill"]) {
      cl.toggle(`corners-${c}`, (cfg.corners || "rounded") === c);
    }
    cl.toggle("nav-rail", cfg.nav_mode === "rail");
    cl.toggle("nav-dock", cfg.nav_mode === "dock");
    for (const col of ["2", "3", "4", "list"]) {
      cl.toggle(`apps-cols-${col}`, cfg.app_cols === col);
    }

    // Quality & battery adaptation
    const pauseThreshold = Number(cfg.pause_below_pct) || 15;
    const lowBatteryUnplugged = hw.battery !== undefined && hw.battery <= pauseThreshold && !hw.charging;
    const wantMode = cfg.quality_mode || "auto";
    const effectiveSaver = wantMode === "saver"
      || (wantMode === "auto" && (ad.quality_mode === "saver" || lowBatteryUnplugged || hw.power_save));
    const effectiveLite = effectiveSaver || wantMode === "balanced"
      || (wantMode === "auto" && (ad.quality_mode === "lite" || ad.glass_blur === false));

    cl.toggle("eco-saver", Boolean(effectiveSaver));

    const surf = cfg.surface_style || "auto";
    cl.toggle("surface-oled", surf === "oled" || (surf === "auto" && lowBatteryUnplugged));
    cl.toggle("surface-solid", surf === "solid" || (surf === "auto" && effectiveLite && !lowBatteryUnplugged));
    cl.toggle("surface-glass", surf === "glass");

    // Accent / background custom overrides
    if (/^#[0-9a-f]{6}$/i.test(cfg.accent_override || "")) {
      root.style.setProperty("--live", cfg.accent_override);
      if (typeof light === "function") {
        root.style.setProperty("--on-live", light(cfg.accent_override) ? "#0b0d12" : "#ffffff");
      }
    }
    if (/^#[0-9a-f]{6}$/i.test(cfg.bg_override || "") && surf !== "oled") {
      root.style.setProperty("--bg", cfg.bg_override);
    }

    // Scale or pause background particles based on battery, visibility & hardware
    if (window.FORGE_THEME && typeof Particles !== "undefined") {
      const hidden = document.hidden;
      const baseFps = ad.target_fps !== undefined ? ad.target_fps : 30;
      window.FORGE_THEME.animations = !hidden && !effectiveSaver && baseFps > 0;
      window.FORGE_THEME.particle_fps = hidden || effectiveSaver ? 0 : (wantMode === "full" ? 60 : Math.min(45, baseFps || 30));
      if (wantMode === "auto" && ad.particle_scale !== undefined) {
        window.FORGE_THEME.particle_amount = Math.max(0, Number(ad.particle_scale));
      }
      Particles.apply();
    }

    applyHomeSectionOrder();
    renderHardwareInspector();
  }

  function applyHomeSectionOrder() {
    const bento = $("home-bento");
    if (!bento) return;
    const enabled = new Set(studioData.app_custom.home_sections || HOME_SECTION_LABELS.map((x) => x[0]));
    const order = studioData.app_custom.home_sections || [];
    const req = location.hash.slice(1);
    const knownPages = ["home", "apps", "studio", "wallpaper", "computers", "settings"];
    const onHome = !knownPages.includes(req) || req === "home";
    bento.hidden = !onHome;
    for (const [key] of HOME_SECTION_LABELS) {
      const sec = document.querySelector(`[data-home-sec="${key}"]`);
      if (!sec) continue;
      sec.hidden = !enabled.has(key);
      const idx = order.indexOf(key);
      sec.style.order = String(idx >= 0 ? idx : 99);
    }
  }

  function renderHardwareInspector() {
    const hw = lastHardware || {};
    const ad = hw.adaptive || {};
    const scr = hw.screen || {};
    const gpu = hw.gpu || {};
    const cpu = hw.cpu || {};
    const aud = hw.audio || {};
    const sen = hw.sensors || {};
    const cap = hw.capabilities || {};

    const formWord = ad.form === "large_tablet" ? "Large tablet"
      : ad.form === "foldable" ? "Foldable / compact tablet"
      : (hw.tablet || (window.innerWidth >= 760)) ? "Tablet" : "Phone";
    const batWord = hw.battery !== undefined
      ? `${hw.battery}%${hw.charging ? " charging" : ""}`
      : "Battery ready";
    const modeWord = ad.quality_mode ? ad.quality_mode[0].toUpperCase() + ad.quality_mode.slice(1) : "Adaptive";

    const pill = $("home-hw-pill");
    if (pill) {
      const bEl = document.createElement("b");
      bEl.textContent = `${formWord} · ${modeWord}`;
      const sEl = document.createElement("span");
      sEl.textContent = `${batWord}${hw.power_save ? " · Saver" : ""}`;
      pill.replaceChildren(bEl, " ", sEl);
    }
    const homeGlance = $("home-hw-summary");
    if (homeGlance) {
      const reasonLine = Array.isArray(ad.reasons) && ad.reasons.length
        ? ad.reasons.join(" · ")
        : `${hw.memory_gb || "—"} GB RAM · ${hw.cores || navigator.hardwareConcurrency || "—"} cores · ${glFacts.webgl2 ? "WebGL 2" : "2D canvas"}`;
      homeGlance.textContent = reasonLine;
    }

    const grid = $("hw-specs-grid");
    if (!grid) return;
    const specs = [
      ["Form & panes", `${formWord} (${ad.panes || (window.innerWidth >= 820 ? 2 : 1)}-pane)`],
      ["Screen", scr.width_dp ? `${scr.width_dp}×${scr.height_dp} dp · ${scr.density_dpi} dpi · ${scr.max_refresh_hz || 60} Hz` : `${window.innerWidth}×${window.innerHeight} px`],
      ["Memory", hw.memory_gb ? `${hw.memory_gb} GB (${hw.avail_gb || "—"} GB free)${hw.low_ram ? " · Low-RAM" : ""}` : `${navigator.deviceMemory || "—"} GB`],
      ["Processor", `${cpu.cores || hw.cores || navigator.hardwareConcurrency || "—"} cores${cpu.abi ? " · " + cpu.abi : ""}${cpu.max_mhz ? " · " + cpu.max_mhz + " MHz" : ""}`],
      ["Graphics", `GLES ${gpu.gles || "3.0"} · ${glFacts.webgl2 ? "WebGL 2" : glFacts.webgl ? "WebGL 1" : "Canvas 2D"}${gpu.vulkan ? " · Vulkan" : ""}`],
      ["Battery & heat", `${batWord}${hw.battery_temp_c ? " · " + hw.battery_temp_c + "°C" : ""} · ${hw.thermal_word || "cool"}`],
      ["Audio capture", aud.microphone === false ? "No mic" : `Visualizer ${aud.output_capture ? "ready" : "mic fallback"} · Mic ${aud.mic_permission ? "allowed" : "ask on play"}`],
      ["Sensors & launcher", `${[sen.accelerometer && "accel", sen.gyroscope && "gyro", sen.light && "light", sen.hinge && "hinge"].filter(Boolean).join(", ") || "standard"} · Pin ${cap.widget_pin !== false ? "yes" : "manual"}`],
    ];
    grid.replaceChildren(...specs.map(([label, val]) => {
      const box = document.createElement("div");
      box.className = "hw-spec";
      const s = document.createElement("small");
      s.textContent = label;
      const b = document.createElement("strong");
      b.textContent = val;
      box.append(s, b);
      return box;
    }));

    const reasonsList = $("hw-adaptive-reasons");
    if (reasonsList) {
      const items = Array.isArray(ad.reasons) && ad.reasons.length
        ? ad.reasons
        : ["Event-driven battery and visibility listeners active (no background polling)."];
      reasonsList.replaceChildren(...items.map((r) => {
        const li = document.createElement("li");
        li.textContent = r;
        return li;
      }));
    }
  }

  // Render Home Now Playing & Queue card
  function renderHomeMedia() {
    const m = studioData.media || {};
    const titleEl = $("home-media-title");
    const subEl = $("home-media-sub");
    const nextEl = $("home-media-next");
    if (!titleEl) return;
    titleEl.textContent = m.title || "Ready for music, podcasts, radio or YouTube";
    subEl.textContent = [m.sub, m.source, m.playing ? "Playing" : ""].filter(Boolean).join(" · ")
      || "Open Music, Media or YouTube below — widgets update automatically.";
    const q = Array.isArray(m.queue) ? m.queue : [];
    const nu = m.next_up || q[0];
    if (nextEl) {
      nextEl.textContent = nu && nu.title ? `Next up: ${nu.title}${nu.sub ? " · " + nu.sub : ""}` : "";
      nextEl.hidden = !(nu && nu.title);
    }
  }

  // Sample text for live WYSIWYG widget preview in Studio
  function sampleBlockPreview(kind, d) {
    const m = studioData.media || {};
    if (kind === "clock") {
      const now = new Date();
      return {
        clock: now.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
        eyebrow: now.toLocaleDateString([], { weekday: "long", day: "numeric", month: "short" }),
        headline: "",
        lines: [],
      };
    }
    if (kind === "media") {
      return {
        eyebrow: (m.playing ? "Playing · " : "Media · ") + (m.source || "Forge Music"),
        headline: m.title || "Midnight Drift",
        lines: [m.sub || "Forge Ambient · Night Study"],
      };
    }
    if (kind === "next_up") {
      const nu = m.next_up || (Array.isArray(m.queue) && m.queue[0]);
      return {
        eyebrow: d.data_source === "computer_relay" ? "Next in Relay" : "Next up",
        headline: nu && nu.title ? nu.title : (d.data_source === "computer_relay" ? "#1592 Forge for Android 2" : "Warm Rain Lo-Fi"),
        lines: [nu && nu.sub ? nu.sub : (d.data_source === "computer_relay" ? "1 running · 2 queued" : "Forge Ambient · Quiet Desk")],
      };
    }
    if (kind === "queue") {
      const q = Array.isArray(m.queue) && m.queue.length ? m.queue : [
        { title: "Warm Rain Lo-Fi" },
        { title: "Aurora Pulse" },
      ];
      return {
        eyebrow: d.data_source === "computer_relay" ? "Relay queue" : "Up next queue",
        headline: `1. ${q[0].title}`,
        lines: q.slice(1, 3).map((x, i) => `${i + 2}. ${x.title}`),
      };
    }
    if (kind === "jobs") {
      return {
        eyebrow: "Forge jobs · Laptop",
        headline: "2 running now",
        lines: ["0 blocked · 3 waiting"],
      };
    }
    if (kind === "hardware") {
      const hw = lastHardware || {};
      const bat = hw.battery !== undefined ? `${hw.battery}%${hw.charging ? " ⚡" : ""}` : "88%";
      const tierRaw = hw.adaptive?.tier || "strong";
      const tierWord = tierRaw[0].toUpperCase() + tierRaw.slice(1);
      return {
        eyebrow: `Device · ${tierWord}`,
        headline: `${bat} · ${hw.thermal_word || "cool"}`,
        lines: [`${hw.memory_gb || 8} GB RAM · ${hw.cores || 8} cores`],
      };
    }
    if (kind === "shortcuts") {
      return {
        eyebrow: "Quick launch",
        headline: (d.shortcuts || []).map((s) => SHORTCUT_CHOICES.find((c) => c[0] === s)?.[1] || s).join(" · "),
        lines: [],
      };
    }
    if (kind === "note") {
      const lines = String(d.note_body || "").split("\n").filter(Boolean);
      return {
        eyebrow: d.note_title || "Forge note",
        headline: lines[0] || "Custom note on your home screen",
        lines: lines.slice(1, 3),
      };
    }
    return { eyebrow: "", headline: "", lines: [] };
  }

  function hexToRgba(hex, alphaPct) {
    const h = /^#([0-9a-f]{6})$/i.exec(hex || "#131826");
    const n = h ? parseInt(h[1], 16) : 0x131826;
    const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    return `rgba(${r}, ${g}, ${b}, ${Math.max(0, Math.min(100, alphaPct)) / 100})`;
  }

  function renderWidgetPreview() {
    const stage = $("studio-preview-box");
    if (!stage) return;
    const d = currentDesign;
    const theme = window.FORGE_THEME || {};
    const bgHex = d.theme_mode === "oled" ? "#000000"
      : d.theme_mode === "custom" ? d.bg_color
      : (theme.panel || "#131826");
    const accentHex = d.theme_mode === "auto" ? (theme.accent || "#7cc8a4") : d.accent_color;
    const textHex = d.theme_mode === "auto" ? (theme.text || "#eef1f8") : d.text_color;
    const scale = d.text_scale === "compact" ? 0.88 : d.text_scale === "large" ? 1.16 : 1;

    const card = document.createElement("div");
    card.className = `wy-widget size-${d.size}`;
    card.style.background = hexToRgba(bgHex, d.opacity);
    card.style.borderColor = hexToRgba(accentHex, Math.min(85, Math.max(25, d.opacity * 0.6)));
    card.style.borderRadius = `${d.radius_dp}px`;
    card.style.color = textHex;

    const p = sampleBlockPreview(d.primary, d);
    if (d.size === "compact") {
      const left = document.createElement("div");
      const eb = document.createElement("div");
      eb.className = "wy-eyebrow";
      eb.style.color = accentHex;
      eb.textContent = p.eyebrow;
      const hl = document.createElement("div");
      hl.className = "wy-headline";
      hl.style.fontSize = `${Math.round(14 * scale)}px`;
      hl.textContent = p.clock || p.headline;
      left.append(eb, hl);
      card.appendChild(left);
      if (d.show_controls && d.primary === "media") {
        const ctrls = document.createElement("div");
        ctrls.className = "wy-controls";
        ctrls.innerHTML = `<span>⏮</span><span>⏯</span><span>⏸</span><span>⏭</span>`;
        card.appendChild(ctrls);
      }
      stage.replaceChildren(card);
      return;
    }

    if (d.show_header) {
      const head = document.createElement("div");
      head.className = "wy-head";
      const nm = document.createElement("span");
      nm.textContent = d.name || "Custom widget";
      const ed = document.createElement("span");
      ed.textContent = "✎";
      head.append(nm, ed);
      card.appendChild(head);
    }

    const sec = d.secondary && d.secondary !== "none" ? sampleBlockPreview(d.secondary, d) : null;
    const cols = document.createElement("div");
    cols.className = "wy-cols" + (d.layout === "split" && sec ? " split" : "");

    const makeCol = (blk) => {
      const col = document.createElement("div");
      if (blk.clock) {
        const ck = document.createElement("div");
        ck.className = "wy-clock";
        ck.style.fontSize = `${Math.round(30 * scale)}px`;
        ck.textContent = blk.clock;
        col.appendChild(ck);
      }
      if (blk.eyebrow) {
        const eb = document.createElement("div");
        eb.className = "wy-eyebrow";
        eb.style.color = accentHex;
        eb.textContent = blk.eyebrow;
        col.appendChild(eb);
      }
      if (blk.headline) {
        const hl = document.createElement("div");
        hl.className = "wy-headline";
        hl.style.fontSize = `${Math.round(15.5 * scale)}px`;
        hl.textContent = blk.headline;
        col.appendChild(hl);
      }
      for (const ln of blk.lines) {
        const l = document.createElement("div");
        l.className = "wy-line";
        l.textContent = ln;
        col.appendChild(l);
      }
      return col;
    };

    cols.appendChild(makeCol(p));
    if (sec) cols.appendChild(makeCol(sec));
    card.appendChild(cols);

    if (d.show_controls) {
      const ctrls = document.createElement("div");
      ctrls.className = "wy-controls";
      if (d.primary === "media" || d.secondary === "media") {
        ctrls.innerHTML = `<span>⏮</span><span>⏯</span><span>⏸</span><span>⏭</span>`;
      } else {
        const sc = (d.shortcuts || ["music", "youtube", "media", "notes"]).slice(0, 4);
        ctrls.replaceChildren(...sc.map((id, idx) => {
          const sp = document.createElement("span");
          if (idx === 0) sp.style.color = accentHex;
          sp.textContent = id[0].toUpperCase() + id.slice(1);
          return sp;
        }));
      }
      card.appendChild(ctrls);
    }

    stage.replaceChildren(card);
  }

  function escapeHtml(s) {
    return String(s || "").replace(/[&<>"']/g, (c) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[c]));
  }

  function syncMakerInputs() {
    const d = currentDesign;
    if (!$("wm-name")) return;
    $("wm-name").value = d.name || "";
    $("wm-size").value = d.size || "card";
    $("wm-layout").value = d.layout || "split";
    $("wm-primary").value = d.primary || "media";
    $("wm-secondary").value = d.secondary || "none";
    $("wm-source").value = d.data_source || "auto";
    $("wm-theme-mode").value = d.theme_mode || "auto";
    $("wm-bg").value = d.bg_color || "#131826";
    $("wm-accent").value = d.accent_color || "#7cc8a4";
    $("wm-text").value = d.text_color || "#eef1f8";
    $("wm-opacity").value = String(d.opacity ?? 92);
    $("wm-opacity-val").textContent = `${d.opacity ?? 92}%`;
    $("wm-radius").value = String(d.radius_dp ?? 20);
    $("wm-radius-val").textContent = `${d.radius_dp ?? 20}dp`;
    $("wm-scale").value = d.text_scale || "normal";
    $("wm-header").checked = d.show_header !== false;
    $("wm-controls").checked = d.show_controls !== false;
    $("wm-note-title").value = d.note_title || "";
    $("wm-note-body").value = d.note_body || "";
    $("wm-note-wrap").hidden = !(d.primary === "note" || d.secondary === "note");
    $("wm-colors-wrap").hidden = d.theme_mode !== "custom";

    const scBox = $("wm-shortcuts-box");
    if (scBox) {
      const picked = Array.isArray(d.shortcuts) ? d.shortcuts : [];
      scBox.replaceChildren(...SHORTCUT_CHOICES.map(([code, label]) => {
        const b = document.createElement("button");
        b.type = "button";
        const on = picked.includes(code);
        b.className = "cat-chip" + (on ? " on" : "");
        b.textContent = label;
        b.addEventListener("click", () => {
          let next = on ? picked.filter((x) => x !== code) : [...picked, code];
          if (next.length > 4) next = next.slice(next.length - 4);
          if (!next.length) next = [code];
          currentDesign.shortcuts = next;
          syncMakerInputs();
          renderWidgetPreview();
        });
        return b;
      }));
    }

    const designChips = $("studio-design-chips");
    if (designChips) {
      designChips.replaceChildren(...(studioData.designs || []).map((item) => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "cat-chip" + (item.id === d.id ? " on" : "");
        b.textContent = item.name;
        b.addEventListener("click", () => {
          currentDesign = structuredClone(item);
          editingWidgetId = -1;
          syncMakerInputs();
          renderWidgetPreview();
        });
        return b;
      }));
    }

    const placedBox = $("studio-placed-list");
    if (placedBox) {
      const placed = studioData.placed_custom || [];
      placedBox.hidden = !placed.length;
      if (placed.length) {
        placedBox.replaceChildren(...placed.map((p) => {
          const b = document.createElement("button");
          b.type = "button";
          b.className = "button quiet mini-btn";
          b.textContent = `Widget #${p.widget_id}: ${p.name} (${p.size}) · Edit`;
          b.addEventListener("click", () => {
            const found = (studioData.designs || []).find((x) => x.id === p.design_id);
            if (found) currentDesign = structuredClone(found);
            editingWidgetId = p.widget_id;
            syncMakerInputs();
            renderWidgetPreview();
            showStudioNotice(`Editing home-screen widget #${p.widget_id}. Saving updates it immediately.`);
          });
          return b;
        }));
      }
    }

    renderWidgetPreview();
  }

  function syncCustomiserInputs() {
    const c = studioData.app_custom || {};
    if (!$("ac-quality")) return;
    $("ac-quality").value = c.quality_mode || "auto";
    $("ac-surface").value = c.surface_style || "auto";
    $("ac-font").value = c.font_scale || "normal";
    $("ac-density").value = c.density || "comfortable";
    $("ac-corners").value = c.corners || "rounded";
    $("ac-nav").value = c.nav_mode || "auto";
    $("ac-cols").value = c.app_cols || "auto";
    $("ac-pause-pct").value = String(c.pause_below_pct ?? 15);
    $("ac-pause-val").textContent = `${c.pause_below_pct ?? 15}%`;
    if (c.accent_override) $("ac-accent").value = c.accent_override;
    if (c.bg_override) $("ac-bg").value = c.bg_override;

    const listBox = $("ac-home-order");
    if (!listBox) return;
    const enabled = Array.isArray(c.home_sections) ? [...c.home_sections] : HOME_SECTION_LABELS.map((x) => x[0]);
    const orderedKeys = [
      ...enabled.filter((k) => HOME_SECTION_LABELS.some((h) => h[0] === k)),
      ...HOME_SECTION_LABELS.map((h) => h[0]).filter((k) => !enabled.includes(k)),
    ];
    listBox.replaceChildren(...orderedKeys.map((key, idx) => {
      const labelText = HOME_SECTION_LABELS.find((h) => h[0] === key)?.[1] || key;
      const row = document.createElement("div");
      row.className = "sec-order-row";
      const lbl = document.createElement("label");
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = enabled.includes(key);
      cb.addEventListener("change", () => {
        const next = cb.checked
          ? [...enabled, key]
          : enabled.filter((x) => x !== key);
        if (!next.length) { cb.checked = true; return; }
        saveAppCustom({ home_sections: next });
      });
      const txt = document.createElement("span");
      txt.textContent = labelText;
      lbl.append(cb, txt);
      row.appendChild(lbl);

      if (enabled.includes(key)) {
        const eIdx = enabled.indexOf(key);
        if (eIdx > 0) {
          const up = document.createElement("button");
          up.type = "button";
          up.className = "button quiet mini-btn";
          up.textContent = "↑";
          up.addEventListener("click", () => {
            const copy = [...enabled];
            [copy[eIdx - 1], copy[eIdx]] = [copy[eIdx], copy[eIdx - 1]];
            saveAppCustom({ home_sections: copy });
          });
          row.appendChild(up);
        }
        if (eIdx < enabled.length - 1) {
          const dn = document.createElement("button");
          dn.type = "button";
          dn.className = "button quiet mini-btn";
          dn.textContent = "↓";
          dn.addEventListener("click", () => {
            const copy = [...enabled];
            [copy[eIdx + 1], copy[eIdx]] = [copy[eIdx], copy[eIdx + 1]];
            saveAppCustom({ home_sections: copy });
          });
          row.appendChild(dn);
        }
      }
      return row;
    }));
  }

  function showStudioNotice(msg) {
    const el = $("studio-status");
    if (!el) return;
    el.textContent = msg || "";
    el.hidden = !msg;
  }

  function persistLocalFallback() {
    try {
      localStorage.setItem("forge.android2.studio", JSON.stringify({
        designs: studioData.designs,
        app_custom: studioData.app_custom,
      }));
    } catch { /* ignore */ }
  }

  async function saveCurrentDesign(andPin = false) {
    currentDesign.name = ($("wm-name").value || "").trim() || "Custom widget";
    const q = new URLSearchParams({
      design: JSON.stringify(currentDesign),
      widget: String(editingWidgetId),
    });
    try {
      const res = await fetch(`/studio-save?${q}`, {
        cache: "no-store",
        headers: { "X-Forge-Studio": "1" },
      });
      if (res.ok) {
        const saved = await res.json();
        if (saved && saved.studio) studioData = saved.studio;
        if (saved && saved.design) currentDesign = saved.design;
      } else {
        throw new Error("local");
      }
    } catch {
      const idx = studioData.designs.findIndex((x) => x.id === currentDesign.id);
      if (idx >= 0) studioData.designs[idx] = structuredClone(currentDesign);
      else studioData.designs.unshift(structuredClone(currentDesign));
      persistLocalFallback();
    }
    syncMakerInputs();
    if (andPin) {
      location.href = `/studio-pin?${new URLSearchParams({ id: currentDesign.id, size: currentDesign.size })}`;
      showStudioNotice(studioData.pin_supported !== false
        ? `Sent "${currentDesign.name}" to Android's home screen picker.`
        : `Saved "${currentDesign.name}". Long-press your home screen → Widgets → Forge → Forge Custom (${currentDesign.size}).`);
    } else {
      showStudioNotice(`Saved "${currentDesign.name}". Placed home-screen widgets updated.`);
    }
  }

  async function saveAppCustom(patch) {
    studioData.app_custom = { ...(studioData.app_custom || {}), ...patch };
    persistLocalFallback();
    applyCustomAndHardware();
    syncCustomiserInputs();
    try {
      const q = new URLSearchParams({ custom: JSON.stringify(studioData.app_custom) });
      const res = await fetch(`/studio-custom?${q}`, {
        cache: "no-store",
        headers: { "X-Forge-Studio": "1" },
      });
      if (res.ok) {
        const got = await res.json();
        if (got && got.app_custom) studioData.app_custom = got.app_custom;
      }
    } catch { /* browser fixture */ }
  }

  async function loadStudio() {
    try {
      const localRaw = localStorage.getItem("forge.android2.studio");
      if (localRaw) {
        const parsed = JSON.parse(localRaw);
        if (Array.isArray(parsed.designs) && parsed.designs.length) studioData.designs = parsed.designs;
        if (parsed.app_custom) studioData.app_custom = { ...studioData.app_custom, ...parsed.app_custom };
      }
    } catch { /* ignore */ }

    try {
      const res = await fetch("/studio.json", { cache: "no-store" });
      if (res.ok) {
        const data = await res.json();
        if (data && Array.isArray(data.designs)) {
          studioData = { ...studioData, ...data };
        }
      }
    } catch { /* fixture fallback */ }

    // Check if opened from a widget's ✎ edit button (?edit=cw-...&widget=12)
    const search = new URLSearchParams(location.search);
    const editId = search.get("edit");
    const wid = Number(search.get("widget") || -1);
    if (editId) {
      const found = (studioData.designs || []).find((x) => x.id === editId);
      if (found) currentDesign = structuredClone(found);
      if (wid > 0) editingWidgetId = wid;
      search.delete("edit");
      search.delete("widget");
      const cleanQs = search.toString();
      history.replaceState(null, "", `${location.pathname}${cleanQs ? "?" + cleanQs : ""}#studio`);
      location.hash = "studio";
      if (typeof showPage === "function") showPage();
    } else if (studioData.designs && studioData.designs.length) {
      const same = studioData.designs.find((x) => x.id === currentDesign.id);
      currentDesign = structuredClone(same || studioData.designs[0]);
    }

    if (!lastHardware || !lastHardware.adaptive) {
      try {
        const hr = await fetch("/forge-hardware.json", {
          cache: "no-store",
          headers: { "X-Forge-Hardware": "1" },
        });
        if (hr.ok) {
          const hp = await hr.json();
          if (hp && typeof hp === "object") lastHardware = hp;
        }
      } catch { /* browser fixture */ }
    }

    renderHomeMedia();
    syncMakerInputs();
    syncCustomiserInputs();
    applyCustomAndHardware();
  }

  function wireStudioEvents() {
    // Studio inner tabs (Widget Maker vs App Customiser)
    document.querySelectorAll("[data-studio-tab]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const target = btn.dataset.studioTab;
        document.querySelectorAll("[data-studio-tab]").forEach((b) =>
          b.setAttribute("aria-selected", String(b === btn)));
        const maker = $("studio-maker-pane");
        const cust = $("studio-custom-pane");
        if (maker) maker.hidden = target !== "maker";
        if (cust) cust.hidden = target !== "custom";
      });
    });

    // Swatches
    const swWrap = $("wm-swatches");
    if (swWrap) {
      swWrap.replaceChildren(...PALETTE_SWATCHES.map((sw) => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "swatch";
        b.title = sw.label;
        b.style.background = `linear-gradient(135deg, ${sw.accent} 45%, ${sw.bg} 46%)`;
        b.addEventListener("click", () => {
          currentDesign.theme_mode = sw.bg === "#000000" ? "oled" : "custom";
          currentDesign.bg_color = sw.bg;
          currentDesign.accent_color = sw.accent;
          currentDesign.text_color = sw.text;
          syncMakerInputs();
          renderWidgetPreview();
        });
        return b;
      }));
    }

    const bindField = (id, fn) => {
      const el = $(id);
      if (!el) return;
      el.addEventListener("input", () => { fn(el); renderWidgetPreview(); });
      el.addEventListener("change", () => { fn(el); syncMakerInputs(); });
    };
    bindField("wm-name", (el) => { currentDesign.name = el.value; });
    bindField("wm-size", (el) => {
      currentDesign.size = el.value;
      if (el.value === "compact") currentDesign.layout = "bar";
      else if (currentDesign.layout === "bar") currentDesign.layout = "split";
    });
    bindField("wm-layout", (el) => { currentDesign.layout = el.value; });
    bindField("wm-primary", (el) => { currentDesign.primary = el.value; });
    bindField("wm-secondary", (el) => { currentDesign.secondary = el.value; });
    bindField("wm-source", (el) => { currentDesign.data_source = el.value; });
    bindField("wm-theme-mode", (el) => { currentDesign.theme_mode = el.value; });
    bindField("wm-bg", (el) => { currentDesign.bg_color = el.value; });
    bindField("wm-accent", (el) => { currentDesign.accent_color = el.value; });
    bindField("wm-text", (el) => { currentDesign.text_color = el.value; });
    bindField("wm-opacity", (el) => {
      currentDesign.opacity = Number(el.value) || 0;
      $("wm-opacity-val").textContent = `${currentDesign.opacity}%`;
    });
    bindField("wm-radius", (el) => {
      currentDesign.radius_dp = Number(el.value) || 0;
      $("wm-radius-val").textContent = `${currentDesign.radius_dp}dp`;
    });
    bindField("wm-scale", (el) => { currentDesign.text_scale = el.value; });
    bindField("wm-header", (el) => { currentDesign.show_header = el.checked; });
    bindField("wm-controls", (el) => { currentDesign.show_controls = el.checked; });
    bindField("wm-note-title", (el) => { currentDesign.note_title = el.value; });
    bindField("wm-note-body", (el) => { currentDesign.note_body = el.value; });

    $("wm-new-btn")?.addEventListener("click", () => {
      currentDesign = {
        ...structuredClone(DEFAULT_DESIGN),
        id: "cw-" + Date.now().toString(36),
        name: "My Widget " + ((studioData.designs?.length || 0) + 1),
      };
      editingWidgetId = -1;
      syncMakerInputs();
      showStudioNotice("Started a new widget design. Customise it and tap Add to home screen.");
    });
    $("wm-save-btn")?.addEventListener("click", () => saveCurrentDesign(false));
    $("wm-pin-btn")?.addEventListener("click", () => saveCurrentDesign(true));
    $("wm-delete-btn")?.addEventListener("click", async () => {
      if ((studioData.designs || []).length <= 1) {
        showStudioNotice("Keep at least one widget template.");
        return;
      }
      const dropId = currentDesign.id;
      try {
        const res = await fetch(`/studio-delete?id=${encodeURIComponent(dropId)}`, {
          cache: "no-store",
          headers: { "X-Forge-Studio": "1" },
        });
        if (res.ok) {
          const got = await res.json();
          if (got && got.studio) studioData = got.studio;
        } else {
          throw new Error("local");
        }
      } catch {
        studioData.designs = studioData.designs.filter((x) => x.id !== dropId);
        persistLocalFallback();
      }
      currentDesign = structuredClone(studioData.designs[0]);
      editingWidgetId = -1;
      syncMakerInputs();
      showStudioNotice("Removed widget design.");
    });

    // App Customiser controls
    for (const [id, key] of [
      ["ac-quality", "quality_mode"],
      ["ac-surface", "surface_style"],
      ["ac-font", "font_scale"],
      ["ac-density", "density"],
      ["ac-corners", "corners"],
      ["ac-nav", "nav_mode"],
      ["ac-cols", "app_cols"],
    ]) {
      $(id)?.addEventListener("change", (e) => saveAppCustom({ [key]: e.target.value }));
    }
    $("ac-pause-pct")?.addEventListener("input", (e) => {
      $("ac-pause-val").textContent = `${e.target.value}%`;
    });
    $("ac-pause-pct")?.addEventListener("change", (e) => {
      saveAppCustom({ pause_below_pct: Number(e.target.value) || 15 });
    });
    $("ac-accent")?.addEventListener("change", (e) => saveAppCustom({ accent_override: e.target.value }));
    $("ac-bg")?.addEventListener("change", (e) => saveAppCustom({ bg_override: e.target.value }));
    $("ac-reset-colors")?.addEventListener("click", () => {
      saveAppCustom({ accent_override: "", bg_override: "" });
      if (typeof lookNow === "function") lookNow();
    });

    // Home Now Playing media transport buttons
    document.querySelectorAll("[data-home-mkey]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const k = btn.dataset.homeMkey;
        fetch(`/forge-media-key?key=${encodeURIComponent(k)}`, { headers: { "X-Forge-Media": "1" } })
          .then(() => setTimeout(loadStudio, 220))
          .catch(() => {});
      });
    });

    // App category filter bar
    const catBar = $("app-cat-bar");
    if (catBar) {
      catBar.addEventListener("click", (e) => {
        const b = e.target.closest("[data-cat]");
        if (!b) return;
        activeAppCategory = b.dataset.cat;
        catBar.querySelectorAll(".cat-chip").forEach((c) => c.classList.toggle("on", c === b));
        if (typeof renderApps === "function") renderApps();
      });
    }

    window.addEventListener("hashchange", applyHomeSectionOrder);
    document.addEventListener("visibilitychange", applyCustomAndHardware);
  }

  // Expose filter helper for start.js's renderApps()
  window.ForgeStudio = {
    matchesCategory(appName) {
      if (activeAppCategory === "all") return true;
      return (APP_CATEGORIES[activeAppCategory] || []).includes(appName);
    },
    syncHome: applyHomeSectionOrder,
    apply: applyCustomAndHardware,
    onHardware(prof) {
      lastHardware = prof || {};
      applyCustomAndHardware();
    },
    reload: loadStudio,
  };

  // Event-driven hardware callback invoked by Main.java HardwareProfile.Monitor
  window.forgeHardwareChanged = (prof) => {
    lastHardware = prof || {};
    if (typeof nameThings === "function" && lastHardware.tablet !== undefined) {
      nameThings(Boolean(lastHardware.tablet));
    }
    applyCustomAndHardware();
  };

  // Initial read from ForgeAndroid.profile() if running inside the APK
  try {
    if (window.ForgeAndroid && typeof window.ForgeAndroid.profile === "function") {
      lastHardware = JSON.parse(window.ForgeAndroid.profile()) || {};
    }
  } catch { lastHardware = {}; }

  wireStudioEvents();
  loadStudio();
})();
