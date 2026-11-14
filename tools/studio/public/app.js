import { Viewer } from "/render.js";

// the token comes in the URL fragment (never sent to the server in the link), kept for this tab only
const fromHash = location.hash.slice(1);
if (/^[0-9a-f]{48}$/.test(fromHash)) { sessionStorage.setItem("studio", fromHash); history.replaceState(null, "", "/"); }
const TOKEN = sessionStorage.getItem("studio") || "";
const $ = (id) => document.getElementById(id);

async function api(path, body) {
  const r = await fetch(path, body === undefined ? { headers: { "x-studio-token": TOKEN } } : { method: "POST", headers: { "x-studio-token": TOKEN, "content-type": "application/json" }, body: JSON.stringify(body) });
  const ct = r.headers.get("content-type") || "";
  if (!ct.startsWith("application/json")) { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r; }
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
  return j;
}
const blobUrl = async (path) => URL.createObjectURL(await (await api(path)).blob());
const loadImg = (src) => new Promise((ok, no) => { const i = new Image(); i.onload = () => ok(i); i.onerror = () => no(new Error("image failed")); i.src = src; });
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; };

const KINDS = [["all", "All"], ["cape", "Capes"], ["hat", "Hats"], ["headband", "Headbands"], ["aura", "Auras"], ["shield", "Shields"]];
const RCOL = { legendary: "#F2A33A", epic: "#B27CFF", rare: "#4F9BFF", uncommon: "#5BD47A", common: "#AEB4BD" };
let items = [], tab = "all", current = null;
const sel = new Set();
const key = (it) => `${it.kind}:${it.id}`;

let viewer;
try { viewer = new Viewer($("view")); } catch (e) { $("empty").textContent = e.message; }

function renderTabs() {
  const t = $("tabs");
  t.replaceChildren();
  for (const [k, label] of KINDS) {
    const n = k === "all" ? items.length : items.filter((i) => i.kind === k).length;
    const b = el("button", k === tab ? "on" : "", `${label} ${n}`);
    b.onclick = () => { tab = k; renderTabs(); renderList(); };
    t.append(b);
  }
}

function renderList() {
  const l = $("list");
  l.replaceChildren();
  const shown = items.filter((i) => tab === "all" || i.kind === tab);
  if (!shown.length) l.append(el("div", "none", "Nothing here yet."));
  for (const it of shown) {
    const row = el("div", "item" + (current && key(current) === key(it) ? " cur" : ""));
    const cb = el("input"); cb.type = "checkbox"; cb.checked = sel.has(key(it));
    cb.onclick = (e) => { e.stopPropagation(); cb.checked ? sel.add(key(it)) : sel.delete(key(it)); updateBar(); };
    const thumb = el("div", "thumb");
    blobUrl(`/api/file/${it.png}`).then((u) => { const i = el("img"); i.src = u; thumb.append(i); }).catch(() => {});
    const txt = el("div", "txt");
    txt.append(el("div", "name", it.name || it.id));
    const sub = el("div", "sub");
    if (it.kind !== "cape") { const r = el("span", "rar", it.rarity || "common"); r.style.color = RCOL[it.rarity] || RCOL.common; sub.append(r, " · "); }
    sub.append(`${it.kind} · ${it.id}`);
    txt.append(sub);
    const changed = (pending.changed || []).some((f) => f.startsWith(it.kind === "cape" ? `capes/${it.id}.` : `cosmetics/${it.id}.`));
    row.append(cb, thumb, txt);
    if (changed) row.append(el("span", "badge", "not live"));
    row.onclick = () => select(it);
    l.append(row);
  }
  updateBar();
}

function updateBar() {
  $("selCount").textContent = `${sel.size} selected`;
  $("removeBtn").disabled = !sel.size;
}

let pending = { changed: [], removed: [] };
function renderPending() {
  const n = pending.changed.length + pending.removed.length;
  $("pending").textContent = n ? `${n} change${n === 1 ? "" : "s"} not published` : "Everything is published";
  $("pending").className = "pending" + (n ? " warn" : "");
}

