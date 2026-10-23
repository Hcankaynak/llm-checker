# LLM Checker

A small Ktor (Kotlin) web app that watches the models on a vLLM server and shows which one is faster right now: average tokens per second and response time.

It is built for closed networks. The UI is plain HTML, CSS and JavaScript served from the JAR. It loads nothing from a CDN and makes no outbound calls except to the vLLM server you configure.

## What you see

One page. A table of models, fastest first, with average tokens/sec, average response time, and a status. Click a row for the averages in the selected window and the specs of the last 5 checks (prompt tokens, output tokens, tokens/sec, time to first token, response time), plus a short note on whether switching to a faster model is worth it.

Checks run in the background. Each one is a real generation, so the default is every 15 minutes: often enough to notice a slowdown during the day, light enough not to load the GPU. Change the interval, protocol, and prompt size under Probe settings. Samples are stored in `DATA_DIR`.

Alarms are rules you add on the same page. Each one watches a model (or every probed model) and compares the new check with a window average, the previous check, or the last 5 checks. The first time decode speed, response time, time to first token, or output tokens crosses the threshold, or a check fails, the app posts to the Zulip channel and topic on that alarm. It stays quiet until the model recovers and crosses again. The bot's site, email, and API key come from the environment.

There is a built-in mock vLLM server (`MOCK_ENABLED=true`) so you can try the app without a GPU.

## Technologies

Kotlin 2.3 on JDK 21, Ktor 3.6 (Netty server, CIO client), kotlinx.coroutines, kotlinx.serialization, Logback, and Gradle 9. The frontend has no build step. Packaged as one fat JAR or a Docker image.

The browser only talks to this app. This app only talks to the vLLM base URL from the environment: `GET /v1/models` for the list, and a streaming chat completion (OpenAI or Anthropic) for each check.

## Run it

Requires JDK 21. Gradle is bundled through the wrapper.

```bash
VLLM_BASE_URL=http://your-vllm-host:8000 ./gradlew run

# or one self-contained JAR
./gradlew buildFatJar
VLLM_BASE_URL=http://your-vllm-host:8000 java -jar build/libs/llm-checker.jar
```

Open http://localhost:18642.

To try it without a vLLM server:

```bash
MOCK_ENABLED=true ./gradlew run
```

### Docker

```bash
docker build -t llm-checker .
docker run -d --name llm-checker -p 18642:18642 -v llm-checker-data:/data \
  -e VLLM_BASE_URL=http://your-vllm-host:8000 llm-checker
```

Or with compose, which pulls `ghcr.io/hcankaynak/llm-checker:latest` (`VLLM_BASE_URL`, `VLLM_API_KEY`, `VLLM_SKIP_TLS_VERIFY` and `MOCK_ENABLED` are read from your shell or a `.env` file):

```bash
VLLM_BASE_URL=http://your-vllm-host:8000 docker compose up -d
```

The image is a two-stage build (JDK 21 to compile, a slim JRE 21 to run), runs as a non-root user, keeps probe data in the `/data` volume, and has a health check on `/api/health`.

For a closed network, build the image where you have internet and carry it across:

```bash
docker save llm-checker | gzip > llm-checker.tar.gz
gunzip -c llm-checker.tar.gz | docker load
```

When vLLM runs on the Docker host itself, `localhost` inside the container is the container. Use the host's address, or on Linux add `--add-host=host.docker.internal:host-gateway` and use `http://host.docker.internal:8000`.

### Kubernetes

Manifests in `deploy/kubernetes` run one pod from `ghcr.io/hcankaynak/llm-checker:latest`, store probes on a persistent volume, and expose it with a ClusterIP Service and an Ingress. Edit the vLLM address, secrets, and ingress host before applying:

```bash
kubectl apply -k deploy/kubernetes
```

