import java.time.Duration;

/**
 * Configuration settings for the LLM Proxy.
 * Values are set via command-line arguments with sensible defaults.
 */
public final class ProxySettings {

    // Port configuration
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;
    public static final int DEFAULT_PORT = 3000;
    public int port = DEFAULT_PORT;

    // Timeout configurations
    public static final Duration MIN_CONNECTION_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration MAX_CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(2);
    public Duration connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;

    public static final Duration MIN_REQUEST_TIMEOUT = Duration.ofMinutes(1);
    public static final Duration MAX_REQUEST_TIMEOUT = Duration.ofHours(24);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofHours(1);
    public Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

    public static final Duration MIN_MODEL_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration MAX_MODEL_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_MODEL_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    public Duration modelRequestTimeout = DEFAULT_MODEL_REQUEST_TIMEOUT;

    public static final Duration MIN_MODEL_REFRESH_INTERVAL = Duration.ofMinutes(1);
    public static final Duration MAX_MODEL_REFRESH_INTERVAL = Duration.ofHours(24);
    public static final Duration DEFAULT_MODEL_REFRESH_INTERVAL = Duration.ofMinutes(15);
    public Duration modelRefreshInterval = DEFAULT_MODEL_REFRESH_INTERVAL;

    // Logging configuration
    public boolean verbose = false;
    public boolean logToFile = false;
    public String logFile = "llm-proxy.log";

    // Config file
    public String configFile = "llm-proxy.toml";

    private ProxySettings() {}

    /**
     * Parses duration from string format (e.g., "2s", "3m", "4h").
     * Bare integers are treated as seconds.
     *
     * @param input the duration string
     * @return parsed Duration
     * @throws IllegalArgumentException if format is invalid
     */
    public static Duration parseDuration(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }

        input = input.trim();

        // Check if it's just a number (treat as seconds)
        try {
            return Duration.ofSeconds(Long.parseLong(input));
        } catch (NumberFormatException e) {
            // Not a bare number, parse with suffix
        }

