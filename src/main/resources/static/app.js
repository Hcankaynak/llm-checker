"use strict";

const $ = (id) => document.getElementById(id);
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const state = { settings: null, models: [], model: localStorage.getItem("model") || "", watching: null, pollTimer: null, selectedRuns: new Set() };

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
  date(t) { return t ? new Date(t).toLocaleString() : "-"; },
  dur(a, b) { if (!a || !b) return "-"; const s = Math.round((b - a) / 1000); return s >= 60 ? `${Math.floor(s / 60)}m ${s % 60}s` : `${s}s`; },
};

/* ---------- tabs ---------- */
function showTab(name) {
  document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t.id === "tab-" + name));
  document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
  location.hash = name;
  if (name === "history") loadHistory();
}
$("tabs").addEventListener("click", (e) => { if (e.target.dataset.tab) showTab(e.target.dataset.tab); });

/* ---------- connection ---------- */
function setPill(kind, text) {
  $("connPill").className = "conn-pill " + (kind || "");
  $("connText").textContent = text;
}

async function loadSettings() {
  const s = await api("/api/settings");
  state.settings = s;
  $("baseUrl").value = s.baseUrl;
  $("skipTls").checked = s.skipTlsVerify;
  $("timeoutSec").value = s.requestTimeoutSec;
  $("keyHint").textContent = s.hasApiKey ? "(a key is stored)" : "";
  $("mockBtn").classList.toggle("hidden", !s.mockAvailable);
}

$("settingsForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  await saveSettings(false);
});

$("clearKeyBtn").addEventListener("click", () => saveSettings(true));

$("mockBtn").addEventListener("click", async () => {
  $("baseUrl").value = state.settings.mockBaseUrl;
  await saveSettings(false);
});

async function saveSettings(clearKey) {
  setMsg("settingsMsg", "Saving...");
  try {
    state.settings = await api("/api/settings", {
      method: "PUT",
      body: {
        baseUrl: $("baseUrl").value.trim(),
        apiKey: $("apiKey").value || null,
        clearApiKey: clearKey,
        skipTlsVerify: $("skipTls").checked,
        requestTimeoutSec: Number($("timeoutSec").value) || 600,
      },
    });
    $("apiKey").value = "";
    $("keyHint").textContent = state.settings.hasApiKey ? "(a key is stored)" : "";
    await loadModels(true);
  } catch (err) {
    setMsg("settingsMsg", err.message, "err");
  }
}

async function loadModels(fromSave = false) {
  $("modelsBox").className = "muted";
  $("modelsBox").textContent = "Loading models...";
  try {
    const res = await api("/api/models");
    state.models = res.models;
    setPill("ok", `${res.baseUrl} \u00b7 ${res.models.length} model${res.models.length === 1 ? "" : "s"} \u00b7 ${fmt.ms(res.latencyMs)}`);
    if (fromSave) setMsg("settingsMsg", `Connected. ${res.models.length} model(s) found in ${fmt.ms(res.latencyMs)}.`, "ok");
    if (!state.models.some((m) => m.id === state.model)) state.model = state.models[0]?.id || "";
    renderModels();
    refreshModelSelects();
    refreshMetrics();
  } catch (err) {
    state.models = [];
    setPill("bad", state.settings?.baseUrl ? "Connection failed" : "Not connected");
    $("modelsBox").className = "errors";
    $("modelsBox").textContent = err.message;
    if (fromSave) setMsg("settingsMsg", err.message, "err");
    refreshModelSelects();
    $("serverStatus").className = "muted";
    $("serverStatus").textContent = "Save a working connection to see live vLLM metrics.";
  }
}

