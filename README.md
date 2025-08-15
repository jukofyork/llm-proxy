# LLM Proxy

A lightweight, OpenAI-compatible HTTP proxy that routes requests to multiple backend LLM servers based on the requested model. It supports streaming (SSE), per-server parameter defaults and overrides, virtual profiles, and simple request shaping via a clean TOML configuration.

Key capabilities:
- Single endpoint per server
- Bearer auth via `api_key`
- Model allow-list
- Defaults (apply if missing), overrides (force-set), and deny (JSON Pointers)
- Virtual model profiles (suffix-based)
- Option to hide base models (expose only profile-suffixed models)
- Default system/developer messages upsert (added if missing)
- llama.cpp-style routing (model via Authorization header on non-`/v1` paths)

---

## Quick Start

Prerequisites:
- Java 17+
- Maven 3.x

1) Configure
- Use `examples/config.toml` as a reference and place your config at the path configured by `Constants.CONFIG_FILE` (eg: `config.toml`).

2) Build
- `./compile.sh`
  - Runs `mvn compile` and `mvn package`

3) Run
- Development (Maven Exec): `./run.sh` (runs `mvn exec:java`)
- From JAR: `./run-jar.sh` (runs `java -jar target/llm-proxy-1.0.0.jar`)

The server listens on the port defined by `Constants.PROXY_PORT` (eg: current default is `3000`).

---

## Configuration

Define servers as top-level tables. Each server has one `endpoint` and optional behaviors and profiles. See `examples/config.toml` for a complete example.

Schema overview:
- Server table: `[ServerName]`
  - `endpoint` (string, required): Base URL to the backend (eg: `https://api.openai.com/v1`)
    - If the endpoint ends with `/v1` and you call `/v1/...`, the proxy strips the duplicate `/v1` once.
  - `api_key` (string, optional): If provided, forwarded as `Authorization: Bearer <api_key>` to the backend
  - `models` (array[string], optional): Allow-list; only these models are exposed
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
- llama.cpp-style routing:
  - For non-`/v1` paths, the proxy extracts the model from the inbound `Authorization: Bearer <model>` header
  - The inbound Authorization header is not forwarded to the backend (the backend Authorization is derived from `api_key`, if configured)

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

- Chat Completions (OpenAI-compatible):
  - The proxy applies deny → defaults → overrides
  - Virtual models: incoming `model: base-suffix` is rewritten to `base` for the backend
  - Streaming (SSE) is enabled by default unless `"stream": false` is explicitly set

- llama.cpp-style:
  - For requests not under `/v1`, the proxy extracts the model from `Authorization: Bearer <model>` and routes accordingly

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

- Ensure `config.toml` (or whatever `Constants.CONFIG_FILE` points to) is readable and follows the schema
- If models aren’t visible in `/v1/models`:
  - Check allow-lists (`models`)
  - Check `hide_base_models`
  - Verify the backend supports `/v1/models` and returns expected data
- Enable `Constants.DEBUG_REQUEST` to log transformed requests (be mindful of sensitive data)

---

## License

Apache 2.0 License - See [LICENSE](LICENSE) for details