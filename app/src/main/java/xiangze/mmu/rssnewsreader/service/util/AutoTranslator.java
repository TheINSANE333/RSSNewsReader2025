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

        // Always execute in background to avoid ANR / deadlocks
        Schedulers.io().scheduleDirect(() -> {

            try {

                if (!prefs.getAutoTranslate()) {
                    Log.d(TAG, "Auto-translate disabled by user.");
                    if (onComplete != null) onComplete.run();
                    return;
                }

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

                        // Always translate original content, not translated HTML
                        String currentHtml = entry.getOriginalHtml();
                        if (currentHtml == null || currentHtml.trim().isEmpty()) {
                            Log.w(TAG, "Skipping ID " + id + " - Empty content.");
                            continue;
                        }

                        String existingTranslated = entry.getTranslatedHtml();
                        if (existingTranslated != null && existingTranslated.contains("translated-title")) {
                            Log.d(TAG, "Skipping ID " + id + " - Already translated.");
                            continue;
                        }

                        String title = entry.getTitle();

                        // Detect language
                        String sourceLang = textUtil.identifyLanguageRx(currentHtml)
                                .subscribeOn(Schedulers.io())
                                .blockingGet();

                        String targetLang = prefs.getDefaultTranslationLanguage();

                        if (sourceLang.equalsIgnoreCase(targetLang)) {
                            Log.d(TAG, "Skipping ID " + id + " - Already in target language.");
                            continue;
                        }

                        Log.d(TAG, "Translating ID " + id + " from " + sourceLang + " to " + targetLang);

                        Single<String> translationSingle;
                        String method = prefs.getTranslationMethod();

                        if ("lineByLine".equalsIgnoreCase(method)) {
                            translationSingle = textUtil.translateHtmlLineByLine(sourceLang, targetLang, currentHtml, title, id);
                        } else if ("paragraphByParagraph".equalsIgnoreCase(method)) {
                            translationSingle = textUtil.translateHtmlByParagraph(sourceLang, targetLang, currentHtml, title, id, p -> {});
                        } else {
                            translationSingle = textUtil.translateHtmlAllAtOnce(sourceLang, targetLang, currentHtml, title, id, p -> {});
                        }

                        Log.d("AUTO TRANSLATOR", "Before blockingGet for ID " + id);

                        String translatedHtml = translationSingle
                                .subscribeOn(Schedulers.io())
                                .blockingGet();

                        Log.d("AUTO TRANSLATOR", "TRANSLATED HTML for ID " + id + ": " + translatedHtml);

                        if (translatedHtml == null || !translatedHtml.contains("[TITLE]") || !translatedHtml.contains("[CONTENT]")) {
                            throw new IllegalStateException("Invalid translated format");
                        }

                        String translatedTitle = translatedHtml.substring(
                                translatedHtml.indexOf("[TITLE]") + 7,
                                translatedHtml.indexOf("[CONTENT]")
                        ).trim();

                        String cleanedTranslated = translatedHtml.substring(
                                translatedHtml.indexOf("[CONTENT]") + 9
                        ).trim();

                        Log.d("AUTO TRANSLATOR", "CLEANED HTML for ID " + id + ": " + cleanedTranslated);
                        Log.d("AUTO TRANSLATOR", "Translated title " + entry.getLink() + ": " + translatedTitle);

                        String existingOriginal = entryRepository.getOriginalHtmlById(id);

                        if ((existingOriginal == null || existingOriginal.trim().isEmpty())) {
                            entryRepository.updateOriginalHtml(currentHtml, id);
                        }

                        entryRepository.updateHtml(cleanedTranslated, id);

                        String translatedContent = textUtil.extractHtmlContent(cleanedTranslated, delimiter);

                        entryRepository.updateTranslatedText(translatedContent, id);
                        entryRepository.updateTranslated(translatedContent, id);
                        entryRepository.updateTranslatedHtml(cleanedTranslated, id);
                        entryRepository.updateTitle(translatedTitle, entry.getFeedId(), entry.getLink());

                        entry.setTranslatedHtml(cleanedTranslated);
                        entry.setTranslated(translatedContent);
                        entry.setTitle(translatedTitle);

                        prefs.setIsTranslatedView(id, true);

                        Log.d(TAG, "SUCCESS: Translated ID " + id);

                    } catch (Exception entryError) {

                        Log.e(TAG, "ERROR translating ID " + id, entryError);
                    }
                }

            } catch (Exception fatal) {
                Log.e(TAG, "Fatal error in auto-translation worker", fatal);
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }

        });
    }


    public void runAutoTranslation() {
        runAutoTranslation(null);
    }
}