function renderModels() {
  if (!state.models.length) { $("modelsBox").className = "muted"; $("modelsBox").textContent = "The server returned no models."; return; }
  $("modelsBox").className = "";
  $("modelsBox").innerHTML = `<div class="table-wrap"><table><thead><tr><th>Model</th><th>Max context</th><th>Owner</th><th></th></tr></thead><tbody>${state.models.map((m) => `
    <tr><td><code>${esc(m.id)}</code>${m.id === state.model ? ' <span class="badge pass">selected</span>' : ""}</td>
    <td class="num">${m.maxModelLen ? m.maxModelLen.toLocaleString() + " tokens" : "-"}</td>
    <td>${esc(m.ownedBy || "-")}</td>
    <td><button class="small ghost" data-use="${esc(m.id)}">Use for tests</button></td></tr>`).join("")}</tbody></table></div>`;
  $("modelsBox").querySelectorAll("[data-use]").forEach((b) => b.addEventListener("click", () => {
    state.model = b.dataset.use; localStorage.setItem("model", state.model); renderModels(); refreshModelSelects();
    toast(`Selected ${state.model}`);
  }));
}

function refreshModelSelects() {
  document.querySelectorAll(".model-select").forEach((sel) => {
    sel.innerHTML = state.models.length
      ? state.models.map((m) => `<option value="${esc(m.id)}" ${m.id === state.model ? "selected" : ""}>${esc(m.id)}</option>`).join("")
      : '<option value="">(connect first)</option>';
    sel.onchange = () => { state.model = sel.value; localStorage.setItem("model", state.model); refreshModelSelects(); renderModels(); };
  });
}

async function refreshMetrics() {
  const box = $("serverStatus");
  try {
    const m = await api("/api/server-metrics");
    box.className = "";
    box.innerHTML = `<div class="kv">
      <div><div class="k">Requests running</div><div class="v">${fmt.n(m.running, 0)}</div></div>
      <div><div class="k">Requests waiting</div><div class="v">${fmt.n(m.waiting, 0)}</div></div>
      <div><div class="k">KV cache usage</div><div class="v">${fmt.pct(m.kvCacheUsage)}</div></div>
      <div><div class="k">Prefix cache hit rate</div><div class="v">${m.prefixCacheQueries ? fmt.pct(m.prefixCacheHits / m.prefixCacheQueries) : "-"}</div></div>
      <div><div class="k">Tokens generated</div><div class="v">${m.generationTokensTotal != null ? Number(m.generationTokensTotal).toLocaleString() : "-"}</div></div>
      <div><div class="k">Preemptions</div><div class="v">${fmt.n(m.preemptionsTotal, 0)}</div></div></div>
      <p class="muted" style="margin:10px 0 0">Prefix cache hit rate is lifetime since the server started.</p>`;
  } catch (err) {
    box.className = "muted";
    box.textContent = err.message.includes("metrics") ? "This server does not expose /metrics, so server-side stats are unavailable." : err.message;
  }
}
$("refreshMetrics").addEventListener("click", refreshMetrics);
$("refreshModels").addEventListener("click", () => loadModels());

/* ---------- compatibility checks ---------- */
$("runChecks").addEventListener("click", async () => {
  const model = $("checkModel").value;
  if (!model) { $("checksOut").innerHTML = '<p class="errors">Connect to a server and pick a model first.</p>'; return; }
  $("runChecks").disabled = true;
  $("checksOut").innerHTML = '<p class="muted">Running checks, this takes a few seconds...</p>';
  try {
    const res = await api("/api/checks", { method: "POST", body: { model } });
    const counts = { pass: 0, warn: 0, fail: 0, skip: 0 };
    res.results.forEach((r) => counts[r.status]++);
    $("checksOut").innerHTML = `<p style="margin:16px 0 0"><span class="badge pass">${counts.pass} passed</span> <span class="badge warn">${counts.warn} warnings</span> <span class="badge fail">${counts.fail} failed</span></p>
      <div class="check-list">${res.results.map((r) => `
        <div class="check-item"><div><span class="badge ${esc(r.status)}">${esc(r.status)}</span></div>
        <div><div class="title">${esc(r.title)}</div><div class="detail">${esc(r.detail)}</div></div>
        <div class="lat">${r.latencyMs != null ? fmt.ms(r.latencyMs) : ""}</div></div>`).join("")}</div>`;
  } catch (err) {
    $("checksOut").innerHTML = `<p class="errors">${esc(err.message)}</p>`;
  } finally {
    $("runChecks").disabled = false;
  }
});

