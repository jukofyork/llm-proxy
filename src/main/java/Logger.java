import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple logging utility for clean console and file output.
 */
public final class Logger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Private constructor to prevent instantiation of the class.
     */
    private Logger() {
    }

    /**
     * Logs an informational message to the console and optionally to file.
     * 
     * @param message The message to be logged.
     */
    public static void info(String message) {
        String logEntry = "[INFO] " + message;
        System.out.println(logEntry);
        writeToFile(logEntry);
    }
    
    /**
     * Logs an error message to the console and optionally to file.
     * 
     * @param message The message to be logged.
     */
    public static void error(String message) {
        String logEntry = "[ERROR] " + message;
        System.err.println(logEntry);
        writeToFile(logEntry);
    }
    
    /**
     * Logs an error message with an associated exception to the console and optionally to file.
     * 
     * @param message The message to be logged.
     * @param e The exception to be logged.
     */
    public static void error(String message, Exception e) {
        String logEntry = "[ERROR] " + message + ": " + e.getMessage();
        System.err.println(logEntry);
        writeToFile(logEntry);
    }

    /**
     * Writes a log entry to file if file logging is enabled.
     * 
     * @param logEntry The log entry to write to file.
     */
    private static void writeToFile(String logEntry) {
        if (!Constants.DEBUG_LOG_TO_FILE) {
            return;
        }

        try (FileWriter fileWriter = new FileWriter(Constants.LOG_FILE, true);
             PrintWriter printWriter = new PrintWriter(fileWriter)) {
            
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            printWriter.println(timestamp + " " + logEntry);
            
        } catch (IOException e) {
            // Avoid infinite recursion by not using Logger.error here
            System.err.println("[ERROR] Failed to write to log file: " + e.getMessage());
        }
    }
}