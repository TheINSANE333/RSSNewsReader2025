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

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    public AutoSummarizer(android.content.Context context, EntryRepository entryRepository, TextUtil textUtil, SharedPreferencesRepository prefs) {
        this.context = context;
        this.entryRepository = entryRepository;
        this.textUtil = textUtil;
        this.prefs = prefs;
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

                while (true) {
                    List<Entry> unsummarizedEntries = entryRepository.getUnsummarizedEntries();
                    if (unsummarizedEntries.isEmpty()) break;

                    Entry entry = unsummarizedEntries.get(0);
                    long id = entry.getId();
                    String title = entry.getTitle();

                    try {
                        // 1. Check if already summarized (Double check to save quota)
                        String existingSummarized = entry.getSummarizedHtml();
                        if (existingSummarized != null && existingSummarized.contains("summarized-title")) {
                            Log.d(TAG, "Skipping ID " + id + " - Already contains summarized marker.");
                            continue;
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

                        // Convert plain text summary to HTML with marker
                        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("");
                        org.jsoup.nodes.Element titleElement = doc.body().appendElement("p");
                        titleElement.addClass("summarized-title");
                        titleElement.text(summarizedTitle); // Use the translated title

                        org.jsoup.nodes.Element contentElement = doc.body().appendElement("p");
                        contentElement.text(summarizedBody); // Use text() to escape any HTML in the summary itself

                        String finalSummarizedHtml = doc.html();

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
                        
                        androidx.core.content.ContextCompat.getMainExecutor(context).execute(() -> {
                            android.widget.Toast.makeText(context, "Summarization failed for: " + title, android.widget.Toast.LENGTH_SHORT).show();
                        });

                        Log.e(TAG, "Stopping entire batch summarization due to error.");
                        break;
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