/* ---------- benchmark form ---------- */
const PRESETS = {
  quick: { desc: "A couple of minutes at most. Good for checking that the pipeline works and getting rough numbers.", promptTokens: 512, sharedPrefix: 0, outputTokens: 128, requestsPerLevel: 8, levels: "1, 4", warmup: 1 },
  claude: { desc: "Mimics an agent loop: a large prompt where most tokens are the same system prompt and file context on every turn, with short replies. Measures cold vs cached time to first token, which dominates how responsive Claude Code feels.", promptTokens: 16000, sharedPrefix: 12000, outputTokens: 400, requestsPerLevel: 8, levels: "1, 2, 4, 8", warmup: 0 },
  stress: { desc: "Moderate prompts with rising parallelism to find where aggregate throughput saturates and latency starts to climb. Use this to size how many developers one server can carry.", promptTokens: 1024, sharedPrefix: 0, outputTokens: 256, requestsPerLevel: 32, levels: "1, 2, 4, 8, 16, 32", warmup: 1 },
  long: { desc: "A very long prompt with no caching to measure raw prefill speed and the cost of a cold, huge context.", promptTokens: 64000, sharedPrefix: 0, outputTokens: 128, requestsPerLevel: 3, levels: "1", warmup: 0 },
};

function applyPreset(name) {
  const p = PRESETS[name];
  $("presetDesc").textContent = p ? p.desc : "Set every parameter yourself.";
  if (!p) return;
  $("promptTokens").value = p.promptTokens;
  $("sharedPrefix").value = p.sharedPrefix;
  $("outputTokens").value = p.outputTokens;
  $("requestsPerLevel").value = p.requestsPerLevel;
  $("levels").value = p.levels;
  $("warmup").value = p.warmup;
}
$("preset").addEventListener("change", () => applyPreset($("preset").value));
["promptTokens", "sharedPrefix", "outputTokens", "requestsPerLevel", "levels", "warmup"].forEach((id) =>
  $(id).addEventListener("input", () => { if ($("preset").value !== "custom") { $("preset").value = "custom"; $("presetDesc").textContent = "Set every parameter yourself."; } }));
$("protocol").addEventListener("change", () => {
  $("ignoreEos").disabled = $("protocol").value === "anthropic";
});

$("benchForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const levels = $("levels").value.split(/[ ,;]+/).filter(Boolean).map(Number);
  if (levels.some((n) => !Number.isInteger(n) || n < 1)) { setMsg("benchMsg", "Concurrency levels must be positive integers, e.g. 1, 2, 4", "err"); return; }
  const body = {
    model: $("benchModel").value,
    protocol: $("protocol").value,
    promptTokens: Number($("promptTokens").value),
    sharedPrefixTokens: Number($("sharedPrefix").value) || 0,
    outputTokens: Number($("outputTokens").value),
    concurrencyLevels: levels,
    requestsPerLevel: Number($("requestsPerLevel").value),
    warmupRequests: Number($("warmup").value) || 0,
    ignoreEos: $("ignoreEos").checked,
    label: $("label").value.trim() || null,
  };
  setMsg("benchMsg", "Starting...");
  try {
    const run = await api("/api/runs", { method: "POST", body });
    setMsg("benchMsg", "");
    watchRun(run.id);
  } catch (err) {
    setMsg("benchMsg", err.message, "err");
  }
});

/* ---------- live run view ---------- */
function watchRun(id) {
  state.watching = id;
  clearTimeout(state.pollTimer);
  poll();
}

async function poll() {
  const id = state.watching;
  if (!id) return;
  try {
    const run = await api("/api/runs/" + id);
    renderRun(run);
    $("startBtn").disabled = run.status === "running";
    if (run.status === "running") state.pollTimer = setTimeout(poll, 1000);
  } catch (err) {
    $("benchResult").innerHTML = `<div class="card"><p class="errors">${esc(err.message)}</p></div>`;
  }
}

