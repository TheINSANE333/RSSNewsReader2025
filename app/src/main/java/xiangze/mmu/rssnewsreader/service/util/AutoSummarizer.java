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
    private final String delimiter = "--####--";

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    public AutoSummarizer(EntryRepository entryRepository, TextUtil textUtil, SharedPreferencesRepository prefs) {
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
                if (!prefs.getAutoSummarize()) {
                    Log.d(TAG, "Auto-summarize disabled by user.");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                // Fetch list of items that need translation
                List<Entry> unsummarizedEntries = entryRepository.getUnsummarizedEntries();

                if (unsummarizedEntries.isEmpty()) {
                    Log.d(TAG, "No unsummarized entries found.");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                Log.d(TAG, "Starting batch summarization for " + unsummarizedEntries.size() + " entries.");

                for (Entry entry : unsummarizedEntries) {
                    long id = entry.getId();

                    try {
                        // 1. Check if already translated (Double check to save quota)
                        String currentHtml = entry.getSummarizedHtml();
                        if (currentHtml != null && currentHtml.contains("summarized-title")) {
                            Log.d(TAG, "Skipping ID " + id + " - Already contains summarized marker.");
                            continue;
                        }

                        String content = entry.getContent();
                        String title = entry.getTitle();
                        String targetLang = prefs.getDefaultTranslationLanguage();
                        int length = prefs.getSummaryLength();
                        String sourceLang = textUtil.identifyLanguageRx(content)
                                .subscribeOn(Schedulers.io())
                                .blockingGet();

                        Log.d(TAG, "Summarizing ID " + id + "in" + targetLang);

                        Single<String> summarizationSingle;

                        // Pass empty progress listener since we are in background
                        summarizationSingle = textUtil.summarizeHtmlAllAtOnce(sourceLang, targetLang, currentHtml, length, id, progress -> {
                        });

                        // 5. Execute Translation (Synchronous / Blocking)
                        // If this fails (Network error, Rate limit), it throws an exception immediately.
                        String summarizedHtml = summarizationSingle.blockingGet();

                        // 6. Save to Database (Only reached if step 5 succeeds)
                        String existingOriginal = entryRepository.getOriginalHtmlById(id);

                        // Backup original if needed
                        if ((existingOriginal == null || existingOriginal.trim().isEmpty()) && currentHtml != null && !currentHtml.trim().isEmpty()) {
                            entryRepository.updateOriginalHtml(currentHtml, id);
                        }

                        // Save new data
                        entryRepository.updateHtml(summarizedHtml, id);

                        String summarizedContent = textUtil.extractHtmlContent(summarizedHtml, delimiter);
                        entryRepository.updateSummarizedText(summarizedContent, id);
                        entryRepository.updateSummarized(summarizedContent, id);
                        entryRepository.updateSummarizedHtml(summarizedHtml, id);

                        // Update in-memory object just in case
                        entry.setSummarizedHtml(summarizedHtml);
                        entry.setSummarized(summarizedContent);

                        prefs.setIsSummarizedView(id, true);

                        Log.d(TAG, "SUCCESS: Summarized ID " + id);

                        // Add a small delay to avoid hitting rate limits (3 seconds)
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            Log.w(TAG, "Auto-summarization sleep interrupted");
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "CRITICAL ERROR summarizing ID " + id + ": " + e.getMessage());
                        Log.e(TAG, "Stopping entire batch summarization due to error.");

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
