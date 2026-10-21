"use strict";

const $ = (id) => document.getElementById(id);
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const state = { models: [] };
const dash = { monitor: null, samples: [], data: null, open: null };

async function api(path, options = {}) {
  const opts = { headers: { "content-type": "application/json" }, ...options };
  if (opts.body && typeof opts.body !== "string") opts.body = JSON.stringify(opts.body);
  let res;
  try {
    res = await fetch(path, opts);
  } catch (e) {
    throw new Error("Cannot reach the LLM Checker backend");
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* not json */ }
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data;
}

function toast(message) {
  const el = $("toast");
  el.textContent = message;
  el.classList.remove("hidden");
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.add("hidden"), 3500);
}

function setMsg(id, text, kind) {
  const el = $(id);
  el.textContent = text || "";
  el.className = "msg" + (kind ? " " + kind : "");
}

const fmt = {
  n(v, d = 1) { return v == null || !isFinite(v) ? "-" : Number(v).toLocaleString(undefined, { minimumFractionDigits: d, maximumFractionDigits: d }); },
  ms(v) { return v == null || !isFinite(v) ? "-" : v >= 10000 ? (v / 1000).toFixed(1) + " s" : v >= 1000 ? (v / 1000).toFixed(2) + " s" : v.toFixed(0) + " ms"; },
  pct(v) { return v == null ? "-" : (v * 100).toFixed(0) + "%"; },
};

function setPill(kind, text) {
  $("connPill").className = "conn-pill " + (kind || "");
  $("connText").textContent = text;
}

function ago(ts) {
  if (!ts) return "never";
  const s = Math.max(0, Math.round((Date.now() - ts) / 1000));
  return s < 60 ? `${s}s ago` : s < 3600 ? `${Math.floor(s / 60)}m ago` : s < 86400 ? `${Math.floor(s / 3600)}h ago` : `${Math.floor(s / 86400)}d ago`;
}

async function loadModels() {
  try {
    const res = await api("/api/models");
    state.models = res.models;
    setPill("ok", res.baseUrl || "Connected");
  } catch (err) {
    state.models = [];
    setPill("bad", "No vLLM server");
    $("modelTable").className = "errors";
    $("modelTable").textContent = err.message + " Set VLLM_BASE_URL and restart.";
  }
}

async function loadDashboard() {
  const minutes = $("dashWindow").value;
  try {
    const [data, samples, monitor] = await Promise.all([
      api("/api/dashboard?minutes=" + minutes),
      api("/api/monitor/samples?minutes=" + minutes),
      api("/api/monitor"),
    ]);
    dash.data = data;
    dash.samples = samples;
    dash.monitor = monitor;
    renderDashboard();
  } catch (err) {
    $("modelTable").className = "errors";
    $("modelTable").textContent = err.message;
  }
}

const speedOf = (m) => m.probe?.avgTps ?? null;
const responseOf = (m) => m.probe?.avgResponseMs ?? null;

function modelRows() {
  const byName = new Map((dash.data?.models || []).map((m) => [m.model, m]));
  for (const m of state.models) {
    if (!byName.has(m.id)) byName.set(m.id, { model: m.id, probe: null });
  }
  return [...byName.values()].sort((a, b) => {
    const sa = speedOf(a), sb = speedOf(b);
    if (sa == null && sb == null) return a.model.localeCompare(b.model);
    if (sa == null) return 1;
    if (sb == null) return -1;
    return sb - sa;
  });
}

function statusBadge(m) {
  const p = m.probe;
  if (!p) return '<span class="muted">no data</span>';
  if (p.failures === p.samples) return `<span class="badge fail" title="${esc(p.lastError || "")}">down</span>`;
  if (p.lastError) return `<span class="badge warn" title="${esc(p.lastError)}">degraded</span>`;
  return '<span class="badge pass">ok</span>';
}