function renderRun(run) {
  const stages = run.stages || [];
  const totalStages = run.request.concurrencyLevels.length;
  const cur = run.currentStage;
  let fraction = stages.length / totalStages;
  if (cur) fraction += (cur.completed / cur.total) / totalStages;
  if (run.status === "completed") fraction = 1;

  const head = `<div class="card"><div class="statusbar">
      <div class="grow">
        <div><span class="badge ${esc(run.status)}">${esc(run.status)}</span>
          <strong style="margin-left:8px">${esc(run.request.label || run.request.model)}</strong>
          <span class="muted"> \u00b7 ${esc(run.request.protocol)} \u00b7 ${run.request.promptTokens.toLocaleString()} in / ${run.request.outputTokens.toLocaleString()} out \u00b7 run ${esc(run.id)}</span></div>
        <div class="muted" style="margin-top:4px">${run.status === "running" ? esc(run.phase) + (cur ? ` \u2014 ${cur.completed}/${cur.total} requests, ${fmt.n(cur.liveOutputTps, 0)} tok/s so far${cur.failed ? `, ${cur.failed} failed` : ""}` : "") : `Took ${fmt.dur(run.startedAt, run.finishedAt)}`}</div>
        <div class="progress"><div style="width:${Math.min(100, fraction * 100).toFixed(1)}%"></div></div>
      </div>
      <div class="row" style="margin:0">
        ${run.status === "running"
          ? '<button class="danger" id="cancelRun">Cancel</button>'
          : `<a href="/api/runs/${esc(run.id)}/export.csv"><button type="button" class="ghost">CSV</button></a><a href="/api/runs/${esc(run.id)}/export.json"><button type="button" class="ghost">JSON</button></a>`}
      </div></div>
      ${run.error ? `<p class="errors" style="margin-bottom:0">${esc(run.error)}</p>` : ""}
    </div>`;

  let body = "";
  if (stages.length || run.coldProbe) body = renderResults(run);
  $("benchResult").innerHTML = head + body;
  const cancel = $("cancelRun");
  if (cancel) cancel.addEventListener("click", async () => { cancel.disabled = true; await api(`/api/runs/${run.id}/cancel`, { method: "POST" }).catch((e) => toast(e.message)); });
}

