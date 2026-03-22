package xiangze.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
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
    private static final int MIN_CONTENT_LENGTH = 100;
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
//                webView.clearCache(true);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
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
                        Log.d(TAG, "[Progress] " + newProgress + "% for: " + currentLink);
                    }
                });
                webView.onResume();
                webView.resumeTimers();
            }
        });
    }

    private void finishAndMoveToNext() {
        Log.d(TAG, "Item complete. Moving to next...");

        // Only mark as successfully processed if it wasn't just added to failedIds
        if (!failedIds.contains(currentIdInProgress)) {
            lastSuccessfullyProcessedId = currentIdInProgress;
        } else {
             Log.d(TAG, "Item " + currentIdInProgress + " failed, not marking as successfully processed.");
        }

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

        if (entry != null && failedIds.contains(entry.getId())) {
            entry = null;
        }

        if (entry == null && !failedIds.isEmpty()) {
            long retryId = failedIds.remove(0);
            int attempts = retryCountMap.getOrDefault(retryId, 0);

            if (attempts < MAX_RETRIES) {
                retryCountMap.put(retryId, attempts + 1);
                Log.d(TAG, "Retrying failed article ID: " + retryId + " | Attempt " + (attempts + 1));
                entry = entryRepository.getEntryById(retryId);
            } else {
                Log.w(TAG, "Max retries reached for article ID: " + retryId);
                entryRepository.updateContent("Extraction Failed. Please try opening in browser.", retryId);
                retryCountMap.remove(retryId);
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

                int baseDelay = feedRepository.getDelayTimeById(entry.getFeedId());
                int attempts = retryCountMap.getOrDefault(entry.getId(), 0);
                delayTime = baseDelay + (attempts * 5); // Add 5 seconds per retry

                Log.d(TAG, "Delay for ID " + entry.getId() + " is " + delayTime + "s (Attempt " + attempts + ")");

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

    public WebViewListener getWebViewCallback() {
        return webViewCallback;
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
        entryRepository.updatePriority(0, id);
        failedIds.add(id);
        finishAndMoveToNext();
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
            Log.d(TAG, "[onPageStarted] " + url);
            currentLoadToken = java.util.UUID.randomUUID().toString();
            hasProcessedCurrentToken = false;
            extractionInProgress = true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                view.loadUrl(url);
                return true;
            } else {
                Log.w(TAG, "Blocked navigation to non-http/https URL: " + url);
                return true; // We handled it by ignoring it
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            Log.d(TAG, "[onPageCommitVisible] triggered for: " + url);
            // We use this as a supplementary trigger to ensure the page is actually rendering
        }

        @Override
        public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                // -10 is ERROR_UNKNOWN_URL_SCHEME
                if (error.getErrorCode() == -10) {
                     Log.w(TAG, "[onReceivedError] Ignored ERR_UNKNOWN_URL_SCHEME for: " + request.getUrl());
                     return;
                }
                Log.e(TAG, "[onReceivedError] Main frame error: " + error.getDescription() + " (" + error.getErrorCode() + ") for " + request.getUrl());
                
                if (error.getDescription() != null && error.getDescription().toString().contains("ERR_NAME_NOT_RESOLVED")) {
                    Log.w(TAG, "Network error ERR_NAME_NOT_RESOLVED detected. Failing current extraction to trigger a retry.");
                    if (extractionInProgress) {
                        hasProcessedCurrentToken = true; // Prevent further processing for this load
                        handleFailure(currentIdInProgress);
                    }
                }
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "[onPageFinished] triggered for: " + url);
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
                     Log.d(TAG, "Page ready (" + value + "). Waiting 3s settle time...");
                     handler.postDelayed(() -> extractHtml(view, executionToken), 3000);
                 } else if (value != null && value.contains("interactive")) {
                     if (attempt < 15) { 
                         Log.d(TAG, "Page interactive. Attempt " + attempt + "/15. Waiting 2s for complete...");
                         handler.postDelayed(() -> checkReadyState(view, executionToken, attempt + 1), 2000);
                     } else {
                         Log.w(TAG, "Page stuck at interactive. Proceeding with 3s settle...");
                         handler.postDelayed(() -> extractHtml(view, executionToken), 3000);
                     }
                 } else {
                     if (attempt < 20) {
                         Log.d(TAG, "Page loading (" + value + "). Attempt " + attempt + "/20. Waiting 2s...");
                         handler.postDelayed(() -> checkReadyState(view, executionToken, attempt + 1), 2000);
                     } else {
                         Log.w(TAG, "Page ready check timed out. Forcing extraction with 3s settle.");
                         handler.postDelayed(() -> extractHtml(view, executionToken), 3000);
                     }
                 }
             });
        }

        private void extractHtml(WebView view, String executionToken) {
             // Final guard before starting extraction
             if (!executionToken.equals(currentLoadToken) || !extractionInProgress || hasProcessedCurrentToken) {
                 Log.d(TAG, "Aborting extraction: Token mismatch or already processed.");
                 return;
             }

             // One last scroll to ensure all lazy content is triggered
             view.evaluateJavascript("(function() { window.scrollTo(0, document.body.scrollHeight); return document.getElementsByTagName('html')[0].outerHTML; })();", value -> {
                if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) {
                    Log.d(TAG, "Ignoring JS callback. Token mismatch.");
                    return;
                }
                hasProcessedCurrentToken = true;
                processHtmlExtraction(value);
            });
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

                if (html != null && html.length() >= 500) {
                    processExtraction(currentIdInProgress, currentLink, currentTitle, html);
                } else {
                    Log.w(TAG, "HTML too short (" + (html != null ? html.length() : 0) + " chars). Retrying...");
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

    @SuppressLint("CheckResult")
    public void processExtraction(long entryId, String link, String title, String html) {
        if (html == null || html.length() < 500) {
            Log.w(TAG, "HTML too short or null in processExtraction. Retrying...");
            handleFailure(entryId);
            return;
        }

        if (html.contains("ERR_NAME_NOT_RESOLVED")) {
            Log.w(TAG, "HTML contains ERR_NAME_NOT_RESOLVED. Retrying extraction...");
            handleFailure(entryId);
            return;
        }

        Handler handler = new Handler(Looper.getMainLooper());
        try {
            // 1. Parse with Readability4J
            Readability4JExtended readability4J = new Readability4JExtended(link, html);
            Article article = readability4J.parse();
            StringBuilder content = new StringBuilder();

            if (title != null && !title.isEmpty()) {
                content.append(title).append(delimiter);
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
                                            // Always add delimiter BEFORE adding a new sentence
                                            content.append(delimiter).append(sentence);
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

                String extractedContent = content.toString();
                int attempts = retryCountMap.getOrDefault(entryId, 0);

                if (extractedContent.length() < MIN_CONTENT_LENGTH && attempts < MAX_RETRIES) {
                    Log.w(TAG, "Extracted content too short (" + extractedContent.length() + " chars) for ID: " + entryId + ". Attempt: " + attempts + ". Retrying...");
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
                    Log.d(TAG, "Original HTML backed up for ID: " + entryId);
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
                    Log.e(TAG, "LOOP DETECTED on ID " + processingId + ". Skipping this entry.");
                    extractionInProgress = false;
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
                    Log.d(TAG, "Language unknown. Detecting from content...");
                    sourceLangSingle = textUtil.identifyLanguageRx(content.toString());
                }

                // 5. Chain: Identify -> Translate/Summarize
                sourceLangSingle
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(detectedLang -> {
                            // Localize the language for this specific processing chain
                            final String localizedLang = detectedLang; 
                            currentLanguage = detectedLang; // Sync back for legacy compatibility
                            
                            String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
                            boolean isSameLanguage = localizedLang.equalsIgnoreCase(targetLang);

                            boolean isAlreadyTranslated = entryObj.getTranslated() != null && !entryObj.getTranslated().trim().isEmpty();
                            boolean isAlreadySummarized = entryObj.getSummarized() != null && !entryObj.getSummarized().trim().isEmpty();

                            boolean isTranslating = xiangze.mmu.rssnewsreader.service.util.AutoTranslator.isProcessing(processingId);
                            boolean isSummarizing = xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.isProcessing(processingId);

                            boolean doTranslate = shouldTranslate && !isSameLanguage && !isAlreadyTranslated && !isTranslating;
                            boolean doSummarize = shouldSummarize && !isAlreadySummarized && !isSummarizing;

                            if (doTranslate && doSummarize) {
                                xiangze.mmu.rssnewsreader.service.util.AutoTranslator.processingIds.add(processingId);
                                xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.processingIds.add(processingId);

                                Single.zip(
                                        textUtil.translateHtmlAllAtOnce(localizedLang, targetLang, doc.html(), processingTitle, processingId, progress -> {}).subscribeOn(Schedulers.io()),
                                        textUtil.summarizeHtmlAllAtOnce(localizedLang, targetLang, doc.html(), length, processingId, processingTitle, progress -> {}).subscribeOn(Schedulers.io()),
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

                                    TextUtil.AiResponse translatedAi = textUtil.parseAiResponse(translatedHtmlRaw, processingTitle);
                                    TextUtil.AiResponse summarizedAi = textUtil.parseAiResponse(summarizedHtmlRaw, "Summary");

                                    String finalTranslatedHtml = textUtil.formatAiResponseToHtml(
                                            translatedAi.title,
                                            translatedAi.content,
                                            feed.getTitle(),
                                            entryObj.getPublishedDate(),
                                            feed.getImageUrl(),
                                            sharedPreferencesRepository.getNight(),
                                            "translated-title"
                                    );

                                    String finalSummarizedHtml = textUtil.formatAiResponseToHtml(
                                            summarizedAi.title,
                                            summarizedAi.content,
                                            feed.getTitle(),
                                            entryObj.getPublishedDate(),
                                            feed.getImageUrl(),
                                            sharedPreferencesRepository.getNight(),
                                            "summarized-title"
                                    );

                                    entryRepository.updateTranslatedHtml(finalTranslatedHtml, processingId);
                                    String translatedContent = textUtil.extractHtmlContent(finalTranslatedHtml, delimiter);
                                    entryRepository.updateTranslatedText(translatedContent, processingId);
                                    entryRepository.updateTranslated(translatedContent, processingId);

                                    entryRepository.updateSummarizedHtml(finalSummarizedHtml, processingId);
                                    String summarizedContent = textUtil.extractHtmlContent(finalSummarizedHtml, delimiter);
                                    entryRepository.updateSummarizedText(summarizedContent, processingId);
                                    entryRepository.updateSummarized(summarizedContent, processingId);

                                    if (processingId == currentIdInProgress) {
                                        handler.postDelayed(this::finishAndMoveToNext, Math.max(WebClient.TRANSLATION_COOLDOWN_MS, WebClient.SUMMARIZATION_COOLDOWN_MS));
                                    }
                                }, error -> handleError(error, processingId));

                            } else if (doTranslate) {
                                xiangze.mmu.rssnewsreader.service.util.AutoTranslator.processingIds.add(processingId);

                                textUtil.translateHtmlAllAtOnce(localizedLang, targetLang, doc.html(), processingTitle, processingId, progress -> {})
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .doFinally(() -> xiangze.mmu.rssnewsreader.service.util.AutoTranslator.processingIds.remove(processingId))
                                        .subscribe(translatedHtmlRaw -> {
                                            TextUtil.AiResponse translatedAi = textUtil.parseAiResponse(translatedHtmlRaw, processingTitle);
                                            String finalTranslatedHtml = textUtil.formatAiResponseToHtml(
                                                    translatedAi.title,
                                                    translatedAi.content,
                                                    feed.getTitle(),
                                                    entryObj.getPublishedDate(),
                                                    feed.getImageUrl(),
                                                    sharedPreferencesRepository.getNight(),
                                                    "translated-title"
                                            );

                                            entryRepository.updateTranslatedHtml(finalTranslatedHtml, processingId);
                                            String translatedContent = textUtil.extractHtmlContent(finalTranslatedHtml, delimiter);
                                            entryRepository.updateTranslatedText(translatedContent, processingId);
                                            entryRepository.updateTranslated(translatedContent, processingId);

                                            if (processingId == currentIdInProgress) {
                                                handler.postDelayed(this::finishAndMoveToNext, WebClient.TRANSLATION_COOLDOWN_MS);
                                            }
                                        }, error -> handleError(error, processingId));

                            } else if (doSummarize) {
                                xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.processingIds.add(processingId);

                                textUtil.summarizeHtmlAllAtOnce(localizedLang, targetLang, doc.html(), length, processingId, processingTitle, progress -> {})
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .doFinally(() -> xiangze.mmu.rssnewsreader.service.util.AutoSummarizer.processingIds.remove(processingId))
                                        .subscribe(summarizedHtmlRaw -> {
                                            TextUtil.AiResponse summarizedAi = textUtil.parseAiResponse(summarizedHtmlRaw, "Summary");
                                            String finalSummarizedHtml = textUtil.formatAiResponseToHtml(
                                                    summarizedAi.title,
                                                    summarizedAi.content,
                                                    feed.getTitle(),
                                                    entryObj.getPublishedDate(),
                                                    feed.getImageUrl(),
                                                    sharedPreferencesRepository.getNight(),
                                                    "summarized-title"
                                            );

                                            entryRepository.updateSummarizedHtml(finalSummarizedHtml, processingId);
                                            String summarizedContent = textUtil.extractHtmlContent(finalSummarizedHtml, delimiter);
                                            entryRepository.updateSummarizedText(summarizedContent, processingId);
                                            entryRepository.updateSummarized(summarizedContent, processingId);

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
                            Log.e(TAG, "Language detection failed", error);
                            if (processingId == currentIdInProgress) {
                                finishAndMoveToNext();
                            }
                        });

            } else {
                handleFailure(entryId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during extraction", e);
            handleFailure(entryId);
        }
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