function switchAdvice(row, rows) {
  const tps = speedOf(row);
  const ranked = rows.filter((r) => speedOf(r) != null);
  if (tps == null) return "No measurements yet. Press Probe now to see how this model is doing right now.";
  const best = ranked[0];
  if (ranked.length < 2) return "This is the only model with data. Probe the others to see whether a faster one is available.";
  if (best.model === row.model) {
    const next = ranked[1];
    const pct = ((tps - speedOf(next)) / speedOf(next)) * 100;
    if (pct < 5) return `Tied with ${next.model}. Either model is fine.`;
    return `Fastest model right now, about ${fmt.n(pct, 0)}% quicker than ${next.model}. No reason to switch.`;
  }
  const pct = ((speedOf(best) - tps) / tps) * 100;
  if (pct < 5) return `About the same speed as ${best.model}, the current fastest. Switching would not change much.`;
  const saved = responseOf(row) != null && responseOf(best) != null ? responseOf(row) - responseOf(best) : null;
  const sooner = saved != null && saved > 50 ? ` Replies arrive about ${fmt.ms(saved)} sooner.` : "";
  return `${best.model} is about ${fmt.n(pct, 0)}% faster at ${fmt.n(speedOf(best), 1)} tok/s.${sooner} Worth switching if the quality is acceptable.`;
}

function renderDashboard() {
  const rows = modelRows();
  const box = $("modelTable");
  renderMonitorPanel();
  if (!rows.length) {
    if (!box.classList.contains("errors")) {
      box.className = "muted";
      box.textContent = "No models. Set VLLM_BASE_URL to your vLLM server and restart.";
    }
    return;
  }
  if (dash.open && !rows.some((r) => r.model === dash.open)) dash.open = null;
  const ranked = rows.filter((r) => speedOf(r) != null);
  const lead = ranked.length > 1 ? (speedOf(ranked[0]) - speedOf(ranked[1])) / speedOf(ranked[1]) : 1;
  const fastest = ranked.length && lead >= 0.05 ? ranked[0].model : null;
  box.className = "";
  box.innerHTML = `<div class="table-wrap"><table><thead><tr>
      <th class="left">Model</th><th>Token/s</th><th>Response time</th><th>Status</th>
    </tr></thead><tbody>${rows.map((m) => modelRowsHtml(m, m.model === fastest, rows)).join("")}</tbody></table></div>`;
}

function modelRowsHtml(m, fastest, rows) {
  const open = dash.open === m.model;
  const main = `<tr class="model-row${open ? " open" : ""}" data-model="${esc(m.model)}">
      <td class="left"><strong>${esc(m.model)}</strong>${fastest ? ' <span class="badge pass tag">fastest</span>' : ""}</td>
      <td class="num">${fmt.n(speedOf(m), 1)}</td>
      <td class="num">${fmt.ms(responseOf(m))}</td>
      <td>${statusBadge(m)}</td></tr>`;
  if (!open) return main;
  const p = m.probe;
  const recent = dash.samples.filter((s) => s.model === m.model).slice(-5).reverse();
  const averages = p
    ? `Averages: ${fmt.n(p.avgTps, 1)} tok/s, response ${fmt.ms(p.avgResponseMs)}, first token ${fmt.ms(p.avgTtftMs)}, p95 response ${fmt.ms(p.p95ResponseMs)}, ${fmt.pct(p.successRate)} successful (${p.samples} checks).`
    : "No probe averages in this window yet.";
  const recentHtml = recent.length ? `<div class="table-wrap"><table><thead><tr>
      <th class="left">When</th><th>Prompt tokens</th><th>Output tokens</th><th>Token/s</th><th>First token</th><th>Response time</th><th>Result</th>
    </tr></thead><tbody>${recent.map((s) => `<tr>
      <td class="left">${ago(s.ts)}</td>
      <td class="num">${s.promptTokens ?? "-"}</td>
      <td class="num">${s.completionTokens ?? "-"}</td>
      <td class="num">${fmt.n(s.decodeTps, 1)}</td>
      <td class="num">${fmt.ms(s.ttftMs)}</td>
      <td class="num">${fmt.ms(s.totalMs)}</td>
      <td>${s.ok ? '<span class="badge pass">ok</span>' : `<span class="badge fail" title="${esc(s.error)}">failed</span>`}</td>
    </tr>`).join("")}</tbody></table></div>` : `<p class="muted">No checks yet for this model.</p>`;
  return main + `<tr class="detail-row"><td colspan="4">
      <p class="advice">${esc(switchAdvice(m, rows))}</p>
      <p class="muted" style="margin:0 0 10px">${esc(averages)}</p>
      <h3>Last ${recent.length} request${recent.length === 1 ? "" : "s"}</h3>
      ${recentHtml}</td></tr>`;
}

