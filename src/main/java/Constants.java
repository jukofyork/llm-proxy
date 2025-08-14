import java.time.Duration;

/**
 * Constants for the SimpleProxy server configuration and operation.
 */
public final class Constants {

    /**
     * Private constructor to prevent instantiation of the class.
     */
    private Constants() {
    }

    // Debug Flags
    public static final boolean DEBUG_REQUEST = true;      // Controls input JSON logging
    public static final boolean DEBUG_LOG_TO_FILE = false; // Controls whether to write logs to file

    // Configuration Constants
    public static final int PROXY_PORT = 3000;
    public static final String CONFIG_FILE = "config.toml";
    public static final String LOG_FILE = "simple-proxy.log";
    
    // Model manager's timeout constants
    public static final Duration MODEL_CONNECTION_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration MODEL_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration MODEL_REFRESH_TTL = Duration.ofSeconds(60);

    // Timeout Constants
    // NOTE: The new o-series models can take ages to reply... 5 minutes response time should hopefully be enough.
    public static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

    // API Constants
    public static final String V1_PREFIX = "/v1";
    public static final String MODELS_ENDPOINT = "/models";

    // HTTP Constants
    public static final String HTTP_OK = "HTTP/1.1 200 OK\r\n";
    public static final String HTTP_BAD_REQUEST = "HTTP/1.1 400 Bad Request\r\n";
    public static final String CONTENT_TYPE_JSON = "Content-Type: application/json\r\n\r\n";
    public static final String CONTENT_TYPE_STREAM = "Content-Type: text/event-stream\r\n\r\n";   
}