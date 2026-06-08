package xiangze.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.JsonReader;
import android.util.JsonToken;
import timber.log.Timber;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.ContextCompat;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import xiangze.mmu.rssnewsreader.data.GlobalState;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
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
import org.jsoup.select.Elements;

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

    private String currentLanguage;
    private boolean isLockedByTtsPlayer = false;
    private final Context context;
    private final EntryRepository entryRepository;
    private final FeedRepository feedRepository;
    private final PlaylistRepository playlistRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final PowerManager.WakeLock wakeLock;
    private WebView webView;
    private String currentLink;
    private String currentTitle;
    private volatile long currentIdInProgress;
    private volatile boolean extractionInProgress;

    private synchronized void setExtractionInProgress(boolean inProgress) {
        this.extractionInProgress = inProgress;
        if (inProgress) {
            if (!wakeLock.isHeld()) {
                Timber.d("Acquiring WakeLock for TtsExtractor");
                wakeLock.acquire(10 * 60 * 1000L); // 10 minute timeout to prevent indefinite hold
            }
        } else {
            if (wakeLock.isHeld()) {
                Timber.d("Releasing WakeLock for TtsExtractor");
                wakeLock.release();
            }
        }
    }

    private int delayTime;
    private TtsPlayerListener ttsCallback;

    private final MutableLiveData<Boolean> finishedSetupLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> snackbarMessageLiveData = new MutableLiveData<>();

    public LiveData<Boolean> getFinishedSetupLiveData() { return finishedSetupLiveData; }
    public LiveData<String> getSnackbarMessageLiveData() { return snackbarMessageLiveData; }

    private Date playlistDate;
    public static final String DELIMITER = "--####--";
    private final List<Long> failedIds = java.util.Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> retryCountMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final int MAX_RETRIES = 5;
    private static final int MIN_CONTENT_LENGTH = 100;
    private long lastExtractStart = 0;

    private volatile long lastSuccessfullyProcessedId = -1;

    @SuppressLint({"SetJavaScriptEnabled", "InvalidWakeLockTag"})
    @Inject
    public TtsExtractor(@ApplicationContext Context context, EntryRepository entryRepository, FeedRepository feedRepository, PlaylistRepository playlistRepository, TextUtil textUtil, SharedPreferencesRepository sharedPreferencesRepository) {
        this.context = context;
        this.entryRepository = entryRepository;
        this.feedRepository = feedRepository;
        this.playlistRepository = playlistRepository;
        this.textUtil = textUtil;
        this.sharedPreferencesRepository = sharedPreferencesRepository;

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RSSReader:TtsExtractorLock");

        ContextCompat.getMainExecutor(context).execute(new Runnable() {
            @Override
            public void run() {
                webView = new WebView(context);
                webView.setWebViewClient(new WebClient());
//                webView.clearCache(true);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
//                settings.setDatabaseEnabled(true);
                settings.setLoadsImagesAutomatically(true);
                settings.setBlockNetworkImage(false);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                settings.setSupportMultipleWindows(false);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(true);
                
                // Set a standard mobile User Agent to avoid being blocked by some sites
                settings.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36");

                // Give the WebView a size so that sites that depend on layout can render correctly
                webView.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), 
                               View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
                webView.layout(0, 0, 1080, 1920);

                webView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onProgressChanged(WebView view, int newProgress) {
                        super.onProgressChanged(view, newProgress);
                        Timber.d("[Progress] " + newProgress + "% for: " + currentLink);
                    }
                });
                webView.onResume();
                webView.resumeTimers();
            }
        });
    }

    private void finishAndMoveToNext() {
        Timber.d("Item complete. Moving to next...");

        // Only mark as successfully processed if it wasn't just added to failedIds
        if (!failedIds.contains(currentIdInProgress)) {
            lastSuccessfullyProcessedId = currentIdInProgress;
        } else {
             Timber.d("Item " + currentIdInProgress + " failed, not marking as successfully processed.");
        }

        Schedulers.io().scheduleDirect(() -> {
            // Your existing cleanup logic
            if (currentIdInProgress == GlobalState.getCurrentViewingId()) {
                if (ttsCallback != null) {
                    String lang = currentLanguage != null ? currentLanguage : "en";
                    Entry entry = entryRepository.getEntryById(currentIdInProgress);
                    String contentToRead;

                    // We fetch the preference again to ensure we use the latest user setting
                    boolean isTranslated = sharedPreferencesRepository.getIsTranslatedView(currentIdInProgress);
                    boolean isSummarized = sharedPreferencesRepository.getIsSummarizedView(currentIdInProgress);

                    if (isTranslated && entry != null && entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty()) {
                        contentToRead = entry.getTranslated();
                        lang = sharedPreferencesRepository.getDefaultTranslationLanguage();
                        Timber.d("[TtsExtractor] Using translated content for TTS");
                    } else if (isSummarized && entry != null && entry.getSummarized() != null && !entry.getSummarized().trim().isEmpty()) {
                        contentToRead = entry.getSummarized();
                        lang = sharedPreferencesRepository.getDefaultTranslationLanguage();
                        Timber.d("[TtsExtractor] Using summarized content for TTS");
                    } else {
                        contentToRead = entry != null ? entry.getContent() : "";
                        Timber.d("[TtsExtractor] Using original content for TTS");
                    }

                    ttsCallback.extractToTts(contentToRead, lang);
                    ttsCallback = null; // Consume the callback so it doesn't fire again unexpectedly
                }
            } else {
                Timber.d("Not viewing this ID. CurrentInProgress: " + currentIdInProgress + ", GlobalViewing: " + GlobalState.getCurrentViewingId());
            }

            finishedSetupLiveData.postValue(true);

            currentIdInProgress = -1;
            setExtractionInProgress(false);

            // LOAD THE NEXT URL
            extractAllEntries();
        });
    }

    public void resetAndRetry(long entryId) {
        Schedulers.single().scheduleDirect(() -> {
            synchronized (this) {
                Timber.d("resetAndRetry called for article ID: " + entryId);
                
                // Remove from failed list if present
                while (failedIds.remove(Long.valueOf(entryId))) {
                    // Keep removing in case of duplicates
                }
                
                // Reset retry count
                retryCountMap.remove(entryId);
                
                // Clear content to trigger re-extraction
                entryRepository.updateContent(null, entryId);
                
                // Ensure extraction is not currently "in progress" for this ID
                if (currentIdInProgress == entryId) {
                    setExtractionInProgress(false);
                    currentIdInProgress = -1;
                }

                // Trigger extraction
                extractAllEntries();
            }
        });
    }

    public void extractAllEntries() {
        Schedulers.single().scheduleDirect(() -> {
            synchronized (this) {
                Timber.d("extractAllEntries called | extractionInProgress = " + extractionInProgress);

                if (extractionInProgress && currentIdInProgress == -1) {
                    Timber.w("Recovery: extractionInProgress = true but currentIdInProgress == -1 → Resetting flag.");
                    setExtractionInProgress(false);
                }

                // 1. PRIORITIZE: Check if the currently viewing article needs extraction
                long viewingId = GlobalState.getCurrentViewingId();
                Entry entry = null;
                if (viewingId != 0 && !failedIds.contains(viewingId)) {
                    Entry viewingEntry = entryRepository.getEntryById(viewingId);
                    if (viewingEntry != null && (viewingEntry.getContent() == null || viewingEntry.getContent().trim().isEmpty())) {
                        // If we are currently extracting SOMETHING ELSE, cancel it and prioritize this one
                        if (extractionInProgress && currentIdInProgress != viewingId) {
                            Timber.d("Interrupting current extraction (" + currentIdInProgress + ") for prioritized viewingId: " + viewingId);
                            cancelExtraction();
                            // cancelExtraction() will reset flags and WebView, then we can proceed to extract viewingId
                        }
                        entry = viewingEntry;
                        Timber.d("Prioritizing currently viewing article from GlobalState: " + viewingId);
                    }
                }

                if (extractionInProgress) {
                    Timber.d("Extraction already in progress for ID: " + currentIdInProgress);
                    return;
                }

                // 2. FALLBACK: Get the next highest priority empty entry
                if (entry == null) {
                    entry = entryRepository.getEmptyContentEntry();
                    if (entry != null && failedIds.contains(entry.getId())) {
                        entry = null;
                    }
                }

        // 3. RETRY LOGIC: If no new empty entries, try retrying a failed one
        if (entry == null && !failedIds.isEmpty()) {
            long retryId = failedIds.remove(0);
            int attempts = retryCountMap.getOrDefault(retryId, 0);

            if (attempts < MAX_RETRIES) {
                retryCountMap.put(retryId, attempts + 1);
                Timber.d("Retrying failed article ID: " + retryId + " | Attempt " + (attempts + 1));
                entry = entryRepository.getEntryById(retryId);
            } else {
                Timber.w("Max retries reached for article ID: " + retryId);
                entryRepository.updateContent("Extraction Failed. Please try opening in browser.", retryId);
                retryCountMap.remove(retryId);
                extractAllEntries();
                return;
            }
        }

        if (entry != null) {
            Timber.d("Next entry: id=" + entry.getId() + ", title=" + entry.getTitle() + ", priority=" + entry.getPriority());
            if (!extractionInProgress) {
                Timber.d("extracting...");
                setExtractionInProgress(true);
                currentIdInProgress = entry.getId();
                currentLink = entry.getLink();
                currentTitle = entry.getTitle();

                currentLanguage = null;

                int baseDelay = feedRepository.getDelayTimeById(entry.getFeedId());
                int attempts = retryCountMap.getOrDefault(entry.getId(), 0);
                delayTime = baseDelay + (attempts * 5); // Add 5 seconds per retry

                Timber.d("Delay for ID " + entry.getId() + " is " + delayTime + "s (Attempt " + attempts + ")");

                final String linkToLoad = currentLink;
                if (isWebViewServiceable()) {
                    Timber.d("WebView is serviceable. Starting WebView extraction.");
                    ContextCompat.getMainExecutor(context).execute(new Runnable() {
                        @Override
                        public void run() {
                            webView.loadUrl(linkToLoad);
                            Timber.d(linkToLoad);
                        }
                    });
                    lastExtractStart = System.currentTimeMillis();

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (extractionInProgress && System.currentTimeMillis() - lastExtractStart > 30000) {
                            Timber.w("[Timeout] WebView extraction stuck >30s, attempting HTTP fallback...");
                            long id = currentIdInProgress;
                            String link = currentLink;
                            String title = currentTitle;
                            // Stop WebView loading on UI thread
                            ContextCompat.getMainExecutor(context).execute(() -> {
                                if (webView != null) {
                                    webView.stopLoading();
                                    webView.loadUrl("about:blank");
                                }
                            });
                            extractViaHttp(id, link, title);
                        }
                    }, 30000);
                } else {
                    Timber.d("WebView is NOT serviceable (background/screen off). Using direct HTTP extraction.");
                    extractViaHttp(entry.getId(), currentLink, currentTitle);
                }
            }
        } else {
            Timber.d("No entry returned by getEmptyContentEntry()");
        }
            }
        });
    }

    public synchronized void cancelExtraction() {
        Timber.d("cancelExtraction called - resetting extraction state");
        setExtractionInProgress(false);
        currentIdInProgress = -1;
        ttsCallback = null;
        
        // Reset WebView
        ContextCompat.getMainExecutor(context).execute(() -> {
            if (webView != null) {
                webView.stopLoading();
                webView.loadUrl("about:blank");
            }
        });
    }

    public void setCallback(TtsPlayerListener callback) {
        this.ttsCallback = callback;
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

    // Helper methods to keep the main function cleaner
    private void handleFailure(long id) {
        synchronized (this) {
            if (id != currentIdInProgress || !extractionInProgress) {
                Timber.w("Ignoring handleFailure for ID: " + id + " since it is no longer current.");
                return;
            }
        }
        entryRepository.updatePriority(0, id);
        failedIds.add(id);
        finishAndMoveToNext();
    }

    private boolean isWebViewServiceable() {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && !powerManager.isInteractive()) {
            Timber.d("WebView not serviceable: Screen is off");
            return false;
        }

        try {
            android.app.ActivityManager.RunningAppProcessInfo appProcessInfo = new android.app.ActivityManager.RunningAppProcessInfo();
            android.app.ActivityManager.getMyMemoryState(appProcessInfo);
            if (appProcessInfo.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                Timber.d("WebView not serviceable: App is in background (importance: " + appProcessInfo.importance + ")");
                return false;
            }
        } catch (Exception e) {
            Timber.e(e, "Error checking app foreground state");
        }

        return true;
    }

    private void extractViaHttp(long entryId, String link, String title) {
        Timber.d("Starting background HTTP extraction for ID: " + entryId + ", Link: " + link);
        Schedulers.io().scheduleDirect(() -> {
            try {
                synchronized (this) {
                    if (entryId != currentIdInProgress || !extractionInProgress) {
                        Timber.w("Aborting HTTP extraction start: ID mismatch or not in progress.");
                        return;
                    }
                }
                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(link)
                        .userAgent("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
                        .timeout(15000)
                        .followRedirects(true)
                        .get();
                String html = doc.outerHtml();

                if (html != null && html.length() >= 500) {
                    Timber.d("HTTP extraction succeeded for ID: " + entryId);
                    processExtraction(entryId, link, title, html);
                } else {
                    Timber.w("HTTP extraction failed: HTML too short or null for ID: " + entryId);
                    handleFailure(entryId);
                }
            } catch (Throwable t) {
                Timber.e(t, "HTTP extraction error for ID: " + entryId);
                handleFailure(entryId);
            }
        });
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
            Timber.d("[onPageStarted] " + url);
            currentLoadToken = java.util.UUID.randomUUID().toString();
            hasProcessedCurrentToken = false;
            setExtractionInProgress(true);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                view.loadUrl(url);
                return true;
            } else {
                Timber.w("Blocked navigation to non-http/https URL: " + url);
                return true; // We handled it by ignoring it
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            Timber.d("[onPageCommitVisible] triggered for: " + url);
            // We use this as a supplementary trigger to ensure the page is actually rendering
        }

        @Override
        public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                // -10 is ERROR_UNKNOWN_URL_SCHEME
                if (error.getErrorCode() == -10) {
                     Timber.w("[onReceivedError] Ignored ERR_UNKNOWN_URL_SCHEME for: " + request.getUrl());
                     return;
                }
                Timber.e("[onReceivedError] Main frame error: " + error.getDescription() + " (" + error.getErrorCode() + ") for " + request.getUrl());
                
                if (error.getDescription() != null && error.getDescription().toString().contains("ERR_NAME_NOT_RESOLVED")) {
                    Timber.w("Network error ERR_NAME_NOT_RESOLVED detected. Failing current extraction to trigger a retry.");
                    if (extractionInProgress) {
                        hasProcessedCurrentToken = true; // Prevent further processing for this load
                        handleFailure(currentIdInProgress);
                    }
                }
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Timber.d("[onPageFinished] triggered for: " + url);
            super.onPageFinished(view, url);
            final String executionToken = currentLoadToken;
            if (extractionInProgress) {
                // Start polling after the initial delay
                handler.postDelayed(() -> checkReadyState(view, executionToken, 1), delayTime * 1000L);
            }
        }
        
        private void checkReadyState(WebView view, String executionToken, int attempt) {
             if (!executionToken.equals(currentLoadToken) || !extractionInProgress || hasProcessedCurrentToken) return;
             
             // Scroll to bottom to trigger lazy loading
             view.evaluateJavascript("(function() { window.scrollTo(0, document.body.scrollHeight); return document.readyState; })();", value -> {
                 if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) return;
                 
                 // Value is JSON string, e.g. "complete"
                 if (value != null && value.contains("complete")) {
                     Timber.d("Page ready (" + value + "). Waiting 5s settle time...");
                     handler.postDelayed(() -> extractHtml(view, executionToken), 5000);
                 } else if (value != null && value.contains("interactive")) {
                     if (attempt < 15) { 
                         Timber.d("Page interactive. Attempt " + attempt + "/15. Waiting 2s for complete...");
                         handler.postDelayed(() -> checkReadyState(view, executionToken, attempt + 1), 2000);
                     } else {
                         Timber.w("Page stuck at interactive. Proceeding with 5s settle...");
                         handler.postDelayed(() -> extractHtml(view, executionToken), 5000);
                     }
                 } else {
                     if (attempt < 20) {
                         Timber.d("Page loading (" + value + "). Attempt " + attempt + "/20. Waiting 2s...");
                         handler.postDelayed(() -> checkReadyState(view, executionToken, attempt + 1), 2000);
                     } else {
                         Timber.w("Page ready check timed out. Forcing extraction with 5s settle.");
                         handler.postDelayed(() -> extractHtml(view, executionToken), 5000);
                     }
                 }
             });
        }

        private void extractHtml(WebView view, String executionToken) {
             // Final guard before starting extraction
             if (!executionToken.equals(currentLoadToken) || !extractionInProgress || hasProcessedCurrentToken) {
                 Timber.d("Aborting extraction: Token mismatch or already processed.");
                 return;
             }

             // One last scroll to ensure all lazy content is triggered
             view.evaluateJavascript("(function() { window.scrollTo(0, document.body.scrollHeight); return document.getElementsByTagName('html')[0].outerHTML; })();", value -> {
                if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) {
                    Timber.d("Ignoring JS callback. Token mismatch.");
                    return;
                }
                hasProcessedCurrentToken = true;
                processHtmlExtraction(value);
            });
        }
    }

    @SuppressLint("CheckResult")
    private void processHtmlExtraction(String value) {
        Timber.d("Processing extracted HTML value...");
        try (JsonReader reader = new JsonReader(new StringReader(value))) {
            reader.setLenient(true);
            if (reader.peek() == JsonToken.STRING) {
                String html = reader.nextString();

                if (html != null && html.length() >= 500) {
                    processExtraction(currentIdInProgress, currentLink, currentTitle, html);
                } else {
                    Timber.w("HTML too short (" + (html != null ? html.length() : 0) + " chars). Retrying...");
                    handleFailure(currentIdInProgress);
                }
            } else {
                handleFailure(currentIdInProgress);
            }
        } catch (Throwable t) {
            Timber.e(t, "Fatal error during processHtmlExtraction");
            if (currentIdInProgress == GlobalState.getCurrentViewingId()) {
                snackbarMessageLiveData.postValue("Extraction crashed (JS): " + t.getClass().getSimpleName());
            }
            handleFailure(currentIdInProgress);
        }
    }

    @SuppressLint("CheckResult")
    public void processExtraction(long entryId, String link, String title, String html) {
        synchronized (this) {
            if (entryId != currentIdInProgress || !extractionInProgress) {
                Timber.w("Aborting processExtraction: Entry ID mismatch or extraction not in progress. EntryId: " + entryId + ", currentIdInProgress: " + currentIdInProgress);
                return;
            }
        }
        if (html == null || html.length() < 500) {
            Timber.w("HTML too short or null in processExtraction. Retrying...");
            handleFailure(entryId);
            return;
        }

        if (textUtil.isErrorHtml(html)) {
            Timber.w("Error page detected in HTML for ID: " + entryId + ". Retrying extraction...");
            handleFailure(entryId);
            return;
        }

        // Move to background thread to avoid ANR during heavy parsing
        Schedulers.io().scheduleDirect(() -> {
            Handler handler = new Handler(Looper.getMainLooper());
            try {
                // 1. Parse with Readability4J
                Readability4JExtended readability4J = new Readability4JExtended(link, html);
                Article article = readability4J.parse();
                StringBuilder content = new StringBuilder();

                String articleContent = article.getContentWithUtf8Encoding();
                Document doc = null;
                if (articleContent != null) {
                    doc = Jsoup.parse(articleContent);
                }

                // Fallback to feed description if extracted content is null/empty or shorter than description
                Entry dbEntry = entryRepository.getEntryById(entryId);
                String description = (dbEntry != null) ? dbEntry.getDescription() : null;
                if (description != null && !description.trim().isEmpty()) {
                    String cleanDesc = Jsoup.parse(description).text().trim();
                    String cleanExtracted = (doc != null) ? doc.body().text().trim() : "";
                    if (cleanDesc.length() > cleanExtracted.length()) {
                        Timber.d("Extracted text length (" + cleanExtracted.length() + ") is shorter than description text (" + cleanDesc.length() + "). Falling back to feed description.");
                        doc = Jsoup.parse(description);
                    }
                }

                if (doc != null) {
                    final Document finalDoc = doc;

                    // Clean images and layout
                    doc.select("img").removeAttr("width");
                    doc.select("img").removeAttr("height");
                    doc.select("img").removeAttr("sizes");
                    doc.select("img").removeAttr("srcset");
                    doc.select("h1").remove();
                    doc.select("img").attr("style", "border-radius: 5px; width: 100%; margin-left:0");
                    doc.select("figure").attr("style", "width: 100%; margin-left:0");
                    doc.select("iframe").attr("style", "width: 100%; margin-left:0");

                    List<String> tags = Arrays.asList("h1", "h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section", "div");

                    // Initialize the Sentence Iterator with Locale.ROOT for universal language support
                    BreakIterator sentenceIterator = BreakIterator.getSentenceInstance(Locale.ROOT);

                    // Extract text by sentences
                    Elements allElements = doc.getAllElements();
                    for (Element element : allElements) {
                        if (tags.contains(element.tagName())) {
                            // Check if any ancestor is also in the selected elements to avoid double counting
                            // We only process the highest-level container in our tag list
                            boolean hasSelectedAncestor = false;
                            Element parent = element.parent();
                            while (parent != null) {
                                if (tags.contains(parent.tagName())) {
                                    hasSelectedAncestor = true;
                                    break;
                                }
                                parent = parent.parent();
                            }

                            if (!hasSelectedAncestor) {
                                String elementText = element.text().trim();
                                if (!elementText.isEmpty() && elementText.length() > 1) {

                                    // --- START SENTENCE SPLITTING LOGIC ---
                                    sentenceIterator.setText(elementText);
                                    int start = sentenceIterator.first();
                                    int end = sentenceIterator.next();

                                    while (end != BreakIterator.DONE) {
                                        String candidate = elementText.substring(start, end);
                                        String sentence = candidate.trim();

                                        // Check if the sentence ends with a common abbreviation
                                        if (textUtil.endsWithAbbreviation(sentence)) {
                                            int nextEnd = sentenceIterator.next();
                                            if (nextEnd != BreakIterator.DONE) {
                                                end = nextEnd;
                                                continue;
                                            }
                                        }

                                        if (!sentence.isEmpty()) {
                                            if (content.length() > 0) {
                                                // Always add DELIMITER BEFORE adding a new sentence
                                                content.append(DELIMITER).append(sentence);
                                            } else {
                                                content.append(sentence);
                                            }
                                        }
                                        start = end;
                                        end = sentenceIterator.next();
                                    }
                                    // --- END SENTENCE SPLITTING LOGIC ---

                                } else if (elementText.length() <= 1) {
                                    element.remove();
                                }
                            }
                        }
                    }

                    // Only prepend title if it's not already at the start of the content
                    String tempContent = content.toString().trim();

                    // Fallback to basic text if extraction yielded very little but body has content
                    if (tempContent.length() < MIN_CONTENT_LENGTH) {
                        String bodyText = doc.body().text();
                        if (bodyText.length() > tempContent.length() + 50) {
                            Timber.d("Extraction too short (" + tempContent.length() + "). Falling back to body text (" + bodyText.length() + ")");
                            tempContent = bodyText;
                            content = new StringBuilder(tempContent);
                        }
                    }

                    if (title != null && !title.trim().isEmpty()) {
                        String cleanTitle = title.trim();
                        boolean isRedundant = false;

                        // Case-insensitive check for redundant title at the start
                        if (tempContent.toLowerCase().startsWith(cleanTitle.toLowerCase())) {
                            isRedundant = true;
                        } else {
                            // Check if the first sentence/line is basically the title
                            String firstLine = tempContent.split(DELIMITER)[0].trim();
                            if (firstLine.equalsIgnoreCase(cleanTitle) ||
                                    (firstLine.length() < 100 && cleanTitle.toLowerCase().contains(firstLine.toLowerCase()))) {
                                isRedundant = true;
                            }
                        }

                        if (!isRedundant) {
                            content.insert(0, cleanTitle + DELIMITER);
                        }
                    }

                    String extractedContent = content.toString();
                    int attempts = retryCountMap.getOrDefault(entryId, 0);

                    if (extractedContent.length() < MIN_CONTENT_LENGTH && attempts < MAX_RETRIES) {
                        Timber.w("Extracted content too short (" + extractedContent.length() + " chars) for ID: " + entryId + ". Attempt: " + attempts + ". Retrying...");
                        handleFailure(entryId);
                        return;
                    }

                    // Save Content & Backup HTML
                    entryRepository.updateContent(extractedContent, entryId);

                    String existingOriginal = entryRepository.getOriginalHtmlById(entryId);
                    String newHtml = doc.html();
                    boolean isProcessed = newHtml.contains("summarized-title") || newHtml.contains("translated-title");

                    if ((existingOriginal == null || existingOriginal.trim().isEmpty()) && !isProcessed) {
                        entryRepository.updateOriginalHtml(newHtml, entryId);
                        Timber.d("Original HTML backed up for ID: " + entryId);
                    }

                    // View State Logic
                    boolean isSummarizedView = sharedPreferencesRepository.getIsSummarizedView(entryId);
                    String existingSummarized = entryRepository.getSummarizedTextById(entryId);
                    boolean hasSummarization = existingSummarized != null && !existingSummarized.trim().isEmpty();

                    if (!isSummarizedView || !hasSummarization) {
                        entryRepository.updateHtml(doc.html(), entryId);
                    }

                    boolean isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(entryId);
                    String existingTranslated = entryRepository.getTranslatedTextById(entryId);
                    boolean hasTranslation = existingTranslated != null && !existingTranslated.trim().isEmpty();

                    if (!isTranslatedView || !hasTranslation) {
                        entryRepository.updateHtml(doc.html(), entryId);
                    }

                    // 3. Loop Guard
                    final long processingId = entryId;
                    final String processingTitle = title;

                    if (processingId == lastSuccessfullyProcessedId) {
                        Timber.e("LOOP DETECTED on ID " + processingId + ". Skipping this entry.");
                        setExtractionInProgress(false);
                        currentIdInProgress = -1;
                        // Try to find another entry instead of stopping
                        handler.postDelayed(this::extractAllEntries, 1000);
                        return;
                    }

                    boolean shouldTranslateGlobal = sharedPreferencesRepository.getAutoTranslate();
                    boolean shouldSummarizeGlobal = sharedPreferencesRepository.getAutoSummarize();

                    final Entry entryObj = entryRepository.getEntryById(processingId);
                    final Feed feed = (entryObj != null) ? feedRepository.getFeedById(entryObj.getFeedId()) : null;

                    boolean shouldTranslateFeed = false;
                    boolean shouldSummarizeFeed = false;
                    if (feed != null) {
                        shouldTranslateFeed = feed.isAutoTranslate();
                        shouldSummarizeFeed = feed.isAutoSummarize();
                    }

                    boolean shouldTranslate = shouldTranslateGlobal && shouldTranslateFeed;
                    boolean shouldSummarize = shouldSummarizeGlobal && shouldSummarizeFeed;
                    int length = sharedPreferencesRepository.getSummaryLength();

                    // 4. Determine Source Language
                    Single<String> sourceLangSingle;
                    if (currentLanguage != null && !currentLanguage.isEmpty() && !"und".equalsIgnoreCase(currentLanguage)) {
                        sourceLangSingle = Single.just(currentLanguage);
                    } else {
                        Timber.d("Language unknown. Detecting from content...");
                        sourceLangSingle = textUtil.identifyLanguageRx(content.toString());
                    }

                    // 5. Chain: Identify -> Translate/Summarize
                    sourceLangSingle
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(detectedLang -> {
                                // Safety Check: Entry could have been deleted or database could be in a transient state
                                if (entryObj == null) {
                                    Timber.e("entryObj is null in sourceLangSingle. Skipping processing for ID: " + processingId);
                                    if (processingId == currentIdInProgress) {
                                        finishAndMoveToNext();
                                    }
                                    return;
                                }

                                // Localize the language for this specific processing chain
                                final String localizedLang = detectedLang;
                                setCurrentLanguage(detectedLang, false); // Sync back for legacy compatibility, respecting lock

                                String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
                                boolean isSameLanguage = localizedLang.equalsIgnoreCase(targetLang);

                                boolean isAlreadyTranslated = entryObj.getTranslated() != null && !entryObj.getTranslated().trim().isEmpty();
                                boolean isAlreadySummarized = entryObj.getSummarized() != null && !entryObj.getSummarized().trim().isEmpty();

                                boolean isTranslating = xiangze.mmu.rssnewsreader.service.util.AutoTranslator.isProcessing(processingId);
                                boolean isSummarizing = xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.isProcessing(processingId);

                                boolean doTranslate = shouldTranslate && !isSameLanguage && !isAlreadyTranslated && !isTranslating;
                                boolean doSummarize = shouldSummarize && !isAlreadySummarized && !isSummarizing;

                                if (doTranslate && doSummarize) {
                                    if (feed == null) {
                                        Timber.e("feed is null but translation/summarization requested. Skipping.");
                                        if (processingId == currentIdInProgress) finishAndMoveToNext();
                                        return;
                                    }
                                    xiangze.mmu.rssnewsreader.service.util.AutoTranslator.processingIds.add(processingId);
                                    xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.processingIds.add(processingId);

                                    Single.zip(
                                            textUtil.translateHtmlAllAtOnce(localizedLang, targetLang, finalDoc.html(), processingTitle, processingId, progress -> {}, false).subscribeOn(Schedulers.io()),
                                            textUtil.summarizeHtmlAllAtOnce(localizedLang, targetLang, finalDoc.html(), length, processingId, processingTitle, progress -> {}, false).subscribeOn(Schedulers.io()),
                                            (translatedHtml, summarizedHtml) -> new String[]{translatedHtml, summarizedHtml}
                                    )
                                            .observeOn(AndroidSchedulers.mainThread())
                                            .doFinally(() -> {
                                                xiangze.mmu.rssnewsreader.service.util.AutoTranslator.processingIds.remove(processingId);
                                                xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.processingIds.remove(processingId);
                                            })
                                            .subscribe(results -> {
                                                String translatedHtmlRaw = results[0];
                                                String summarizedHtmlRaw = results[1];

                                                TextUtil.ProcessedAiResponse translatedProcessed = textUtil.processAiResponse(
                                                        translatedHtmlRaw,
                                                        processingTitle,
                                                        feed.getTitle(),
                                                        entryObj.getPublishedDate(),
                                                        feed.getImageUrl(),
                                                        sharedPreferencesRepository.getNight(),
                                                        "translated-title"
                                                );

                                                TextUtil.ProcessedAiResponse summarizedProcessed = textUtil.processAiResponse(
                                                        summarizedHtmlRaw,
                                                        processingTitle,
                                                        feed.getTitle(),
                                                        entryObj.getPublishedDate(),
                                                        feed.getImageUrl(),
                                                        sharedPreferencesRepository.getNight(),
                                                        "summarized-title"
                                                );

                                                entryRepository.updateTranslatedPair(processingId, translatedProcessed.contentToRead, translatedProcessed.html);
                                                entryRepository.updateSummarizedPair(processingId, summarizedProcessed.contentToRead, summarizedProcessed.html);

                                                if (processingId == currentIdInProgress) {
                                                    handler.postDelayed(this::finishAndMoveToNext, Math.max(WebClient.TRANSLATION_COOLDOWN_MS, WebClient.SUMMARIZATION_COOLDOWN_MS));
                                                }
                                            }, error -> handleError(error, processingId));

                                } else if (doTranslate) {
                                    if (feed == null) {
                                        if (processingId == currentIdInProgress) finishAndMoveToNext();
                                        return;
                                    }
                                    xiangze.mmu.rssnewsreader.service.util.AutoTranslator.processingIds.add(processingId);

                                    textUtil.translateHtmlAllAtOnce(localizedLang, targetLang, finalDoc.html(), processingTitle, processingId, progress -> {}, false)
                                            .subscribeOn(Schedulers.io())
                                            .observeOn(AndroidSchedulers.mainThread())
                                            .doFinally(() -> xiangze.mmu.rssnewsreader.service.util.AutoTranslator.processingIds.remove(processingId))
                                            .subscribe(translatedHtmlRaw -> {
                                                TextUtil.ProcessedAiResponse translatedProcessed = textUtil.processAiResponse(
                                                        translatedHtmlRaw,
                                                        processingTitle,
                                                        feed.getTitle(),
                                                        entryObj.getPublishedDate(),
                                                        feed.getImageUrl(),
                                                        sharedPreferencesRepository.getNight(),
                                                        "translated-title"
                                                );

                                                entryRepository.updateTranslatedPair(processingId, translatedProcessed.contentToRead, translatedProcessed.html);

                                                if (processingId == currentIdInProgress) {
                                                    handler.postDelayed(this::finishAndMoveToNext, WebClient.TRANSLATION_COOLDOWN_MS);
                                                }
                                            }, error -> handleError(error, processingId));

                                } else if (doSummarize) {
                                    if (feed == null) {
                                        if (processingId == currentIdInProgress) finishAndMoveToNext();
                                        return;
                                    }
                                    xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.processingIds.add(processingId);

                                    textUtil.summarizeHtmlAllAtOnce(localizedLang, targetLang, finalDoc.html(), length, processingId, processingTitle, progress -> {}, false)
                                            .subscribeOn(Schedulers.io())
                                            .observeOn(AndroidSchedulers.mainThread())
                                            .doFinally(() -> xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.processingIds.remove(processingId))
                                            .subscribe(summarizedHtmlRaw -> {
                                                TextUtil.ProcessedAiResponse summarizedProcessed = textUtil.processAiResponse(
                                                        summarizedHtmlRaw,
                                                        processingTitle,
                                                        feed.getTitle(),
                                                        entryObj.getPublishedDate(),
                                                        feed.getImageUrl(),
                                                        sharedPreferencesRepository.getNight(),
                                                        "summarized-title"
                                                );

                                                entryRepository.updateSummarizedPair(processingId, summarizedProcessed.contentToRead, summarizedProcessed.html);

                                                if (processingId == currentIdInProgress) {
                                                    handler.postDelayed(this::finishAndMoveToNext, WebClient.SUMMARIZATION_COOLDOWN_MS);
                                                }
                                            }, error -> handleError(error, processingId));
                                } else {
                                    if (processingId == currentIdInProgress) {
                                        finishAndMoveToNext();
                                    }
                                }
                            }, error -> {
                                Timber.e(error, "Language detection failed");
                                if (processingId == currentIdInProgress) {
                                    finishAndMoveToNext();
                                }
                            });

                } else {
                    handleFailure(entryId);
                }
            } catch (Throwable t) {
                Timber.e(t, "Fatal error during extraction for ID: " + entryId);
                if (entryId == GlobalState.getCurrentViewingId()) {
                    snackbarMessageLiveData.postValue("Extraction crashed: " + t.getClass().getSimpleName() + " - " + t.getMessage());
                }
                handleFailure(entryId);
            }
        });    }

    private void handleError(Throwable error, long id) {
        Timber.e(error, "Process Failed for ID: " + id);
        setExtractionInProgress(false);
        currentIdInProgress = -1;
        snackbarMessageLiveData.postValue("Process failed.");
        finishedSetupLiveData.postValue(true);

        if (id == GlobalState.getCurrentViewingId()) {
            if (ttsCallback != null) {
                Timber.d("Notifying TTS callback of failure for ID: " + id);
                ttsCallback.extractToTts(null, "en");
                ttsCallback = null;
            }
        }
    }

    public void setCurrentLanguage(String lang, boolean lock) {
        Timber.d("[setCurrentLanguage] REQUESTED lang = " + lang + ", lock = " + lock + " | current = " + currentLanguage + ", isLocked = " + isLockedByTtsPlayer);

        if (!isLockedByTtsPlayer || lock) {
            Timber.d("Language set to: " + lang + " | lock=" + lock);
            this.currentLanguage = lang;
            isLockedByTtsPlayer = lock;
        } else {
            Timber.d("Ignored language override to: " + lang + " due to lock");
        }

        Timber.d("Language set to: " + lang + " | lock=" + lock + " | isLocked=" + isLockedByTtsPlayer);
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