function renderResults(run) {
  const stages = run.stages.filter((s) => s.completed > 0);
  const s = run.summary;
  const cold = run.coldProbe && run.coldProbe.ok ? run.coldProbe : null;
  const ref = stages.find((x) => x.concurrency === 1) || stages[0];
  const warmTtft = ref?.ttft?.p50;
  const cal = run.calibration;

  const kpis = [];
  if (stages.length) {
    const best = stages.reduce((a, b) => (b.outputTps > a.outputTps ? b : a));
    kpis.push(kpi("Peak throughput", fmt.n(best.outputTps, 0), "tok/s", `aggregate output at concurrency ${best.concurrency}`));
    kpis.push(kpi(ref.concurrency === 1 ? "Single-stream speed" : `Per-request speed @${ref.concurrency}`, fmt.n(ref.perRequestTps?.p50, 1), "tok/s", `min\u2013p95 range: ${fmt.n(ref.perRequestTps?.min, 0)}\u2013${fmt.n(ref.perRequestTps?.p95, 0)}`));
    kpis.push(kpi("Time to first token", fmt.ms(ref.ttft?.p50), "", `p95 ${fmt.ms(ref.ttft?.p95)} at concurrency ${ref.concurrency}`));
    kpis.push(kpi("Inter-token latency", fmt.ms(ref.itl?.p50), "", `p99 ${fmt.ms(ref.itl?.p99)} (stutter indicator)`));
    kpis.push(kpi("Prefill speed", fmt.n(ref.inputTps, 0), "tok/s", `${fmt.n(ref.avgPromptTokens, 0)} prompt tokens per request`));
  }
  if (cold) {
    const speedup = warmTtft ? cold.ttftMs / warmTtft : null;
    kpis.push(kpi("Cold vs cached TTFT", fmt.ms(cold.ttftMs), "", warmTtft ? `\u2192 ${fmt.ms(warmTtft)} with shared prefix (${fmt.n(speedup, 1)}\u00d7 faster)` : "first request, nothing cached"));
  }
  const failed = run.stages.reduce((a, x) => a + x.failed, 0);
  if (failed) kpis.push(kpi("Failed requests", String(failed), "", "see errors below"));

  const out = [];
  if (kpis.length) out.push(`<div class="kpis">${kpis.join("")}</div>`);
  if (ref && run.status !== "running") out.push(verdict(ref, cold, run));
  if (stages.length) {
    out.push(`<div class="charts">
      <div class="card chart"><h3>Throughput vs concurrency</h3>${lineChart(stages.map((x) => x.concurrency), [
        { name: "Aggregate output tok/s", color: "#4f9cf9", values: stages.map((x) => x.outputTps) },
        { name: "Per-request tok/s (p50)", color: "#3fb950", values: stages.map((x) => x.perRequestTps?.p50) }], "tok/s")}</div>
      <div class="card chart"><h3>Latency vs concurrency</h3>${lineChart(stages.map((x) => x.concurrency), [
        { name: "TTFT p50", color: "#a78bfa", values: stages.map((x) => x.ttft?.p50) },
        { name: "TTFT p95", color: "#f0883e", values: stages.map((x) => x.ttft?.p95) },
        { name: "Inter-token p99", color: "#f85149", values: stages.map((x) => x.itl?.p99) }], "ms")}</div></div>`);
    out.push(stageTable(stages));
  }
  const errs = {};
  run.stages.forEach((st) => Object.entries(st.errors || {}).forEach(([k, v]) => (errs[k] = (errs[k] || 0) + v)));
  if (run.coldProbe && !run.coldProbe.ok) errs[run.coldProbe.error] = 1;
  if (Object.keys(errs).length) out.push(`<div class="card"><h3>Errors</h3><ul class="errors">${Object.entries(errs).map(([k, v]) => `<li>${v}\u00d7 ${esc(k)}</li>`).join("")}</ul></div>`);
  if (cal) out.push(`<p class="muted" style="margin:0">Prompt sizing: ${cal.fromServer ? "calibrated against the server's own token counts" : "tokenizer calibration failed, using a rough 1.3 tokens/word estimate"} (${fmt.n(cal.tokensPerWord, 2)} tokens/word). Reported prompt tokens are the server's actual counts.</p>`);
  return out.join("");
}

function kpi(label, value, unit, sub) {
  return `<div class="kpi"><div class="label">${esc(label)}</div><div class="value">${esc(value)}${unit ? `<small>${esc(unit)}</small>` : ""}</div><div class="sub">${esc(sub)}</div></div>`;
}

function verdict(ref, cold, run) {
  const tps = ref.perRequestTps?.p50;
  const ttft = ref.ttft?.p50;
  if (tps == null) return "";
  let kind, speedText;
  if (tps >= 50) { kind = "good"; speedText = "streaming will feel instant"; }
  else if (tps >= 25) { kind = "good"; speedText = "comfortable for interactive coding"; }
  else if (tps >= 12) { kind = "warn"; speedText = "usable but noticeably slow for long edits"; }
  else { kind = "bad"; speedText = "too slow for a pleasant interactive experience"; }
  const worst = cold ? Math.max(cold.ttftMs, ttft || 0) : ttft;
  let ttftText = "";
  if (worst != null) ttftText = worst > 15000 ? ` The first token takes ${fmt.ms(worst)} on a cold prompt, which will feel like a hang on every large context.`
    : worst > 5000 ? ` Cold first-token time is ${fmt.ms(worst)}; tolerable only when the prefix cache is warm.` : ` First token arrives in ${fmt.ms(worst)}.`;
  const sat = run.stages.length > 1 ? saturation(run.stages) : "";
  return `<div class="verdict ${kind}"><strong>At concurrency ${ref.concurrency}:</strong> ${fmt.n(tps, 1)} tok/s per request, ${speedText}.${esc(ttftText)}${sat}</div>`;
}

