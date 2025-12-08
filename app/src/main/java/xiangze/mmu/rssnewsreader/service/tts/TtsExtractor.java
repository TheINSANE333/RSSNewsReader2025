package xiangze.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.ContextCompat;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.feed.FeedRepository;
import xiangze.mmu.rssnewsreader.data.playlist.PlaylistRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewListener;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.extended.Readability4JExtended;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class TtsExtractor {

    private final String TAG = TtsExtractor.class.getSimpleName();
    private String currentLanguage;
    private boolean isLockedByTtsPlayer = false;
    private final Context context;
    private final EntryRepository entryRepository;
    private final FeedRepository feedRepository;
    private final PlaylistRepository playlistRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private WebView webView;
    private String currentLink;
    private String currentTitle;
    private long currentIdInProgress;
    private boolean extractionInProgress;
    private int delayTime;
    private TtsPlayerListener ttsCallback;
    private TtsPlaylist ttsPlaylist;
    private WebViewListener webViewCallback;
    private Date playlistDate;
    public final String delimiter = "--####--";
    private final List<Long> failedIds = new ArrayList<>();
    private final HashMap<Long, Integer> retryCountMap = new HashMap<>();
    private final int MAX_RETRIES = 5;
    private long lastExtractStart = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Inject
    public TtsExtractor(@ApplicationContext Context context, TtsPlaylist ttsPlaylist, EntryRepository entryRepository, FeedRepository feedRepository, PlaylistRepository playlistRepository, TextUtil textUtil, SharedPreferencesRepository sharedPreferencesRepository) {
        this.context = context;
        this.ttsPlaylist = ttsPlaylist;
        this.entryRepository = entryRepository;
        this.feedRepository = feedRepository;
        this.playlistRepository = playlistRepository;
        this.textUtil = textUtil;
        this.sharedPreferencesRepository = sharedPreferencesRepository;

        ContextCompat.getMainExecutor(context).execute(new Runnable() {
            @Override
            public void run() {
                webView = new WebView(context);
                webView.setWebViewClient(new WebClient());
                webView.clearCache(true);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
            }
        });
    }

    private void finishAndMoveToNext() {
        Log.d(TAG, "Item complete. Moving to next...");

        // Your existing cleanup logic
        if (currentIdInProgress == ttsPlaylist.getPlayingId()) {
            if (ttsCallback != null) {
                String lang = currentLanguage != null ? currentLanguage : "en";

                Entry entry = entryRepository.getEntryById(currentIdInProgress);
                String contentToRead;

                // We fetch the preference again to ensure we use the latest user setting
                boolean isTranslated = sharedPreferencesRepository.getIsTranslatedView(currentIdInProgress);

                if (isTranslated && entry != null && entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty()) {
                    contentToRead = entry.getTranslated();
                    Log.d(TAG, "[TtsExtractor] Using translated content for TTS");
                } else {
                    contentToRead = entry != null ? entry.getContent() : "";
                    Log.d(TAG, "[TtsExtractor] Using original content for TTS");
                }

                ttsCallback.extractToTts(contentToRead, lang);
                ttsCallback = null; // Consume the callback so it doesn't fire again unexpectedly
            }
        } else {
            Log.d(TAG, "Not playing this ID. Current: " + currentIdInProgress + ", Playing: " + ttsPlaylist.getPlayingId());
        }

        if (webViewCallback != null) {
            webViewCallback.finishedSetup();
            webViewCallback = null;
        }

        currentIdInProgress = -1;
        extractionInProgress = false;

        // LOAD THE NEXT URL
        extractAllEntries();
    }

    public void extractAllEntries() {
        Log.d(TAG, "extractAllEntries called | extractionInProgress = " + extractionInProgress);

        if (extractionInProgress && currentIdInProgress == -1) {
            Log.w(TAG, "Recovery: extractionInProgress = true but currentIdInProgress == -1 → Resetting flag.");
            extractionInProgress = false;
        }

        Entry entry = entryRepository.getEmptyContentEntry();

        if (entry == null && !failedIds.isEmpty()) {
            long retryId = failedIds.remove(0);
            int attempts = retryCountMap.getOrDefault(retryId, 0);

            if (attempts < MAX_RETRIES) {
                retryCountMap.put(retryId, attempts + 1);
                Log.d(TAG, "Retrying failed article ID: " + retryId + " | Attempt " + (attempts + 1));
                entry = entryRepository.getEntryById(retryId);
            } else {
                Log.w(TAG, "Max retries reached for article ID: " + retryId);
                extractAllEntries();
                return;
            }
        }

        if (entry != null) {
            Log.d(TAG, "Next entry: id=" + entry.getId() + ", title=" + entry.getTitle() + ", priority=" + entry.getPriority());
            if (!extractionInProgress) {
                Log.d(TAG, "extracting...");
                extractionInProgress = true;
                currentIdInProgress = entry.getId();
                currentLink = entry.getLink();
                currentTitle = entry.getTitle();
                delayTime = feedRepository.getDelayTimeById(entry.getFeedId());
                ContextCompat.getMainExecutor(context).execute(new Runnable() {
                    @Override
                    public void run() {
                        webView.loadUrl(currentLink);
                        Log.d("Test url",currentLink);
                    }
                });
                lastExtractStart = System.currentTimeMillis();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (extractionInProgress && System.currentTimeMillis() - lastExtractStart > 30000) {
                        Log.w(TAG, "[Timeout] Extraction stuck >30s, resetting manually");
                        failedIds.add(currentIdInProgress);
                        currentIdInProgress = -1;
                        extractionInProgress = false;
                        extractAllEntries();
                    }
                }, 30000);
            }
        }else {
            Log.d(TAG, "No entry returned by getEmptyContentEntry()");
        }
    }

    private void translateHtml(String html, String content, final long currentIdInProgress, String currentTitle) {
        String sourceLanguage = textUtil.identifyLanguageRx(content).blockingGet();
        String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        setCurrentLanguage(targetLanguage, false);

        if (!sourceLanguage.equals(targetLanguage)) {
            Log.d(TAG, "translateHtml: translating from " + sourceLanguage + " to " + targetLanguage);

            String method = sharedPreferencesRepository.getTranslationMethod();
            Single<String> translationSingle;

            if ("lineByLine".equalsIgnoreCase(method)) {
                translationSingle = textUtil.translateHtmlLineByLine(
                        sourceLanguage,
                        targetLanguage,
                        html,
                        currentTitle,
                        currentIdInProgress
                );
            } else if ("paragraphByParagraph".equalsIgnoreCase(method)) {
                translationSingle = textUtil.translateHtmlByParagraph(
                        sourceLanguage,
                        targetLanguage,
                        html,
                        currentTitle,
                        currentIdInProgress,
                        progress -> {}
                );
            } else {
                translationSingle = textUtil.translateHtmlAllAtOnce(
                        sourceLanguage,
                        targetLanguage,
                        html,
                        currentTitle,
                        currentIdInProgress,
                        progress -> {}
                );
            }

            translationSingle
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            translatedHtml -> {
                                entryRepository.updateHtml(translatedHtml, currentIdInProgress);
                                String translatedContent = textUtil.extractHtmlContent(translatedHtml, delimiter);
                                entryRepository.updateTranslatedText(translatedContent, currentIdInProgress);
                                entryRepository.updateTranslated(translatedContent, currentIdInProgress);
                                setCurrentLanguage(targetLanguage, true);

                                if (!sharedPreferencesRepository.hasTranslationToggle(currentIdInProgress)) {
                                    sharedPreferencesRepository.setIsTranslatedView(currentIdInProgress, true);
                                    Log.d(TAG, "[translateHtml] Defaulting to translated view since this is first-time translation.");
                                }

                                Log.d(TAG, "translateHtml: translation completed and saved");
                            },
                            throwable -> {
                                Log.e(TAG, "translateHtml: error translating", throwable);
                                failedIds.add(currentIdInProgress);
                            }
                    );
        }
    }

    public void setCallback(TtsPlayerListener callback) {
        this.ttsCallback = callback;
    }

    public void setCallback(WebViewListener callback) {
        this.webViewCallback = callback;
    }

    public void prioritize() {
        Date newPlaylistDate = playlistRepository.getLatestPlaylistCreatedDate();

        if (playlistDate == null || !playlistDate.equals(newPlaylistDate)) {
            playlistDate = newPlaylistDate;
            entryRepository.clearPriority();
            List<Long> playlist = stringToLongList(playlistRepository.getLatestPlaylist());
            long lastId = entryRepository.getLastVisitedEntryId();
            int index = playlist.indexOf(lastId);
            int priority = 1;
            entryRepository.updatePriority(priority, lastId);

            boolean loop = true;
            while (loop) {
                index += 1;
                priority += 1;
                if (index < playlist.size()) {
                    long id = playlist.get(index);
                    entryRepository.updatePriority(priority, id);
                } else {
                    loop = false;
                }
            }
        }
        extractAllEntries();
    }

    public class WebClient extends WebViewClient {

        private final Handler handler = new Handler();

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "[onPageFinished] triggered for: " + url);
            super.onPageFinished(view, url);
            if (extractionInProgress && webView.getProgress() == 100) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript("(function() {return document.getElementsByTagName('html')[0].outerHTML;})();", new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(final String value) {
                                Log.d(TAG, "Receiving value...");
                                JsonReader reader = new JsonReader(new StringReader(value));
                                reader.setLenient(true);
                                boolean stopExtracting = false;
                                StringBuilder content = new StringBuilder();
                                try {
                                    if (reader.peek() == JsonToken.STRING) {
                                        String html = reader.nextString();
                                        boolean isTranslated = sharedPreferencesRepository.getIsTranslatedView(currentIdInProgress);
                                        if (html != null) {
                                            Readability4JExtended readability4J = new Readability4JExtended(currentLink, html);
                                            Article article = readability4J.parse();

                                            if (currentTitle != null && !currentTitle.isEmpty()) {
                                                content.append(currentTitle).append(delimiter);
                                            }

                                            if (article.getContentWithUtf8Encoding() != null) {
                                                Document doc = Jsoup.parse(article.getContentWithUtf8Encoding());
                                                doc.select("img").removeAttr("width");
                                                doc.select("img").removeAttr("height");
                                                doc.select("img").removeAttr("sizes");
                                                doc.select("img").removeAttr("srcset");
                                                doc.select("h1").remove();
                                                doc.select("img").attr("style", "border-radius: 5px; width: 100%; margin-left:0"); // find all images and set width to 100%
                                                doc.select("figure").attr("style", "width: 100%; margin-left:0"); // find all images and set width to 100%
                                                doc.select("iframe").attr("style", "width: 100%; margin-left:0"); // find all images and set width to 100%

                                                List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
                                                for (Element element : doc.getAllElements()) {
                                                    if (tags.contains(element.tagName())) {
                                                        boolean sameContent = false;
                                                        for (Element child : element.children()) {
                                                            if (tags.contains(child.tagName())) {
                                                                sameContent = true;
                                                            }
                                                        }
                                                        if (!sameContent) {
                                                            String text = element.text().trim();
                                                            if (!text.isEmpty() && text.length() > 1) {
                                                                if (currentTitle != null && !currentTitle.isEmpty()) {
                                                                    content.append(delimiter).append(text);
                                                                } else {
                                                                    content.append(text);
                                                                }
                                                            } else {
                                                                element.remove();
                                                            }
                                                        }
                                                    }
                                                }

                                                entryRepository.updateHtml(doc.html(), currentIdInProgress);

                                                if (entryRepository.getOriginalHtmlById(currentIdInProgress) == null) {
                                                    entryRepository.updateOriginalHtml(doc.html(), currentIdInProgress);
                                                    entryRepository.updateContent(content.toString(), currentIdInProgress);
                                                }

                                                final long processingId = currentIdInProgress;
                                                final String processingTitle = currentTitle;

                                                boolean shouldTranslate = sharedPreferencesRepository.getAutoTranslate();
                                                String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();

                                                if (shouldTranslate) {
                                                    Log.d(TAG, "Queue paused. Waiting for translation of ID: " + currentIdInProgress);
                                                    textUtil.translateHtmlAllAtOnce(
                                                                    currentLanguage, // source
                                                                    targetLanguage,  // target
                                                                    doc.html(),      // html
                                                                    processingTitle,
                                                                    processingId,
                                                                    progress -> {}   // Empty progress for background scrape
                                                            )
                                                            .subscribeOn(Schedulers.io())
                                                            .observeOn(AndroidSchedulers.mainThread()) // Must be Main thread to call WebClient methods
                                                            .subscribe(translatedHtml -> {
                                                                // --- SUCCESS ---
                                                                Log.d(TAG, "Translation finished for ID: " + processingId);

                                                                // Save Translated Data
                                                                entryRepository.updateHtml(translatedHtml, currentIdInProgress);
                                                                String translatedContent = textUtil.extractHtmlContent(translatedHtml, "--####--");
                                                                entryRepository.updateTranslatedText(translatedContent, currentIdInProgress);
                                                                entryRepository.updateTranslated(translatedContent, currentIdInProgress);

                                                                // 3. NOW we move to the next item (One-by-One flow)
                                                                finishAndMoveToNext();
                                                            }, error -> {
                                                                // --- ERROR ---
                                                                Log.e(TAG, "Translation Failed for ID: " + processingId, error);

                                                                // 4. STOP EVERYTHING
                                                                // We do NOT call extractAllEntries(). The queue dies here.
                                                                if (webViewCallback != null) {
                                                                    webViewCallback.makeSnackbar("Translation error. Queue stopped.");
                                                                    webViewCallback.finishedSetup();
                                                                }
                                                                extractionInProgress = false;
                                                                currentIdInProgress = -1;
                                                                // The loop ends because we didn't call extractAllEntries()
                                                            });

                                                }
                                                else {
                                                    // No translation needed? Proceed immediately.
                                                    finishAndMoveToNext();
                                                }

                                                if (content.toString().isEmpty()) {
                                                    stopExtracting = true;
                                                }

                                                if (currentIdInProgress == ttsPlaylist.getPlayingId()) {
                                                    if (ttsCallback != null) {
                                                        String lang = currentLanguage != null ? currentLanguage : "en";

                                                        Entry entry = entryRepository.getEntryById(currentIdInProgress);
                                                        String contentToRead;

                                                        if (isTranslated && entry != null && entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty()) {
                                                            contentToRead = entry.getTranslated();
                                                            Log.d(TAG, "[TtsExtractor] Using translated content for TTS");
                                                        } else {
                                                            contentToRead = entry != null ? entry.getContent() : "";
                                                            Log.d(TAG, "[TtsExtractor] Using original content for TTS");
                                                        }

                                                        ttsCallback.extractToTts(contentToRead, lang);
                                                        ttsCallback = null;
                                                    }
                                                } else {
                                                    Log.d(TAG, "not playing this ID");
                                                }
                                            } else {
                                                Log.d(TAG, "Empty content");
                                            }
                                        } else {
                                            if (webViewCallback != null) {
                                                webViewCallback.makeSnackbar("Failed to retrieve the html");
                                            }
                                            Log.d(TAG, "No html found!");
                                        }
                                    } else {
                                        Log.e(TAG, "[onReceiveValue] Unexpected JSON token");
                                        if (webViewCallback != null) {
                                            webViewCallback.makeSnackbar("Extraction failed");
                                        }
                                        Log.d(TAG, "Error peeking reader!");
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "[onReceiveValue] Exception during extraction", e);
                                    failedIds.add(currentIdInProgress);
                                    Log.d(TAG, e.getMessage());
                                    e.printStackTrace();
                                } finally {
                                    Log.d(TAG, "[onReceiveValue] Finally block: resetting flags for ID = " + currentIdInProgress);
                                    currentIdInProgress = -1;
                                    extractionInProgress = false;
                                    extractAllEntries();
                                }

                                if (stopExtracting || content.toString().isEmpty()) {
                                    Log.w(TAG, "Extraction failed for ID: " + currentIdInProgress);
                                    failedIds.add(currentIdInProgress);
                                }

                                if (webViewCallback != null) {
                                    Log.d(TAG, "Extraction complete. Notifying UI via finishedSetup()");
                                    webViewCallback.finishedSetup();
                                    webViewCallback = null;
                                }

                                Log.d(TAG, "[onReceiveValue] Extraction completed for ID: " + currentIdInProgress);
                                currentIdInProgress = -1;
                                extractionInProgress = false;
                                extractAllEntries();
                            }
                        });
                    }
                }, delayTime * 1000L);
            } else {
                Log.d(TAG, "loading WebView");
            }
        }
    }

    public void setCurrentLanguage(String lang, boolean lock) {
        Log.d("TtsExtractor", "[setCurrentLanguage] REQUESTED lang = " + lang + ", lock = " + lock + " | current = " + currentLanguage + ", isLocked = " + isLockedByTtsPlayer);

        Log.d("TtsExtractor", Log.getStackTraceString(new Throwable()));

        if (!isLockedByTtsPlayer || lock) {
            Log.d("TtsExtractor", "Language set to: " + lang + " | lock=" + lock);
            this.currentLanguage = lang;
            isLockedByTtsPlayer = lock;
        } else {
            Log.d("TtsExtractor", "Ignored language override to: " + lang + " due to lock");
        }

        Log.d("TtsExtractor", "Language set to: " + lang + " | lock=" + lock + " | isLocked=" + isLockedByTtsPlayer);
    }

    public List<Long> stringToLongList(String genreIds) {
        List<Long> list = new ArrayList<>();

        String[] array = genreIds.split(",");

        for (String s : array) {
            if (!s.isEmpty()) {
                list.add(Long.parseLong(s));
            }
        }
        return list;
    }

    public boolean isLocked() {
        return isLockedByTtsPlayer;
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }
}