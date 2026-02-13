package xiangze.mmu.rssnewsreader.ui.webview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.view.KeyEvent;
import java.io.StringReader;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.ai.Message;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.model.ai.ChatActivity;
import xiangze.mmu.rssnewsreader.model.ai.AiClient;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlaylist;
import xiangze.mmu.rssnewsreader.service.tts.TtsService;
import xiangze.mmu.rssnewsreader.databinding.ActivityWebviewBinding;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;
import xiangze.mmu.rssnewsreader.ui.feed.ReloadDialog;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;



import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WebViewActivity extends AppCompatActivity implements WebViewListener {
    private final static String TAG = "WebViewActivity";
    private LiveData<Entry> autoProcessingObserver;
    private Observer<Entry> checkAutoProcessed;
    // Share
    private ActivityWebviewBinding binding;
    private WebViewViewModel webViewViewModel;
    private WebView webView;
    private LinearProgressIndicator loading;
    private MenuItem browserButton;
    private MenuItem offlineButton;
    private MenuItem reloadButton;
    private MenuItem bookmarkButton;
    private MenuItem chatbotButton;
    private MenuItem translationButton;
    private MenuItem summarizationButton;
    private MenuItem highlightTextButton;
    private MenuItem backgroundMusicButton;
    private String currentLink;
    private long currentId;
    private long feedId;
    private String html;
    private String content;
    private String bookmark;
    private boolean isPlaying;
    private boolean isReadingMode;
    private boolean clearHistory;
    private MenuItem toggleTranslationButton;
    private MenuItem toggleSummarizationButton;
    private boolean isTranslatedView = false;
    private boolean isSummarizedView = false;
    private MaterialToolbar toolbar;

    // Translation
    private String targetLanguage;
    private String translationMethod;
    private TextUtil textUtil;
    private CompositeDisposable compositeDisposable;
    private LiveData<Entry> liveEntryObserver;

    // Reading Mode
    private MenuItem switchPlayModeButton;
    private LinearLayout functionButtonsReadingMode;

    // Playing Mode
    private MenuItem switchReadModeButton;
    private MaterialButton playPauseButton;
    private MaterialButton skipNextButton;
    private MaterialButton skipPreviousButton;
    private MaterialButton fastForwardButton;
    private MaterialButton rewindButton;
    private LinearLayout functionButtons;
    private MediaBrowserHelper mMediaBrowserHelper;
    private Set<Long> translatedArticleIds = new HashSet<>();
    private int summaryLength = 200;
    private volatile boolean isRequestRunning = false;

    @Inject
    TtsPlayer ttsPlayer;

    @Inject
    TtsPlaylist ttsPlaylist;

    @Inject
    TtsExtractor ttsExtractor;

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    @Inject
    EntryRepository entryRepository;

    private final MediaControllerCompat.Callback mediaControllerCallback =
            new MediaControllerCompat.Callback() {
                @Override
                public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
                    super.onPlaybackStateChanged(state);
                    isPlaying = state.getState() == PlaybackStateCompat.STATE_PLAYING;
                    updatePlayPauseButtonIcon(isPlaying);
                    Log.d(TAG, "Playback state changed: " + state.getState());
                }

                @Override
                public void onMetadataChanged(MediaMetadataCompat metadata) {
                    super.onMetadataChanged(metadata);
                    if (metadata != null) {
                        String mediaIdStr = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                        if (mediaIdStr != null) {
                            long newId = Long.parseLong(mediaIdStr);
                            if (newId != currentId && newId != 0) {
                                Log.d(TAG, "Metadata changed to new article ID: " + newId);
                                currentId = newId;
                                sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
                                loadEntryContent();
                            }
                        }
                    }
                }
            };

    private void showTranslationLanguageDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Default Translation Language");

        CharSequence[] entries = getResources().getStringArray(R.array.defaultTranslationLanguage);
        CharSequence[] entryValues = getResources().getStringArray(R.array.defaultTranslationLanguage_values);

        builder.setItems(entries, (dialog, which) -> {
            makeSnackbar("Translating to " + entries[which]);
            String selectedValue = entryValues[which].toString();
            sharedPreferencesRepository.setDefaultTranslationLanguage(selectedValue);
            targetLanguage = selectedValue;
            translate();
            dialog.dismiss();
        });

        builder.show();
    }

    private void doWhenTranslationFinish(EntryInfo entryInfo, String originalHtml, String translatedHtml) {
        loading.clearAnimation();
        loading.setVisibility(View.INVISIBLE);
        webView.animate().alpha(1.0f).setDuration(800).start();

        translatedHtml = translatedHtml
                .replaceAll("(?s)^\\s*```[a-zA-Z]*\\n?", "") // Removes the opening ```html
                .replaceAll("(?s)\\n?```\\s*$", "");        // Removes the closing ```

        if (webViewViewModel.getOriginalHtmlById(currentId) == null && originalHtml != null) {
            webViewViewModel.updateOriginalHtml(originalHtml, currentId);
            entryRepository.updateOriginalHtml(originalHtml, currentId);
            Log.d(TAG, "Original HTML backed up from method parameter.");
        }
        String translatedTitle = translatedHtml.substring(
            translatedHtml.indexOf("[TITLE]") + 7,
            translatedHtml.indexOf("[CONTENT]")
        ).trim();

        entryInfo.setEntryTitle(translatedTitle);
        Document doc = Jsoup.parse(translatedHtml.substring(
            translatedHtml.indexOf("[CONTENT]") + 9
        ).trim());
        doc.head().append(webViewViewModel.getStyle(sharedPreferencesRepository.getNight()));
        Objects.requireNonNull(doc.selectFirst("body"))
                .prepend(webViewViewModel.getHtml(
                        entryInfo.getEntryTitle(),
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl(),
                        sharedPreferencesRepository.getNight()
                ));
        String finalHtml = doc.html();

        webViewViewModel.updateTranslatedHtml(finalHtml, currentId);
        entryRepository.updateTranslatedHtml(finalHtml, currentId);

        String translatedContent = textUtil.extractHtmlContent(finalHtml, "--####--");
        webViewViewModel.updateTranslated(translatedContent, currentId);
        webViewViewModel.updateEntryTranslatedField(currentId, translatedContent);
        entryRepository.updateTranslatedText(translatedContent, currentId);

        isSummarizedView = false;
        sharedPreferencesRepository.setIsSummarizedView(currentId, false);

        isTranslatedView = true;
        sharedPreferencesRepository.setIsTranslatedView(currentId, true);

        webView.loadDataWithBaseURL("file///android_res/", finalHtml, "text/html", "UTF-8", null);

        toggleTranslationButton.setVisible(true);

        webViewViewModel.updateTranslatedHtml(finalHtml, currentId);
        webViewViewModel.setTranslatedTextReady(currentId, translatedContent);

        Log.d(TAG, "FINAL translatedContent passed to TTS: " + translatedContent);
        Log.d(TAG, "FINAL currentId: " + currentId + ", isTranslatedView: " + isTranslatedView);
    }

    private void doWhenSummarizationFinish(EntryInfo entryInfo, String originalHtml, String summarizedHtml) {
        loading.clearAnimation();
        loading.setVisibility(View.INVISIBLE);
        webView.animate().alpha(1.0f).setDuration(800).start();

        if (webViewViewModel.getOriginalHtmlById(currentId) == null && originalHtml != null) {
            webViewViewModel.updateOriginalHtml(originalHtml, currentId);
            entryRepository.updateOriginalHtml(originalHtml, currentId);
            Log.d(TAG, "Original HTML backed up from method parameter.");
        }

        summarizedHtml = summarizedHtml
                .replaceAll("(?s)^\\s*```[a-zA-Z]*\\n?", "") // Removes the opening ```html
                .replaceAll("(?s)\\n?```\\s*$", "");        // Removes the closing ```

        String summarizedTitle = entryInfo.getEntryTitle();
        String summarizedBody = summarizedHtml;

        if (summarizedHtml.contains("[TITLE]") && summarizedHtml.contains("[CONTENT]")) {
            summarizedTitle = summarizedHtml.substring(
                    summarizedHtml.indexOf("[TITLE]") + 7,
                    summarizedHtml.indexOf("[CONTENT]")
            ).trim();
            summarizedBody = summarizedHtml.substring(
                    summarizedHtml.indexOf("[CONTENT]") + 9
            ).trim();
        }

        Document doc = Jsoup.parse(summarizedBody);
        doc.head().append(webViewViewModel.getStyle(sharedPreferencesRepository.getNight()));
        Objects.requireNonNull(doc.selectFirst("body"))
                .prepend(webViewViewModel.getHtml(
                        summarizedTitle,
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl(),
                        sharedPreferencesRepository.getNight()
                ));
        String finalHtml = doc.html();

        webViewViewModel.updateSummarizedHtml(finalHtml, currentId);
        entryRepository.updateSummarizedHtml(finalHtml, currentId);

        String summarizedContent = textUtil.extractHtmlContent(finalHtml, "--####--");
        webViewViewModel.updateSummarized(summarizedContent, currentId);
        webViewViewModel.updateEntrySummarizedField(currentId, summarizedContent);
        entryRepository.updateSummarizedText(summarizedContent, currentId);

        isTranslatedView = false;
        sharedPreferencesRepository.setIsTranslatedView(currentId, false);

        isSummarizedView = true;
        sharedPreferencesRepository.setIsSummarizedView(currentId, true);

        webView.loadDataWithBaseURL("file///android_res/", finalHtml, "text/html", "UTF-8", null);

        toggleSummarizationButton.setVisible(true);

        webViewViewModel.updateSummarizedHtml(finalHtml, currentId);
        webViewViewModel.setSummarizedTextReady(currentId, summarizedContent);

        Log.d(TAG, "FINAL summarizedContent passed to TTS: " + summarizedContent);
        Log.d(TAG, "FINAL currentId: " + currentId + ", isSummarizedView: " + isSummarizedView);
    }

    @SuppressLint("CheckResult")
    private void translate() {
        if (!new AiClient(this).hasKey()) {
            showMissingKeyDialog();
            return;
        }

        String content = webViewViewModel.getOriginalHtmlById(currentId);
        if (content == null || content.trim().isEmpty()) {
            content = webViewViewModel.getHtmlById(currentId);
        }

        if (content == null || content.trim().isEmpty()) {
            makeSnackbar("Content is being extracted, please wait...");
            return;
        }

        final String finalContent = content;

        animateToolbarIcon(R.id.translate);
        loading.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse));
        webView.animate().alpha(0.5f).setDuration(300).start();

        Log.d(TAG, "translate: html\n" + webViewViewModel.getHtmlById(currentId));
        makeSnackbar("Translation in progress");
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(10);

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) {
            makeSnackbar("Entry info could not be loaded.");
            loading.setVisibility(View.INVISIBLE);
            return;
        }

        if (webViewViewModel.getOriginalHtmlById(currentId) == null) {
            webViewViewModel.updateOriginalHtml(finalContent, currentId);
            Log.d(TAG, "Original HTML backed up before translation.");
        }

        String feedLanguage = entryInfo.getFeedLanguage();
        String userConfiguredLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
        loading.setProgress(40);

        textUtil.identifyLanguageRx(finalContent).subscribe(
            identifiedLanguage -> {
                String sourceLanguage = (userConfiguredLang != null && !userConfiguredLang.isEmpty())
                        ? feedLanguage : identifiedLanguage;

                if (sourceLanguage != null && sourceLanguage.equalsIgnoreCase(targetLanguage)) {
                    runOnUiThread(() -> {
                        loading.clearAnimation();
                        loading.setVisibility(View.INVISIBLE);
                        webView.animate().alpha(1.0f).setDuration(300).start();
                        makeSnackbar("Article is already in " + Locale.forLanguageTag(targetLanguage).getDisplayLanguage());
                    });
                    return;
                }

                Log.d(TAG, "Translating from " + sourceLanguage + " to " + targetLanguage);
                Log.d("ORIGINAL CONTENT FOR TRANSLATION", finalContent);
                performAITranslation(sourceLanguage, targetLanguage, finalContent, entryInfo.getEntryTitle());
            },
            error -> {
                Log.e(TAG, "Language identification failed, falling back to feedLanguage");
                performAITranslation(feedLanguage, targetLanguage, finalContent, entryInfo.getEntryTitle());
            }
        );
    }

    private String translateChunkWithRetry(AiClient aiClient, List<Message> messages) {

        int maxRetries = 5;
        int delayMs = 2500;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return aiClient.getChatResponse(messages);

            } catch (Exception e) {

                boolean isRateLimit =
                        e.getMessage() != null &&
                                (e.getMessage().contains("429") ||
                                        e.getMessage().toLowerCase().contains("rate"));

                if (isRateLimit && attempt < maxRetries) {

                    Log.e(TAG, "Rate limit hit, retry " + attempt);

                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}

                } else {
                    throw new RuntimeException(e);
                }
            }
        }

        return null;
    }

    private void startProgressSimulation(int start, int max, int stepDelayMs) {
        isRequestRunning = true;

        new Thread(() -> {
            int progress = start;
            while (isRequestRunning && progress < max) {
                progress++;
                int value = progress;

                runOnUiThread(() -> loading.setProgress(value));

                try {
                    Thread.sleep(stepDelayMs);
                } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    private void stopProgressSimulation() {
        isRequestRunning = false;
    }

    private void performAITranslation(
            String sourceLanguage,
            String targetLanguage,
            String content,
            String title
    ) {

        Log.d(TAG, "Starting AI translation (Single Request Mode)");

        final String originalHtml = content;

        runOnUiThread(() -> {
            loading.setVisibility(View.VISIBLE);
            loading.setIndeterminate(false);
            loading.setProgress(0);
        });

        new Thread(() -> {

            AiClient aiClient = new AiClient(this);

            try {
                /* Stage 1: Initialization */
                runOnUiThread(() -> loading.setProgress(10));

                List<Message> messages = new ArrayList<>();

                String baseSystemPrompt = "Translate title and html to the target language. " +
                        "Preserve all HTML exactly." +
                        "Format your response exactly like this: [TITLE] <translated_title> [CONTENT] <translated_html_content>. ";
                String customPrompt = sharedPreferencesRepository.getCustomTranslationPrompt();
                if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                    baseSystemPrompt += "\n\nAdditional Instructions:\n" + customPrompt;
                }

                messages.add(new Message(
                    "system",
                    baseSystemPrompt
                ));

                messages.add(new Message(
                    "user",
                    String.format(
                            "Target Language: %s\nTitle: %s\nContent: %s",
                            targetLanguage,
                            title,
                            content
                    )
                ));

                /* Stage 2: Prompt Prepared */
                runOnUiThread(() -> loading.setProgress(25));

                /* Stage 3: Network Request */
                startProgressSimulation(25, 85, 300);

                // Optional stall fallback → indeterminate
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isRequestRunning) {
                        loading.setIndeterminate(true);
                    }
                }, 15000);

                String translatedHtml = translateChunkWithRetry(aiClient, messages);

                stopProgressSimulation();

                runOnUiThread(() -> {
                    loading.setIndeterminate(false);
                    loading.setProgress(90);
                });

                /* Stage 4: Validation */
                if (translatedHtml == null || translatedHtml.trim().isEmpty()) {
                    throw new Exception("AI returned empty response.");
                }

                final String finalHtml = translatedHtml;

                /* Stage 5: Apply Result */
                runOnUiThread(() -> {
                    loading.setProgress(100);
                    loading.setVisibility(View.GONE);

                    Log.d(TAG, "Translation complete, " + finalHtml);

                    doWhenTranslationFinish(
                            webViewViewModel.getLastVisitedEntry(),
                            originalHtml,
                            finalHtml
                    );

                    makeSnackbar("Translation completed successfully");
                });

            } catch (Exception e) {

                Log.e(TAG, "Translation error", e);
                stopProgressSimulation();

                runOnUiThread(() -> {
                    loading.setIndeterminate(false);
                    loading.setVisibility(View.GONE);
                    makeSnackbar("Translation failed: " + e.getMessage());
                });
            }

        }).start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webViewViewModel = new ViewModelProvider(this).get(WebViewViewModel.class);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.article_pop_enter, R.anim.article_pop_exit);
                }
            }
        });

        webViewViewModel.getTranslatedTextReady().observe(this, translatedText -> {
            if (!isReadingMode && isTranslatedView && translatedText != null && !translatedText.trim().isEmpty()) {
                Log.d(TAG, "TTS triggered after LiveData translation update");

                String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");

                ttsPlayer.extract(currentId, feedId, translatedText, lang);
                if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                    mMediaBrowserHelper.getTransportControls().prepare();
                }
                Log.d(TAG, "LiveData.observe fired, isTranslatedView = " + isTranslatedView);
            }
        });

        webViewViewModel.getSummarizedTextReady().observe(this, summarizedText -> {
            if (!isReadingMode && isSummarizedView && summarizedText != null && !summarizedText.trim().isEmpty()) {
                Log.d(TAG, "TTS triggered after LiveData summarization update");

                String lang = getLanguageForCurrentView(currentId, isSummarizedView, "en");

                ttsPlayer.extract(currentId, feedId, summarizedText, lang);
                if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                    mMediaBrowserHelper.getTransportControls().prepare();
                }
                Log.d(TAG, "LiveData.observe fired, isSummarizedView = " + isSummarizedView);
            }
        });

        isReadingMode = getIntent().getBooleanExtra("read", false);
        currentId = getIntent().getLongExtra("entry_id", 0);

        if (currentId != 0) {
            ttsPlaylist.updatePlayingId(currentId);
            sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
            entryRepository.updateDate(new Date(), currentId);
        }

        if (ttsPlayer.isPlaying() && isReadingMode) {
            ttsPlayer.stop();
        }

        initializeUI();
        ttsPlayer.setWebViewCallback(this);

        targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        translationMethod = sharedPreferencesRepository.getTranslationMethod();
        textUtil = new TextUtil(sharedPreferencesRepository);
        compositeDisposable = new CompositeDisposable();
        summaryLength = sharedPreferencesRepository.getSummaryLength();

        initializeToolbarListeners();
        initializeWebViewSettings();
        initializePlaybackModes();
        loadEntryContent();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                        keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {

            MediaSessionCompat mediaSession = TtsService.getMediaSession();
            if (mediaSession != null && mediaSession.isActive()) {
                MediaControllerCompat controller = mediaSession.getController();
                controller.dispatchMediaButtonEvent(event);
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private void initializeUI() {
        binding = ActivityWebviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        webView = binding.webview;
        loading = binding.loadingWebView;
        functionButtons = binding.functionButtons;
        functionButtonsReadingMode = binding.functionButtonsReading;

        playPauseButton = binding.playPauseButton;
        skipNextButton = binding.skipNextButton;
        skipPreviousButton = binding.skipPreviousButton;
        fastForwardButton = binding.fastForwardButton;
        rewindButton = binding.rewindButton;

        toolbar = binding.toolbar;
        browserButton = toolbar.getMenu().findItem(R.id.openInBrowser);
        offlineButton = toolbar.getMenu().findItem(R.id.exitBrowser);
        reloadButton = toolbar.getMenu().findItem(R.id.reload);
        bookmarkButton = toolbar.getMenu().findItem(R.id.bookmark);
        chatbotButton = toolbar.getMenu().findItem(R.id.chatbot);
        translationButton = toolbar.getMenu().findItem(R.id.translate);
        summarizationButton = toolbar.getMenu().findItem(R.id.summarize);
        toggleTranslationButton = toolbar.getMenu().findItem(R.id.toggleTranslation);
        toggleSummarizationButton = toolbar.getMenu().findItem(R.id.toggleSummarization);
        highlightTextButton = toolbar.getMenu().findItem(R.id.highlightText);
        backgroundMusicButton = toolbar.getMenu().findItem(R.id.toggleBackgroundMusic);
        switchReadModeButton = toolbar.getMenu().findItem(R.id.switchReadMode);
        switchPlayModeButton = toolbar.getMenu().findItem(R.id.switchPlayMode);

        toggleTranslationButton.setVisible(false);
        toggleSummarizationButton.setVisible(false);

        highlightTextButton.setTitle(sharedPreferencesRepository.getHighlightText()
                ? R.string.highlight_text_turn_off : R.string.highlight_text_turn_on);
        backgroundMusicButton.setTitle(sharedPreferencesRepository.getBackgroundMusic()
                ? R.string.background_music_turn_off : R.string.background_music_turn_on);
    }

    private void loadHtmlIntoWebView(String html) {
        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle(sharedPreferencesRepository.getNight()));

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo != null && !doc.html().contains("class=\"entry-header\"")) {
            doc.selectFirst("body").prepend(
                webViewViewModel.getHtml(
                        entryInfo.getEntryTitle(),
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl(),
                        sharedPreferencesRepository.getNight()
                )
            );
        }

        if (!isTranslatedView && !isSummarizedView) {
            webViewViewModel.updateOriginalHtml(html, currentId);
        }

        webView.loadDataWithBaseURL("file///android_res/", doc.html(), "text/html", "UTF-8", null);

        webView.postDelayed(() -> {
            int scrollX = sharedPreferencesRepository.getScrollX(currentId);
            int scrollY = sharedPreferencesRepository.getScrollY(currentId);
            webView.scrollTo(scrollX, scrollY);
        }, 300);

        syncLoadingWithTts();
    }

    private void initializePlaybackModes() {
        if (isReadingMode) {
            switchReadMode();
        } else {
            switchPlayMode();
        }
    }

    private void loadEntryContent() {
        EntryInfo entryInfo;
        if (currentId != 0) {
            entryInfo = webViewViewModel.getEntryInfoById(currentId);
        } else {
            entryInfo = webViewViewModel.getLastVisitedEntry();
        }

        if (entryInfo == null) {
            makeSnackbar("No article to load.");
            return;
        }

        webViewViewModel.clearViewData();
        currentId = entryInfo.getEntryId();
        feedId = entryInfo.getFeedId();
        currentLink = entryInfo.getEntryLink();
        bookmark = entryInfo.getBookmark();

        webViewViewModel.prioritizeEntry(currentId);
        Entry entry = entryRepository.getEntryById(currentId);

        if (entry == null) {
            makeSnackbar("Failed to load article content.");
            return;
        }

        // 1. Browser mode has absolute priority
        if (sharedPreferencesRepository.getWebViewMode(currentId)) {
            loadFromBrowserMode(entryInfo);
            return;
        }

        // 2. Determine data availability
        boolean hasTranslation = entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty();
        boolean hasSummary = entry.getSummarized() != null && !entry.getSummarized().trim().isEmpty();

        // 3. Populate / clear ViewModel (avoid stale state)
        if (hasSummary) {
            webViewViewModel.updateSummarized(entry.getSummarized(), currentId);
            webViewViewModel.updateSummarizedHtml(entry.getSummarizedHtml(), currentId);
        }

        if (hasTranslation) {
            webViewViewModel.updateTranslated(entry.getTranslated(), currentId);
            webViewViewModel.updateTranslatedHtml(entry.getTranslatedHtml(), currentId);
        }

        if (entry.getOriginalHtml() != null) {
            webViewViewModel.updateOriginalHtml(entry.getOriginalHtml(), currentId);
        }

        // 4. Resolve view state safely (Preference > Priority: Summary > Translation > Original)
        if (sharedPreferencesRepository.hasSummarizationToggle(currentId) ||
                sharedPreferencesRepository.hasTranslationToggle(currentId)) {
            // USE SAVED PREFERENCE
            isSummarizedView = sharedPreferencesRepository.getIsSummarizedView(currentId) && hasSummary;
            isTranslatedView = !isSummarizedView && sharedPreferencesRepository.getIsTranslatedView(currentId) && hasTranslation;
        } else {
            // NO PREFERENCE: Use Data Priority
            if (hasSummary) {
                isSummarizedView = true;
                isTranslatedView = false;
            } else if (hasTranslation) {
                isSummarizedView = false;
                isTranslatedView = true;
            } else {
                isSummarizedView = false;
                isTranslatedView = false;
            }
        }

        // Ensure preferences are in sync with reality ONLY if there's a positive state to save
        // or if a preference already existed (to lock in a 'false' choice).
        if (sharedPreferencesRepository.hasSummarizationToggle(currentId) || isSummarizedView) {
            sharedPreferencesRepository.setIsSummarizedView(currentId, isSummarizedView);
        }
        if (sharedPreferencesRepository.hasTranslationToggle(currentId) || isTranslatedView) {
            sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);
        }

        Log.d(TAG, "Resolved View State -> Summarized: " + isSummarizedView + ", Translated: " + isTranslatedView);

        // 5. Update toolbar buttons
        refreshButtonVisibility();

        // 6. Select content to load based on the resolved state
        String htmlToLoad;
        String contentToRead;
        String lang;

        if (isSummarizedView) {
            htmlToLoad = webViewViewModel.getSummarizedHtmlById(currentId);
            contentToRead = entry.getSummarized();
            // Assuming your summaries are in a specific language or the default app language
            lang = getLanguageForCurrentView(currentId, true, "en");
        } else if (isTranslatedView) {
            htmlToLoad = webViewViewModel.getTranslatedHtmlById(currentId);
            contentToRead = entry.getTranslated();
            lang = getLanguageForCurrentView(currentId, true, "en");
        } else {
            htmlToLoad = webViewViewModel.getOriginalHtmlById(currentId);
            if (htmlToLoad == null || htmlToLoad.trim().isEmpty()) {
                htmlToLoad = entry.getHtml();
            }
            contentToRead = entry.getContent();
            lang = getLanguageForCurrentView(currentId, false, "en");
        }

        Log.d(TAG, "HTML length = " + (htmlToLoad != null ? htmlToLoad.length() : 0));
        Log.d(TAG, "TTS language = " + lang);

        // 7. Load content
        ttsExtractor.setCurrentLanguage(lang, true);

        if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
            loadHtmlIntoWebView(htmlToLoad);

            if (contentToRead != null && !contentToRead.trim().isEmpty()) {
                ttsPlayer.extract(entry.getId(), entry.getFeedId(), contentToRead, lang);
            }
        } else {
            Log.w(TAG, "HTML missing, skipping WebView load.");
        }

        sharedPreferencesRepository.setCurrentReadingEntryId(currentId);

        // 8. Observers
        observeLiveEntry();
        subscribeToEntry(currentId);
        syncLoadingWithTts();
        refreshButtonVisibility();
    }


    private void loadFromBrowserMode(EntryInfo entryInfo) {
        refreshButtonVisibility();
        webView.loadUrl(entryInfo.getEntryLink());
    }

    private void observeLiveEntry() {
        webViewViewModel.triggerEntryRefresh(currentId);

        webViewViewModel.getLiveEntry().observe(this, entry -> {
            if (entry == null) {
                toggleTranslationButton.setVisible(false);
                toggleSummarizationButton.setVisible(false);
                makeSnackbar("This article is missing.");
            }
        });

        // Observer for Original HTML changes
        webViewViewModel.getOriginalHtmlLiveData().observe(this, originalHtml -> {
            refreshButtonVisibility(); // Check all buttons whenever original changes
            // Refresh webview content if we are currently viewing original
            if (!isTranslatedView && !isSummarizedView) {
                Log.d("CONTENT", "original");
                loadHtmlToWebView(originalHtml);
            }
        });

        // Observer for Translated HTML changes
        webViewViewModel.getTranslatedHtmlLiveData().observe(this, translatedHtml -> {
            refreshButtonVisibility(); // Check all buttons
            // Refresh webview if we are currently viewing translation
            if (isTranslatedView) {
                Log.d("CONTENT", "translated");
                loadHtmlToWebView(translatedHtml);

                Entry entry = entryRepository.getEntryById(currentId);
                if (entry != null && entry.getTranslated() != null) {
                    String lang = getLanguageForCurrentView(currentId, true, "en");
                    ttsPlayer.extract(entry.getId(), entry.getFeedId(), entry.getTranslated(), lang);
                    if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                        mMediaBrowserHelper.getTransportControls().prepare();
                    }
                }
            }
        });

        // Observer for Summarized HTML changes
        webViewViewModel.getSummarizedHtmlLiveData().observe(this, summarizedHtml -> {
            refreshButtonVisibility(); // Check all buttons
            // Refresh webview if we are currently viewing summary
            if (isSummarizedView) {
                Log.d("CONTENT", "summarized");
                loadHtmlToWebView(summarizedHtml);

                Entry entry = entryRepository.getEntryById(currentId);
                if (entry != null && entry.getSummarized() != null) {
                    String lang = getLanguageForCurrentView(currentId, true, "en");
                    ttsPlayer.extract(entry.getId(), entry.getFeedId(), entry.getSummarized(), lang);
                    if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                        mMediaBrowserHelper.getTransportControls().prepare();
                    }
                }
            }
        });
    }

    private void refreshButtonVisibility() {
        String originalHtml = webViewViewModel.getOriginalHtmlById(currentId);
        if (originalHtml == null || originalHtml.trim().isEmpty()) {
            originalHtml = webViewViewModel.getHtmlById(currentId);
        }
        String translatedHtml = webViewViewModel.getTranslatedHtmlById(currentId);
        String summarizedHtml = webViewViewModel.getSummarizedHtmlById(currentId);

        boolean hasOriginal = originalHtml != null && !originalHtml.trim().isEmpty();
        boolean hasTranslated = translatedHtml != null && !translatedHtml.trim().isEmpty();
        boolean hasSummarized = summarizedHtml != null && !summarizedHtml.trim().isEmpty();

        // Check for plain text content as well
        Entry entry = entryRepository.getEntryById(currentId);
        boolean hasPlainText = entry != null && entry.getContent() != null && !entry.getContent().trim().isEmpty();

//        Log.d("REFRESH BUTTON", "original: " + originalHtml);
//        Log.d("REFRESH BUTTON", "translated: " + translatedHtml);
//        Log.d("REFRESH BUTTON", "summarized: " + summarizedHtml);
//        Log.d("REFRESH BUTTON", "plainText: " + hasPlainText);

        // 1. Update Translation Buttons
        if (hasOriginal && hasTranslated) {
            // Show Toggle, make it priority
            toggleTranslationButton.setVisible(true);
            Log.d("REFRESH BUTTON", "Set toggle translate button to visible");
            toggleTranslationButton.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");

            // Downgrade the main Translate button to make room for Toggle
            translationButton.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        } else {
            toggleTranslationButton.setVisible(false);
            Log.d("REFRESH BUTTON", "Set toggle translate button to invisible");
            // Reset Translate button to priority if no translation exists
            translationButton.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }

        // 2. Update Summarization Buttons
        if (hasOriginal && hasSummarized) {
            // Show Toggle, make it priority
            toggleSummarizationButton.setVisible(true);
            Log.d("REFRESH BUTTON", "Set toggle summarize button to visible");
            toggleSummarizationButton.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            toggleSummarizationButton.setTitle(isSummarizedView ? "Show Original" : "Show Summarization");

            // Downgrade main Summarize button
            summarizationButton.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        } else {
            toggleSummarizationButton.setVisible(false);
            Log.d("REFRESH BUTTON", "Set toggle summarize button to invisible");
            // Reset Summarize button to priority
            summarizationButton.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }

        // 3. Update Browser/Offline Buttons
        boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);
        boolean hasContent = hasOriginal || hasTranslated || hasSummarized || hasPlainText;

        if (isWebViewMode) {
            browserButton.setVisible(false);
            // Only show offline button if we have extracted content to show
            offlineButton.setVisible(hasContent);
        } else {
            // Reader mode (or fallback to live URL)
            browserButton.setVisible(hasContent);
            offlineButton.setVisible(false);
        }
    }

    private void subscribeToEntry(long entryId) {
        if (autoProcessingObserver != null && checkAutoProcessed != null) {
            autoProcessingObserver.removeObserver(checkAutoProcessed);
        }

        autoProcessingObserver = webViewViewModel.getEntryEntityById(entryId);
        
        if (checkAutoProcessed == null) {
            checkAutoProcessed = new Observer<Entry>() {
                @Override
                public void onChanged(Entry entry) {
                    if (entry == null) return;
                    
                    // Crucial: Ignore updates for entries that are no longer current
                    if (entry.getId() != currentId) {
                        Log.d(TAG, "Ignored update for ID " + entry.getId() + " because currentId is " + currentId);
                        return;
                    }

                    boolean hasContent = entry.getContent() != null && !entry.getContent().trim().isEmpty();
                    boolean hasOriginalHtml = entry.getOriginalHtml() != null && !entry.getOriginalHtml().trim().isEmpty();
                    boolean hasSummary = entry.getSummarized() != null && !entry.getSummarized().trim().isEmpty();
                    boolean hasTranslation = entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty();

                    if (hasContent || hasOriginalHtml || hasSummary || hasTranslation) {

                        String originalHtmlFromDb = entry.getOriginalHtml();
                        if (originalHtmlFromDb != null && webViewViewModel.getOriginalHtmlById(currentId) == null) {
                            webViewViewModel.updateOriginalHtml(originalHtmlFromDb, currentId);
                            Log.d(TAG, "Original HTML restored from DB.");
                        }

                        if (hasSummary && !isSummarizedView && !isTranslatedView) {
                            String summarizedHtmlFromDb = entry.getSummarizedHtml();
                            String currentSummarizedInVm = webViewViewModel.getSummarizedHtmlLiveData().getValue();

                            // 1. If user explicitly chose original view, respect it.
                            boolean userPrefOriginal = sharedPreferencesRepository.hasSummarizationToggle(currentId) &&
                                    !sharedPreferencesRepository.getIsSummarizedView(currentId);

                            if (summarizedHtmlFromDb != null && (currentSummarizedInVm == null || currentSummarizedInVm.isEmpty()) && !userPrefOriginal) {
                                isSummarizedView = true;
                                isTranslatedView = false;
                                sharedPreferencesRepository.setIsSummarizedView(currentId, true);
                                sharedPreferencesRepository.setIsTranslatedView(currentId, false);
                                webViewViewModel.updateSummarizedHtml(summarizedHtmlFromDb, currentId);
                                Log.d(TAG, "Summarized HTML synced from auto processing.");

                                refreshButtonVisibility();
                                webViewViewModel.triggerEntryRefresh(currentId);
                            }
                        } else if (hasTranslation && !isTranslatedView && !isSummarizedView) {
                            String translatedHtmlFromDb = entry.getTranslatedHtml();
                            String currentTranslatedInVm = webViewViewModel.getTranslatedHtmlLiveData().getValue();

                            // 1. If user explicitly chose original view, respect it.
                            boolean userPrefOriginal = sharedPreferencesRepository.hasTranslationToggle(currentId) &&
                                    !sharedPreferencesRepository.getIsTranslatedView(currentId);

                            if (translatedHtmlFromDb != null && (currentTranslatedInVm == null || currentTranslatedInVm.isEmpty()) && !userPrefOriginal) {
                                isTranslatedView = true;
                                isSummarizedView = false;
                                sharedPreferencesRepository.setIsTranslatedView(currentId, true);
                                sharedPreferencesRepository.setIsSummarizedView(currentId, false);
                                webViewViewModel.updateTranslatedHtml(translatedHtmlFromDb, currentId);
                                Log.d(TAG, "Translated HTML synced from auto processing.");

                                refreshButtonVisibility();
                                webViewViewModel.triggerEntryRefresh(currentId);
                            }
                        } else if (!isSummarizedView && !isTranslatedView && hasOriginalHtml) {
                            // Just regular extraction finished
                            String htmlFromDb = entry.getHtml();
                            if (htmlFromDb == null) htmlFromDb = entry.getOriginalHtml();

                            // Use LiveData value from ViewModel to detect if we are currently showing a fallback or partial content
                            String currentViewModelHtml = webViewViewModel.getOriginalHtmlLiveData().getValue();
                            boolean isShowingFallback = (currentViewModelHtml == null || currentViewModelHtml.trim().isEmpty());
                            
                            // Allow update if we were showing nothing, or if the new content is significantly larger (indicating a full unlock)
                            boolean shouldUpdate = isShowingFallback;
                            if (!isShowingFallback && htmlFromDb != null) {
                                int currentLen = currentViewModelHtml.length();
                                int newLen = htmlFromDb.length();
                                if (newLen > currentLen + 1000) { // Significant increase suggests full article unlocked
                                    shouldUpdate = true;
                                    Log.d(TAG, "Content significantly increased (" + currentLen + " -> " + newLen + "). Updating view.");
                                }
                            }

                            if (htmlFromDb != null && shouldUpdate) {
                                Log.d(TAG, "Regular extraction synced. Switching to extracted view.");

                                // 1. Notify user
                                if (isShowingFallback) {
                                    makeSnackbar("Article extracted. Switching to reader view...");
                                } else {
                                    makeSnackbar("Full article unlocked.");
                                }

                                // 2. Update ViewModel which triggers the LiveData observer to load HTML
                                webViewViewModel.updateOriginalHtml(htmlFromDb, currentId);

                                // 3. Update buttons
                                refreshButtonVisibility();

                                // 4. Start TTS immediately
                                String lang = getLanguageForCurrentView(currentId, false, "en");
                                ttsPlayer.extract(currentId, feedId, entry.getContent(), lang);

                                // If we've reached a likely "full" state, we can stop observing, 
                                // otherwise keep observing for further improvements (like late-loading images or text)
                                if (htmlFromDb.length() > 2000) {
                                     autoProcessingObserver.removeObserver(this);
                                }
                            }
                        } else {
                            // Already in a processed view, or waiting for more data. 
                            refreshButtonVisibility();
                        }
                    }
                }
            };
        }
        
        // Use observe (with lifecycle) if possible, but since we manage it manually and it might persist 
        // across some states, observeForever is okay IF we strictly unsubscribe.
        // Given we are in an Activity, observe(this, ...) is much safer to avoid leaks.
        autoProcessingObserver.observe(this, checkAutoProcessed);
    }

    private void loadHtmlToWebView(String html) {
        if (html == null || html.trim().isEmpty()) {
            return;
        }

        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle(sharedPreferencesRepository.getNight()));

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo != null && !doc.html().contains("class=\"entry-header\"")) {
            doc.selectFirst("body").prepend(
                    webViewViewModel.getHtml(
                            entryInfo.getEntryTitle(),
                            entryInfo.getFeedTitle(),
                            entryInfo.getEntryPublishedDate(),
                            entryInfo.getFeedImageUrl(),
                            sharedPreferencesRepository.getNight()
                    )
            );
        }

        webView.loadDataWithBaseURL("file///android_res/", doc.html(), "text/html", "UTF-8", null);
    }

    private void startChat() {
        Log.d(TAG, "CHAT BUTTON PRESSED");
        Intent intent = new Intent(this, ChatActivity.class);
        startActivity(intent);
    }

    private void showMissingKeyDialog() {
        new AlertDialog.Builder(this)
                .setTitle("API Key Missing")
                .setMessage("Please configure the OpenRouter API Key in Settings to use AI features.")
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void summarize() {
        if (!new AiClient(this).hasKey()) {
            showMissingKeyDialog();
            return;
        }

        // 1. Data Retrieval
        String htmlStr = webViewViewModel.getOriginalHtmlById(currentId);
        if (htmlStr == null || htmlStr.trim().isEmpty()) {
            htmlStr = webViewViewModel.getHtmlById(currentId);
        }

        if (htmlStr == null || htmlStr.trim().isEmpty()) {
            makeSnackbar("Content is being extracted, please wait...");
            return;
        }

        final String html = htmlStr;

        animateToolbarIcon(R.id.summarize);
        loading.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse));
        webView.animate().alpha(0.5f).setDuration(300).start();

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);

        if (entryInfo == null) {
            makeSnackbar("Entry info could not be loaded.");
            return;
        }

        Log.d(TAG, "Summarize: html length: " + (html != null ? html.length() : 0));

        // 2. Prepare UI
        makeSnackbar("Summarization in progress");
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);

        // 3. Prepare Data for AI
        // We clean the HTML here to extract only text, saving tokens and improving AI focus
        TextUtil textUtil = new TextUtil(sharedPreferencesRepository);
        String cleanContent = (html != null) ? textUtil.extractHtmlContent(html, "--####--") : "";

        String title = entryInfo.getEntryTitle();
        String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        loading.setProgress(20);

        // 4. Background Execution
        new Thread(() -> {
            AiClient aiClient = new AiClient(this);

            try {
                List<Message> messages = new ArrayList<>();
                runOnUiThread(() -> loading.setProgress(45));

                // System Prompt
                String baseSystemPrompt = "You are a helpful assistant designed to summarize web articles. " +
                        "Provide a concise summary of the content in targeted language. " +
                        "If the content is short, do not make it longer. ";
                String customPrompt = sharedPreferencesRepository.getCustomSummarizationPrompt();
                if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                    baseSystemPrompt += "\n\nAdditional Instructions:\n" + customPrompt;
                }

                messages.add(new Message(
                        "system",
                        baseSystemPrompt
                ));

                // User Prompt
                String prompt = String.format(
                        "Please summarize the following article titled \"%s\".\n" +
                                "Target Language: %s\n" +
                                "Length: %s\n" +
                                "Content:\n%s",
                        title, targetLanguage, summaryLength, cleanContent
                );

                messages.add(new Message("user", prompt));

                runOnUiThread(() -> loading.setProgress(55));

                // Execute Request
                // Updated to use the method available in your AiClient
                String summaryResult = aiClient.getChatResponse(messages);
                Log.d(TAG, "AI Summary Response: " + summaryResult);

                runOnUiThread(() -> loading.setProgress(80));

                // Validation
                if (summaryResult == null || summaryResult.trim().isEmpty()) {
                    throw new Exception("AI returned empty response.");
                }

                // 5. Update UI (Main Thread)
                runOnUiThread(() -> {
                    Log.d(TAG, "Summarization complete, length: " + summaryResult.length());
                    loading.setProgress(100);
                    loading.setVisibility(View.GONE);

                    // Open ChatActivity to display the result
                    runOnUiThread(() -> {
                        doWhenSummarizationFinish(entryInfo, html, summaryResult);
                    });

                });

            } catch (Exception e) {
                Log.e(TAG, "Summarization error: " + e.getMessage(), e);

                runOnUiThread(() -> {
                    loading.setVisibility(View.GONE);
                    makeSnackbar("Summarization failed: " + e.getMessage());
                });
            }

        }).start();
    }

    @SuppressLint("NonConstantResourceId")
    private boolean handleOtherToolbarItems(int itemId) {
        if (itemId == R.id.summarize) {
            summarize();
            return true;
        } else if (itemId == R.id.chatbot) {
            startChat();
            return true;
        } else if (itemId == R.id.translate) {
            if (targetLanguage == null || targetLanguage.isEmpty()) {
                showTranslationLanguageDialog(this);
            }
            translate();
            return true;
        } else if (itemId == R.id.zoomIn) {
            adjustTextZoom(true);
            return true;
        } else if (itemId == R.id.zoomOut) {
            adjustTextZoom(false);
            return true;
        } else if (itemId == R.id.bookmark) {
            toggleBookmark();
            return true;
        } else if (itemId == R.id.share) {
            shareCurrentLink();
            return true;
        } else if (itemId == R.id.openInBrowser) {
            sharedPreferencesRepository.setWebViewMode(currentId, true);
            refreshButtonVisibility();
            webView.loadUrl(currentLink);
            hideFakeLoading();
            return true;
        } else if (itemId == R.id.exitBrowser) {
            sharedPreferencesRepository.setWebViewMode(currentId, false);
            EntryInfo entryInfo = webViewViewModel.getLastVisitedEntry();
            String rebuiltHtml = rebuildHtml(entryInfo);
            loadEntryContent();
            refreshButtonVisibility();
            hideFakeLoading();
            return true;
        } else if (itemId == R.id.reload) {
            ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_message);
            dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
            return true;
        } else if (itemId == R.id.toggleBackgroundMusic) {
            toggleBackgroundMusic();
            return true;
        } else if (itemId == R.id.openTtsSetting) {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
            return true;
        } else if (itemId == R.id.toggleTranslation) {
            // 1. Toggle the state
            boolean targetState = !isTranslatedView;

            // 2. Enforce Mutual Exclusivity
            isTranslatedView = targetState;
            if (targetState) {
                isSummarizedView = false; // Turn off summary if translation is on
                sharedPreferencesRepository.setIsSummarizedView(currentId, false);
            }
            sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);

            // 3. Get Data
            String translatedHtml = webViewViewModel.getTranslatedHtmlById(currentId);
            String originalHtml = webViewViewModel.getOriginalHtmlById(currentId);
            if (originalHtml == null || originalHtml.trim().isEmpty()) {
                originalHtml = webViewViewModel.getHtmlById(currentId);
            }

            // Safety: if both are missing, use the entry's stored HTML directly
            Entry entry = webViewViewModel.getEntryById(currentId);
            if (entry != null) {
                if (originalHtml == null || originalHtml.trim().isEmpty()) {
                    originalHtml = entry.getHtml();
                }
            }

            // 4. Decide what to load
            String htmlToLoad = isTranslatedView ? translatedHtml : originalHtml;

            if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
                String finalHtmlToLoad = htmlToLoad;
                webView.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    loadHtmlIntoWebView(finalHtmlToLoad);
                }).start();

                // Refresh buttons to update titles ("Show Original" vs "Show Translation")
                refreshButtonVisibility();

                // Handle TTS
                if (entry != null) {
                    if (isTranslatedView) {
                        String translated = entry.getTranslated();
                        if (translated != null) {
                            String lang = getLanguageForCurrentView(currentId, true, "en");
                            ttsPlayer.extract(currentId, feedId, translated, lang);
                            if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                                mMediaBrowserHelper.getTransportControls().prepare();
                            }
                        }
                    } else {
                        // Revert to original TTS
                        String original = entry.getContent();
                        String lang = getLanguageForCurrentView(currentId, false, "en");
                        ttsPlayer.extract(currentId, feedId, original, lang);
                        if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                            mMediaBrowserHelper.getTransportControls().prepare();
                        }
                    }
                }
            }
            return true;
        } else if (itemId == R.id.toggleSummarization) {
            // 1. Toggle the state
            boolean targetState = !isSummarizedView;

            // 2. Enforce Mutual Exclusivity
            isSummarizedView = targetState;
            if (targetState) {
                isTranslatedView = false; // Turn off translation if summary is on
                sharedPreferencesRepository.setIsTranslatedView(currentId, false);
            }
            sharedPreferencesRepository.setIsSummarizedView(currentId, isSummarizedView);

            // 3. Get Data
            String summarizedHtml = webViewViewModel.getSummarizedHtmlById(currentId);
            String originalHtml = webViewViewModel.getOriginalHtmlById(currentId);
            if (originalHtml == null || originalHtml.trim().isEmpty()) {
                originalHtml = webViewViewModel.getHtmlById(currentId);
            }
            
            // Safety: if both are missing, use the entry's stored HTML directly
            Entry entry = webViewViewModel.getEntryById(currentId);
            if (entry != null) {
                if (originalHtml == null || originalHtml.trim().isEmpty()) {
                    originalHtml = entry.getHtml();
                }
            }

            // 4. Decide what to load
            String htmlToLoad = isSummarizedView ? summarizedHtml : originalHtml;

            if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
                String finalHtmlToLoad = htmlToLoad;
                webView.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    loadHtmlIntoWebView(finalHtmlToLoad);
                }).start();

                // Refresh buttons to update titles
                refreshButtonVisibility();

                // Handle TTS
                if (entry != null) {
                    if (isSummarizedView) {
                        String summarized = entry.getSummarized();
                        if (summarized != null) {
                            String lang = getLanguageForCurrentView(currentId, true, "en");
                            ttsPlayer.extract(currentId, feedId, summarized, lang);
                            if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                                mMediaBrowserHelper.getTransportControls().prepare();
                            }
                        }
                    } else {
                        // Revert to original TTS
                        String original = entry.getContent();
                        String lang = getLanguageForCurrentView(currentId, false, "en");
                        ttsPlayer.extract(currentId, feedId, original, lang);
                        if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                            mMediaBrowserHelper.getTransportControls().prepare();
                        }
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    private String rebuildHtml(EntryInfo entryInfo) {
        String html = webViewViewModel.getHtmlById(entryInfo.getEntryId());

        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle(sharedPreferencesRepository.getNight()));

        Objects.requireNonNull(doc.selectFirst("body")).prepend(
                webViewViewModel.getHtml(
                        entryInfo.getEntryTitle(),
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl(),
                        sharedPreferencesRepository.getNight()
                )
        );

        return doc.html();
    }

    private void adjustTextZoom(boolean zoomIn) {
        int currentZoom = webView.getSettings().getTextZoom();
        int newZoom = zoomIn ? currentZoom + 10 : currentZoom - 10;
        webView.getSettings().setTextZoom(newZoom);
        sharedPreferencesRepository.setTextZoom(newZoom);
    }

    private void toggleBookmark() {
        if (bookmark == null || bookmark.equals("N")) {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
            webViewViewModel.updateBookmark("Y", currentId);
            bookmark = "Y";
            makeSnackbar("Bookmark Complete");
        } else {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
            webViewViewModel.updateBookmark("N", currentId);
            bookmark = "N";
            makeSnackbar("Bookmark Removed");
        }
    }

    private void shareCurrentLink() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, currentLink);
        sendIntent.setType("text/plain");

        Intent shareIntent = Intent.createChooser(sendIntent, null);
        startActivity(shareIntent);
    }

    private void toggleBackgroundMusic() {
        boolean backgroundMusic = sharedPreferencesRepository.getBackgroundMusic();
        sharedPreferencesRepository.setBackgroundMusic(!backgroundMusic);
        if (backgroundMusic) {
            ttsPlayer.stopMediaPlayer();
            backgroundMusicButton.setTitle(R.string.background_music_turn_on);
            makeSnackbar("Background music is turned off");
        } else {
            ttsPlayer.setupMediaPlayer(false);
            backgroundMusicButton.setTitle(R.string.background_music_turn_off);
            makeSnackbar("Background music is turned on");
        }
    }

    private void initializeToolbarListeners() {
        toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());

        toolbar.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.translate) {
                View translateView = toolbar.findViewById(itemId);
                if (translateView != null) {
                    translateView.setOnLongClickListener(v -> {
                        showTranslationLanguageDialog(translateView.getContext());
                        return true;
                    });
                }
            }

            if (itemId == R.id.switchPlayMode) {
                isReadingMode = false;
                functionButtonsReadingMode.setVisibility(View.INVISIBLE);
                switchPlayModeButton.setVisible(false);
                ttsExtractor.setCallback((WebViewListener) null);
                switchPlayMode();
                mMediaBrowserHelper.onStart();
                functionButtons.setVisibility(View.VISIBLE);
                functionButtons.setAlpha(1.0f);
                return true;

            } else if (itemId == R.id.switchReadMode) {
                isReadingMode = true;
                functionButtons.setVisibility(View.INVISIBLE);
                switchReadModeButton.setVisible(false);
                ttsPlayer.setWebViewCallback(null);
                mMediaBrowserHelper.getTransportControls().stop();
                mMediaBrowserHelper.onStop();
                webView.clearMatches();
                switchReadMode();
                return true;

            } else if (itemId == R.id.highlightText) {
                boolean isHighlight = sharedPreferencesRepository.getHighlightText();
                sharedPreferencesRepository.setHighlightText(!isHighlight);
                if (isHighlight) {
                    webView.clearMatches();
                    highlightTextButton.setTitle(R.string.highlight_text_turn_on);
                    Snackbar.make(findViewById(R.id.webView_view), "Highlight is turned off", Snackbar.LENGTH_SHORT).show();
                } else {
                    highlightTextButton.setTitle(R.string.highlight_text_turn_off);
                    Snackbar.make(findViewById(R.id.webView_view), "Highlight is turned on", Snackbar.LENGTH_SHORT).show();
                }
                return true;
            }

            return handleOtherToolbarItems(itemId);
        });
    }

    private void initializeWebViewSettings() {
        webView.setBackgroundColor(0);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setDisplayZoomControls(false);

        int textZoom = sharedPreferencesRepository.getTextZoom();
        if (textZoom != 0) {
            webView.getSettings().setTextZoom(textZoom);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.getSettings().setAlgorithmicDarkeningAllowed(sharedPreferencesRepository.getNight());
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);

                int ttsProgress = ttsPlayer.getCurrentExtractProgress();

                int combinedProgress = Math.min(newProgress, ttsProgress);

                loading.setVisibility(View.VISIBLE);
                loading.setProgress(combinedProgress);
                if (combinedProgress >= 95 && (!ttsPlayer.isPreparing() || ttsPlayer.ttsIsNull())) {
                    loading.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override
    public void showFakeLoading() {
        runOnUiThread(() -> {
            Log.d(TAG, "TTS is preparing, showing fake loading indicator.");
            loading.setProgress(0);
            loading.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void hideFakeLoading() {
        runOnUiThread(() -> {
            Log.d(TAG, "TTS is ready, hiding fake loading indicator.");
            loading.setVisibility(View.GONE);
        });
    }

    @Override
    public void updateLoadingProgress(int progress) {
        runOnUiThread(() -> {
            if (loading.getVisibility() != View.VISIBLE) {
                loading.setVisibility(View.VISIBLE);
            }
            loading.setProgress(progress);

            if (progress >= 100 && !ttsPlayer.isPreparing()) {
                loading.setVisibility(View.GONE);
            }
        });
    }

    public void syncLoadingWithTts() {
        runOnUiThread(() -> {
            int ttsProgress = ttsPlayer.getCurrentExtractProgress();
            int webProgress = webView.getProgress();
            int combinedProgress = Math.min(ttsProgress, webProgress);

            if (combinedProgress >= 100 && !ttsPlayer.isPreparing()) {
                loading.setProgress(100);
                loading.setVisibility(View.GONE);
                Log.d(TAG, "[syncLoadingWithTts] Forcibly hid loading.");
            } else {
                loading.setProgress(combinedProgress);
                loading.setVisibility(View.VISIBLE);
                Log.d(TAG, "[syncLoadingWithTts] Still loading... progress = " + combinedProgress);
            }
        });
    }

    private void switchReadMode() {
        functionButtonsReadingMode.setVisibility(View.VISIBLE);

        webView.setWebViewClient(new ReadingWebClient());

        binding.nextArticleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (ttsPlaylist.skipNext()) {
                    setupReadingWebView();
                } else {
                    Snackbar.make(findViewById(R.id.webView_view), "This is the last article", Snackbar.LENGTH_SHORT).show();
                }
            }
        });

        binding.previousArticleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (ttsPlaylist.skipPrevious()) {
                    setupReadingWebView();
                } else {
                    Snackbar.make(findViewById(R.id.webView_view), "This is the first article", Snackbar.LENGTH_SHORT).show();
                }
            }
        });

        setupReadingWebView();

        ttsPlayer.setupMediaPlayer(false);

        switchPlayModeButton.setVisible(true);
    }

    private void switchPlayMode() {
        webView.setWebViewClient(new WebClient());
        setupMediaPlaybackButtons();

        mMediaBrowserHelper = new MediaBrowserConnection(this);
        mMediaBrowserHelper.registerCallback(new MediaBrowserListener());

        switchReadModeButton.setVisible(true);
    }

    private void setupMediaPlaybackButtons() {
        playPauseButton.setOnClickListener(view -> {
            if (isPlaying) {
                mMediaBrowserHelper.getTransportControls().pause();
                Log.d(TAG, "switchPlayMode: pausing " + ttsPlaylist.getPlayingId());
            } else {
                mMediaBrowserHelper.getTransportControls().play();
                Log.d(TAG, "switchPlayMode: playing " + ttsPlaylist.getPlayingId());
            }
        });

        skipNextButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().skipToNext());
        skipPreviousButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().skipToPrevious());
        fastForwardButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().fastForward());
        rewindButton.setOnClickListener(view -> mMediaBrowserHelper.getTransportControls().rewind());
    }

    private void setupReadingWebView() {
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);
        bookmarkButton.setVisible(false);
        loading.setProgress(0);
        translationButton.setVisible(false);
        summarizationButton.setVisible(false);

        MediaMetadataCompat metadata = ttsPlaylist.getCurrentMetadata();

        content = metadata.getString("content");
        bookmark = metadata.getString("bookmark");
        currentLink = metadata.getString("link");
        currentId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
        webViewViewModel.prioritizeEntry(currentId);
        refreshButtonVisibility();
        feedId = metadata.getLong("feedId");

        if (bookmark == null || bookmark.equals("N")) {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
        } else {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
        }

        boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);

        if (isWebViewMode) {
            webView.loadUrl(currentLink);
            Log.d(TAG, "Restoring web view mode: " + currentLink);
        } else {
            String entryTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE);
            String feedTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE);
            long date = metadata.getLong("date");
            Date publishDate = new Date(date);
            String feedImageUrl = metadata.getString("feedImageUrl");

            isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentId);
            isSummarizedView = sharedPreferencesRepository.getIsSummarizedView(currentId);
            String htmlToLoad = isSummarizedView
                    ? webViewViewModel.getSummarizedHtmlById(currentId)
                    : isTranslatedView
                    ? webViewViewModel.getTranslatedHtmlById(currentId)
                    : webViewViewModel.getOriginalHtmlById(currentId);

            if (htmlToLoad == null || htmlToLoad.trim().isEmpty()) {
                htmlToLoad = webViewViewModel.getHtmlById(currentId);
            }

            if (htmlToLoad == null) {
                htmlToLoad = metadata.getString("html");
            }

            if (htmlToLoad != null) {
                loadHtmlIntoWebView(htmlToLoad);
            }

            reloadButton.setVisible(true);
            bookmarkButton.setVisible(true);
            translationButton.setVisible(true);
            summarizationButton.setVisible(true);
            highlightTextButton.setVisible(true);
        }
        refreshButtonVisibility();
    }

    @Override
    public void highlightText(String searchText) {
        if (!isReadingMode && sharedPreferencesRepository.getHighlightText()) {
            String text = searchText.trim();
            if (text.length() < 2) return;
            
            // Robust escaping for JS string
            String escapedText = text.replace("\\", "\\\\")
                                     .replace("'", "\\'")
                                     .replace("\"", "\\\"")
                                     .replace("\n", "\\n")
                                     .replace("\r", "\\r");
            
            Log.d(TAG, "Highlighting text via JS: " + escapedText);
            
            String js = "(function() {" +
                    "  try {" +
                    "    var text = '" + escapedText + "';" +
                    "    if (!text) return;" +
                    "    window.ttsHighlightId = (window.ttsHighlightId || 0) + 1;" +
                    "    var myId = window.ttsHighlightId;" +
                    "    " +
                    "    function clean(s) { return s.replace(/^[.,!?;:\"'\\s]+/, '').replace(/[.,!?;:\"'\\s]+$/, '').trim(); }" +
                    "    var cleanSearchText = clean(text);" +
                    "    if (!cleanSearchText) return;" +
                    "    " +
                    "    function removeHighlights() {" +
                    "      var highlights = document.querySelectorAll('.tts-highlight');" +
                    "      highlights.forEach(function(el) {" +
                    "        var parent = el.parentNode;" +
                    "        if (parent) {" +
                    "          var textNode = document.createTextNode(el.textContent);" +
                    "          parent.replaceChild(textNode, el);" +
                    "          parent.normalize();" +
                    "        }" +
                    "      });" +
                    "    }" +
                    "    " +
                    "    var attempts = 0;" +
                    "    function tryHighlight() {" +
                    "      if (window.ttsHighlightId !== myId) return;" +
                    "      " +
                    "      if (document.readyState !== 'complete' && attempts < 3) {" +
                    "        attempts++;" +
                    "        setTimeout(tryHighlight, 500);" +
                    "        return;" +
                    "      }" +
                    "      " +
                    "      removeHighlights();" +
                    "      " +
                    "      var nodes = [];" +
                    "      var fullText = '';" +
                    "      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {" +
                    "        acceptNode: function(node) {" +
                    "          var p = node.parentNode;" +
                    "          if (!p) return NodeFilter.FILTER_REJECT;" +
                    "          var tag = p.tagName.toUpperCase();" +
                    "          if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return NodeFilter.FILTER_REJECT;" +
                    "          return NodeFilter.FILTER_ACCEPT;" +
                    "        }" +
                    "      }, false);" +
                    "      " +
                    "      var node;" +
                    "      while(node = walker.nextNode()) {" +
                    "        nodes.push({ node: node, start: fullText.length, end: fullText.length + node.nodeValue.length });" +
                    "        fullText += node.nodeValue;" +
                    "      }" +
                    "      " +
                    "      var index = fullText.indexOf(text);" +
                    "      var matchLen = text.length;" +
                    "      if (index === -1) {" +
                    "        index = fullText.indexOf(cleanSearchText);" +
                    "        matchLen = cleanSearchText.length;" +
                    "      }" +
                    "      " +
                    "      if (index !== -1) {" +
                    "        var matchEnd = index + matchLen;" +
                    "        var firstMark = null;" +
                    "        for (var i = 0; i < nodes.length; i++) {" +
                    "          var m = nodes[i];" +
                    "          if (m.end > index && m.start < matchEnd) {" +
                    "            var offsetStart = Math.max(0, index - m.start);" +
                    "            var offsetEnd = Math.min(m.node.nodeValue.length, matchEnd - m.start);" +
                    "            var range = document.createRange();" +
                    "            range.setStart(m.node, offsetStart);" +
                    "            range.setEnd(m.node, offsetEnd);" +
                    "            var mark = document.createElement('mark');" +
                    "            mark.className = 'tts-highlight';" +
                    "            try {" +
                    "              range.surroundContents(mark);" +
                    "              if (!firstMark) firstMark = mark;" +
                    "            } catch(e) { console.warn('Highlight failed', e); }" +
                    "          }" +
                    "        }" +
                    "        if (firstMark) firstMark.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                    "      } else if (attempts < 2) {" +
                    "        attempts++;" +
                    "        setTimeout(tryHighlight, 800);" +
                    "      }" +
                    "    }" +
                    "    tryHighlight();" +
                    "  } catch(e) {" +
                    "    console.error('Highlight error:', e);" +
                    "  }" +
                    "})();";
            
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        }
    }

    @Override
    public void finishedSetup() {
        ContextCompat.getMainExecutor(getApplicationContext()).execute(new Runnable() {
            @Override
            public void run() {
                if (!isReadingMode) {
                    loading.setVisibility(View.INVISIBLE);
                    functionButtons.setVisibility(View.VISIBLE);
                    functionButtons.setAlpha(1.0f);
                }
                reloadButton.setVisible(true);
                bookmarkButton.setVisible(true);
                translationButton.setVisible(true);
                highlightTextButton.setVisible(true);
                refreshButtonVisibility();
            }
        });
    }

    private String getLanguageForCurrentView(long entryId, boolean isTranslated, String defaultLang) {
        if (isTranslated) {
            return sharedPreferencesRepository.getDefaultTranslationLanguage();
        }

        EntryInfo info = webViewViewModel.getEntryInfoById(entryId);
        String lang = (info != null && info.getFeedLanguage() != null && !info.getFeedLanguage().trim().isEmpty())
                ? info.getFeedLanguage()
                : defaultLang;

        Log.d(TAG, "getLanguageForCurrentView: Using lang=" + lang + " for isTranslated=" + isTranslated);
        return lang;
    }

    @Override
    public void makeSnackbar(String message) {
        Snackbar.make(findViewById(R.id.webView_view), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public void reload() {
        if (currentId <= 0) {
            Log.w(TAG, "reload() aborted: invalid currentId");
            return;
        }

        Log.d(TAG, "Reload triggered for entryId: " + currentId);

        webViewViewModel.resetEntry(currentId);
        webViewViewModel.clearLiveEntryCache(currentId);
        
        // Reset view states to ensure we see the fresh original content
        sharedPreferencesRepository.setIsTranslatedView(currentId, false);
        sharedPreferencesRepository.setIsSummarizedView(currentId, false);

        if (!isReadingMode) {
            if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                mMediaBrowserHelper.getTransportControls().stop();
            }
        }

        finish();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }

        Intent intent = getIntent();
        intent.putExtra("entry_id", currentId); // CRITICAL: Ensure we reload the currently viewed article
        startActivity(intent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
        }
    }

    @Override
    public void askForReload(long feedId) {
        ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_suggestion_message);
        dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (autoProcessingObserver != null && checkAutoProcessed != null) {
            autoProcessingObserver.removeObserver(checkAutoProcessed);
        }

        if (isReadingMode) {
            switchPlayModeButton.setVisible(false);
            functionButtonsReadingMode.setVisibility(View.INVISIBLE);
        } else {
            functionButtons.setVisibility(View.INVISIBLE);
            switchReadModeButton.setVisible(false);
        }
        reloadButton.setVisible(false);
        bookmarkButton.setVisible(false);
        translationButton.setVisible(false);
        highlightTextButton.setVisible(false);
        compositeDisposable.dispose();
        textUtil.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        ttsPlayer.setWebViewCallback(this);
        if (!isReadingMode) {
            mMediaBrowserHelper.onStart();

            MediaControllerCompat mediaController = mMediaBrowserHelper.getMediaController();
            if (mediaController != null) {
                MediaControllerCompat.setMediaController(this, mediaController);
            }
        }
    }

    @Override
    public void onStop() {
        if (isReadingMode) {
            if (ttsExtractor.getWebViewCallback() == this) {
                ttsExtractor.setCallback((WebViewListener) null);
            }
        } else {
            if (ttsPlayer.getWebViewCallback() == this) {
                ttsPlayer.setWebViewCallback(null);
            }
            mMediaBrowserHelper.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        ttsPlayer.setWebViewConnected(false);
        ttsPlayer.setUiControlPlayback(false);

        if (extractionRunnable != null) {
            extractionHandler.removeCallbacks(extractionRunnable);
        }

        if (webView != null && currentId != 0) {
            sharedPreferencesRepository.setScrollX(currentId, webView.getScrollX());
            sharedPreferencesRepository.setScrollY(currentId, webView.getScrollY());
            sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);
            sharedPreferencesRepository.setIsSummarizedView(currentId, isSummarizedView);
        }

        MediaControllerCompat mediaController = mMediaBrowserHelper.getMediaController();
        if (mediaController != null) {
            mediaController.unregisterCallback(mediaControllerCallback);
            Log.d(TAG, "MediaController callback unregistered");
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ttsPlayer.setWebViewConnected(true);

        updatePlayPauseButtonIcon(ttsPlayer.isSpeaking() && !ttsPlayer.isPausedManually());

        Log.d(TAG, "onResume: isSpeaking=" + ttsPlayer.isSpeaking() + ", isPausedManually=" + ttsPlayer.isPausedManually());

        if (!isReadingMode) {
            mMediaBrowserHelper.onStart();
            MediaControllerCompat mediaController = mMediaBrowserHelper.getMediaController();
            if (mediaController != null) {
                mediaController.registerCallback(mediaControllerCallback);
            }
        }

    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged: orientation changed, activity not recreated.");
    }

    private class WebClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.d(TAG, "WebClient: onPageStarted - loadingWebView visible.");
            webViewViewModel.setLoadingState(true);
            if (clearHistory) {
                clearHistory = false;
                webView.clearHistory();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
            view.loadUrl(request.getUrl().toString());
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            Log.d(TAG, "WebClient: onPageCommitVisible - loadingWebView hidden.");
            webView.animate().alpha(1.0f).setDuration(800).setStartDelay(400).start();
            webViewViewModel.setLoadingState(false);
            if (content != null && !content.trim().isEmpty()) {
                if (currentId != ttsPlaylist.getPlayingId()) {
                    ttsPlaylist.updatePlayingId(currentId);
                    mMediaBrowserHelper.getTransportControls().sendCustomAction("autoPlay", null);
                }
                functionButtons.setVisibility(View.VISIBLE);
                functionButtons.setAlpha(1.0f);
                reloadButton.setVisible(true);
                bookmarkButton.setVisible(true);
                highlightTextButton.setVisible(true);
            } else {
                if (currentId != ttsPlaylist.getPlayingId()) {
                    ttsPlaylist.updatePlayingId(currentId);
                }
                // Extraction moved to onPageFinished for better timing
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "WebClient: onPageFinished triggered for: " + url);
            if (content == null || content.trim().isEmpty()) {
                triggerManualExtraction(view);
            }
        }
    }

    private class ReadingWebClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.d(TAG, "ReadingWebClient: onPageStarted - loadingWebView visible.");
            webViewViewModel.setLoadingState(true);
            ttsExtractor.setCallback(WebViewActivity.this);
            if (clearHistory) {
                clearHistory = false;
                webView.clearHistory();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
            view.loadUrl(request.getUrl().toString());
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            loading.setVisibility(View.INVISIBLE);
            Log.d(TAG, "ReadingWebClient: onPageCommitVisible - loadingWebView hidden.");
            webView.animate().alpha(1.0f).setDuration(800).setStartDelay(400).start();
            webViewViewModel.setLoadingState(false);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "ReadingWebClient: onPageFinished triggered for: " + url);
            if (content == null || content.trim().isEmpty()) {
                triggerManualExtraction(view);
            }
        }
    }

    private final Handler extractionHandler = new Handler(Looper.getMainLooper());
    private Runnable extractionRunnable;

    private void triggerManualExtraction(WebView view) {
        if (extractionRunnable != null) {
            extractionHandler.removeCallbacks(extractionRunnable);
        }
        triggerManualExtraction(view, 1);
    }

    private void triggerManualExtraction(WebView view, int attempt) {
        if (currentId <= 0 || currentLink == null || isFinishing() || isDestroyed()) return;

        Log.d(TAG, "Triggering manual extraction (attempt " + attempt + ") for: " + currentLink);

        // 1. Always scroll to trigger potential lazy loading/unlocking
        view.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null);

        // 2. Wait a bit for the scroll to trigger JS events before checking status
        extractionRunnable = () -> {
            if (isFinishing() || isDestroyed()) return;

            String checkJs = "(function() { " +
                    "var locked = !!document.querySelector('.paywall, .subscription-wall, #paywall, .premium-content, .locked-article, .teaser-content, .read-more-content, .membership-paywall, .paywall-container, .subscription-required, .membership-required'); " +
                    "return document.readyState + '|' + locked; " +
                    "})();";

            view.evaluateJavascript(checkJs, value -> {
                if (isFinishing() || isDestroyed()) return;
                
                String res = (value != null) ? value.replace("\"", "") : "";
                String[] parts = res.split("\\|");
                String readyState = parts.length > 0 ? parts[0] : "";
                boolean isLocked = parts.length > 1 && Boolean.parseBoolean(parts[1]);

                if (isLocked && attempt < 12 && readyState.contains("complete")) {
                    Log.d(TAG, "Content seems LOCKED (attempt " + attempt + "). Retrying in 2.5s...");
                    extractionRunnable = () -> triggerManualExtraction(view, attempt + 1);
                    extractionHandler.postDelayed(extractionRunnable, 2500);
                } else {
                    // Final extraction
                    view.evaluateJavascript("(function() {return document.getElementsByTagName('html')[0].outerHTML;})();", htmlValue -> {
                        if (isFinishing() || isDestroyed()) return;
                        
                        JsonReader reader = new JsonReader(new StringReader(htmlValue));
                        reader.setLenient(true);
                        try {
                            if (reader.peek() == JsonToken.STRING) {
                                String extractedHtml = reader.nextString();
                                if (extractedHtml != null) {
                                    EntryInfo info = webViewViewModel.getEntryInfoById(currentId);
                                    String title = (info != null) ? info.getEntryTitle() : "";
                                    ttsExtractor.processExtraction(currentId, currentLink, title, extractedHtml);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Manual extraction failed", e);
                        }
                    });
                }
            });
        };
        
        // Short delay after scroll before running the check
        extractionHandler.postDelayed(extractionRunnable, 3000);
    }

    private class MediaBrowserConnection extends MediaBrowserHelper {
        private MediaBrowserConnection(Context context) {
            super(context, TtsService.class);
        }

        @Override
        protected void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
            super.onChildrenLoaded(parentId, children);

            final MediaControllerCompat mediaController = getMediaController();
            if (mediaController != null) {
                ttsPlayer.setWebViewCallback(WebViewActivity.this);
                ttsPlayer.setWebViewConnected(true);
                mediaController.getTransportControls().prepare();
            }
        }
    }

    private class MediaBrowserListener extends MediaControllerCompat.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            super.onPlaybackStateChanged(state);
            isPlaying = state != null && state.getState() == PlaybackStateCompat.STATE_PLAYING;
            updatePlayPauseButtonIcon(isPlaying);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            // 1. Safety check: Exit if metadata is null
            if (metadata == null) {
                return;
            }

            // 2. Extract Media ID String and check before parsing
            String mediaIdStr = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
            if (mediaIdStr == null || mediaIdStr.isEmpty()) {
                Log.d(TAG, "onMetadataChanged: Received empty Media ID, skipping UI update.");
                return;
            }

            // Now it is safe to parse
            try {
                if (currentId != Long.parseLong(mediaIdStr)) {
                    webViewViewModel.clearViewData();
                }
                currentId = Long.parseLong(mediaIdStr);
                // Update observer to the new ID immediately
                subscribeToEntry(currentId);
            } catch (NumberFormatException e) {
                Log.e(TAG, "onMetadataChanged: Error parsing Media ID: " + mediaIdStr, e);
                return;
            }

            // 3. Update UI visibility and state
            clearHistory = true;
            runOnUiThread(() -> {
                loading.setVisibility(View.VISIBLE);
                loading.setProgress(10);
            });

            functionButtons.setVisibility(View.VISIBLE);
            functionButtons.setAlpha(0.5f);
            reloadButton.setVisible(false);
            bookmarkButton.setVisible(false);
            highlightTextButton.setVisible(false);

            // 4. Extract other Metadata fields
            content = metadata.getString("content");
            bookmark = metadata.getString("bookmark");
            currentLink = metadata.getString("link");
            feedId = metadata.getLong("feedId");

            // 5. Update Bookmark Icon
            if (bookmark == null || bookmark.equals("N")) {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
            } else {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
            }

            // 6. Logic for Content View (Summary / Translation / Original)
            isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentId);
            isSummarizedView = sharedPreferencesRepository.getIsSummarizedView(currentId);

            String htmlToLoad = isSummarizedView
                    ? webViewViewModel.getSummarizedHtmlById(currentId)
                    : isTranslatedView
                    ? webViewViewModel.getTranslatedHtmlById(currentId)
                    : webViewViewModel.getOriginalHtmlById(currentId);

            if (htmlToLoad == null || htmlToLoad.trim().isEmpty()) {
                htmlToLoad = webViewViewModel.getHtmlById(currentId);
            }

            // Fallback to the HTML bundled in metadata if DB returns null
            if (htmlToLoad == null) {
                htmlToLoad = metadata.getString("html");
            }

            // 7. WebView Loading Logic
            boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);

            if (isWebViewMode) {
                webView.loadUrl(currentLink);
                Log.d(TAG, "Restoring web view mode: " + currentLink);
            } else if (htmlToLoad != null) {
                loadHtmlIntoWebView(htmlToLoad);
            } else {
                webView.loadUrl(currentLink);
                Log.d(TAG, "Fallback: loading live URL - " + currentLink);
            }
            refreshButtonVisibility();

            // 8. TTS Bridge
            if (ttsPlayer.isWebViewConnected()) {
                ttsPlayer.setUiControlPlayback(true);
            }
        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
        }
    }

    private void updatePlayPauseButtonIcon(boolean playing) {
        int iconRes = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        playPauseButton.setIcon(ContextCompat.getDrawable(this, iconRes));
    }

    private void animateToolbarIcon(int itemId) {
        View view = toolbar.findViewById(itemId);
        if (view != null) {
            android.view.animation.Animation bounce = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.button_bounce);
            view.startAnimation(bounce);
        }
    }
}