function saturation(stages) {
  const good = stages.filter((s) => s.completed > 0);
  if (good.length < 2) return "";
  const best = good.reduce((a, b) => (b.outputTps > a.outputTps ? b : a));
  const base = good[0].perRequestTps?.p50;
  const floor = 20;
  const ok = good.filter((s) => (s.perRequestTps?.p50 ?? 0) >= floor).map((s) => s.concurrency);
  const hint = base && ok.length ? ` Each user keeps at least ${floor} tok/s up to concurrency ${Math.max(...ok)}.` : ` Per-user speed drops below ${floor} tok/s as soon as load is added.`;
  return ` Aggregate throughput peaks at ${fmt.n(best.outputTps, 0)} tok/s with ${best.concurrency} parallel requests.${hint}`;
}

function stageTable(stages) {
  const rows = stages.map((s) => `<tr>
    <td>${s.concurrency}</td>
    <td class="num">${s.completed}${s.failed ? ` <span class="errors">/ ${s.failed} failed</span>` : ""}</td>
    <td class="num">${fmt.n(s.outputTps, 1)}</td>
    <td class="num">${fmt.n(s.inputTps, 0)}</td>
    <td class="num">${fmt.n(s.perRequestTps?.p50, 1)}</td>
    <td class="num">${fmt.ms(s.ttft?.p50)}</td>
    <td class="num">${fmt.ms(s.ttft?.p95)}</td>
    <td class="num">${fmt.ms(s.tpot?.p50)}</td>
    <td class="num">${fmt.ms(s.itl?.p99)}</td>
    <td class="num">${fmt.ms(s.e2e?.p50)}</td>
    <td class="num">${s.server?.generationTps != null ? fmt.n(s.server.generationTps, 1) : "-"}</td>
    <td class="num">${s.server?.prefixCacheHitRate != null ? fmt.pct(s.server.prefixCacheHitRate) : s.avgCachedTokens != null ? fmt.n(s.avgCachedTokens, 0) + " tok" : "-"}</td>
    <td class="num">${s.server ? `${fmt.n(s.server.peakRunning, 0)} / ${fmt.n(s.server.peakWaiting, 0)}` : "-"}</td>
    <td class="num">${s.server?.peakKvCacheUsage != null ? fmt.pct(s.server.peakKvCacheUsage) : "-"}</td></tr>`).join("");
  return `<div class="card"><h3>Results by concurrency</h3><div class="table-wrap"><table><thead><tr>
    <th>Conc.</th><th>Done</th><th>Out tok/s</th><th>In tok/s</th><th>Req tok/s p50</th><th>TTFT p50</th><th>TTFT p95</th><th>TPOT p50</th><th>ITL p99</th><th>E2E p50</th><th>Server tok/s</th><th>Prefix cache</th><th>Run / wait</th><th>KV peak</th>
    </tr></thead><tbody>${rows}</tbody></table></div>
    <p class="muted" style="margin:10px 0 0">TTFT = time to first token. TPOT = mean time per output token. ITL = gap between streamed chunks. Server columns come from vLLM's <code>/metrics</code> when reachable.</p></div>`;
}