`VLLM_BASE_URL` is in `configmap.yaml`. `VLLM_API_KEY`, `ZULIP_EMAIL`, and `ZULIP_API_KEY` are in `secret.yaml`. The Ingress host is `llm-checker.example.com` and `ingressClassName` is `nginx`; change both to match the cluster. Delete the `tls` block for plain HTTP, or create a secret named `llm-checker-tls` for that host. A check started from the UI can take as long as `VLLM_TIMEOUT_SEC`, so the Ingress annotations keep the nginx proxy open for 600 seconds.

The image is private. If the cluster can reach GitHub Container Registry, create a pull secret and uncomment `imagePullSecrets` in `deployment.yaml`:

```bash
kubectl -n llm-checker create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username=YOUR_GITHUB_USER \
  --docker-password=YOUR_GITHUB_TOKEN
```

On a closed network, import the image into the cluster registry, set `image` to that name, and leave `imagePullPolicy: IfNotPresent`. Keep `replicas: 1`: the data volume is ReadWriteOnce. Without an Ingress controller, reach the Service with `kubectl -n llm-checker port-forward svc/llm-checker 18642:80`.

### Configuration

The vLLM connection is environment variables only. Restart the app to change it.

| Variable | Default | Purpose |
| --- | --- | --- |
| `VLLM_BASE_URL` | empty | vLLM base URL, for example `http://10.0.0.12:8000`. A trailing `/v1` is fine. |
| `VLLM_API_KEY` | empty | Bearer token, if the server requires one |
| `VLLM_SKIP_TLS_VERIFY` | `false` | Accept a self-signed certificate |
| `VLLM_TIMEOUT_SEC` | `600` | Request timeout, 5 to 7200 seconds |
| `PORT` | `18642` | HTTP port |
| `HOST` | `0.0.0.0` | Bind address |
| `DATA_DIR` | `./data` | Probe settings and samples |
| `MOCK_ENABLED` | `false` | Serve a fake vLLM at `/mock` and use it when `VLLM_BASE_URL` is empty |
| `MOCK_SPEED` | `1.0` | Mock speed multiplier |
| `ZULIP_SITE` | empty | Zulip server URL, for example `https://chat.example.com` |
| `ZULIP_EMAIL` | empty | Bot email address |
| `ZULIP_API_KEY` | empty | Bot API key |

## Tests

```bash
./gradlew test
```

Integration tests start an embedded server with the mock vLLM and cover model listing, streaming, and a probe round.

## Continuous integration

Two GitHub Actions workflows live in `.github/workflows`.

**Build** runs on every push and pull request. It runs `./gradlew build`, so the check is green only when the project compiles and the tests pass.

**Publish image** pushes to GitHub Container Registry.

- Every commit on `main` publishes `ghcr.io/hcankaynak/llm-checker:latest`. Pull requests do not move that tag. `docker-compose.yml` uses this image.
- A git tag on `main` publishes a fixed image with the same name, for example `ghcr.io/hcankaynak/llm-checker:v0.0.1`. Later commits move only `:latest`.

```bash
git checkout main
git tag v0.0.1
git push origin v0.0.1
```

The tagged commit has to be in `main`'s history. The repository's Actions workflow permissions need to be read and write so the workflow can push the package (Settings → Actions → General → Workflow permissions).

## HTTP API

| Method and path | Description |
| --- | --- |
| `GET /api/health` | Liveness, includes the configured base URL |
| `GET /api/models` | Models served by the configured vLLM |
| `GET /api/dashboard?minutes=1440` | Per-model averages (`minutes=0` for all time) |
| `GET/PUT /api/monitor` | Read or change the check schedule, models, and workload |
| `POST /api/monitor/probe` | Run one check round now |
| `GET/DELETE /api/monitor/samples` | Raw samples (`?minutes=&model=`), or clear them |
| `GET/PUT /api/alarms` | Alarm rules, and whether Zulip is configured |
| `POST /api/alarms/test` | Post one test message to a channel and topic |
