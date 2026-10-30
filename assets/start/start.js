// The APK's start page: the laptop address and the apps bundled in the APK.
const saved = new URLSearchParams(location.search).get("host");
if (saved) document.getElementById("host").value = saved;
fetch("/apps.json")
  .then((r) => r.json())
  .then((apps) => {
    const grid = document.getElementById("apps");
    for (const app of apps) {
      const a = document.createElement("a");
      a.className = "app";
      a.href = `https://${app.name}.offline.forge/`;
      a.style.setProperty("--accent", app.accent || "#7cc8a4");
      const icon = document.createElement("span");
      icon.className = "icon";
      icon.textContent = app.icon || "";
      const label = document.createElement("strong");
      label.textContent = app.label;
      const line = document.createElement("small");
      line.textContent = app.tagline || "";
      a.append(icon, label, line);
      grid.append(a);
    }
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
