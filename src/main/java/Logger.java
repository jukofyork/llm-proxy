import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple logging utility for clean console and file output.
 */
public final class Logger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static ProxySettings settings;

    private enum LogLevel {
        INFO("[INFO]", System.out),
        WARNING("[WARNING]", System.err),
        ERROR("[ERROR]", System.err);

        private final String prefix;
        private final PrintStream stream;

        LogLevel(String prefix, PrintStream stream) {
            this.prefix = prefix;
            this.stream = stream;
        }
    }

    /**
     * Private constructor to prevent instantiation of the class.
     */
    private Logger() {
    }

    /**
     * Initializes the logger with proxy settings.
     *
     * @param proxySettings the settings to use for logging
     */
    public static void initialize(ProxySettings proxySettings) {
        settings = proxySettings;
    }

    /**
     * Logs an informational message to the console and optionally to file.
     * 
     * @param message The message to be logged.
     */
    public static void info(String message) {
        log(LogLevel.INFO, message, null);
    }
    
    /**
     * Logs a warning message to the console and optionally to file.
     * 
     * @param message The message to be logged.
     */
    public static void warning(String message) {
        log(LogLevel.WARNING, message, null);
    }
    
    /**
     * Logs an warning message with an associated exception to the console and optionally to file.
     * 
     * @param message The message to be logged.
     * @param e The exception to be logged.
     */
    public static void warning(String message, Exception e) {
        log(LogLevel.WARNING, message, e);
    }
    
    /**
     * Logs an error message to the console and optionally to file.
     * 
     * @param message The message to be logged.
     */
    public static void error(String message) {
        log(LogLevel.ERROR, message, null);
    }
    
    /**
     * Logs an error message with an associated exception to the console and optionally to file.
     * 
     * @param message The message to be logged.
     * @param e The exception to be logged.
     */
    public static void error(String message, Exception e) {
        log(LogLevel.ERROR, message, e);
    }

    /**
     * Core logging method that handles all log levels and output.
     *
     * @param level The log level
     * @param message The message to be logged
     * @param exception Optional exception to include
     */
    private static void log(LogLevel level, String message, Exception exception) {
        // Skip INFO messages if not in verbose mode
        if (level == LogLevel.INFO && (settings == null || !settings.verbose)) {
            return;
        }

        String logEntry = level.prefix + " " + message;
        if (exception != null) {
            logEntry += ": " + exception.getMessage();
        }

        level.stream.println(logEntry);
        writeToFile(logEntry);
    }

    /**
     * Writes a log entry to file if file logging is enabled.
     *
     * @param logEntry The log entry to write to file.
     */
    private static void writeToFile(String logEntry) {
        if (settings == null || !settings.logToFile) {
            return;
        }

        try (FileWriter fileWriter = new FileWriter(settings.logFile, true);
             PrintWriter printWriter = new PrintWriter(fileWriter)) {

            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            printWriter.println(timestamp + " " + logEntry);

        } catch (IOException e) {
            // Avoid infinite recursion by not using Logger.error here
            System.err.println("[ERROR] Failed to write to log file: " + e.getMessage());
        }
    }
}