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
