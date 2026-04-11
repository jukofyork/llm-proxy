# LLM Proxy

A lightweight, OpenAI-compatible HTTP proxy that routes requests to multiple backend LLM servers based on the requested model. It supports streaming (SSE), per-server parameter defaults and overrides, virtual profiles, and simple request shaping via a clean TOML configuration.

Key capabilities:
- Single endpoint per server
- Bearer auth via `api_key`
- Model allow-list (including hidden models via `*` prefix)
- Defaults (apply if missing), overrides (force-set), and deny (JSON Pointers)
- Virtual model profiles (suffix-based)
- Option to hide base models (expose only profile-suffixed models)
- Default system/developer messages upsert (added if missing)

---

## Quick Start

Prerequisites:
- Java 17+
- Maven 3.x

1) Configure
- Use `examples/config.toml` as a reference and place your config at `llm-proxy.toml` (or specify with `--config`).

2) Build
- `./compile.sh`
  - Runs `mvn compile` and `mvn package`

3) Run
- Development (Maven Exec): `./run.sh` (runs `mvn exec:java`)
- From JAR: `./run-jar.sh` (runs `java -jar target/llm-proxy-1.0.0.jar`)

The default port is `3000`. Use `--port` to change it.

---

## Configuration

Define servers as top-level tables. Each server has one `endpoint` and optional behaviors and profiles. See `examples/config.toml` for a complete example.

Schema overview:
- Server table: `[ServerName]`
  - `endpoint` (string, required): Base URL to the backend (eg: `https://api.openai.com/v1`)
    - If the endpoint ends with `/v1` and you call `/v1/...`, the proxy strips the duplicate `/v1` once.
  - `api_key` (string, optional): If provided, forwarded as `Authorization: Bearer <api_key>` to the backend
  - `models` (array[string], optional): Allow-list; only these models are exposed. Prefix with `*` to add hidden models that bypass the backend's `/v1/models` list (eg: Fireworks Fire Pass models).
  - `defaults` (object, optional): Deep merge applied only for missing fields (objects only)
  - `overrides` (object, optional): Deep merge that force-sets values (overwrites request fields)
  - `deny` (array[string], optional): Fields to remove (JSON Pointers, or dot-paths converted to pointers)
  - `hide_base_models` (bool, optional, default false): If true, base models are not listed nor routable; only profile-suffixed models are available
  - `default_system_message` (string, optional): If `messages` array exists and no `system` role, insert one at the beginning
  - `default_developer_message` (string, optional): If `messages` array exists and no `developer` role, insert one right after the first `system` message (or at the beginning if none)

- Profiles (virtual models): `[ServerName.profileSuffix]`
  - Same optional fields as server-level for params/messages:
    - `defaults`, `overrides`, `deny`
    - `default_system_message`, `default_developer_message`
  - The profile suffix forms virtual model IDs as `baseModel-profileSuffix`, but the backend receives the base model name (the proxy rewrites it).

Behavior notes:
- Arrays (eg: `messages`) are not deeply merged:
  - `overrides.messages` replaces the entire array
  - `defaults.messages` is used only if `messages` is entirely absent
- deny removes object fields only (array element removal is not supported)
- Profile-level values override server-level values for that request
- Hidden models: prefix any model in `models` with `*` to expose it even if the backend doesn't list it in `/v1/models`. The `*` is stripped when routing to the backend.

Example `config.toml` which I use:

