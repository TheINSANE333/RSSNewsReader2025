package xiangze.mmu.rssnewsreader.service.util;

import android.util.Log;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    public AutoTranslator(EntryRepository entryRepository, TextUtil textUtil, SharedPreferencesRepository prefs) {
        this.entryRepository = entryRepository;
        this.textUtil = textUtil;
        this.prefs = prefs;
    }

    public void runAutoTranslation(@Nullable Runnable onComplete) {
        if (!prefs.getAutoTranslate()) {
            Log.d(TAG, "Auto-translate disabled by user.");
            return;
        }

        List<Entry> untranslatedEntries = entryRepository.getUntranslatedEntries();
        AtomicInteger remaining = new AtomicInteger(untranslatedEntries.size());

        if (untranslatedEntries.isEmpty() && onComplete != null) {
            onComplete.run();
            return;
        }

        for (Entry entry : untranslatedEntries) {
            String html = entry.getHtml();
            String content = entry.getContent();
            String title = entry.getTitle();
            long id = entry.getId();

            if (html != null && !html.contains("translated-title")) {
                textUtil.identifyLanguageRx(content)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(sourceLang -> {
                            String targetLang = prefs.getDefaultTranslationLanguage();
                            if (!sourceLang.equalsIgnoreCase(targetLang)) {
                                String method = prefs.getTranslationMethod();
                                Single<String> translationSingle;

                                if ("lineByLine".equalsIgnoreCase(method)) {
                                    translationSingle = textUtil.translateHtmlLineByLine(sourceLang, targetLang, html, title, id);
                                } else if ("paragraphByParagraph".equalsIgnoreCase(method)) {
                                    translationSingle = textUtil.translateHtmlByParagraph(sourceLang, targetLang, html, title, id, progress-> {});
                                } else {
                                    translationSingle = textUtil.translateHtmlAllAtOnce(sourceLang, targetLang, html, title, id, progress -> {});
                                }

                                translationSingle.subscribe(translatedHtml -> {
                                    String existingOriginal = entryRepository.getOriginalHtmlById(id);
                                    if ((existingOriginal == null || existingOriginal.trim().isEmpty()) && html != null && !html.trim().isEmpty()) {
                                        entryRepository.updateOriginalHtml(html, id);
                                    }
                                    entryRepository.updateHtml(translatedHtml, id);
                                    String translatedContent = textUtil.extractHtmlContent(translatedHtml, delimiter);
                                    entryRepository.updateTranslatedText(translatedContent, id);
                                    entryRepository.updateTranslated(translatedContent, id);
                                    entry.setHtml(translatedHtml);
                                    entry.setTranslated(translatedContent);
                                    Log.d(TAG, "Original HTML saved:\n" + html);
                                    Log.d(TAG, "Translated HTML saved:\n" + translatedHtml);

                                    Log.d(TAG, "Translated article ID: " + id);
                                    if (remaining.decrementAndGet() == 0 && onComplete != null) {
                                        onComplete.run();
                                    }

                                }, error -> {
                                    Log.e(TAG, "Failed to translate article ID: " + id, error);
                                    if (remaining.decrementAndGet() == 0 && onComplete != null) {
                                        onComplete.run();
                                    }
                                });

                            } else {
                                if (remaining.decrementAndGet() == 0 && onComplete != null) {
                                    onComplete.run();
                                }
                            }

                        }, error -> {
                            Log.e(TAG, "Language detection failed for article ID: " + id, error);
                            if (remaining.decrementAndGet() == 0 && onComplete != null) {
                                onComplete.run();
                            }
                        });
            } else {
                if (remaining.decrementAndGet() == 0 && onComplete != null) {
                    onComplete.run();
                }
            }
        }
    }

    public void runAutoTranslation() {
        runAutoTranslation(null);
    }
}
