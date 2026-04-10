# Agent Directives

This file provides essential guidance for AI agents working on this codebase.

## Project Overview

A lightweight Java 17 HTTP proxy server that routes OpenAI-compatible API requests to multiple backend LLM servers based on model selection. Uses Maven for build, TOML for configuration, and Jackson for JSON processing.

## Build Commands

```bash
# Compile only
mvn compile

# Package JAR (includes dependencies via shade plugin)
mvn package

# Full build (compile + package)
./compile.sh

# Run in development mode (via Maven exec plugin)
./run.sh

# Run packaged JAR
./run-jar.sh
```

## Testing

**No tests are currently configured in this project.** To add tests:

1. Add JUnit dependency to `pom.xml`:
```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
```

2. Run single test class:
```bash
mvn test -Dtest=ClassName
```

3. Run single test method:
```bash
mvn test -Dtest=ClassName#methodName
```

## Code Style Guidelines

### Formatting
- **Indentation**: 4 spaces (no tabs)
- **Line endings**: LF (Unix-style)
- **Encoding**: UTF-8
- **Max line length**: 120 characters (soft limit)

### Naming Conventions
- **Classes**: PascalCase (e.g., `HttpProxy`, `RouteResolver`)
- **Methods**: camelCase (e.g., `sendResponse`, `readRequestBody`)
- **Variables**: camelCase (e.g., `connectionTimeout`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `PROXY_PORT`, `CONFIG_FILE`)
- **Interfaces**: PascalCase with descriptive names (e.g., `RequestRouter`)
- **Records**: PascalCase (e.g., `ProxyTarget`, `RouteTarget`)

### Imports
- No wildcard imports (`import java.util.*` is NOT allowed)
- Group imports: java.*, javax.*, then third-party (com.*, org.*)
- Static imports allowed only for constants and utility methods
- Import only what is used (no unused imports)

### Type Safety
- Use `var` only when type is obvious from RHS
- Prefer `Optional<T>` over null returns
- Use `Duration` for time periods, not raw integers
- Use `Map<String, String>` for headers (lowercase keys)

### Error Handling
- Never silently swallow exceptions
- Log errors via `Logger.error(message, exception)`
- Use specific exception types, not generic `Exception`
- Validate inputs at method boundaries
- Fail fast with clear error messages

### Documentation
- All public classes must have Javadoc with `@author` optional
- All public methods must have Javadoc explaining:
  - What the method does
  - Parameter descriptions
  - Return value description
  - Exceptions thrown
- Use `{@link ClassName}` for cross-references

### Code Structure
- Utility classes must have private constructor
- Prefer composition over inheritance
- Use records for immutable data carriers
- Keep classes focused (single responsibility)
- Maximum method length: ~50 lines
- Maximum class length: ~300 lines

## Project-Specific Patterns

### Configuration
- Constants in `Constants.java` (no instantiation)
- Runtime config loaded via `ConfigLoader`
- TOML configuration file location set by `Constants.CONFIG_FILE`

### HTTP Handling
- Use `HttpServerWrapper` for incoming requests
- Use `HttpClientWrapper` for outbound requests
- Headers stored as lowercase keys in maps
- Streaming responses use `text/event-stream`

### Routing
- Implement `RequestRouter` interface for custom routing
- Return `null` from `route()` to reject request (400 Bad Request)
- Virtual model format: `baseModel-profileSuffix`

### Logging
- Use `Logger.info()`, `Logger.warning()`, `Logger.error()`
- Enable file logging via `Constants.DEBUG_LOG_TO_FILE`
- Never use `System.out`/`System.err` directly (except in Logger)

## Dependencies

Current dependencies (do not add without justification):
- Jackson databind (JSON processing)
- Jackson TOML (configuration parsing)
- Java 17 standard library (HTTP server, HTTP client)

## File Locations

```
src/main/java/          - Java source files
examples/config.toml    - Example configuration
config.toml            - Runtime configuration (user-created)
target/                 - Build output
pom.xml                - Maven configuration
```

## Common Tasks

### Add a new server configuration
Edit `config.toml` (see `examples/config.toml` for schema)

### Change port or timeouts
Modify `Constants.java` (requires recompile)

### Enable debug logging
Set `Constants.DEBUG_REQUEST = true`

### Add a new dependency
Edit `pom.xml` `<dependencies>` section, then run `mvn compile`