/* ---------- charts (inline SVG, no dependencies) ---------- */
function lineChart(xs, series, unit) {
  const W = 520, H = 250, m = { l: 54, r: 14, t: 14, b: 34 };
  const all = series.flatMap((s) => s.values).filter((v) => v != null && isFinite(v));
  if (!all.length) return '<p class="muted">No data</p>';
  const rawMax = Math.max(...all) * 1.1 || 1;
  const step = niceStep(rawMax / 4);
  const max = Math.ceil(rawMax / step) * step;
  const iw = W - m.l - m.r, ih = H - m.t - m.b;
  const x = (i) => m.l + (xs.length === 1 ? iw / 2 : (iw * i) / (xs.length - 1));
  const y = (v) => m.t + ih - (v / max) * ih;
  let g = "";
  for (let v = 0; v <= max + 1e-9; v += step) {
    g += `<line x1="${m.l}" x2="${W - m.r}" y1="${y(v)}" y2="${y(v)}" stroke="#2a3441" stroke-width="1"/><text x="${m.l - 8}" y="${y(v) + 4}" text-anchor="end" fill="#8b98a8" font-size="11">${fmtAxis(v)}</text>`;
  }
  xs.forEach((v, i) => { g += `<text x="${x(i)}" y="${H - 12}" text-anchor="middle" fill="#8b98a8" font-size="11">${v}</text>`; });
  g += `<text x="${m.l + iw / 2}" y="${H - 0}" text-anchor="middle" fill="#8b98a8" font-size="10.5">concurrent requests</text>`;
  g += `<text x="12" y="${m.t + 4}" fill="#8b98a8" font-size="10.5">${esc(unit)}</text>`;
  series.forEach((s) => {
    const pts = s.values.map((v, i) => (v == null || !isFinite(v) ? null : [x(i), y(v)]));
    const path = pts.filter(Boolean).map((p, i) => `${i ? "L" : "M"}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
    if (path) g += `<path d="${path}" fill="none" stroke="${s.color}" stroke-width="2.2" stroke-linejoin="round"/>`;
    pts.forEach((p, i) => { if (p) g += `<circle cx="${p[0]}" cy="${p[1]}" r="3.6" fill="${s.color}"><title>${esc(s.name)} @${xs[i]}: ${fmtAxis(s.values[i])} ${esc(unit)}</title></circle>`; });
  });
  const legend = series.map((s) => `<span><i style="background:${s.color}"></i>${esc(s.name)}</span>`).join("");
  return `<svg viewBox="0 0 ${W} ${H}" role="img">${g}</svg><div class="legend">${legend}</div>`;
}
function niceStep(raw) {
  const p = Math.pow(10, Math.floor(Math.log10(raw)));
  const f = raw / p;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
}
function fmtAxis(v) { return v >= 1000 ? (v / 1000).toFixed(v % 1000 === 0 ? 0 : 1) + "k" : v >= 10 ? v.toFixed(0) : v.toFixed(1); }

/* ---------- history ---------- */
async function loadHistory() {
  const box = $("historyBox");
  try {
    const runs = await api("/api/runs");
    state.selectedRuns = new Set([...state.selectedRuns].filter((id) => runs.some((r) => r.id === id)));
    if (!runs.length) { box.className = "muted"; box.textContent = "No runs yet. Start one from the Benchmark tab."; updateCompareBtn(); return; }
    box.className = "";
    box.innerHTML = `<div class="table-wrap"><table><thead><tr><th class="left"></th><th class="left">When</th><th class="left">Run</th><th>Load</th><th>Peak tok/s</th><th>Single tok/s</th><th>TTFT p50</th><th>Status</th><th></th></tr></thead><tbody>${runs.map((r) => `<tr>
      <td class="left"><input type="checkbox" data-sel="${esc(r.id)}" ${state.selectedRuns.has(r.id) ? "checked" : ""}></td>
      <td class="left">${fmt.date(r.createdAt)}</td>
      <td class="left"><strong>${esc(r.label || r.model)}</strong><div class="muted">${esc(r.label ? r.model + " \u00b7 " : "")}${esc(r.protocol)}</div></td>
      <td class="num">${r.promptTokens.toLocaleString()} in / ${r.outputTokens} out<div class="muted">conc ${esc(r.concurrencyLevels.join(", "))}</div></td>
      <td class="num">${r.summary ? fmt.n(r.summary.peakOutputTps, 0) : "-"}</td>
      <td class="num">${r.summary ? fmt.n(r.summary.singleStreamTps, 1) : "-"}</td>
      <td class="num">${r.summary ? fmt.ms(r.summary.ttftP50Ms) : "-"}</td>
      <td><span class="badge ${esc(r.status)}">${esc(r.status)}</span></td>
      <td style="text-align:right"><button class="small ghost" data-view="${esc(r.id)}">Open</button> <button class="small ghost danger" data-del="${esc(r.id)}">Delete</button></td></tr>`).join("")}</tbody></table></div>`;
    box.querySelectorAll("[data-view]").forEach((b) => b.addEventListener("click", () => { showTab("bench"); watchRun(b.dataset.view); }));
    box.querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", async () => {
      if (!confirm("Delete this run?")) return;
      await api("/api/runs/" + b.dataset.del, { method: "DELETE" }).catch((e) => toast(e.message));
      state.selectedRuns.delete(b.dataset.del);
      loadHistory();
    }));
    box.querySelectorAll("[data-sel]").forEach((c) => c.addEventListener("change", () => {
      c.checked ? state.selectedRuns.add(c.dataset.sel) : state.selectedRuns.delete(c.dataset.sel);
      updateCompareBtn();
    }));
    updateCompareBtn();
  } catch (err) {
    box.className = "errors"; box.textContent = err.message;
  }
}
function updateCompareBtn() { $("compareBtn").disabled = state.selectedRuns.size < 2; }
$("refreshHistory").addEventListener("click", loadHistory);

$("compareBtn").addEventListener("click", async () => {
  const runs = await Promise.all([...state.selectedRuns].map((id) => api("/api/runs/" + id)));
  const levels = [...new Set(runs.flatMap((r) => r.stages.map((s) => s.concurrency)))].sort((a, b) => a - b);
  const names = runs.map((r) => r.request.label || `${r.request.model} (${r.id})`);
  const cell = (run, lvl, f, d = 1, unit) => {
    const st = run.stages.find((s) => s.concurrency === lvl);
    const v = st ? f(st) : null;
    return v == null ? "-" : (unit === "ms" ? fmt.ms(v) : fmt.n(v, d));
  };
  const delta = (lvl, f, lowerBetter) => {
    const vals = runs.map((r) => { const st = r.stages.find((s) => s.concurrency === lvl); return st ? f(st) : null; });
    if (vals.length < 2 || vals[0] == null || vals[1] == null || vals[0] === 0) return "";
    const pct = ((vals[1] - vals[0]) / vals[0]) * 100;
    const good = lowerBetter ? pct < 0 : pct > 0;
    return ` <span class="${good ? "delta-up" : "delta-down"}">${pct > 0 ? "+" : ""}${pct.toFixed(0)}%</span>`;
  };
  const metrics = [
    { title: "Aggregate output tok/s", f: (s) => s.outputTps, d: 1, lower: false },
    { title: "Per-request tok/s (p50)", f: (s) => s.perRequestTps?.p50, d: 1, lower: false },
    { title: "TTFT p50", f: (s) => s.ttft?.p50, unit: "ms", lower: true },
    { title: "TTFT p95", f: (s) => s.ttft?.p95, unit: "ms", lower: true },
  ];
  $("compareBox").innerHTML = `<div class="card"><h2>Comparison</h2>
    <p class="muted">Percent change is shown for the second run relative to the first.</p>
    ${metrics.map((m) => `<h3 style="margin-top:16px">${m.title}</h3><div class="table-wrap"><table><thead><tr><th>Concurrency</th>${names.map((n) => `<th>${esc(n)}</th>`).join("")}</tr></thead><tbody>
      ${levels.map((lvl) => `<tr><td>${lvl}</td>${runs.map((r, i) => `<td class="num">${cell(r, lvl, m.f, m.d, m.unit)}${i === 1 ? delta(lvl, m.f, m.lower) : ""}</td>`).join("")}</tr>`).join("")}
    </tbody></table></div>`).join("")}</div>`;
  $("compareBox").scrollIntoView({ behavior: "smooth" });
});

/* ---------- boot ---------- */
async function boot() {
  applyPreset("quick");
  $("protocol").dispatchEvent(new Event("change"));
  refreshModelSelects();
  try {
    await loadSettings();
    if (state.settings.baseUrl) await loadModels();
    else setPill("", "Not connected");
  } catch (err) {
    setPill("bad", err.message);
  }
  const hash = location.hash.replace("#", "");
  if (["connection", "checks", "bench", "history"].includes(hash)) showTab(hash);
  try {
    const runs = await api("/api/runs");
    const active = runs.find((r) => r.status === "running");
    if (active) watchRun(active.id);
    else if (runs.length) { state.watching = runs[0].id; poll(); }
  } catch { /* ignore */ }
}
boot();
