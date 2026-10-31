# Deploy: auth gateway + Bifrost + MCP server

Three containers on two private networks (gateway + Bifrost, gateway + MCP), so only the gateway reaches both. Only the gateway publishes a port, and only to the address in `GATEWAY_BIND` (loopback by default), so the reverse proxy is its only way in. Bifrost and the MCP server publish nothing.

```
phone ──HTTPS──▶ reverse proxy ──▶ gateway (:8080 in container) ──┬─ /v1/responses ─▶ bifrost:8080 ──▶ Anthropic
                                                                   └─ /mcp ─────────▶ mcp:8080 ─────▶ Brave Search API
```

## Files

| Tracked | Real copy (gitignored) | Contents |
|---|---|---|
| `.env.example` | `.env` | `GATEWAY_BIND` (host address and port for the reverse proxy), `BRAVE_API_KEY` (the MCP server's web search), `PIRATE_WEATHER_API_KEY` (optional: the server's weather), `PLEX_*` (optional: the music lookup) |
| `gateway.env.example` | `local/gateway.env` | Issuer, audience, virtual key, rate limits, body-log retention |
| `bifrost.env.example` | `local/bifrost.env` | Anthropic API key, virtual key |
| `bifrost/config.example.json` | `local/bifrost-config.json` | Bifrost config: provider key, virtual key name, model allowlist, budget |

The optional `PLEX_*` settings enable `music_find`, which the phone's `play_music` calls privately to get the exact names in your Plex music library. Get the token through plex.tv's PIN flow: it creates a "Samu" device in your account that you can revoke at any time. The MCP container must be able to reach the Plex server's LAN address; use its IP in `PLEX_URL`, since containers can't resolve `.local` (mDNS) names. Plex's certificate is issued for a `*.plex.direct` name, not the LAN address, so set `PLEX_TLS_NAME` to that name; the certificate is still fully verified.

Secrets stay in the env files. The Bifrost config refers to them as `env.ANTHROPIC_API_KEY` and `env.BIFROST_VIRTUAL_KEY`.

## Setup

1. Copy each example to its real location and fill in the placeholders. Generate the virtual key once and use it in both env files:
   ```sh
   echo "sk-bf-$(openssl rand -hex 24)"
   ```
2. In `local/bifrost-config.json`, set the virtual key name and the monthly budget (`max_limit`, in dollars).
3. Set a spend limit on the Anthropic Console workspace too, as a backstop.
4. Start the stack. The gateway and MCP images come from Docker Hub (`mahasamuha/samu-gateway`, `mahasamuha/samu-mcp`), published by `.github/workflows/images.yml` on every push to `main`; only the `deploy/` directory is needed on the server:
   ```sh
   docker compose pull && docker compose up -d
   ```
   Set `IMAGE_TAG` in `.env` to a version such as `0.3.1` (or a `sha-<commit>` tag) to pin or roll back. To build from a checkout instead (development):
   ```sh
   docker compose -f compose.yaml -f compose.build.yaml up -d --build
   ```
   Every push to `main` gets a semantic version (tag `vX.Y.Z`, computed by `.github/scripts/next-version.sh` from conventional-commit subjects), and both images are published as `X.Y.Z`, `X.Y`, `latest` and `sha-<commit>`. `curl <gateway>/healthz` shows which version is running.
5. Point the reverse proxy at `GATEWAY_BIND` (see below).

The Bifrost config keeps these settings on purpose:
- `extra_fields_placement: "headers"`: removes Bifrost's metadata, including the provider's organization and workspace IDs, from response bodies and stream chunks. The gateway forwards only `content-type`, so none of it reaches the phone.
- `disable_content_logging: true`: Bifrost keeps cost and usage, not prompts and answers. Bodies are logged by the gateway, with retention.
- `enforce_auth_on_inference: true`: every request needs the virtual key.

The config store and budget usage live in the `bifrost-data` volume. `docker compose down -v` deletes them, which resets spend tracking.

## Reverse proxy (Nginx Proxy Manager)

How the proxy reaches the gateway depends on where it runs:
- **Proxy in Docker on the same host (recommended):** set `PROXY_NETWORK` in `deploy/.env` to the proxy's Docker network and add `COMPOSE_FILE=compose.yaml:compose.proxy-network.yaml` there too, so plain `docker compose` commands include the override. The gateway publishes no host port; the proxy forwards to `samu-gateway:8080` (a network alias, so it can't clash with another container named `gateway`).
- **Proxy elsewhere, or not in Docker:** set `GATEWAY_BIND` to an address the proxy can reach (loopback only works if the proxy runs directly on the host), and keep that port closed to the internet.

**During development** the stack can run on a workstation while the proxy runs on another host: set `GATEWAY_BIND` to the workstation's LAN address and a port, and point a separate proxy host at it. Note that ports published by Docker bypass ufw (Docker's iptables rules come first), so the gateway is reachable from the whole LAN, not only the proxy. That's acceptable because every request needs a valid token, but don't count on ufw to narrow it.

Proxy host in Nginx Proxy Manager:

| Setting | Value |
|---|---|
| Domain name | `<gateway-host>` |
| Scheme / forward host / port | `http` / `samu-gateway` (shared network) or the `GATEWAY_BIND` address / `8080` or the `GATEWAY_BIND` port |
| Websockets support | Off (not used) |
| Block common exploits | On |
| SSL | Request a certificate; Force SSL on; HTTP/2 on |
| Advanced | Nothing needed. The gateway sends `X-Accel-Buffering: no` on streams so nginx doesn't buffer them, and nginx's defaults (60 s read timeout, 1 MB body limit) fit. |

Forwarding every path is fine: the gateway returns 404 for anything but its route allowlist (`POST /v1/responses`, `POST /mcp`, `GET /healthz`; plan §2). Per-IP rate limiting, if wanted, belongs at the proxy; the gateway limits per token subject.

Check it from outside once it's up: `curl https://<gateway-host>/healthz` returns `{"status":"ok"}`, and `curl -X POST https://<gateway-host>/v1/responses` returns 401.

## Operations

- **Debug logging** (raw upstream events in the body log; turns itself off after `DEBUG_DURATION`):
  ```sh
  docker compose kill -s SIGUSR2 gateway   # on; run again to turn it off early
  ```
- **Body logs:** hourly JSONL files in the `gateway-logs` volume (`/app/logs`), deleted after `LOG_BODY_RETENTION`.
- **Costs and budget:** the Bifrost dashboard. It has no login in this setup, so it's never published by default. See `compose.admin.yaml` for temporary loopback access over an SSH tunnel.
- **Updates:** `docker compose pull && docker compose up -d` picks up the latest gateway and MCP images. Bifrost and the Node base images are pinned; bump their tags in `compose.yaml` and the Dockerfiles deliberately.
- **Publishing** needs a `DOCKERHUB_TOKEN` secret in the GitHub repo (a Docker Hub access token for `mahasamuha` with read/write).
- **MCP image build:** the MCP image bundles `capabilities/` and `skills/` from the repo root (it serves them as the `capability://index` resource), so its build context is the repo root: `docker build -f mcp/Dockerfile .`. `compose.build.yaml` and the workflow do this already. `CAPABILITIES_FILE` and `SKILLS_DIR` override the baked-in paths; an invalid manifest stops the server at startup.
