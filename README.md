# LLM Checker

A small Ktor (Kotlin) web app that connects to a vLLM server, lists its models, checks that it can work with Claude Code, and benchmarks it: tokens per second, time to first token, behaviour under concurrency and prefix-cache effects.

It is built for closed networks. The UI is plain HTML, CSS and JavaScript served from the JAR. It loads nothing from a CDN, uses no web fonts and makes no outbound calls except to the vLLM server you configure.

## Features

- **Connection**: base URL, optional API key, optional "skip TLS verification" for self-signed certificates. Lists models with their max context length. Shows live vLLM state from `/metrics` (running and waiting requests, KV cache usage, prefix-cache hit rate).
- **Compatibility checks** for what Claude Code relies on:
  - `GET /v1/models` and the model's `max_model_len`
  - chat completions, streaming, and whether the stream includes `usage`
  - OpenAI-format tool calling (needs `--enable-auto-tool-choice` and a `--tool-call-parser` on the server)
  - the native Anthropic `POST /v1/messages` endpoint, plus Anthropic-format tool use
  - `/metrics`
- **Benchmarks** over either the OpenAI or Anthropic protocol, with a concurrency sweep:
  - time to first token (TTFT), time per output token (TPOT), inter-token latency (ITL), end-to-end latency, each with p50/p90/p95/p99
  - per-request decode tokens/s and aggregate output and input (prefill) tokens/s
  - cold vs cached TTFT when a shared prefix is used, which models how an agent re-sends the same system prompt and file context every turn
  - server-side cross-checks scraped from `/metrics`: generation tokens/s, prefix-cache hit rate, peak running and waiting requests, peak KV cache usage, preemptions
  - presets: quick check, Claude Code agent session, concurrency stress sweep, long-context prefill
- **History** persisted to disk, with CSV and JSON export, and side-by-side comparison of runs (for example before and after changing a vLLM flag).
- A **built-in mock vLLM server** (opt-in) so you can try the app without a GPU.

### How prompts are sized

Prompt sizes are in tokens. Before each run the app sends two tiny calibration requests and uses the server's own `usage.prompt_tokens` to work out tokens per word for that model's tokenizer. Every request gets a unique run nonce, so earlier runs never pollute the prefix cache. The token counts shown in results always come from the server's usage block.

## Run it

Requires JDK 21. Gradle is bundled through the wrapper.

```bash
# development
./gradlew run

# or build one self-contained JAR and run it anywhere with a JRE 21
./gradlew buildFatJar
java -jar build/libs/llm-checker.jar
```

Then open http://localhost:18642 and enter your vLLM URL on the Connection tab.

To try it without a vLLM server:

```bash
MOCK_ENABLED=true ./gradlew run
```

### Configuration (environment variables)

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `18642` | HTTP port |
| `HOST` | `0.0.0.0` | Bind address |
| `VLLM_BASE_URL` | empty | Initial vLLM base URL (can be changed in the UI) |
| `VLLM_API_KEY` | empty | Initial API key |
| `DATA_DIR` | `./data` | Where settings and run history are stored |
| `MOCK_ENABLED` | `false` | Serve a fake vLLM at `/mock` and use it by default |
| `MOCK_SPEED` | `1.0` | Mock speed multiplier |

Settings saved in the UI (including the API key) are written to `DATA_DIR/settings.json` with `600` permissions. Run history is stored under `DATA_DIR/runs/`.

## Tests

```bash
./gradlew test
```

The integration tests start a real embedded server with the mock vLLM and exercise model listing, both streaming protocols, the compatibility checks, and a full benchmark run.

## HTTP API

| Method and path | Description |
| --- | --- |
| `GET/PUT /api/settings` | Read or update the connection settings (the API key is write-only) |
| `GET /api/models` | Models served by the configured vLLM |
| `GET /api/server-metrics` | Snapshot of vLLM's Prometheus metrics |
| `POST /api/checks` | Run compatibility checks for `{ "model": "..." }` |
| `POST /api/runs` | Start a benchmark (one at a time) |
| `GET /api/runs`, `GET /api/runs/{id}` | List runs, or poll one run |
| `POST /api/runs/{id}/cancel`, `DELETE /api/runs/{id}` | Cancel or delete a run |
| `GET /api/runs/{id}/export.csv`, `.json` | Export per-request results |

## Notes on the numbers

- Run benchmarks when nobody else is using the server, or the results include their traffic. The server-side columns make this visible: peak running requests above your concurrency level means other users were active.
- With the Anthropic protocol the output length cannot be forced (there is no `ignore_eos`), so completions may be shorter than requested. Throughput numbers still use the real token counts.
- If the server does not send a `usage` block when streaming, token counts are estimated from the number of streamed chunks and results are flagged.
