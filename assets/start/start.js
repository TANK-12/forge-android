// The APK's start page: the paired computer and its apps (or the pairing
// form), the owner's Tailscale way, and the apps bundled in the APK.
// Main.java answers /hub.json, /pair, /open and /forget inside the app.
const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
if (params.get("owner")) $("host").value = params.get("owner");

function onHome(url, host) {
  try {
    const u = new URL(url);
    return u.protocol === "https:" && u.hostname.replace(/^\[|\]$/g, "") === host;
  } catch {
    return false;
  }
}

function tile(app, href) {
  const a = document.createElement("a");
  a.className = "app";
  a.href = href;
  a.style.setProperty("--accent", app.accent || "#7cc8a4");
  const icon = document.createElement("span");
  icon.className = "icon";
  icon.textContent = app.icon || "";
  const label = document.createElement("strong");
  label.textContent = app.label || app.name;
  const line = document.createElement("small");
  line.textContent = app.tagline || "";
  a.append(icon, label, line);
  return a;
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

function showPair(message) {
  $("home").hidden = true;
  $("pair").hidden = false;
  for (const k of ["host", "check", "code"]) {
    const v = params.get(`pair_${k}`);
    if (v && !$(`pair-${k}`).value) $(`pair-${k}`).value = v;
  }
  if (message) {
    $("pair-error").textContent = message;
    $("pair-error").hidden = false;
  }
}

function showHome(state) {
  $("pair").hidden = true;
  $("home").hidden = false;
  $("home-line").textContent = `Paired with the computer at ${state.host}.`;
  const grid = $("hub-apps");
  grid.replaceChildren();
  if (state.error) {
    $("home-error").textContent = state.error;
    $("home-error").hidden = false;
    return;
  }
  const apps = (state.hub && state.hub.apps) || [];
  for (const app of apps) {
    // only https addresses on the paired computer open here (Main.ours)
    if (!onHome(app.url, state.host)) continue;
    const t = tile(app, app.url);
    if (!app.running) {
      t.classList.add("off");
      t.querySelector("small").textContent = "Not running on the computer";
    }
    grid.append(t);
  }
  if (!apps.length) $("home-line").textContent += " It offers no apps to phones yet.";
}

function load() {
  return fetch("/hub.json", { cache: "no-store" })
    .then((r) => r.json())
    .then((state) => {
      if (!state.paired) return showPair();
      if (state.signed_out) {
        return showPair(`The computer at ${state.host} no longer knows this phone. Pair it again with a new code.`);
      }
      showHome(state);
    })
    .catch(() => showPair());
}

$("link").addEventListener("input", (e) => {
  if (fillFrom(e.target.value)) $("pair-name").focus();
});
$("pair-form").addEventListener("submit", () => {
  // the form's GET to /pair stays inside the app; Main.java answers below
  $("pair-error").hidden = true;
  $("pair-go").disabled = true;
  $("pair-go").textContent = "Pairing...";
});
$("home-forget").addEventListener("click", (e) => {
  if (!confirm("Forget this computer? The phone has to pair again with a new code.")) e.preventDefault();
});
window.forgeAnswer = (r) => {
  $("pair-go").disabled = false;
  $("pair-go").textContent = "Pair";
  if (r.ok) {
    for (const k of ["host", "check", "code"]) params.delete(`pair_${k}`);
    history.replaceState(null, "", `/?${params}`);
    $("pair-form").reset();
    $("link").value = "";
    load();
  } else {
    showPair(r.error);
  }
};
load();

fetch("/apps.json")
  .then((r) => r.json())
  .then((apps) => {
    const grid = $("apps");
    for (const app of apps) grid.append(tile(app, `https://${app.name}.offline.forge/`));
    $("local").hidden = !apps.length;
  });

// How strong this phone is (shared/perf/device.json, read through the APK's
// ForgeAndroid bridge): the laptop's phone hub uses the same answer to fold
// away apps too heavy for it.
if (window.ForgeDevice) {
  ForgeDevice.start("/device.json").then((d) => {
    const tier = d.registry.tiers[d.tier] || {};
    document.getElementById("device-now").textContent = `${tier.label || d.tier}: ${d.auto ? `because ${d.why}` : "picked in Forge"}. ${tier.about || ""}`;
    const p = d.profile, facts = [];
    if (p.memory_gb) facts.push(`${p.memory_gb} GB memory`);
    if (p.cores) facts.push(`${p.cores} processor threads`);
    if (p.battery !== undefined) facts.push(`battery ${p.battery}%${p.charging ? " charging" : ""}`);
    // Android's PowerManager thermal status, 0 (none) to 6 (shutdown)
    const heat = ["running cool", "slightly warm", "warm", "hot", "very hot", "very hot", "very hot"];
    if (p.thermal !== undefined) facts.push(heat[p.thermal] || "warm");
    if (p.power_save) facts.push("battery saver on");
    facts.push(`${(d.registry.forms[d.form] || {}).label || d.form} screen`);
    document.getElementById("device-facts").textContent = facts.join(", ");
    document.getElementById("device").hidden = false;
  }).catch(() => {});
}