async function refresh(data) {
  const d = data || (await api("/api/list"));
  items = d.items;
  pending = d.pending;
  for (const k of [...sel]) if (!items.some((i) => key(i) === k)) sel.delete(k);
  if (current && !items.some((i) => key(i) === key(current))) { current = null; viewer?.show(null); $("meta").hidden = true; $("empty").hidden = false; }
  renderTabs(); renderList(); renderPending();
}

async function select(it) {
  current = it;
  renderList();
  $("empty").hidden = true;
  const m = $("meta");
  m.hidden = false;
  $("mName").value = it.name || ""; $("mId").value = it.id; $("mDesc").value = it.desc || "";
  $("mRarity").value = it.rarity || "common"; $("mRarity").disabled = it.kind === "cape";
  $("mFrame").value = it.frametime || ""; $("mFrame").disabled = it.kind === "aura";
  try {
    const img = await loadImg(await blobUrl(`/api/file/${it.png}`));
    const json = it.model ? await (await fetch(`/api/file/${it.model}`, { headers: { "x-studio-token": TOKEN } })).json() : null;
    if (current !== it) return;
    viewer?.show({ kind: it.kind, img, json, frametime: it.frametime });
  } catch (e) { toast(`Can't show ${it.id}: ${e.message}`, true); }
}

$("saveBtn").onclick = async () => {
  if (!current) return;
  try {
    const d = await api("/api/update", { kind: current.kind, id: current.id, name: $("mName").value, desc: $("mDesc").value, rarity: $("mRarity").value, frametime: $("mFrame").value });
    toast(d.msg);
    const k = key(current);
    await refresh(d);
    current = items.find((i) => key(i) === k) || null;
    renderList();
  } catch (e) { toast(e.message, true); }
};

// ---------------- remove
$("removeBtn").onclick = () => {
  const names = items.filter((i) => sel.has(key(i))).map((i) => `${i.name} (${i.kind})`);
  $("removeText").textContent = `Remove ${names.length === 1 ? names[0] : `${names.length} items: ${names.join(", ")}`}?`;
  $("removeDlg").showModal();
};
$("removeGo").onclick = async (e) => {
  e.preventDefault();
  try {
    const d = await api("/api/remove", { items: items.filter((i) => sel.has(key(i))).map((i) => ({ kind: i.kind, id: i.id })) });
    sel.clear();
    $("removeDlg").close();
    toast(d.msg);
    await refresh(d);
  } catch (err) { toast(err.message, true); }
};

// ---------------- upload
let picked = [];
const HELP = {
  hat: "A Blockbench .bbmodel (start from tools/cosmetics/templates/hat.bbmodel), or a Java Block/Item .json export with its PNGs.",
  headband: "A Blockbench .bbmodel (template: tools/cosmetics/templates/headband.bbmodel), or a Java .json export with its PNGs.",
  shield: "A Blockbench .bbmodel (template: tools/cosmetics/templates/shield.bbmodel), or a Java .json export with its PNGs. It replaces the shield players hold.",
  aura: "The emitter .json and its sprite sheet .png (columns = frames, rows = variants). See backend/README.md.",
  cape: "One PNG: 64:32 (cape + elytra) or 22:17 (cape only), any scale; frames stacked vertically = animated.",
};
function guessKind() {
  if (picked.some((f) => /\.bbmodel$/i.test(f.name))) return;
  if (picked.length === 1 && /\.png$/i.test(picked[0].name)) $("uKind").value = "cape";
}
function renderFiles() {
  const fl = $("fileList");
  fl.replaceChildren(...picked.map((f) => el("span", "file", `${f.name} · ${Math.ceil(f.size / 1024)} KB`)));
  $("kindHelp").textContent = HELP[$("uKind").value];
  if (!$("uName").value && picked[0]) {
    const base = picked.find((f) => !/\.png$/i.test(f.name))?.name || picked[0].name;
    const stem = base.replace(/\.[^.]+$/, "");
    $("uName").value = stem.replace(/[_-]+/g, " ").replace(/\b\w/g, (c) => c.toUpperCase()).slice(0, 48);
    $("uId").value = stem.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").slice(0, 32);
  }
}
const addFiles = (list) => { picked = [...picked, ...list].slice(0, 16); guessKind(); renderFiles(); };
$("uploadBtn").onclick = () => { picked = []; $("uploadForm").reset(); $("uErr").textContent = ""; renderFiles(); $("uploadDlg").showModal(); };
$("files").onchange = (e) => addFiles([...e.target.files]);
$("uKind").onchange = renderFiles;
const drop = $("drop");
drop.ondragover = (e) => { e.preventDefault(); drop.classList.add("over"); };
drop.ondragleave = () => drop.classList.remove("over");
drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove("over"); addFiles([...e.dataTransfer.files]); };
const b64 = (f) => new Promise((ok, no) => { const r = new FileReader(); r.onload = () => ok(String(r.result).split(",")[1] || ""); r.onerror = no; r.readAsDataURL(f); });
$("uGo").onclick = async (e) => {
  e.preventDefault();
  $("uErr").textContent = "";
  if (!picked.length) { $("uErr").textContent = "Add the files first."; return; }
  if (!$("uploadForm").reportValidity()) return;
  $("uGo").disabled = true;
  try {
    const files = await Promise.all(picked.map(async (f) => ({ name: f.name, data: await b64(f) })));
    const d = await api("/api/upload", { kind: $("uKind").value, id: $("uId").value, name: $("uName").value, rarity: $("uRarity").value, desc: $("uDesc").value, frametime: $("uFrame").value, replace: $("uReplace").checked, files });
    $("uploadDlg").close();
    toast(d.msg);
    await refresh(d);
    const it = items.find((i) => i.id === $("uId").value);
    if (it) select(it);
  } catch (err) { $("uErr").textContent = err.message; }
  finally { $("uGo").disabled = false; }
};