        // Parse with suffix
        int suffixStart = -1;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!Character.isDigit(c)) {
                suffixStart = i;
                break;
            }
        }

        if (suffixStart <= 0 || suffixStart >= input.length()) {
            throw new IllegalArgumentException(
                    "Invalid duration format: '" + input + "'. Use format: 10s, 5m, 1h"
            );
        }

        try {
            long value = Long.parseLong(input.substring(0, suffixStart));
            String suffix = input.substring(suffixStart);

            return switch (suffix) {
                case "s" -> Duration.ofSeconds(value);
                case "m" -> Duration.ofMinutes(value);
                case "h" -> Duration.ofHours(value);
                default -> throw new IllegalArgumentException(
                        "Invalid duration suffix: '" + suffix + "'. Use: s, m, h"
                );
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid duration format: '" + input + "'. Use format: 10s, 5m, 1h"
            );
        }
    }

    /**
     * Validates all settings are within allowed ranges.
     *
     * @throws IllegalArgumentException if any setting is invalid
     */
    public void validate() {
        // Validate port
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                    "Invalid port: " + port + ". Must be between " + MIN_PORT + " and " + MAX_PORT
            );
        }

        // Validate connection timeout
        if (connectionTimeout.compareTo(MIN_CONNECTION_TIMEOUT) < 0) {
            throw new IllegalArgumentException(
                    "Connection timeout " + formatDuration(connectionTimeout) +
                            " is below minimum of " + formatDuration(MIN_CONNECTION_TIMEOUT)
            );
        }
        if (connectionTimeout.compareTo(MAX_CONNECTION_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Connection timeout " + formatDuration(connectionTimeout) +
                            " exceeds maximum of " + formatDuration(MAX_CONNECTION_TIMEOUT)
            );
        }

        // Validate request timeout
        if (requestTimeout.compareTo(MIN_REQUEST_TIMEOUT) < 0) {
            throw new IllegalArgumentException(
                    "Request timeout " + formatDuration(requestTimeout) +
                            " is below minimum of " + formatDuration(MIN_REQUEST_TIMEOUT)
            );
        }
        if (requestTimeout.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Request timeout " + formatDuration(requestTimeout) +
                            " exceeds maximum of " + formatDuration(MAX_REQUEST_TIMEOUT)
            );
        }

        // Validate model request timeout
        if (modelRequestTimeout.compareTo(MIN_MODEL_REQUEST_TIMEOUT) < 0) {
            throw new IllegalArgumentException(
                    "Model request timeout " + formatDuration(modelRequestTimeout) +
                            " is below minimum of " + formatDuration(MIN_MODEL_REQUEST_TIMEOUT)
            );
        }
        if (modelRequestTimeout.compareTo(MAX_MODEL_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Model request timeout " + formatDuration(modelRequestTimeout) +
                            " exceeds maximum of " + formatDuration(MAX_MODEL_REQUEST_TIMEOUT)
            );
        }

        // Validate model refresh interval
        if (modelRefreshInterval.compareTo(MIN_MODEL_REFRESH_INTERVAL) < 0) {
            throw new IllegalArgumentException(
                    "Model refresh interval " + formatDuration(modelRefreshInterval) +
                            " is below minimum of " + formatDuration(MIN_MODEL_REFRESH_INTERVAL)
            );
        }
        if (modelRefreshInterval.compareTo(MAX_MODEL_REFRESH_INTERVAL) > 0) {
            throw new IllegalArgumentException(
                    "Model refresh interval " + formatDuration(modelRefreshInterval) +
                            " exceeds maximum of " + formatDuration(MAX_MODEL_REFRESH_INTERVAL)
            );
        }
    }

    /**
     * Formats a duration for display.
     */
    private static String formatDuration(Duration d) {
        if (d.getSeconds() < 60) {
            return d.getSeconds() + "s";
        } else if (d.toMinutes() < 60) {
            return d.toMinutes() + "m";
        } else {
            return d.toHours() + "h";
        }
    }

    /**
     * Parses command-line arguments and returns configured ProxySettings.
     *
     * @param args command-line arguments
     * @return configured ProxySettings instance
     * @throws IllegalArgumentException if arguments are invalid
     */
    public static ProxySettings parseArgs(String[] args) {
        ProxySettings settings = new ProxySettings();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-h", "--help" -> {
                    printHelp();
                    System.exit(0);
                }
                case "-p", "--port" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--port requires a port number");
                    }
                    try {
                        settings.port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid port number: " + args[i]);
                    }
                }
                case "-t", "--connection-timeout" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--connection-timeout requires a duration");
                    }
                    settings.connectionTimeout = parseDuration(args[++i]);
                }
                case "-r", "--request-timeout" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--request-timeout requires a duration");
                    }
                    settings.requestTimeout = parseDuration(args[++i]);
                }
                case "-m", "--model-request-timeout" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--model-request-timeout requires a duration");
                    }
                    settings.modelRequestTimeout = parseDuration(args[++i]);
                }
                case "-i", "--model-refresh-interval" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--model-refresh-interval requires a duration");
                    }
                    settings.modelRefreshInterval = parseDuration(args[++i]);
                }
                case "-v", "--verbose" -> settings.verbose = true;
                case "-l", "--log" -> settings.logToFile = true;
                case "-c", "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--config requires a file path");
                    }
                    settings.configFile = args[++i];
                }
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }

        settings.validate();
        return settings;
    }

    /**
     * Prints help text with usage information.
     */
	private static void printHelp() {
		System.out.println("LLM Proxy - OpenAI-compatible API proxy");
		System.out.println();
		System.out.println("Usage: java -jar llm-proxy.jar [OPTIONS]");
		System.out.println();
		System.out.println("Options:");
		System.out.println("  -p, --port <num>                    Port to listen on (default: " + DEFAULT_PORT
				+ ", range: " + MIN_PORT + "-" + MAX_PORT + ")");
		System.out.println("  -t, --connection-timeout <dur>      Connection timeout (default: "
				+ formatDuration(DEFAULT_CONNECTION_TIMEOUT) + ", min: " + formatDuration(MIN_CONNECTION_TIMEOUT)
				+ ", max: " + formatDuration(MAX_CONNECTION_TIMEOUT) + ")");
		System.out.println("  -r, --request-timeout <dur>         Request timeout (default: "
				+ formatDuration(DEFAULT_REQUEST_TIMEOUT) + ", min: " + formatDuration(MIN_REQUEST_TIMEOUT) + ", max: "
				+ formatDuration(MAX_REQUEST_TIMEOUT) + ")");
		System.out.println("  -m, --model-request-timeout <dur>   Model discovery timeout (default: "
				+ formatDuration(DEFAULT_MODEL_REQUEST_TIMEOUT) + ", min: " + formatDuration(MIN_MODEL_REQUEST_TIMEOUT)
				+ ", max: " + formatDuration(MAX_MODEL_REQUEST_TIMEOUT) + ")");
		System.out.println("  -i, --model-refresh-interval <dur>  Model list refresh interval (default: "
				+ formatDuration(DEFAULT_MODEL_REFRESH_INTERVAL) + ", min: "
				+ formatDuration(MIN_MODEL_REFRESH_INTERVAL) + ", max: " + formatDuration(MAX_MODEL_REFRESH_INTERVAL)
				+ ")");
		System.out.println("  -v, --verbose                       Enable INFO console output (WARNING always shown)");
		System.out.println("  -l, --log                           Enable file logging");
		System.out.println("  -c, --config <path>                 Config file path (default: llm-proxy.toml)");
		System.out.println("  -h, --help                          Show this help message");
		System.out.println();
		System.out.println("Duration format:");
		System.out.println("  10s    = 10 seconds");
		System.out.println("  5m     = 5 minutes");
		System.out.println("  1h     = 1 hour");
		System.out.println("  10     = 10 seconds (bare integer)");
		System.out.println();
		System.out.println("Examples:");
		System.out.println("  ./run.sh -p 8080 -v");
		System.out.println("  ./run.sh -i 10m -l");
		System.out.println("  ./run.sh -t 5s -r 30m -c my-config.toml");
	}
}