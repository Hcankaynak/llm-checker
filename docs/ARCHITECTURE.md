# Architecture

LLM Checker is a single JVM process. It serves a static web UI, exposes a small JSON API, and acts as an HTTP client towards your vLLM server. Nothing else is required at runtime: no database, no message broker, no external services.

## Technologies

| Layer | Technology | Version | Why |
| --- | --- | --- | --- |
| Language | Kotlin (JVM) | 2.3.21 | Requested stack; coroutines suit many concurrent streaming requests |
| Runtime | JDK / JRE | 21 | LTS release |
| Web framework | Ktor server | 3.6.0 | Routing, content negotiation, status pages, call logging |
| HTTP server engine | Netty (via Ktor) | bundled | Non-blocking server for the API and static UI |
| HTTP client | Ktor client with the CIO engine | 3.6.0 | Streams server-sent events from vLLM with per-chunk timing |
| Concurrency | kotlinx.coroutines | 1.11.0 | Concurrent benchmark workers, cancellation, background probe loop |
| JSON | kotlinx.serialization | 1.11.0 | API payloads, run history on disk, parsing vLLM responses |
| Logging | Logback (SLF4J) | 1.5.18 | Console logs |
| Build | Gradle (Kotlin DSL) with the Ktor plugin | 9.8.2 | `buildFatJar` produces one self-contained JAR |
| Tests | kotlin.test on JUnit Platform, Ktor server | - | Unit tests plus integration tests against the mock vLLM |
| Frontend | Plain HTML, CSS and JavaScript (ES2020) | - | No build step, no CDN, no web fonts, so it works on a closed network |
| Charts | Hand-written inline SVG | - | No charting library to download |
| Persistence | JSON and JSONL files in `DATA_DIR` | - | Settings, run history, probe samples |
| Packaging | Docker (multi-stage, Temurin 21) and docker-compose | - | Build once, copy the image into the closed network |

## System topology

```mermaid
flowchart TB
    subgraph User["User machine"]
        Browser["Web browser<br/>Dashboard, Connection, Compatibility,<br/>Benchmark, History"]
    end

    subgraph Container["LLM Checker (one JVM / one container, port 18642)"]
        direction TB
        Static["Static UI<br/>index.html, app.js, style.css"]
        API["Ktor + Netty<br/>JSON API under /api"]
        Bench["BenchmarkManager<br/>runs one benchmark at a time"]
        Monitor["MonitorService<br/>background probe loop"]
        Checks["Compatibility checks"]
        Client["VllmClient<br/>Ktor CIO, SSE stream timing"]
        Mock["Optional mock vLLM<br/>/mock (MOCK_ENABLED)"]
        Disk[("DATA_DIR volume<br/>settings.json<br/>runs/*.json<br/>monitor-samples.jsonl")]
        Static --- API
        API --> Bench
        API --> Monitor
        API --> Checks
        Bench --> Client
        Monitor --> Client
        Checks --> Client
        Bench --> Disk
        Monitor --> Disk
        API --> Disk
    end

    subgraph VLLM["vLLM server (closed network)"]
        OpenAI["/v1/models<br/>/v1/chat/completions"]
        Anthropic["/v1/messages"]
        Metrics["/metrics (Prometheus)"]
    end

    Browser -- "HTTP (UI + /api, polling)" --> Static
    Browser -- "HTTP /api" --> API
    Client -- "HTTP / SSE" --> OpenAI
    Client -- "HTTP / SSE" --> Anthropic
    Client -- "HTTP scrape" --> Metrics
    Client -. "when pointed at the mock" .-> Mock
```

The only outbound connections are from `VllmClient` to the vLLM base URL you configure. The browser only talks to LLM Checker.

## Source dependency map

Arrows mean "depends on". Files live in `src/main/kotlin/io/llmchecker/`.

```mermaid
flowchart TD
    App["Application.kt<br/>wiring, plugins"]
    Routes["Routes.kt<br/>REST endpoints, CSV export"]
    Bench["Benchmark.kt<br/>BenchmarkManager, stage runner,<br/>ServerMonitor, stats assembly"]
    Mon["Monitor.kt<br/>MonitorService, probes"]
    Dash["Dashboard.kt<br/>per-model aggregation"]
    Chk["Checks.kt<br/>compatibility checks"]
    Vc["VllmClient.kt<br/>models, SSE streaming, /metrics parser"]
    Pb["PromptBuilder.kt<br/>sized prompts, shared prefix"]
    St["Stats.kt<br/>percentiles"]
    Store["Stores.kt<br/>SettingsStore, RunStore"]
    Models["Models.kt<br/>serializable DTOs"]
    Cfg["Config.kt<br/>environment variables"]
    MockF["MockVllm.kt<br/>fake vLLM server"]

    App --> Cfg
    App --> Routes
    App --> Bench
    App --> Mon
    App --> Store
    App --> MockF
    Routes --> Bench
    Routes --> Mon
    Routes --> Dash
    Routes --> Chk
    Routes --> Vc
    Bench --> Vc
    Bench --> Pb
    Bench --> St
    Bench --> Store
    Mon --> Vc
    Mon --> Pb
    Mon --> Bench
    Mon --> Store
    Dash --> St
    Chk --> Vc
    Vc --> Models
    Bench --> Models
    Store --> Models
    Dash --> Models
```