// ---------------- publish
$("publishBtn").onclick = () => {
  const c = $("changes");
  c.replaceChildren();
  const all = [...pending.changed.map((f) => `+ ${f}`), ...pending.removed.map((f) => `− ${f}`)];
  c.append(el("div", "", all.length ? `${all.length} file change(s) since the last publish:` : "No changes since the last publish."));
  for (const f of all.slice(0, 40)) c.append(el("code", "", f));
  $("log").hidden = !$("log").textContent;
  $("publishDlg").showModal();
};
let polling = null;
async function poll() {
  const j = await api("/api/job");
  const lg = $("log");
  lg.hidden = false;
  lg.textContent = `${j.title}\n${j.log}`;
  lg.scrollTop = lg.scrollHeight;
  if (j.running) { polling = setTimeout(poll, 700); return; }
  polling = null;
  $("publishGo").disabled = false;
  toast(j.ok ? `${j.title}: done` : `${j.title} failed, see the log`, !j.ok);
  refresh();
}
$("publishGo").onclick = async () => {
  try {
    await api("/api/publish", { passphrase: $("pass").value, migrated: $("migrated").checked, confirm: $("confirmTxt").value.trim() });
    $("pass").value = ""; $("confirmTxt").value = ""; $("migrated").checked = false;
    $("publishGo").disabled = true;
    if (!polling) poll();
  } catch (e) { toast(e.message, true); }
};
$("migrateBtn").onclick = async () => {
  try {
    const { files } = await api("/api/migrations", {});
    if (!confirm("Migrations change the live database. Continue?")) return;
    const f = prompt(`Run which migration once on the live database?\n${files.join("\n")}\n(each one only once, before deploying)`, files[files.length - 1] || "");
    if (!f) return;
    await api("/api/migrate", { file: f.trim() });
    if (!polling) poll();
  } catch (e) { toast(e.message, true); }
};

// ---------------- toast
let tt;
function toast(msg, bad) {
  let t = document.querySelector(".toast");
  if (!t) { t = el("div", "toast"); document.body.append(t); }
  t.textContent = msg;
  t.classList.toggle("bad", !!bad);
  t.classList.add("on");
  clearTimeout(tt);
  tt = setTimeout(() => t.classList.remove("on"), 4200);
}

// ---------------- start
(async () => {
  if (!TOKEN) { $("empty").textContent = "Open the studio with the link it printed (it carries the access key)."; return; }
  try {
    const skin = await loadImg(await blobUrl("/api/skin"));
    viewer?.setSkin(skin);
  } catch { toast("No skin found: start the launcher once (or a game) so a skin is cached.", true); }
  try { await refresh(); } catch (e) { $("empty").textContent = e.message; }
})();