$("modelTable").addEventListener("click", (e) => {
  const tr = e.target.closest("tr.model-row");
  if (!tr) return;
  dash.open = dash.open === tr.dataset.model ? null : tr.dataset.model;
  renderDashboard();
});

const INTERVAL_CHOICES = [[300, "5 minutes"], [900, "15 minutes"], [1800, "30 minutes"], [3600, "1 hour"]];

function fillIntervalSelect(current) {
  const choices = INTERVAL_CHOICES.slice();
  if (current && !choices.some(([sec]) => sec === current)) {
    const label = current < 120 ? `${current} seconds` : `${Math.round(current / 60)} minutes`;
    choices.unshift([current, label]);
  }
  $("monInterval").innerHTML = choices.map(([sec, label]) =>
    `<option value="${sec}">${label}${sec === 900 ? " (recommended)" : ""}</option>`).join("");
  $("monInterval").value = String(current || 900);
}

function renderMonitorModels() {
  const box = $("monModels");
  if (!state.models.length) { box.className = "checks muted"; box.textContent = "No models from the server yet."; return; }
  const chosen = new Set(dash.monitor?.config.models || []);
  box.className = "checks";
  box.innerHTML = state.models.map((m) => `<label><input type="checkbox" data-mon="${esc(m.id)}" ${chosen.has(m.id) ? "checked" : ""}> ${esc(m.id)}</label>`).join("");
}

let monitorFormDirty = false;
function renderMonitorPanel() {
  const st = dash.monitor;
  if (!st) return;
  if (!monitorFormDirty) {
    $("monEnabled").checked = st.config.enabled;
    fillIntervalSelect(st.config.intervalSec);
    $("monProtocol").value = st.config.protocol;
    $("monPrompt").value = st.config.promptTokens;
    $("monOutput").value = st.config.outputTokens;
    renderMonitorModels();
  }
  const parts = [];
  if (st.config.enabled) {
    const wait = st.nextRunAt ? Math.round((st.nextRunAt - Date.now()) / 1000) : null;
    parts.push(st.probing ? "Probing now..." : wait != null ? `Next check in ${Math.max(0, wait)}s` : st.lastRunAt ? "Next check starting" : "Waiting for the first check");
    if (st.lastRunAt) parts.push(`last run ${ago(st.lastRunAt)}`);
  } else parts.push("Automatic checks are off");
  if (st.note) parts.push(st.note);
  setMsg("monMsg", parts.join(" \u00b7 "), "");
}

$("monitorForm").addEventListener("input", () => { monitorFormDirty = true; });
$("monitorForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const models = [...document.querySelectorAll("[data-mon]")].filter((c) => c.checked).map((c) => c.dataset.mon);
  try {
    dash.monitor = await api("/api/monitor", {
      method: "PUT",
      body: {
        enabled: $("monEnabled").checked,
        intervalSec: Number($("monInterval").value),
        models,
        protocol: $("monProtocol").value,
        promptTokens: Number($("monPrompt").value),
        outputTokens: Number($("monOutput").value),
      },
    });
    monitorFormDirty = false;
    setMsg("monMsg", "Saved.", "ok");
    toast("Probe settings saved");
    loadDashboard();
  } catch (err) { setMsg("monMsg", err.message, "err"); }
});
$("probeNow").addEventListener("click", async () => {
  const checked = [...document.querySelectorAll("[data-mon]")].filter((c) => c.checked).map((c) => c.dataset.mon);
  const models = checked.length ? checked : state.models.map((m) => m.id);
  $("probeNow").disabled = true;
  setMsg("monMsg", "Probing...", "");
  try {
    await api("/api/monitor/probe", { method: "POST", body: { models } });
    monitorFormDirty = false;
    await loadDashboard();
  } catch (err) { setMsg("monMsg", err.message, "err"); }
  finally { $("probeNow").disabled = false; }
});
$("clearSamples").addEventListener("click", async () => {
  if (!confirm("Delete all stored probe data?")) return;
  await api("/api/monitor/samples", { method: "DELETE" }).catch((e) => toast(e.message));
  loadDashboard();
});
$("dashWindow").addEventListener("change", loadDashboard);
$("dashRefresh").addEventListener("click", loadDashboard);
setInterval(() => { if (!document.hidden) loadDashboard(); }, 5000);

async function boot() {
  await loadModels();
  await loadDashboard();
}
boot();