```toml
[OpenAI]
endpoint = "https://api.openai.com/v1"
api_key = "sk-proj-..."
models = [ "gpt-5", "o3", "o1" ]
overrides = { stream = true, stream_options = { include_usage = true } }
deny = ["/temperature"]
default_developer_message = "Formatting re-enabled"
hide_base_models = true

[OpenAI.high]
overrides = { reasoning_effort = "high" }

[OpenRouter]
endpoint = "https://openrouter.ai/api/v1"
api_key = "sk-or-v1-..."
models = ["anthropic/claude-sonnet-4", "anthropic/claude-opus-4.1", "google/gemini-2.5-pro"]
overrides = { temperature = 0.0, stream = true, stream_options = { include_usage = true } }
hide_base_models = true

[OpenRouter.high]
overrides = { reasoning = { "max_tokens" = 32000 } }

[DeepSeek]
endpoint = "https://api.deepseek.com/v1"
api_key = "sk-..."
models = ["deepseek-chat", "deepseek-reasoner"]
overrides = { temperature = 0.0, stream = true, stream_options = { include_usage = true } }

[Fireworks]
endpoint = "https://api.fireworks.ai/inference/v1"
api_key = "fw_..."
models = ["*accounts/fireworks/routers/kimi-k2p5-turbo"]
overrides = { temperature = 0.0, stream = true, stream_options = { include_usage = true } }

["SERVER-Z-8080"]
endpoint = "http://192.168.1.115:8080"
deny = ["/temperature"]

["SERVER-Z-8081"]
endpoint = "http://192.168.1.115:8081"
deny = ["/temperature"]

["MAC-STUDIO-8080"]
endpoint = "http://192.168.1.120:8080"
deny = ["/temperature"]

["MAC-STUDIO-8081"]
endpoint = "http://192.168.1.120:8081"
deny = ["/temperature"]
```

See the example [config.toml](examples/config.toml) file for more detailed examples including multiple servers and profiles.

---

## API Behavior

- Models listing: `GET /v1/models`
  - The proxy aggregates models from all configured servers, applying allow-lists and profile expansion
  - Respects `hide_base_models` (shows only profile-suffixed models if enabled)
  - Hidden models (prefixed with `*` in config) are included in the listing with the `*` removed

- Chat Completions (OpenAI-compatible):
  - The proxy applies deny → defaults → overrides
  - Virtual models: incoming `model: base-suffix` is rewritten to `base` for the backend
  - Streaming (SSE) is disabled by default; set `"stream": true` to enable

---

## Command-Line Options

The proxy accepts various command-line options to customize behavior:

```
-p, --port <num>                    Port to listen on (default: 3000, range: 1024-65535)
-t, --connection-timeout <dur>      Connection timeout (default: 10s, min: 1s, max: 5m)
-r, --request-timeout <dur>         Request timeout (default: 1h, min: 10s, max: 24h)
-m, --model-request-timeout <dur>   Model discovery timeout (default: 5s, min: 1s, max: 2m)
-i, --model-refresh-interval <dur>  Model list refresh interval (default: 5m, min: 10s, max: 1h)
-v, --verbose                       Enable INFO console output (WARNING always shown)
-l, --log                           Enable file logging to llm-proxy.log
-c, --config <path>                 Config file path (default: llm-proxy.toml)
-h, --help                          Show help message
```

### Duration Format

Durations can be specified as:
- `500ms` - milliseconds
- `10s` - seconds
- `5m` - minutes
- `1h` - hours
- `10` - bare integers are treated as seconds

### Examples

```bash
# Run on port 8080 with verbose output
java -jar target/llm-proxy-1.0.0.jar -p 8080 -v

# Custom timeouts and refresh interval
java -jar target/llm-proxy-1.0.0.jar -t 5s -r 30m -i 10m

# Use custom config file with file logging
java -jar target/llm-proxy-1.0.0.jar -c my-config.toml -l
```

---

## Helper Scripts

- Build:
  - `./compile.sh` → `mvn compile && mvn package`
- Run (dev):
  - `./run.sh` → `mvn exec:java`
- Run JAR:
  - `./run-jar.sh` → `java -jar target/llm-proxy-1.0.0.jar`

---

## Troubleshooting

- Ensure `llm-proxy.toml` (or your config file specified with `--config`) is readable and follows the schema
- If models aren't visible in `/v1/models`:
  - Check allow-lists (`models`)
  - Check `hide_base_models`
  - Verify the backend supports `/v1/models` and returns expected data
  - For hidden models (prefixed with `*`), ensure the `*` is present in the config and the full model name is correct
- Use `--verbose` or `-v` to log transformed requests (be mindful of sensitive data)
- Check `llm-proxy.log` for detailed logs (if `--log` or `-l` was used)

---

## License

Apache 2.0 License - See [LICENSE](LICENSE) for details