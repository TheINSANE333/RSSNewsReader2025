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

import java.text.BreakIterator;
import java.util.Locale;

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

    private long lastSuccessfullyProcessedId = -1;

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

        lastSuccessfullyProcessedId = currentIdInProgress;

        // Your existing cleanup logic
        if (currentIdInProgress == ttsPlaylist.getPlayingId()) {
            if (ttsCallback != null) {
                String lang = currentLanguage != null ? currentLanguage : "en";
                Entry entry = entryRepository.getEntryById(currentIdInProgress);
                String contentToRead;

                // We fetch the preference again to ensure we use the latest user setting
                boolean isTranslated = sharedPreferencesRepository.getIsTranslatedView(currentIdInProgress);
                boolean isSummarized = sharedPreferencesRepository.getIsSummarizedView(currentIdInProgress);

                if (isTranslated && entry != null && entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty()) {
                    contentToRead = entry.getTranslated();
                    Log.d(TAG, "[TtsExtractor] Using translated content for TTS");
                } else if (isSummarized && entry != null && entry.getSummarized() != null && !entry.getSummarized().trim().isEmpty()) {
                    contentToRead = entry.getSummarized();
                    Log.d(TAG, "[TtsExtractor] Using summarized content for TTS");
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

                currentLanguage = null;

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

        private final Handler handler = new Handler(Looper.getMainLooper());
        private String currentLoadToken = ""; // Unique ID for every page load attempt
        private static final long TRANSLATION_COOLDOWN_MS = 3000;
        private static final long SUMMARIZATION_COOLDOWN_MS = 5000;
        private boolean hasProcessedCurrentToken = false;

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            currentLoadToken = java.util.UUID.randomUUID().toString();
            hasProcessedCurrentToken = false;
            extractionInProgress = true;
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
            final String executionToken = currentLoadToken;
            if (extractionInProgress && view.getProgress() == 100) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!executionToken.equals(currentLoadToken) || !extractionInProgress || hasProcessedCurrentToken) {
                            Log.d(TAG, "Ignoring stale onPageFinished event.");
                            return;
                        }
                        view.evaluateJavascript("(function() {return document.getElementsByTagName('html')[0].outerHTML;})();", new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(final String value) {
                                if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) {
                                    Log.d(TAG, "Ignoring JS callback. Token mismatch.");
                                    return;
                                }
                                hasProcessedCurrentToken = true;
                                processHtmlExtraction(value);
                            }
                        });
                    }
                }, delayTime * 1000L);
            }
        }
    }

    @SuppressLint("CheckResult")
    private void processHtmlExtraction(String value) {
        Log.d(TAG, "Processing extracted HTML value...");
        JsonReader reader = new JsonReader(new StringReader(value));
        reader.setLenient(true);

        try {
            if (reader.peek() == JsonToken.STRING) {
                String html = reader.nextString();

                if (html != null) {
                    // 1. Parse with Readability4J
                    Readability4JExtended readability4J = new Readability4JExtended(currentLink, html);
                    Article article = readability4J.parse();
                    StringBuilder content = new StringBuilder();

                    if (currentTitle != null && !currentTitle.isEmpty()) {
                        content.append(currentTitle).append(delimiter);
                    }

                    String articleContent = article.getContentWithUtf8Encoding();
                    if (articleContent != null) {
                        // 2. Clean with Jsoup
                        Document doc = Jsoup.parse(articleContent);

                        // Clean images and layout
                        doc.select("img").removeAttr("width");
                        doc.select("img").removeAttr("height");
                        doc.select("img").removeAttr("sizes");
                        doc.select("img").removeAttr("srcset");
                        doc.select("h1").remove();
                        doc.select("img").attr("style", "border-radius: 5px; width: 100%; margin-left:0");
                        doc.select("figure").attr("style", "width: 100%; margin-left:0");
                        doc.select("iframe").attr("style", "width: 100%; margin-left:0");

                        List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");

                        // Initialize the Sentence Iterator with Locale.ROOT for universal language support
                        BreakIterator sentenceIterator = BreakIterator.getSentenceInstance(Locale.ROOT);

                        // Extract text by sentences
                        for (Element element : doc.getAllElements()) {
                            if (tags.contains(element.tagName())) {
                                // Check if this element contains other "target" tags to avoid double-processing nested content
                                boolean hasNestedTag = false;
                                for (Element child : element.children()) {
                                    if (tags.contains(child.tagName())) {
                                        hasNestedTag = true;
                                        break;
                                    }
                                }

                                if (!hasNestedTag) {
                                    String elementText = element.text().trim();
                                    if (!elementText.isEmpty() && elementText.length() > 1) {

                                        // --- START SENTENCE SPLITTING LOGIC ---
                                        sentenceIterator.setText(elementText);
                                        int start = sentenceIterator.first();
                                        for (int end = sentenceIterator.next(); end != BreakIterator.DONE; start = end, end = sentenceIterator.next()) {
                                            String sentence = elementText.substring(start, end).trim();

                                            if (!sentence.isEmpty()) {
                                                if (content.length() > 0) {
                                                    // Always add delimiter BEFORE adding a new sentence
                                                    content.append(delimiter).append(sentence);
                                                } else {
                                                    content.append(sentence);
                                                }
                                            }
                                        }
                                        // --- END SENTENCE SPLITTING LOGIC ---

                                    } else if (elementText.length() <= 1) {
                                        element.remove();
                                    }
                                }
                            }
                        }

                        // Save Content & Backup HTML
                        entryRepository.updateContent(content.toString(), currentIdInProgress);
                        entryRepository.updateOriginalHtml(doc.html(), currentIdInProgress);

                        // View State Logic
                        boolean isSummarizedView = sharedPreferencesRepository.getIsSummarizedView(currentIdInProgress);
                        String existingSummarized = entryRepository.getSummarizedTextById(currentIdInProgress);
                        boolean hasSummarization = existingSummarized != null && !existingSummarized.trim().isEmpty();

                        if (!isSummarizedView || !hasSummarization) {
                            entryRepository.updateHtml(doc.html(), currentIdInProgress);
                        }

                        boolean isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentIdInProgress);
                        String existingTranslated = entryRepository.getTranslatedTextById(currentIdInProgress);
                        boolean hasTranslation = existingTranslated != null && !existingTranslated.trim().isEmpty();

                        if (!isTranslatedView || !hasTranslation) {
                            entryRepository.updateHtml(doc.html(), currentIdInProgress);
                        }

                        // 3. Loop Guard
                        final long processingId = currentIdInProgress;
                        final String processingTitle = currentTitle;

                        if (processingId == lastSuccessfullyProcessedId) {
                            Log.e(TAG, "LOOP DETECTED on ID " + processingId + ". Aborting.");
                            if (webViewCallback != null) {
                                webViewCallback.makeSnackbar("Queue stopped: Loop detected.");
                                webViewCallback.finishedSetup();
                            }
                            extractionInProgress = false;
                            currentIdInProgress = -1;
                            return;
                        }

                        boolean shouldTranslate = sharedPreferencesRepository.getAutoTranslate();
                        boolean shouldSummarize = sharedPreferencesRepository.getAutoSummarize();
                        int length = sharedPreferencesRepository.getSummaryLength();

                        // 4. Determine Source Language
                        Single<String> sourceLangSingle;
                        if (currentLanguage != null && !currentLanguage.isEmpty() && !"und".equalsIgnoreCase(currentLanguage)) {
                            sourceLangSingle = Single.just(currentLanguage);
                        } else {
                            Log.d(TAG, "Language unknown. Detecting from content...");
                            sourceLangSingle = textUtil.identifyLanguageRx(content.toString());
                        }

                        // 5. Chain: Identify -> Translate/Summarize
                        Handler handler = new Handler(Looper.getMainLooper());

                        sourceLangSingle
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(detectedLang -> {
                                    currentLanguage = detectedLang;
                                    String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
                                    boolean isSameLanguage = detectedLang.equalsIgnoreCase(targetLang);

                                    if (shouldTranslate && !isSameLanguage) {
                                        textUtil.translateHtmlAllAtOnce(detectedLang, targetLang, doc.html(), processingTitle, processingId, progress -> {})
                                                .subscribeOn(Schedulers.io())
                                                .observeOn(AndroidSchedulers.mainThread())
                                                .subscribe(translatedHtml -> {
                                                    entryRepository.updateHtml(translatedHtml, processingId);
                                                    entryRepository.updateTranslatedHtml(translatedHtml, processingId);
                                                    String translatedContent = textUtil.extractHtmlContent(translatedHtml, delimiter);
                                                    entryRepository.updateTranslatedText(translatedContent, processingId);
                                                    entryRepository.updateTranslated(translatedContent, processingId);

                                                    if (processingId == currentIdInProgress) {
                                                        handler.postDelayed(this::finishAndMoveToNext, WebClient.TRANSLATION_COOLDOWN_MS);
                                                    }
                                                }, error -> handleError(error, processingId));

                                    } else if (shouldSummarize) {
                                        textUtil.summarizeHtmlAllAtOnce(detectedLang, targetLang, doc.html(), length, processingId, progress -> {})
                                                .subscribeOn(Schedulers.io())
                                                .observeOn(AndroidSchedulers.mainThread())
                                                .subscribe(summarizedHtml -> {
                                                    entryRepository.updateHtml(summarizedHtml, processingId);
                                                    entryRepository.updateSummarizedHtml(summarizedHtml, processingId);
                                                    String summarizedContent = textUtil.extractHtmlContent(summarizedHtml, delimiter);
                                                    entryRepository.updateSummarizedText(summarizedContent, processingId);
                                                    entryRepository.updateSummarized(summarizedContent, processingId);

                                                    if (processingId == currentIdInProgress) {
                                                        handler.postDelayed(this::finishAndMoveToNext, WebClient.TRANSLATION_COOLDOWN_MS);
                                                    }
                                                }, error -> handleError(error, processingId));
                                    } else {
                                        finishAndMoveToNext();
                                    }
                                }, error -> {
                                    Log.e(TAG, "Language detection failed", error);
                                    finishAndMoveToNext();
                                });

                    } else {
                        handleFailure(currentIdInProgress);
                    }
                } else {
                    handleFailure(currentIdInProgress);
                }
            } else {
                handleFailure(currentIdInProgress);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during extraction", e);
            handleFailure(currentIdInProgress);
        }
    }

    // Helper methods to keep the main function cleaner
    private void handleFailure(long id) {
        failedIds.add(id);
        finishAndMoveToNext();
    }

    private void handleError(Throwable error, long id) {
        Log.e(TAG, "Process Failed for ID: " + id, error);
        extractionInProgress = false;
        currentIdInProgress = -1;
        if (webViewCallback != null) {
            webViewCallback.makeSnackbar("Process failed.");
            webViewCallback.finishedSetup();
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