package xiangze.mmu.rssnewsreader.service.util;

import android.util.Log;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

public class AutoSummarizer {

    private static final String TAG = "AutoSummarizer";
    private final EntryRepository entryRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository prefs;
    private final android.content.Context context;
    private final String delimiter = "--####--";
    public static final java.util.Set<Long> processingIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    public static final java.util.Set<Long> failedSessionIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    public AutoSummarizer(android.content.Context context, EntryRepository entryRepository, TextUtil textUtil, SharedPreferencesRepository prefs) {
        this.context = context;
        this.entryRepository = entryRepository;
        this.textUtil = textUtil;
        this.prefs = prefs;
    }

    public static boolean isProcessing(long id) {
        return processingIds.contains(id);
    }

    public static boolean hasFailed(long id) {
        return failedSessionIds.contains(id);
    }

    /**
     * Runs summarization sequentially.
     * WARNING: This method blocks the calling thread.
     * Do NOT call this directly from the Main/UI Thread.
     */
    public void runAutoSummarization(@Nullable Runnable onComplete) {

        Schedulers.io().scheduleDirect(() -> {
            try {
                if (prefs.getAutoSummarize()) {
                    Log.d(TAG, "Starting batch summarization.");
                } else {
                    Log.d(TAG, "Auto-summarize disabled by user.");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                List<Entry> unsummarizedEntries = entryRepository.getUnsummarizedEntries();
                for (Entry entry : unsummarizedEntries) {
                    if (!prefs.getAutoSummarize()) {
                        Log.d(TAG, "Auto-summarize disabled during batch.");
                        break;
                    }

                    long id = entry.getId();
                    String title = entry.getTitle();

                    // Check if already being processed by another thread or failed in this session
                    if (processingIds.contains(id) || failedSessionIds.contains(id)) {
                        Log.d(TAG, "Skipping ID " + id + " (In-progress or failed session)");
                        continue;
                    }

                    try {
                        processingIds.add(id);

                        // Double check summarized marker
                        String existingSummarized = entry.getSummarizedHtml();
                        if (existingSummarized != null && existingSummarized.contains("summarized-title")) {
                            Log.d(TAG, "Skipping ID " + id + " - Already summarized.");
                            continue;
                        } else if (existingSummarized != null && !existingSummarized.trim().isEmpty()) {
                            Log.d(TAG, "ID " + id + " has invalid summarized_html. Resetting.");
                            entryRepository.resetSummarized(id);
                        }

                        // Use original HTML as source for summarization
                        String sourceHtml = entry.getOriginalHtml();
                        if (sourceHtml == null || sourceHtml.trim().isEmpty()) {
                            sourceHtml = entry.getHtml();
                        }

                        if (sourceHtml == null || sourceHtml.trim().isEmpty()) {
                            Log.w(TAG, "Skipping ID " + id + " - No content to summarize.");
                            continue;
                        }

                        String content = entry.getContent();
                        String targetLang = prefs.getDefaultTranslationLanguage();
                        int length = prefs.getSummaryLength();
                        String sourceLang = textUtil.identifyLanguageRx(content)
                                .subscribeOn(Schedulers.io())
                                .blockingGet();

                        Log.d(TAG, "Summarizing ID " + id + " in " + targetLang);

                        Single<String> summarizationSingle;

                        // Pass empty progress listener since we are in background
                        summarizationSingle = textUtil.summarizeHtmlAllAtOnce(sourceLang, targetLang, sourceHtml, length, id, title, progress -> {
                        });

                        // 5. Execute Summarization (Synchronous / Blocking)
                        // If this fails (Network error, Rate limit), it throws an exception immediately.
                        String summaryText = summarizationSingle.blockingGet();

                        String summarizedTitle = "Summary";
                        String summarizedBody = summaryText;

                        if (summaryText.contains("[TITLE]") && summaryText.contains("[CONTENT]") && 
                                summaryText.indexOf("[TITLE]") < summaryText.indexOf("[CONTENT]")) {
                            summarizedTitle = summaryText.substring(
                                    summaryText.indexOf("[TITLE]") + 7,
                                    summaryText.indexOf("[CONTENT]")
                            ).trim();
                            summarizedBody = summaryText.substring(
                                    summaryText.indexOf("[CONTENT]") + 9
                            ).trim();
                        }

                        xiangze.mmu.rssnewsreader.model.EntryInfo info = entryRepository.getEntryInfoById(id);
                        String finalSummarizedHtml = textUtil.formatAiResponseToHtml(
                                summarizedTitle,
                                summarizedBody,
                                info.getFeedTitle(),
                                info.getEntryPublishedDate(),
                                info.getFeedImageUrl(),
                                prefs.getNight(),
                                "summarized-title"
                        );

                        // 6. Save to Database (Only reached if step 5 succeeds)
                        String existingOriginal = entryRepository.getOriginalHtmlById(id);

                        // Backup original if needed
                        if ((existingOriginal == null || existingOriginal.trim().isEmpty()) && sourceHtml != null && !sourceHtml.trim().isEmpty()) {
                            entryRepository.updateOriginalHtml(sourceHtml, id);
                        }

                        // Save new data atomically (without overwriting original 'html' column)
                        String summarizedContent = textUtil.extractHtmlContent(finalSummarizedHtml, delimiter);
                        entryRepository.updateSummarizedHtml(finalSummarizedHtml, id);
                        entryRepository.updateSummarized(summarizedContent, id);

                        // Update in-memory object just in case
                        entry.setSummarizedHtml(finalSummarizedHtml);
                        entry.setSummarized(summarizedContent);

                        // Only auto-switch the view if the user hasn't manually interacted with this article's view state yet
                        if (!prefs.hasSummarizationToggle(id)) {
                            prefs.setIsSummarizedView(id, true);
                        }

                        Log.d(TAG, "SUCCESS: Summarized ID " + id);

                        // Add a small delay to avoid hitting rate limits
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            Log.w(TAG, "Auto-summarization sleep interrupted");
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "CRITICAL ERROR summarizing ID " + id + ": " + e.getMessage());
                        
                        failedSessionIds.add(id);

                        androidx.core.content.ContextCompat.getMainExecutor(context).execute(() -> {
                            android.widget.Toast.makeText(context, "Summarization failed for: " + title, android.widget.Toast.LENGTH_SHORT).show();
                        });

                        Log.e(TAG, "Continuing to next article in batch.");
                        continue;
                    } finally {
                        processingIds.remove(id);
                    }
                }
            } catch (Exception fatal) {
                Log.e(TAG, "Fatal error in auto-summarization worker", fatal);
            } finally {
                // Batch finished (or stopped early)
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    public void runAutoSummarization() {
        runAutoSummarization(null);
    }
}
