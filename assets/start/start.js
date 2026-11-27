// Workspaces share the same local origins and saved app data as earlier builds.
const pages = ["home", "apps", "wallpaper", "computers", "settings"];
let localApps = [];
let homePins = null;
try { homePins = JSON.parse(localStorage.getItem("forge.android.pins")); } catch { /* default grid */ }
if (!Array.isArray(homePins)) homePins = null;
function showPage() {
  const requested = location.hash.slice(1);
  const page = pages.includes(requested) ? requested : "home";
  document.querySelectorAll("[data-pane]").forEach((pane) => {
    const available = pane.id !== "walls" || walls.themes.length || walls.looks.length;
    pane.hidden = pane.dataset.pane !== page || !available;
  });
  document.querySelectorAll("[data-page]").forEach((link) => {
    if (link.dataset.page === page) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  if (typeof wallPreview !== "undefined" && wallPreview) wallPreview.sync();
  document.title = `Forge · ${page[0].toUpperCase()}${page.slice(1)}`;
}
function renderApps() {
  const query = $("app-search").value.trim().toLowerCase();
  const matches = localApps.filter((app) => `${app.label} ${app.name} ${app.tagline}`.toLowerCase().includes(query));
  const pins = homePins === null ? localApps.slice(0, 6).map((app) => app.name) : homePins;
  $("apps").replaceChildren(...matches.map((app) => {
    const card = el("div", "launcher");
    const pin = el("button", "pin", pins.includes(app.name) ? "★" : "☆");
    pin.type = "button";
    pin.setAttribute("aria-label", `${pins.includes(app.name) ? "Unpin" : "Pin"} ${app.label || app.name} ${pins.includes(app.name) ? "from" : "to"} Home`);
    pin.setAttribute("aria-pressed", String(pins.includes(app.name)));
    pin.addEventListener("click", () => {
      homePins = pins.includes(app.name) ? pins.filter((name) => name !== app.name) : [...pins, app.name];
      try { localStorage.setItem("forge.android.pins", JSON.stringify(homePins)); } catch { /* keep this session */ }
      renderApps();
    });
    card.append(tile(app, `https://${app.name}.offline.forge/`), pin);
    return card;
  }));
  $("home-apps").replaceChildren(...localApps.filter((app) => pins.includes(app.name)).map((app) => tile(app, `https://${app.name}.offline.forge/`)));
  $("app-count").textContent = `${matches.length} of ${localApps.length} apps · saves stay on this device`;
  $("app-empty").hidden = matches.length > 0;
}
function homeClock() {
  const now = new Date();
  $("home-clock").textContent = now.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  $("home-date").textContent = now.toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" });
}
// The APK's start page: a top in the picked Forge theme, the Visualiser
// and Wallpaper and widgets one tap away, the paired computers side by side
// (Heat, Mirror, the XPS) with their apps, adding one (found on the Wi-Fi, a pairing link
// or typed codes), files shared to Forge waiting for a computer, the
// owner's Tailscale way, the apps bundled in the APK, and this Forge's
// version and updates. Main.java answers /computers.json, /hub.json?host=,
// /scan.json, /look.json, /update.json, /pair, /open, /forget, /send,
// /visualiser, /theme, /update-check, /update-install, /wall-live, /wall-still and
// /widget-add inside the app;
// nothing here reaches the network by itself.
const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
if (params.get("owner")) $("host").value = params.get("owner");
let sharing = 0;
let noun = "phone";

function plain(host) {
  return String(host || "").replace(/^\[|\]$/g, "");
}

function onComputer(url, host) {
  try {
    const u = new URL(url);
    return u.protocol === "https:" && plain(u.hostname) === host;
  } catch {
    return false;
  }
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function tile(app, href) {
  const a = el("a", "app");
  a.href = href;
  a.style.setProperty("--accent", app.accent || "#7cc8a4");
  a.append(el("span", "icon", app.icon || ""), el("strong", "", app.label || app.name), el("small", "", app.tagline || ""));
  return a;
}

function action(text, href, quiet = true) {
  const a = el("a", quiet ? "button quiet" : "button", text);
  a.href = href;
  return a;
}

function where(host, to) {
  const q = new URLSearchParams({ host });
  if (to) q.set("to", to);
  return `/open?${q}`;
}

// One computer's card: filled in when its own answer comes (each on its
// own, so an away computer never holds up the others).
function card(c) {
  const box = el("article", "computer");
  box.dataset.host = c.host;
  const head = el("header");
  const dot = el("span", "dot");
  head.append(dot, el("h3", "", c.name || c.host), el("small", "", c.name ? c.host : ""));
  const status = el("p", "muted", "Connecting...");
  const row = el("div", "row");
  const grid = el("div", "grid");
  box.append(head, status, row, grid);
  fetch(`/hub.json?${new URLSearchParams({ host: c.host })}`, { cache: "no-store" })
    .then((r) => r.json())
    .then((state) => fill(box, c, state, { dot, status, row, grid, head }))
    .catch(() => {
      dot.className = "dot away";
      status.textContent = "Could not ask the computer.";
    });
  return box;
}

function fill(box, c, state, parts) {
  const { dot, status, row, grid, head } = parts;
  if (state.moved_from && state.host) {
    // the router gave the computer a new address; Forge found it by its certificate
    c.host = state.host;
    box.dataset.host = c.host;
    head.querySelector("small").textContent = c.name ? c.host : "";
  }
  const hub = state.hub || {};
  const name = hub.computer || c.name;
  if (name) {
    head.querySelector("h3").textContent = name;
    head.querySelector("small").textContent = c.host;
  }
  const forget = action("Forget", `/forget?${new URLSearchParams({ host: c.host })}`);
  forget.classList.add("forget");
  forget.addEventListener("click", (e) => {
    if (!confirm(`Forget ${name || c.host}? This ${noun} has to pair with it again with a new code.`)) e.preventDefault();
  });
  if (state.signed_out) {
    dot.className = "dot away";
    status.textContent = `${name || "This computer"} no longer knows this ${noun}. Forget it here and pair again with a new code.`;
    row.append(forget);
    return;
  }
  if (state.error) {
    dot.className = "dot away";
    status.textContent = state.error;
    row.append(action("Open Forge", where(c.host), false), forget);
    return;
  }
  dot.className = "dot on";
  const may = hub.may || [];
  const apps = (hub.apps || []).filter((a) => onComputer(a.url, c.host));
  const running = apps.filter((a) => a.running).length;
  status.textContent = apps.length
    ? `${running} of ${apps.length} apps running there.`
    : "It offers no apps to this device yet.";
  if (state.moved_from) status.textContent += " Reconnected at its current address.";
  if (c.owner) {
    row.append(action("Relay & Chat", where(c.host, "relay"), false));
    row.append(action("Forge Apps", where(c.host, "apps")));
  } else {
    row.append(action("Open Forge", where(c.host), false));
  }
  if (may.includes("remote")) row.append(action("Remote", where(c.host, "remote")));
  if (may.includes("media")) row.append(action("Music, videos, pictures", where(c.host, "files")));
  if (sharing) {
    if (may.includes("send")) {
      const send = el("button", "", `Send ${sharing === 1 ? "the file" : `${sharing} files`} here`);
      send.type = "button";
      send.addEventListener("click", () => {
        for (const b of document.querySelectorAll(".computer button")) b.disabled = true;
        location.href = `/send?${new URLSearchParams({ host: c.host })}`;
      });
      row.append(send);
    } else {
      row.append(el("small", "", "Does not take files from this device yet"));
    }
  }
  row.append(forget);
  if (!may.length) {
    box.append(el("p", "note", `Its owner can let this ${noun} control what it plays, play its music, videos and pictures, or take files: Devices, Phones on that computer.`));
  }
  for (const app of apps) {
    const t = tile(app, app.url);
    if (!app.running) {
      t.classList.add("off");
      t.removeAttribute("href");
      t.setAttribute("aria-disabled", "true");
      t.querySelector("small").textContent = "Not running on the computer";
    }
    grid.append(t);
  }
}

// Petal on this device (Relay task 1292): Termux fetches the Android kit
// from the computer's Petal share (apps/petal/kit_share.py, port 47324).
function petalCommand(computers) {
  const lan = computers.map((c) => c.host).find((h) => h && !h.startsWith("127.") && h !== "localhost");
  const kit = `http://${lan || "computer-address"}:47324/Petal-Android.tar.gz`;
  $("petal-cmd").textContent = `pkg install -y python && curl -fsSO ${kit} && tar xzf Petal-Android.tar.gz && bash Petal/start-petal-termux.sh`;
}

$("petal-copy").addEventListener("click", () => {
  const text = $("petal-cmd").textContent;
  const done = () => ($("petal-copy").textContent = "Copied");
  (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject())
    .then(done)
    .catch(() => {
      getSelection().selectAllChildren($("petal-cmd"));
      if (document.execCommand("copy")) done();
    });
});

function load() {
  return fetch("/computers.json", { cache: "no-store" })
    .then((r) => r.json())
    .then((state) => {
      sharing = state.sharing || 0;
      if (sharing || params.has("pair_host")) location.hash = "computers";
      showShare();
      const list = $("computers");
      list.replaceChildren(...state.computers.map(card));
      petalCommand(state.computers);
      const qpcs = $("quick-computers");
      if (qpcs) {
        const ownerPcs = state.computers.filter((c) => c.owner);
        qpcs.hidden = !ownerPcs.length;
        if (ownerPcs.length) {
          $("quick-login-form").hidden = true;
          $("quick-home-note").textContent = "Pick Halo or Laptop to see Relay, chat, or use Forge:";
          qpcs.replaceChildren(
            ...ownerPcs.map((c) => action(`${c.name || c.host} · Relay`, where(c.host, "relay"), false)),
            ...ownerPcs.map((c) => action(`${c.name || c.host} · Apps`, where(c.host, "apps"), true)),
          );
        } else {
          $("quick-login-form").hidden = false;
        }
      }
      $("none").hidden = state.computers.length > 0;
      $("add").open = !state.computers.length || fromLink();
      $("add-title").textContent = state.computers.length ? "Add another computer" : "Add a computer";
    });
}

function fromLink() {
  let filled = false;
  for (const k of ["host", "check", "code"]) {
    const v = params.get(`pair_${k}`);
    if (v && !$(`pair-${k}`).value) {
      $(`pair-${k}`).value = v;
      filled = true;
    }
  }
  return filled;
}

function showShare(note) {
  $("share").hidden = !sharing && !note;
  $("share-line").textContent = note || (sharing ? `${sharing === 1 ? "A file" : `${sharing} files`} to send to a computer` : "");
  $("share-how").textContent = note ? "" : "Pick Send on a computer below.";
}

// A pairing link: forge-phone://pair?host=...&check=...&code=...
function fillFrom(text) {
  let url;
  try {
    url = new URL(String(text).trim());
  } catch {
    return false;
  }
  if (url.protocol !== "forge-phone:") return false;
  const q = url.searchParams;
  for (const k of ["host", "check", "code"]) if (q.get(k)) $(`pair-${k}`).value = q.get(k);
  return Boolean(q.get("host"));
}

function pairError(message) {
  if (message) location.hash = "computers";
  $("add").open = true;
  $("pair-error").textContent = message;
  $("pair-error").hidden = !message;
}

$("scan").addEventListener("click", () => {
  $("scan").disabled = true;
  $("scan-line").textContent = "Looking (a few seconds)...";
  $("found").replaceChildren();
  fetch("/scan.json", { cache: "no-store" })
    .then((r) => r.json())
    .then((r) => {
      const fresh = r.found.filter((f) => !f.paired);
      $("scan-line").textContent = r.error
        || (r.found.length
          ? `${r.found.length} with the phone link on${fresh.length < r.found.length ? `, ${r.found.length - fresh.length} already paired` : ""}.`
          : "None found. Check the phone link is on in Devices, Phones, or type the address.");
      for (const f of r.found) {
        const b = el("button", f.paired ? "quiet" : "", f.paired ? `${f.name || f.host} (paired)` : `Pair with ${f.host}`);
        b.type = "button";
        b.disabled = f.paired;
        b.addEventListener("click", () => {
          $("pair-host").value = f.host;
          $("pair-check").focus();
        });
        $("found").append(b);
      }
    })
    .catch(() => ($("scan-line").textContent = "The search did not finish."))
    .finally(() => ($("scan").disabled = false));
});
$("link").addEventListener("input", (e) => {
  if (fillFrom(e.target.value)) $("pair-name").focus();
});
$("pair-form").addEventListener("submit", () => {
  // the form's GET to /pair stays inside the app; Main.java answers below
  pairError("");
  $("pair-go").disabled = true;
  $("pair-go").textContent = "Pairing...";
});
window.forgeAnswer = (r) => {
  if (r.owner_login !== undefined) {
    for (const id of ["quick-go", "owner-go"]) {
      if ($(id)) { $(id).disabled = false; $(id).textContent = id === "quick-go" ? "Sign in & open Relay" : "Sign in"; }
    }
    if (!r.owner_login) {
      for (const id of ["quick-error", "owner-error"]) {
        if ($(id)) { $(id).textContent = r.error || "Sign-in failed."; $(id).hidden = false; }
      }
    } else {
      for (const id of ["quick-error", "owner-error"]) {
        if ($(id)) { $(id).hidden = true; $(id).textContent = ""; }
      }
      for (const id of ["quick-pass", "owner-pass", "quick-check", "owner-check"]) {
        if ($(id)) $(id).value = "";
      }
      load();
    }
    return;
  }
  if (r.update) {
    updateSaid(r);
    return;
  }
  if (r.resumed) {
    // back from the Visualiser, Wallpaper and widgets, Android's wallpaper screen or its installer
    lookNow();
    loadWalls();
    loadUpdate();
    return;
  }
  if (r.sending) {
    showShare(`Sending ${r.name} (${r.sending} of ${r.of})...`);
    return;
  }
  if (r.sent !== undefined) {
    sharing = r.ok ? 0 : Math.max(0, sharing - r.sent);
    load().then(() => showShare(r.ok ? r.sent === 1 ? "Sent the file. It is in Forge inbox in the computer's Downloads." : `Sent ${r.sent} files. They are in Forge inbox in the computer's Downloads.` : r.error));
    return;
  }
  $("pair-go").disabled = false;
  $("pair-go").textContent = "Pair";
  if (r.ok && r.forgot) {
    load();
  } else if (r.ok) {
    for (const k of ["host", "check", "code"]) params.delete(`pair_${k}`);
    history.replaceState(null, "", `/?${params}`);
    $("pair-form").reset();
    $("link").value = "";
    $("found").replaceChildren();
    $("scan-line").textContent = "";
    load();
  } else {
    pairError(r.error);
  }
};

fetch("/apps.json")
  .then((r) => r.json())
  .then((apps) => {
    localApps = apps;
    renderApps();
    showPage();
  });

function nameThings(tablet) {
  noun = tablet ? "tablet" : "phone";
  for (const n of document.querySelectorAll(".noun")) n.textContent = noun;
  $("pair-name").placeholder = tablet ? "My tablet" : "My phone";
}

// How strong this device is (shared/perf/device.json, read through the APK's
// ForgeAndroid bridge): the computer's phone hub uses the same answer to fold
// away apps too heavy for it.
if (window.ForgeDevice) {
  ForgeDevice.start("/device.json").then((d) => {
    const p = d.profile;
    nameThings(Boolean(p.tablet) || d.form === "tablet");
    const tier = d.registry.tiers[d.tier] || {};
    $("device-now").textContent = `${tier.label || d.tier}: ${d.auto ? `because ${d.why}` : "picked in Forge"}. ${tier.about || ""}`;
    const facts = [];
    if (p.memory_gb) facts.push(`${p.memory_gb} GB memory`);
    if (p.cores) facts.push(`${p.cores} processor threads`);
    if (p.battery !== undefined) facts.push(`battery ${p.battery}%${p.charging ? " charging" : ""}`);
    // Android's PowerManager thermal status, 0 (none) to 6 (shutdown)
    const heat = ["running cool", "slightly warm", "warm", "hot", "very hot", "very hot", "very hot"];
    if (p.thermal !== undefined) facts.push(heat[p.thermal] || "warm");
    if (p.power_save) facts.push("battery saver on");
    const form = (d.registry.forms[d.form] || {}).label || d.form;
    facts.push(/screen$/i.test(form) ? form.toLowerCase() : `${form.toLowerCase()} screen`);
    $("device-facts").textContent = facts.join(", ");
    showPage();
  }).catch(() => nameThings(false));
} else {
  nameThings(false);
}
load().then(() => {
  if (fromLink()) $("pair-name").focus();
});

function submitOwnerLogin(hostId, checkId, userId, passId, btnId, errId) {
  const err = $(errId);
  if (err) { err.hidden = true; err.textContent = ""; }
  const h = $(hostId).value.trim();
  const c = $(checkId) ? $(checkId).value.trim() : "";
  const u = $(userId).value.trim();
  const p = $(passId).value;
  if (!h || !u || !p) return;
  if (!window.ForgeAndroid || !ForgeAndroid.ownerSignIn) {
    if (err) { err.textContent = "Update Forge for Android to use quick sign-in."; err.hidden = false; }
    return;
  }
  $(btnId).disabled = true;
  $(btnId).textContent = "Signing in…";
  ForgeAndroid.ownerSignIn(h, u, p, c);
}

if ($("quick-login-form")) {
  $("quick-login-form").addEventListener("submit", (e) => {
    e.preventDefault();
    submitOwnerLogin("quick-host", "quick-check", "quick-user", "quick-pass", "quick-go", "quick-error");
  });
}
if ($("owner-signin-form")) {
  $("owner-signin-form").addEventListener("submit", (e) => {
    e.preventDefault();
    submitOwnerLogin("owner-host", "owner-check", "owner-user", "owner-pass", "owner-go", "owner-error");
  });
}
if ($("quick-scan")) {
  $("quick-scan").addEventListener("click", () => {
    $("quick-scan").disabled = true;
    $("quick-scan").textContent = "Scanning…";
    fetch("/scan.json", { cache: "no-store" })
      .then((r) => r.json())
      .then((r) => {
        if (r.found && r.found.length) {
          $("quick-host").value = r.found[0].host;
          if (!r.found[0].paired && $("quick-check")) $("quick-check").focus();
          else $("quick-user").focus();
        } else if ($("quick-error")) {
          $("quick-error").textContent = r.error || "No computer found on this Wi-Fi; enter its Tailscale address.";
          $("quick-error").hidden = false;
        }
      })
      .finally(() => {
        $("quick-scan").disabled = false;
        $("quick-scan").textContent = "Find on Wi-Fi";
      });
  });
}

// -- the top, the tiles and updates (Relay task 1316) ----------------------

function rgb(hex) {
  const n = parseInt(String(hex).slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function mix(a, b, t) {
  const x = rgb(a);
  const y = rgb(b);
  return `#${x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, "0")).join("")}`;
}

function light(hex) {
  const [r, g, b] = rgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.4;
}

// The theme picked in Wallpaper and widgets colours the whole page.
function applyLook(t) {
  const hex = /^#[0-9a-f]{6}$/i;
  if (!hex.test(t.bg || "") || !hex.test(t.accent || "")) return;
  const text = hex.test(t.text || "") ? t.text : light(t.bg) ? "#1b2130" : "#e8ebf2";
  const card = hex.test(t.panel || "") ? t.panel : t.bg;
  const root = document.documentElement.style;
  root.setProperty("--bg", t.bg);
  root.setProperty("--card", card === t.bg ? mix(t.bg, text, 0.05) : card);
  root.setProperty("--line", mix(card, text, 0.16));
  root.setProperty("--text", text);
  root.setProperty("--dim", mix(text, t.bg, 0.35));
  root.setProperty("--live", t.accent);
  root.setProperty("--live2", hex.test(t.accent2 || "") ? t.accent2 : mix(t.accent, text, 0.45));
  root.setProperty("--on-live", light(t.accent) ? "#0b0d12" : "#ffffff");
  root.setProperty("--wall-dim", String(Math.max(0, Math.min(1, t.dim ?? 0.12))));
  root.colorScheme = light(t.bg) ? "light" : "dark";
  if (/^walls\/[a-z0-9._-]+\.(jpg|png|webp)$/.test(t.wall || "")) root.setProperty("--wall", `url("/${t.wall}")`);
  root.setProperty("--wall", t.wall === "home-picture" ? 'url("/home-picture")' : /^walls\/[a-z0-9._-]+\.(jpg|png|webp)$/.test(t.wall || "") ? `url("/${t.wall}")` : "none");
  window.FORGE_THEME = t;
  if (typeof Particles !== "undefined") Particles.apply();
  if (typeof walls !== "undefined" && walls.themes.length) showWalls();
  if (t.label) $("look-line").textContent = `${t.label} theme. Wallpaper, live wallpaper and widgets.`;
}

function greet() {
  const h = new Date().getHours();
  $("greet").textContent = h < 5 ? "Up late" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

function lookNow() {
  fetch("/look.json", { cache: "no-store" })
    .then((r) => r.json())
    .then(applyLook)
    .catch(() => {});
}
lookNow();
fetch("/visualiser/looks.json")
  .then((r) => r.json())
  .then((d) => {
    const looks = (Array.isArray(d) ? d : d.looks || []).map((l) => l.label).filter(Boolean);
    if (looks.length > 1) $("viz-line").textContent = `${looks.length} looks, from ${looks[0]} to ${looks[looks.length - 1]}. Plays to your music.`;
  })
  .catch(() => {});
greet();

// Wallpapers (task 1322): Forge's own picker. A theme or a visualiser look is
// used as the live wallpaper (it changes at once when that Forge wallpaper is
// already in use; otherwise Android asks once), or a theme's picture is set
// as a plain still wallpaper. Main.java does the setting.
let wallPreview;
const walls = { tab: "theme", themes: [], looks: [], now: {}, pick: { theme: "", visualiser: "" } };
const hexColour = /^#[0-9a-f]{6}$/i;

function wallCard(kind, item) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "wall";
  b.setAttribute("role", "option");
  b.setAttribute("aria-selected", String(walls.pick[kind] === item.id));
  const c = item.colours || {};
  if (kind === "theme" && /^walls\/[a-z0-9._-]+\.(jpg|png|webp)$/.test(item.wall || "")) {
    b.style.backgroundImage = `url("/${item.wall}")`;
  } else if (kind === "theme") {
    b.style.background = `linear-gradient(150deg, ${item.accent}, ${item.bg})`;
  } else if (hexColour.test(c.primary || "") && hexColour.test(c.background || "")) {
    const second = hexColour.test(c.secondary || "") ? c.secondary : c.primary;
    b.style.background = `radial-gradient(circle at 30% 75%, ${c.primary}, transparent 42%), radial-gradient(circle at 75% 30%, ${second}, transparent 50%), ${c.background}`;
  }
  const name = document.createElement("span");
  name.textContent = item.label || item.name || item.id;
  b.append(name);
  const on = walls.now.now === kind && (kind === "theme" ? walls.now.theme : walls.now.look) === item.id;
  if (on) {
    const tag = document.createElement("span");
    tag.className = "on";
    tag.textContent = "On now";
    b.append(tag);
  }
  b.addEventListener("click", () => {
    walls.pick[kind] = item.id;
    showWalls();
  });
  return b;
}

function wallButton(label, href, quiet) {
  const a = document.createElement("a");
  a.className = quiet ? "button quiet" : "button";
  a.href = href;
  a.textContent = label;
  return a;
}

function showWalls() {
  const kind = walls.tab;
  const list = kind === "theme" ? walls.themes : walls.looks;
  document.querySelectorAll(".tab").forEach((t) => t.setAttribute("aria-selected", String(t.dataset.tab === kind)));
  $("wall-grid").replaceChildren(...list.map((item) => wallCard(kind, item)));
  const n = walls.now;
  const themeName = (walls.themes.find((t) => t.id === n.theme) || {}).label || n.theme;
  const lookName = (walls.looks.find((l) => l.id === n.look) || {}).name || n.look;
  $("walls-now").textContent =
    n.now === "theme" ? `On now: Forge particles, ${themeName}`
      : n.now === "visualiser" ? `On now: the visualiser, ${lookName}`
        : n.now === "other" ? "On now: another app's live wallpaper"
          : n.now === "still" ? "On now: a still picture" : "";
  const id = walls.pick[kind];
  const item = list.find((x) => x.id === id);
  const actions = [];
  $("wall-preview-wrap").hidden = kind !== "theme" || !item;
  if (item && kind === "theme") {
    const current = window.FORGE_THEME || {};
    const previewLook = { ...current, ...item, particles: current.particle_kind && current.particle_kind !== "theme" ? current.particles : item.particles };
    if (!wallPreview && typeof Particles !== "undefined") wallPreview = Particles.preview($("wall-preview"), () => previewLook);
    if (wallPreview) { wallPreview.look = () => previewLook; wallPreview.apply(); }
    $("wall-preview-wrap").style.backgroundImage = /^walls\/[a-z0-9._-]+\.(jpg|png|webp)$/.test(item.wall || "") ? `url("/${item.wall}")` : "none";
    $("wall-preview-wrap").style.backgroundColor = item.bg || "var(--bg)";
    $("wall-preview-caption").textContent = `${item.label || item.id} · preview inside Forge`;
  }
  // task 1330: what this tablet's Android has for live wallpapers (Main.java,
  // LiveWall): its confirm screen, its list, the maker's wallpaper app, or none
  const way = n.live || "confirm";
  const name = kind === "theme" ? "Forge particles" : "Forge visualiser";
  if (item && n.allowed !== false) {
    const live = new URLSearchParams({ kind, id });
    const ours = n.now === kind;
    if (way !== "none" || ours) {
      actions.push(wallButton(ours ? "Use it now" : "Set as live wallpaper", `/wall-live?${live}`));
      if (!ours && n.result && way !== "none") actions.push(wallButton("Try another Android screen", `/wall-live?${live}&next=true`, true));
    } else if (kind === "visualiser") {
      actions.push(wallButton("Open the visualiser", "/visualiser"));
    }
    if (kind === "theme") {
      const quiet = way !== "none" || ours;
      for (const [where, label] of [["both", "Still: both screens"], ["home", "Still: home screen"], ["lock", "Still: lock screen"]]) {
        actions.push(wallButton(label, `/wall-still?${new URLSearchParams({ id, where })}`, quiet || where !== "both"));
      }
    }
    $("wall-note").textContent = ours
      ? "Changes at once: this Forge wallpaper is already on."
      : way === "none"
        ? kind === "theme"
          ? "Android has no live wallpaper picker Forge can open. Animation cannot be applied to the Android home screen without a working system picker. Still buttons apply a picture only."
          : "Android has no live wallpaper picker Forge can open. The visualiser can still animate inside Forge; that does not apply it to the Android home screen."
        : way === "app"
          ? `Only once: Forge opens ${n.live_app || "the tablet's wallpaper app"}, where you pick ${name}. After that Forge switches it with no questions. Exactly where to tap:`
          : way === "list"
            ? `Only once: Forge opens ${n.live_app || "the tablet's list of live wallpapers"}, where you pick ${name}. After that Forge switches it with no questions. Exactly where to tap:`
            : "Only once: Android shows the wallpaper to confirm. After that Forge switches it with no questions. Exactly where to tap:";
  } else {
    $("wall-note").textContent = n.allowed === false
      ? "Android blocks wallpaper changes for this profile. No still picture or live wallpaper was applied."
      : "Pick one to see what you can do with it.";
  }
  // task 1334: the steps in this tablet's own names (Main.java, LiveWall.Plan)
  const steps = item && n.now !== kind && way !== "none" ? (n.live_steps || {})[kind] || [] : [];
  $("wall-steps").replaceChildren(...steps.map((s) => el("li", "", s)));
  $("wall-steps").hidden = !steps.length;
  $("wall-actions").replaceChildren(...actions);
}

function loadWalls() {
  fetch("/walls.json", { cache: "no-store" })
    .then((r) => r.json())
    .then((n) => {
      walls.now = n;
      $("wall-result").textContent = n.result ? `Last attempt: ${n.result}` : "";
      $("wall-diagnostics").textContent = n.diagnostics || "No device details returned.";
      if (!walls.pick.theme) walls.pick.theme = n.theme || "";
      if (!walls.pick.visualiser) walls.pick.visualiser = n.look || "";
      if (n.pin === false) $("widgets-note").textContent = "This home screen adds widgets itself: long-press it, then Widgets, then Forge.";
      showWalls();
    })
    .catch(() => {});
}

Promise.all([
  fetch("/themes.json").then((r) => r.json()),
  fetch("/visualiser/presets.json").then((r) => r.json()),
])
  .then(([themes, looks]) => {
    walls.themes = (Array.isArray(themes) ? themes : []).filter((t) => t && t.id);
    walls.looks = (Array.isArray(looks) ? looks : looks.presets || []).filter((l) => l && l.id);
    showPage();
    loadWalls();
  })
  .catch(() => {});
document.querySelectorAll(".tab").forEach((t) =>
  t.addEventListener("click", () => {
    walls.tab = t.dataset.tab;
    showWalls();
  }),
);

let update = {};

function size(bytes) {
  return `${(bytes / 1048576).toFixed(1)} MB`;
}

function ago(ms) {
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return "just now";
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  return h < 36 ? `${h} h ago` : `${Math.round(h / 24)} days ago`;
}

function showUpdate(u) {
  update = u;
  const o = u.offer;
  $("hero-version").textContent = u.version ? `Version ${u.version}` : "";
  $("about-version").textContent = `Version ${u.version} (build ${u.code}).`;
  if (u.updated_at && Date.now() - u.updated_at < 3 * 86400000) {
    const from = u.updated_from ? ` from ${u.updated_from}` : "";
    $("hero-line").textContent = `Updated${from} to ${u.version}. Your computers and settings stayed.`;
  }
  $("offer").hidden = !o;
  $("update-go").hidden = !o;
  $("update-go").disabled = Boolean(u.fetching);
  $("update-check").disabled = Boolean(u.checking);
  $("update-notes").hidden = !(o && o.notes);
  if (o) {
    $("offer-line").textContent = `Forge ${o.version_name} is ready`;
    $("update-go").textContent = `Update to ${o.version_name}`;
    $("update-notes").textContent = o.notes || "";
    $("update-line").textContent = `Forge ${o.version_name} is ready from ${o.where} (${size(o.size)}).`;
  } else if (u.checking) {
    $("update-line").textContent = "Checking for updates...";
  } else {
    $("update-line").textContent = u.checked ? `Forge is up to date. Checked ${ago(u.checked)}.` : "Not checked for updates yet.";
  }
  const said = u.said || [];
  $("update-said").hidden = !said.length;
  $("said-list").replaceChildren(...said.map((line) => el("li", "", line)));
}

function loadUpdate() {
  return fetch("/update.json", { cache: "no-store" })
    .then((r) => r.json())
    .then(showUpdate)
    .catch(() => {});
}

// What Main.java says while it checks, downloads and hands the file over.
function updateSaid(r) {
  const line = $("update-line");
  const bar = $("update-bar");
  if (r.update !== "downloading") bar.hidden = true;
  if (r.update === "checking") {
    $("update-check").disabled = true;
    line.textContent = "Checking for updates...";
  } else if (r.update === "checked") {
    loadUpdate();
  } else if (r.update === "downloading") {
    $("update-go").disabled = true;
    bar.hidden = false;
    const pct = r.size ? Math.min(100, Math.round((100 * r.done) / r.size)) : 0;
    bar.firstElementChild.style.width = `${pct}%`;
    line.textContent = `Downloading: ${size(r.done)} of ${size(r.size)}.`;
  } else if (r.update === "allow") {
    $("update-go").disabled = false;
    line.textContent = "Android asks once: switch on Allow from this source, then come back. The update carries on by itself.";
  } else if (r.update === "handed" || r.update === "confirm") {
    line.textContent = "Android's installer is asking you to confirm the update.";
  } else if (r.update === "installed") {
    line.textContent = "Updated. Open Forge again to use the new version.";
  } else if (r.update === "failed") {
    $("update-go").disabled = false;
    $("update-check").disabled = false;
    line.textContent = r.error || "The update did not finish.";
  }
}

$("update-check").addEventListener("click", () => (location.href = "/update-check"));
$("update-go").addEventListener("click", () => (location.href = "/update-install"));
$("offer").addEventListener("click", (e) => {
  e.preventDefault();
  location.hash = "settings";
  showPage();
  $("about").scrollIntoView({ behavior: "smooth", block: "center" });
});
loadUpdate();

window.addEventListener("hashchange", () => { showPage(); window.scrollTo(0, 0); });
$("app-search").addEventListener("input", renderApps);
$("wall-copy").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("wall-diagnostics").textContent);
    $("wall-copy").textContent = "Copied";
  } catch {
    const selection = window.getSelection();
    selection.selectAllChildren($("wall-diagnostics"));
    $("wall-copy").textContent = document.execCommand("copy") ? "Copied" : "Select and copy the details";
  }
});
if (typeof Particles !== "undefined") {
  Particles.init();
  Particles.apply();
}
homeClock();
setInterval(() => { if (!document.hidden) { homeClock(); lookNow(); } }, 30000);
showPage();
