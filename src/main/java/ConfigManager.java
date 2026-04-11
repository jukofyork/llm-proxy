import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe manager for runtime configuration.
 * Holds the current configuration in an AtomicReference and provides
 * atomic reload operations with validation.
 */
public class ConfigManager {

    private final AtomicReference<RuntimeConfig> configRef;
    private final String configPath;

    /**
     * Creates a new ConfigManager with the initial configuration.
     *
     * @param initialConfig the initial runtime configuration
     * @param configPath the path to the configuration file for reloads
     */
    public ConfigManager(RuntimeConfig initialConfig, String configPath) {
        this.configRef = new AtomicReference<>(initialConfig);
        this.configPath = configPath;
    }

    /**
     * Returns a snapshot of the current configuration.
     * Call this at the start of request processing to get a consistent view.
     *
     * @return the current RuntimeConfig snapshot
     */
    public RuntimeConfig get() {
        return configRef.get();
    }

    /**
     * Reloads the configuration from disk.
     * Validates the new configuration before swapping. If validation fails,
     * logs an error and keeps the current configuration running.
     *
     * @return true if reload succeeded, false if validation failed
     */
    public boolean reload() {
        RuntimeConfig newConfig = ConfigLoader.load(configPath);
        if (newConfig == null) {
            Logger.error("Configuration reload failed - keeping current configuration", null);
            return false;
        }
        configRef.set(newConfig);
        Logger.info("Configuration reloaded successfully");
        return true;
    }
}
