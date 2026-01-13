package xiangze.mmu.rssnewsreader.service.util;

import android.util.Log;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

public class AutoTranslator {

    private static final String TAG = "AutoTranslator";
    private final EntryRepository entryRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository prefs;
    private final String delimiter = "--####--";

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    public AutoTranslator(EntryRepository entryRepository, TextUtil textUtil, SharedPreferencesRepository prefs) {
        this.entryRepository = entryRepository;
        this.textUtil = textUtil;
        this.prefs = prefs;
    }

    /**
     * Runs translation sequentially.
     * WARNING: This method blocks the calling thread.
     * Do NOT call this directly from the Main/UI Thread.
     */
    public void runAutoTranslation(@Nullable Runnable onComplete) {
        if (!prefs.getAutoTranslate()) {
            Log.d(TAG, "Auto-translate disabled by user.");
            if (onComplete != null) onComplete.run();
            return;
        }

        // Fetch list of items that need translation
        List<Entry> untranslatedEntries = entryRepository.getUntranslatedEntries();

        if (untranslatedEntries.isEmpty()) {
            Log.d(TAG, "No untranslated entries found.");
            if (onComplete != null) onComplete.run();
            return;
        }

        Log.d(TAG, "Starting batch translation for " + untranslatedEntries.size() + " entries.");

        for (Entry entry : untranslatedEntries) {
            long id = entry.getId();

            try {
                // 1. Check if already translated (Double check to save quota)
                String currentHtml = entry.getTranslatedHtml();
                if (currentHtml != null && currentHtml.contains("translated-title")) {
                    Log.d(TAG, "Skipping ID " + id + " - Already contains translated marker.");
                    continue;
                }

                String content = entry.getContent();
                String title = entry.getTitle();

                // 2. Identify Language (Synchronous / Blocking)
                // blockingGet() waits until the network call finishes.
                String sourceLang = textUtil.identifyLanguageRx(content)
                        .subscribeOn(Schedulers.io())
                        .blockingGet();

                String targetLang = prefs.getDefaultTranslationLanguage();

                // 3. Skip if languages match
                if (sourceLang.equalsIgnoreCase(targetLang)) {
                    Log.d(TAG, "Skipping ID " + id + " - Source is already " + targetLang);
                    continue;
                }

                Log.d(TAG, "Translating ID " + id + " from " + sourceLang + " to " + targetLang);

                // 4. Select Method
                String method = prefs.getTranslationMethod();
                Single<String> translationSingle;

                // Pass empty progress listener since we are in background
                if ("lineByLine".equalsIgnoreCase(method)) {
                    translationSingle = textUtil.translateHtmlLineByLine(sourceLang, targetLang, currentHtml, title, id);
                } else if ("paragraphByParagraph".equalsIgnoreCase(method)) {
                    translationSingle = textUtil.translateHtmlByParagraph(sourceLang, targetLang, currentHtml, title, id, progress -> {});
                } else {
                    // This calls your updated method with the Semaphore
                    translationSingle = textUtil.translateHtmlAllAtOnce(sourceLang, targetLang, currentHtml, title, id, progress -> {});
                }

                // 5. Execute Translation (Synchronous / Blocking)
                // If this fails (Network error, Rate limit), it throws an exception immediately.
                String translatedHtml = translationSingle.blockingGet();

                // 6. Save to Database (Only reached if step 5 succeeds)
                String existingOriginal = entryRepository.getOriginalHtmlById(id);

                // Backup original if needed
                if ((existingOriginal == null || existingOriginal.trim().isEmpty()) && currentHtml != null && !currentHtml.trim().isEmpty()) {
                    entryRepository.updateOriginalHtml(currentHtml, id);
                }

                // Save new data
                entryRepository.updateHtml(translatedHtml, id);

                String translatedContent = textUtil.extractHtmlContent(translatedHtml, delimiter);
                entryRepository.updateTranslatedText(translatedContent, id);
                entryRepository.updateTranslated(translatedContent, id);
                entryRepository.updateTranslatedHtml(translatedHtml, id);

                // Update in-memory object just in case
                entry.setTranslatedHtml(translatedHtml);
                entry.setTranslated(translatedContent);

                prefs.setIsTranslatedView(id, true);

                Log.d(TAG, "SUCCESS: Translated ID " + id);

            } catch (Exception e) {
                Log.e(TAG, "CRITICAL ERROR translating ID " + id + ": " + e.getMessage());
                Log.e(TAG, "Stopping entire batch translation due to error.");

                // STOP EVERYTHING: Break the loop.
                // Any remaining entries in 'untranslatedEntries' will wait for the next scheduled worker run.
                break;
            }
        }

        // Batch finished (or stopped early)
        if (onComplete != null) {
            onComplete.run();
        }
    }

    public void runAutoTranslation() {
        runAutoTranslation(null);
    }
}
