package xiangze.mmu.rssnewsreader.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

/**
 * A custom Timber {@link Timber.Tree} that captures log messages into an in-memory
 * ring buffer and persists them to a JSONL file on disk.
 * <p>
 * This enables an in-app debug log viewer so the user can review previous
 * error/warning messages without needing to connect logcat.
 * <p>
 * Only messages at or above the configured minimum priority are captured
 * (default: {@link Log#WARN}).
 */
public class AppLogger extends Timber.DebugTree {

    /** Maximum number of log entries kept in memory and on disk. */
    private static final int MAX_ENTRIES = 500;

    private static final String LOG_FILE_NAME = "app_debug_log.jsonl";
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static final LinkedList<LogEntry> entries = new LinkedList<>();
    private static File logFile;
    private static int minPriority = Log.WARN;

    /**
     * Represents a single captured log entry.
     */
    public static class LogEntry {
        public final long timestamp;
        public final int priority;
        public final String tag;
        public final String message;
        @Nullable public final String throwable;

        public LogEntry(long timestamp, int priority, String tag, String message,
                        @Nullable String throwable) {
            this.timestamp = timestamp;
            this.priority = priority;
            this.tag = tag;
            this.message = message;
            this.throwable = throwable;
        }

        /** Human-readable timestamp. */
        public String formattedTime() {
            return DATE_FORMAT.format(new Date(timestamp));
        }

        /** Short priority label (V/D/I/W/E/A). */
        public String priorityLabel() {
            switch (priority) {
                case Log.VERBOSE: return "V";
                case Log.DEBUG:   return "D";
                case Log.INFO:    return "I";
                case Log.WARN:    return "W";
                case Log.ERROR:   return "E";
                case Log.ASSERT:  return "A";
                default:          return "?";
            }
        }

        /** Serialize to JSON for disk persistence. */
        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("ts", timestamp);
            obj.put("p", priority);
            obj.put("tag", tag != null ? tag : "");
            obj.put("msg", message != null ? message : "");
            if (throwable != null) {
                obj.put("ex", throwable);
            }
            return obj;
        }

        /** Deserialize from JSON. */
        public static LogEntry fromJson(JSONObject obj) throws JSONException {
            return new LogEntry(
                    obj.getLong("ts"),
                    obj.getInt("p"),
                    obj.optString("tag", ""),
                    obj.optString("msg", ""),
                    obj.optString("ex", null)
            );
        }
    }

    /**
     * Initialize the logger. Call this early in {@code Application.onCreate()}.
     *
     * @param context application context
     */
    public static void init(@NonNull Context context) {
        logFile = new File(context.getFilesDir(), LOG_FILE_NAME);
        loadFromDisk();
    }

    /**
     * Set the minimum log priority to capture.
     * Defaults to {@link Log#WARN} if not set.
     */
    public static void setMinPriority(int priority) {
        minPriority = priority;
    }

    @Override
    protected void log(int priority, @Nullable String tag, @NonNull String message,
                       @Nullable Throwable t) {
        // Always let DebugTree do its normal logcat output
        super.log(priority, tag, message, t);

        // Only capture messages at or above the minimum priority
        if (priority < minPriority) {
            return;
        }

        String throwableStr = null;
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            throwableStr = sw.toString();
        }

        LogEntry entry = new LogEntry(
                System.currentTimeMillis(),
                priority,
                tag,
                message,
                throwableStr
        );

        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.removeFirst();
            }
        }

        appendToDisk(entry);
    }

    /**
     * Returns an unmodifiable snapshot of all captured log entries (newest last).
     */
    public static List<LogEntry> getEntries() {
        synchronized (entries) {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }

    /**
     * Returns entries filtered by minimum priority.
     */
    public static List<LogEntry> getEntries(int minPriority) {
        synchronized (entries) {
            List<LogEntry> filtered = new ArrayList<>();
            for (LogEntry entry : entries) {
                if (entry.priority >= minPriority) {
                    filtered.add(entry);
                }
            }
            return filtered;
        }
    }

    /**
     * Clears all log entries from memory and disk.
     */
    public static void clear() {
        synchronized (entries) {
            entries.clear();
        }
        if (logFile != null && logFile.exists()) {
            logFile.delete();
        }
    }

    /**
     * Returns all entries as a single string for sharing.
     */
    public static String exportAsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== App Debug Log ===\n");
        sb.append("Exported: ").append(DATE_FORMAT.format(new Date())).append("\n\n");

        List<LogEntry> snapshot = getEntries();
        for (LogEntry entry : snapshot) {
            sb.append(entry.formattedTime())
                    .append(" [").append(entry.priorityLabel()).append("] ");
            if (entry.tag != null && !entry.tag.isEmpty()) {
                sb.append(entry.tag).append(": ");
            }
            sb.append(entry.message).append("\n");
            if (entry.throwable != null) {
                sb.append(entry.throwable).append("\n");
            }
        }
        return sb.toString();
    }

    // ---- Disk persistence ----

    private static void appendToDisk(LogEntry entry) {
        if (logFile == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(entry.toJson().toString());
            writer.newLine();
        } catch (IOException | JSONException e) {
            // Silently fail — we don't want logging to break the app
        }
    }

    private static void loadFromDisk() {
        if (logFile == null || !logFile.exists()) return;

        List<LogEntry> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    loaded.add(LogEntry.fromJson(new JSONObject(line)));
                } catch (JSONException e) {
                    // Skip malformed lines
                }
            }
        } catch (IOException e) {
            // Silently fail
        }

        // Keep only the last MAX_ENTRIES
        int start = Math.max(0, loaded.size() - MAX_ENTRIES);
        synchronized (entries) {
            entries.clear();
            for (int i = start; i < loaded.size(); i++) {
                entries.add(loaded.get(i));
            }
        }

        // Rewrite the file if we trimmed entries
        if (loaded.size() > MAX_ENTRIES) {
            rewriteDisk();
        }
    }

    private static void rewriteDisk() {
        if (logFile == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, false))) {
            synchronized (entries) {
                for (LogEntry entry : entries) {
                    writer.write(entry.toJson().toString());
                    writer.newLine();
                }
            }
        } catch (IOException | JSONException e) {
            // Silently fail
        }
    }
}
