import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * File system watcher for configuration file changes.
 * Uses WatchService to monitor the config file and triggers
 * reloads with debouncing to handle rapid successive edits.
 */
public class ConfigWatcher implements Runnable {

    private final Path watchDir;
    private final String configFileName;
    private final ConfigManager configManager;
    private final int debounceSeconds;
    private final ScheduledExecutorService debounceExecutor;
    private volatile boolean pendingReload = false;

    /**
     * Creates a new ConfigWatcher.
     *
     * @param configPath the full path to the configuration file
     * @param configManager the manager to call for reloads
     * @param debounceSeconds the debounce interval in seconds
     */
    public ConfigWatcher(String configPath, ConfigManager configManager, int debounceSeconds) {
        Path fullPath = Path.of(configPath).toAbsolutePath();
        this.watchDir = fullPath.getParent();
        this.configFileName = fullPath.getFileName().toString();
        this.configManager = configManager;
        this.debounceSeconds = debounceSeconds;
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-reload-debounce");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void run() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            watchDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            Logger.info("Started watching configuration file: " + configFileName);

            while (!Thread.interrupted()) {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                        Path changed = (Path) event.context();
                        if (changed.toString().equals(configFileName)) {
                            debounceReload();
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    Logger.warning("Watch key invalidated, stopping file watcher", null);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.info("Configuration file watcher interrupted");
        } catch (IOException e) {
            Logger.error("Failed to watch configuration file", e);
        } finally {
            debounceExecutor.shutdown();
        }
    }

    /**
     * Debounces reload requests to handle rapid successive edits.
     * Only schedules a reload if one isn't already pending.
     */
    private void debounceReload() {
        if (!pendingReload) {
            pendingReload = true;
            debounceExecutor.schedule(() -> {
                try {
                    boolean success = configManager.reload();
                    if (success) {
                        // Pass the new RuntimeConfig to ModelsManager to avoid stale config
                        RuntimeConfig newConfig = configManager.get();
                        ModelsManager.refreshModels(newConfig);
                    }
                } finally {
                    pendingReload = false;
                }
            }, debounceSeconds, TimeUnit.SECONDS);
        }
    }
}
