package xiangze.mmu.rssnewsreader.ui.debuglog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.util.AppLogger;

/**
 * Activity that displays the captured debug log entries from {@link AppLogger}.
 * <p>
 * Features:
 * <ul>
 *   <li>Filter by severity (All / Errors / Warnings / Info+)</li>
 *   <li>Tap an entry to expand the full message + stack trace</li>
 *   <li>Long-press to copy a single entry</li>
 *   <li>Share all logs as plain text</li>
 *   <li>Clear all stored logs</li>
 * </ul>
 */
public class DebugLogActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private LogAdapter adapter;
    private View emptyLayout;
    private TextView logCountText;
    private int currentMinPriority = Log.WARN; // Default: show warnings & errors

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug_log);

        // Toolbar
        findViewById(R.id.toolbar_debug_log).setOnClickListener(v -> finish());
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.toolbar_debug_log))
                .setNavigationOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recycler_debug_log);
        emptyLayout = findViewById(R.id.layout_empty);
        logCountText = findViewById(R.id.text_log_count);

        // RecyclerView setup
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Start scrolled to bottom (newest)
        recyclerView.setLayoutManager(layoutManager);
        adapter = new LogAdapter();
        recyclerView.setAdapter(adapter);

        // Filter chips
        ChipGroup chipGroup = findViewById(R.id.chip_group_filter);
        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.chip_all) {
                currentMinPriority = Log.WARN;
            } else if (checkedId == R.id.chip_error) {
                currentMinPriority = Log.ERROR;
            } else if (checkedId == R.id.chip_warn) {
                currentMinPriority = Log.WARN;
            } else if (checkedId == R.id.chip_info) {
                currentMinPriority = Log.INFO;
            }
            refreshLogs();
        });

        // Bottom buttons
        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Clear Logs")
                    .setMessage("Delete all stored debug log entries?")
                    .setPositiveButton("Clear", (dialog, which) -> {
                        AppLogger.clear();
                        refreshLogs();
                        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        findViewById(R.id.btn_share_logs).setOnClickListener(v -> {
            String text = AppLogger.exportAsText();
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "App Debug Logs");
            shareIntent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(shareIntent, "Share debug logs"));
        });

        refreshLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLogs();
    }

    private void refreshLogs() {
        List<AppLogger.LogEntry> entries = AppLogger.getEntries(currentMinPriority);
        adapter.setEntries(entries);

        if (entries.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyLayout.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyLayout.setVisibility(View.GONE);
            recyclerView.scrollToPosition(entries.size() - 1);
        }

        logCountText.setText(entries.size() + " entries"
                + (currentMinPriority > Log.WARN ? " (filtered)" : ""));
    }

    // ---- Adapter ----

    private class LogAdapter extends RecyclerView.Adapter<LogViewHolder> {
        private List<AppLogger.LogEntry> items = new ArrayList<>();
        // Track which entries are expanded (by position)
        private final java.util.Set<Integer> expandedPositions = new java.util.HashSet<>();

        void setEntries(List<AppLogger.LogEntry> entries) {
            items = new ArrayList<>(entries);
            expandedPositions.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_log_entry, parent, false);
            return new LogViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
            AppLogger.LogEntry entry = items.get(position);
            boolean expanded = expandedPositions.contains(position);
            holder.bind(entry, expanded);

            // Tap to expand/collapse
            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                if (expandedPositions.contains(pos)) {
                    expandedPositions.remove(pos);
                } else {
                    expandedPositions.add(pos);
                }
                notifyItemChanged(pos);
            });

            // Long-press to copy
            holder.itemView.setOnLongClickListener(v -> {
                StringBuilder sb = new StringBuilder();
                sb.append(entry.formattedTime())
                        .append(" [").append(entry.priorityLabel()).append("] ");
                if (entry.tag != null) sb.append(entry.tag).append(": ");
                sb.append(entry.message);
                if (entry.throwable != null) sb.append("\n").append(entry.throwable);

                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Log Entry", sb.toString()));
                Toast.makeText(DebugLogActivity.this, "Log entry copied", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        private final TextView priorityText;
        private final TextView tagText;
        private final TextView timestampText;
        private final TextView messageText;
        private final TextView throwableText;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            priorityText = itemView.findViewById(R.id.text_priority);
            tagText = itemView.findViewById(R.id.text_tag);
            timestampText = itemView.findViewById(R.id.text_timestamp);
            messageText = itemView.findViewById(R.id.text_message);
            throwableText = itemView.findViewById(R.id.text_throwable);
        }

        void bind(AppLogger.LogEntry entry, boolean expanded) {
            priorityText.setText(entry.priorityLabel());

            // Color the priority pill based on severity
            GradientDrawable bg = (GradientDrawable) priorityText.getBackground().mutate();
            switch (entry.priority) {
                case Log.ERROR:
                case Log.ASSERT:
                    bg.setColor(0xFFD32F2F); // Red
                    break;
                case Log.WARN:
                    bg.setColor(0xFFF57C00); // Orange
                    break;
                case Log.INFO:
                    bg.setColor(0xFF1976D2); // Blue
                    break;
                default:
                    bg.setColor(0xFF757575); // Grey
                    break;
            }

            tagText.setText(entry.tag != null ? entry.tag : "");
            timestampText.setText(entry.formattedTime());

            if (expanded) {
                messageText.setMaxLines(Integer.MAX_VALUE);
            } else {
                messageText.setMaxLines(4);
            }
            messageText.setText(entry.message);

            if (entry.throwable != null && !entry.throwable.isEmpty()) {
                throwableText.setVisibility(expanded ? View.VISIBLE : View.GONE);
                throwableText.setText(entry.throwable);
                if (expanded) {
                    throwableText.setMaxLines(Integer.MAX_VALUE);
                } else {
                    throwableText.setMaxLines(6);
                }
            } else {
                throwableText.setVisibility(View.GONE);
            }
        }
    }
}