External library dependencies: every file that handles JSON uses `kotlinx.serialization`; `Routes.kt` and `Application.kt` use Ktor server; `VllmClient.kt` uses Ktor client (CIO); `Benchmark.kt` and `Monitor.kt` use `kotlinx.coroutines`.

## How a benchmark works

```mermaid
sequenceDiagram
    autonumber
    participant UI as Browser
    participant API as Ktor API
    participant BM as BenchmarkManager
    participant VC as VllmClient
    participant V as vLLM

    UI->>API: POST /api/runs (model, prompt size, output size, concurrency levels)
    API->>BM: start(request), rejected if another run is active
    API-->>UI: 202 + run id
    loop every second while running
        UI->>API: GET /api/runs/{id}
        API-->>UI: phase, progress, finished stages
    end

    BM->>VC: two tiny calibration requests (max_tokens=1)
    VC->>V: POST /v1/chat/completions
    V-->>VC: usage.prompt_tokens
    Note over BM: derive tokens-per-word for this tokenizer, size prompts accurately

    opt shared prefix > 0
        BM->>VC: one cold request (prefix not cached)
        Note over BM: recorded as the cold TTFT
    end

    loop for each concurrency level
        BM->>V: scrape /metrics (before)
        par N closed-loop workers
            BM->>VC: stream request with unique suffix
            VC->>V: POST stream=true, include_usage=true
            V-->>VC: SSE chunks
            Note over VC: timestamp first chunk (TTFT), every chunk (ITL), usage block (token counts)
        end
        BM->>V: sample /metrics every second, then once at the end
        Note over BM: per stage: percentiles, aggregate tok/s, server tok/s, cache hit rate, peaks
    end

    BM->>BM: summarize and save runs/{id}.json
    UI->>API: GET /api/runs/{id}
    API-->>UI: completed run with all stages
```

### What gets measured

| Metric | Definition |
| --- | --- |
| TTFT | Time from sending the request to the first streamed content chunk |
| Decode tok/s (per request) | `(completion_tokens - 1) / (last chunk time - first chunk time)` |
| TPOT | Mean milliseconds per output token during decode |
| ITL | Gap between consecutive streamed chunks (reported as p50/p99, a stutter indicator) |
| E2E latency | Full request time, first byte out to stream end |
| Aggregate output tok/s | Total completion tokens across all requests divided by the stage wall time |
| Prefill tok/s | Total prompt tokens divided by the stage wall time |
| Server tok/s | Change in vLLM's `generation_tokens_total` counter divided by wall time (cross-check) |
| Prefix cache hit rate | Change in vLLM's prefix-cache hits divided by queries during the stage |

## How the dashboard works

```mermaid
flowchart LR
    Loop["MonitorService loop<br/>every N seconds"] -->|"one uncached request per selected model"| VC["VllmClient"]
    VC --> V["vLLM"]
    Loop -->|"append sample"| JSONL[("monitor-samples.jsonl")]
    Runs[("runs/*.json<br/>benchmark history")] --> Agg
    JSONL --> Agg["computeDashboard()<br/>averages, p50/p95, success rate"]
    Agg -->|"GET /api/dashboard"| UI["Dashboard tab<br/>model table, expandable rows"]
    JSONL -->|"GET /api/monitor/samples"| UI
```

- Probes are skipped while a benchmark is running, so they never skew benchmark results.
- Dashboard "average speed" is the mean of per-request decode tok/s over successful probes. Benchmark figures use the lowest-concurrency stage of each run, which is closest to one developer's experience.
- Both data sources are plain files, so the dashboard survives restarts and the container can be replaced without losing history as long as the `/data` volume is kept.

## HTTP API surface

| Method and path | Handled by |
| --- | --- |
| `GET/PUT /api/settings` | `SettingsStore` |
| `GET /api/models`, `GET /api/server-metrics` | `VllmClient` |
| `POST /api/checks` | `Checks.kt` |
| `POST /api/runs`, `GET /api/runs`, `GET/DELETE /api/runs/{id}`, `POST /api/runs/{id}/cancel`, exports | `BenchmarkManager`, `RunStore` |
| `GET /api/dashboard` | `Dashboard.kt` |
| `GET/PUT /api/monitor`, `POST /api/monitor/probe`, `GET/DELETE /api/monitor/samples` | `MonitorService` |
| `GET /api/health` | liveness, used by the Docker health check |

## Deployment in a closed network

```mermaid
flowchart LR
    subgraph Online["Machine with internet"]
        Build["docker build -t llm-checker ."]
        Save["docker save llm-checker | gzip > llm-checker.tar.gz"]
        Build --> Save
    end
    subgraph Closed["Closed network"]
        Load["docker load < llm-checker.tar.gz"]
        Run["docker run -p 18642:18642 -v llm-checker-data:/data llm-checker"]
        VLLM["vLLM servers"]
        Load --> Run
        Run -- "HTTP" --> VLLM
    end
    Save -. "copy the file" .-> Load
```

Without Docker, copy `build/libs/llm-checker.jar` (about 33 MB, everything included) to any machine with a JRE 21 and run `java -jar llm-checker.jar`.
