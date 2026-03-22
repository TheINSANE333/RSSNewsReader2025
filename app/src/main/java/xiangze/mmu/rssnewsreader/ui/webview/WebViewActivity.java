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

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
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
import xiangze.mmu.rssnewsreader.service.util.AutoSummarizer;
import xiangze.mmu.rssnewsreader.service.util.AutoTranslator;
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
            showModelSelectionDialogForTranslation();
            dialog.dismiss();
        });

        builder.show();
    }

    private void showModelSelectionDialogForTranslation() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Choose Translation Model");

        String[] models = getResources().getStringArray(R.array.ai_model);
        String[] modelValues = getResources().getStringArray(R.array.ai_model_values);

        builder.setItems(models, (dialog, which) -> {
            String selectedModel = modelValues[which];
            translate(selectedModel);
        });
        builder.show();
    }

    private void showModelSelectionDialogForSummarization() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Choose Summarization Model");

        String[] models = getResources().getStringArray(R.array.ai_model);
        String[] modelValues = getResources().getStringArray(R.array.ai_model_values);

        builder.setItems(models, (dialog, which) -> {
            String selectedModel = modelValues[which];
            summarize(selectedModel);
        });
        builder.show();
    }

    private void doWhenTranslationFinish(EntryInfo entryInfo, String originalHtml, String translationRaw) {
        loading.clearAnimation();
        loading.setVisibility(View.INVISIBLE);
        webView.animate().alpha(1.0f).setDuration(800).start();

        if (webViewViewModel.getOriginalHtmlById(currentId) == null && originalHtml != null) {
            webViewViewModel.updateOriginalHtml(originalHtml, currentId);
            entryRepository.updateOriginalHtml(originalHtml, currentId);
            Log.d(TAG, "Original HTML backed up from method parameter.");
        }

        TextUtil.AiResponse aiRes = textUtil.parseAiResponse(translationRaw, entryInfo.getEntryTitle());

        // Wrap result in HTML with marker and header
        String finalHtml = textUtil.formatAiResponseToHtml(
                aiRes.title,
                aiRes.content,
                entryInfo.getFeedTitle(),
                entryInfo.getEntryPublishedDate(),
                entryInfo.getFeedImageUrl(),
                sharedPreferencesRepository.getNight(),
                "translated-title"
        );

        webView.loadDataWithBaseURL("file///android_res/", finalHtml, "text/html", "UTF-8", null);

        toggleTranslationButton.setVisible(true);

        webViewViewModel.updateTranslatedHtml(finalHtml, currentId);
        String translatedContent = textUtil.extractHtmlContent(finalHtml, "--####--");
        webViewViewModel.setTranslatedTextReady(currentId, translatedContent);

        Log.d(TAG, "FINAL translatedContent passed to TTS: " + translatedContent);
        Log.d(TAG, "FINAL currentId: " + currentId + ", isTranslatedView: " + isTranslatedView);
    }

    private void doWhenSummarizationFinish(EntryInfo entryInfo, String originalHtml, String summaryRaw) {
        loading.clearAnimation();
        loading.setVisibility(View.INVISIBLE);
        webView.animate().alpha(1.0f).setDuration(800).start();

        if (webViewViewModel.getOriginalHtmlById(currentId) == null && originalHtml != null) {
            webViewViewModel.updateOriginalHtml(originalHtml, currentId);
            entryRepository.updateOriginalHtml(originalHtml, currentId);
            Log.d(TAG, "Original HTML backed up from method parameter.");
        }

        TextUtil.AiResponse aiRes = textUtil.parseAiResponse(summaryRaw, entryInfo.getEntryTitle());

        // Use unified formatter to include marker and header
        String finalHtml = textUtil.formatAiResponseToHtml(
                aiRes.title,
                aiRes.content,
                entryInfo.getFeedTitle(),
                entryInfo.getEntryPublishedDate(),
                entryInfo.getFeedImageUrl(),
                sharedPreferencesRepository.getNight(),
                "summarized-title"
        );

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
        refreshButtonVisibility();
        webViewViewModel.triggerEntryRefresh(currentId);

        // TTS
        String lang = getLanguageForCurrentView(currentId, true, "en");
        ttsPlayer.extract(currentId, feedId, summarizedContent, lang);
        if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
            mMediaBrowserHelper.getTransportControls().prepare();
        }
    }

    @SuppressLint("CheckResult")
    private void translate(String translationModel) {
        if (!new AiClient(this).hasKey(translationModel)) {
            showMissingKeyDialog();
            return;
        }

        // 1. Check if already translated or processing
        if (AutoTranslator.isProcessing(currentId)) {
            makeSnackbar("Translation is already in progress...");
            return;
        }

        String translatedHtml = webViewViewModel.getTranslatedHtmlById(currentId);
        if (translatedHtml != null && !translatedHtml.trim().isEmpty() && translatedHtml.contains("translated-title")) {
            makeSnackbar("Article is already translated. Toggling view...");
            handleOtherToolbarItems(R.id.toggleTranslation);
            return;
        }

        // 2. Data Retrieval
        String content = webViewViewModel.getOriginalHtmlById(currentId);
        if (content == null || content.trim().isEmpty()) {
            content = webViewViewModel.getHtmlById(currentId);
        }

        if (content == null || content.trim().isEmpty()) {
            makeSnackbar("Content is being extracted, please wait...");
            return;
        }

        final String sourceHtml = content;
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) {
            makeSnackbar("Entry info could not be loaded.");
            return;
        }

        animateToolbarIcon(R.id.translate);
        loading.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse));
        webView.animate().alpha(0.5f).setDuration(300).start();

        // 3. Execution
        makeSnackbar("Translation in progress");
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);

        String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();

        AutoTranslator.processingIds.add(currentId);

        compositeDisposable.add(textUtil.identifyLanguageRx(sourceHtml)
                .flatMap(sourceLang -> textUtil.translateHtmlAllAtOnce(sourceLang, targetLanguage, sourceHtml, entryInfo.getEntryTitle(), currentId, progress -> {
                    runOnUiThread(() -> loading.setProgress(progress));
                }))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> {
                    AutoTranslator.processingIds.remove(currentId);
                    hideFakeLoading();
                })
                .subscribe(translatedResult -> {
                    doWhenTranslationFinish(entryInfo, sourceHtml, translatedResult);
                }, error -> {
                    Log.e(TAG, "Translation error", error);
                    makeSnackbar("Translation failed: " + error.getMessage());
                }));
    }

    @SuppressLint( "SetJavaScriptEnabled" )
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

        if (savedInstanceState != null) {
            currentId = savedInstanceState.getLong("current_id");
            isReadingMode = savedInstanceState.getBoolean("is_reading_mode");
        } else {
            isReadingMode = getIntent().getBooleanExtra("read", false);
            currentId = getIntent().getLongExtra("entry_id", 0);
        }

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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("current_id", currentId);
        outState.putBoolean("is_reading_mode", isReadingMode);
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
        // Use more robust element selection instead of string check
        if (entryInfo != null && doc.selectFirst(".entry-header") == null) {
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
            // Avoid redundant updates if possible
            String existingOriginal = webViewViewModel.getOriginalHtmlById(currentId);
            boolean isProcessed = html.contains("summarized-title") || html.contains("translated-title");
            
            if (!isProcessed && (existingOriginal == null || !existingOriginal.equals(html))) {
                webViewViewModel.updateOriginalHtml(html, currentId);
            }
        }

        webView.loadDataWithBaseURL("file:///android_res/", doc.html(), "text/html", "UTF-8", null);
        webView.animate().alpha(1.0f).setDuration(300).start();

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

    private void loadCurrentViewState() {
        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;

        // 1. Resolve content based on priority: Summary > Translation > Original
        String htmlToLoad;
        String contentToRead;
        String lang;

        if (isSummarizedView) {
            htmlToLoad = webViewViewModel.getSummarizedHtmlById(currentId);
            contentToRead = entry.getSummarized();
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

        // 2. Load into WebView
        if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
            loadHtmlIntoWebView(htmlToLoad);
        } else {
            Log.w(TAG, "HTML missing, loading live URL as fallback.");
            webView.loadUrl(currentLink);
            webView.animate().alpha(1.0f).setDuration(300).start();
            showFakeLoading();
        }

        // 3. Handle TTS
        if (contentToRead != null && !contentToRead.trim().isEmpty()) {
            content = contentToRead;
            ttsPlayer.extract(currentId, feedId, contentToRead, lang);
            if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                mMediaBrowserHelper.getTransportControls().prepare();
            }
        } else {
            // Trigger extraction if no content is available
            ttsPlayer.extract(currentId, feedId, null, lang);
        }

        // 4. Update UI
        refreshButtonVisibility();
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

        // 3. Populate ViewModel
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

        // 4. Resolve view state safely (Priority: Summary > Translation > Original)
        isSummarizedView = hasSummary;
        isTranslatedView = !hasSummary && hasTranslation;

        sharedPreferencesRepository.setIsSummarizedView(currentId, isSummarizedView);
        sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);

        // 5. Load the resolved state
        loadCurrentViewState();

        sharedPreferencesRepository.setCurrentReadingEntryId(currentId);

        // 6. Observers
        observeLiveEntry();
        subscribeToEntry(currentId);
        syncLoadingWithTts();
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
            }
        });

        // Observer for Summarized HTML changes
        webViewViewModel.getSummarizedHtmlLiveData().observe(this, summarizedHtml -> {
            refreshButtonVisibility(); // Check all buttons
            // Refresh webview if we are currently viewing summary
            if (isSummarizedView) {
                Log.d("CONTENT", "summarized");
                loadHtmlToWebView(summarizedHtml);
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

                        if (hasSummary) {
                            String summarizedHtmlFromDb = entry.getSummarizedHtml();
                            String currentSummarizedInVm = webViewViewModel.getSummarizedHtmlLiveData().getValue();

                            // Only auto-switch if this is NEW content we haven't seen in the VM yet
                            if (summarizedHtmlFromDb != null && !summarizedHtmlFromDb.equals(currentSummarizedInVm)) {
                                Log.d(TAG, "New Summarized HTML detected. Switching view.");
                                isSummarizedView = true;
                                isTranslatedView = false;
                                sharedPreferencesRepository.setIsSummarizedView(currentId, true);
                                sharedPreferencesRepository.setIsTranslatedView(currentId, false);
                                webViewViewModel.updateSummarizedHtml(summarizedHtmlFromDb, currentId);

                                webView.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                                    loadHtmlIntoWebView(summarizedHtmlFromDb);
                                }).start();

                                refreshButtonVisibility();
                                webViewViewModel.triggerEntryRefresh(currentId);

                                // Handle TTS
                                String summarized = entry.getSummarized();
                                if (summarized != null) {
                                    String lang = getLanguageForCurrentView(currentId, true, "en");
                                    ttsPlayer.extract(currentId, feedId, summarized, lang);
                                    if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                                        mMediaBrowserHelper.getTransportControls().prepare();
                                    }
                                }
                            }
                        } else if (hasTranslation) {
                            String translatedHtmlFromDb = entry.getTranslatedHtml();
                            String currentTranslatedInVm = webViewViewModel.getTranslatedHtmlLiveData().getValue();

                            if (translatedHtmlFromDb != null && !isTranslatedView && !translatedHtmlFromDb.equals(currentTranslatedInVm)) {
                                isTranslatedView = true;
                                isSummarizedView = false;
                                sharedPreferencesRepository.setIsTranslatedView(currentId, true);
                                sharedPreferencesRepository.setIsSummarizedView(currentId, false);
                                webViewViewModel.updateTranslatedHtml(translatedHtmlFromDb, currentId);
                                Log.d(TAG, "Translated HTML synced from auto processing.");

                                refreshButtonVisibility();
                                webViewViewModel.triggerEntryRefresh(currentId);
                            }
                        } else if (!isSummarizedView && !isTranslatedView) {
                            if (hasOriginalHtml) {
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
                                }
                            } else if (hasContent) {
                                // Extraction failed or only plain text available
                                String contentFromDb = entry.getContent();
                                String currentViewModelHtml = webViewViewModel.getOriginalHtmlLiveData().getValue();
                                
                                if (currentViewModelHtml == null || currentViewModelHtml.trim().isEmpty()) {
                                    Log.d(TAG, "No HTML but content available. Showing plain text / failure message.");
                                    loadHtmlToWebView(contentFromDb);
                                    refreshButtonVisibility();
                                    
                                    // Start TTS for the failure message or plain text
                                    String lang = getLanguageForCurrentView(currentId, false, "en");
                                    ttsPlayer.extract(currentId, feedId, contentFromDb, lang);
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

        // Handle delimiters if this is plain text content from TtsExtractor
        String processedHtml = html;
        if (html.contains("--####--")) {
            processedHtml = html.replace("--####--", "<br><br>");
        }

        Document doc = Jsoup.parse(processedHtml);
        doc.head().append(webViewViewModel.getStyle(sharedPreferencesRepository.getNight()));

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo != null && doc.selectFirst(".entry-header") == null) {
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

        webView.loadDataWithBaseURL("file:///android_res/", doc.html(), "text/html", "UTF-8", null);
        webView.animate().alpha(1.0f).setDuration(300).start();
    }

    private void startChat() {
        Log.d(TAG, "CHAT BUTTON PRESSED");
        Intent intent = new Intent(this, ChatActivity.class);
        startActivity(intent);
    }

    private void showMissingKeyDialog() {
        new AlertDialog.Builder(this)
                .setTitle("API Key Missing")
                .setMessage("Please configure the groq API Key in Settings to use AI features.")
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void summarize(String summarizationModel) {
        if (!new AiClient(this).hasKey(summarizationModel)) {
            showMissingKeyDialog();
            return;
        }

        // 1. Check if already summarized or processing
        if (AutoSummarizer.isProcessing(currentId)) {
            makeSnackbar("Summarization is already in progress...");
            return;
        }

        String summarizedHtml = webViewViewModel.getSummarizedHtmlById(currentId);
        if (summarizedHtml != null && !summarizedHtml.trim().isEmpty() && summarizedHtml.contains("summarized-title")) {
            makeSnackbar("Article is already summarized. Toggling view...");
            handleOtherToolbarItems(R.id.toggleSummarization);
            return;
        }

        // 2. Data Retrieval
        String htmlStr = webViewViewModel.getOriginalHtmlById(currentId);
        if (htmlStr == null || htmlStr.trim().isEmpty()) {
            htmlStr = webViewViewModel.getHtmlById(currentId);
        }

        if (htmlStr == null || htmlStr.trim().isEmpty()) {
            makeSnackbar("Content is being extracted, please wait...");
            return;
        }

        final String sourceHtml = htmlStr;
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) {
            makeSnackbar("Entry info could not be loaded.");
            return;
        }

        animateToolbarIcon(R.id.summarize);
        loading.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse));
        webView.animate().alpha(0.5f).setDuration(300).start();

        // 3. Execution
        makeSnackbar("Summarization in progress");
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);

        String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        int length = sharedPreferencesRepository.getSummaryLength();

        AutoSummarizer.processingIds.add(currentId);

        compositeDisposable.add(textUtil.identifyLanguageRx(sourceHtml)
                .flatMap(sourceLang -> textUtil.summarizeHtmlAllAtOnce(sourceLang, targetLanguage, sourceHtml, length, currentId, entryInfo.getEntryTitle(), progress -> {
                    runOnUiThread(() -> loading.setProgress(progress));
                }))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> {
                    AutoSummarizer.processingIds.remove(currentId);
                    hideFakeLoading();
                })
                .subscribe(summaryResult -> {
                    doWhenSummarizationFinish(entryInfo, sourceHtml, summaryResult);
                }, error -> {
                    Log.e(TAG, "Summarization error", error);
                    makeSnackbar("Summarization failed: " + error.getMessage());
                }));
    }

    @SuppressLint("NonConstantResourceId")
    private boolean handleOtherToolbarItems(int itemId) {
        if (itemId == R.id.summarize) {
            showModelSelectionDialogForSummarization();
            return true;
        } else if (itemId == R.id.chatbot) {
            startChat();
            return true;
        } else if (itemId == R.id.translate) {
            if (targetLanguage == null || targetLanguage.isEmpty()) {
                showTranslationLanguageDialog(this);
            } else {
                showModelSelectionDialogForTranslation();
            }
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
            isTranslatedView = !isTranslatedView;
            if (isTranslatedView) {
                isSummarizedView = false; // Mutually exclusive
            }
            sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);
            sharedPreferencesRepository.setIsSummarizedView(currentId, isSummarizedView);

            // 2. Refresh UI and TTS
            loadCurrentViewState();
            return true;
        } else if (itemId == R.id.toggleSummarization) {
            // 1. Toggle the state
            isSummarizedView = !isSummarizedView;
            if (isSummarizedView) {
                isTranslatedView = false; // Mutually exclusive
            }
            sharedPreferencesRepository.setIsSummarizedView(currentId, isSummarizedView);
            sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);

            // 2. Refresh UI and TTS
            loadCurrentViewState();
            return true;
        } else {
            return false;
        }
    }

    private String rebuildHtml(EntryInfo entryInfo) {
        String html = webViewViewModel.getHtmlById(entryInfo.getEntryId());

        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle(sharedPreferencesRepository.getNight()));

        String titleClass = null;
        if (isSummarizedView) titleClass = "summarized-title";
        else if (isTranslatedView) titleClass = "translated-title";

        Objects.requireNonNull(doc.selectFirst("body")).prepend(
                webViewViewModel.getHtml(
                        entryInfo.getEntryTitle(),
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl(),
                        sharedPreferencesRepository.getNight(),
                        titleClass
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

            if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
                loadHtmlIntoWebView(htmlToLoad);
            } else {
                webView.loadUrl(currentLink);
                webView.animate().alpha(1.0f).setDuration(300).start();
                showFakeLoading();
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
                loading.setVisibility(View.INVISIBLE);
                if (!isReadingMode) {
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
        sharedPreferencesRepository.removeTranslatedViewToggle(currentId);
        sharedPreferencesRepository.removeSummarizedViewToggle(currentId);

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
        }

        if (mMediaBrowserHelper != null) {
            MediaControllerCompat mediaController = mMediaBrowserHelper.getMediaController();
            if (mediaController != null) {
                mediaController.unregisterCallback(mediaControllerCallback);
                Log.d(TAG, "MediaController callback unregistered");
            }
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ttsPlayer.setWebViewConnected(true);

        updatePlayPauseButtonIcon(ttsPlayer.isSpeaking() && !ttsPlayer.isPausedManually());

        Log.d(TAG, "onResume: isSpeaking=" + ttsPlayer.isSpeaking() + ", isPausedManually=" + ttsPlayer.isPausedManually());

        if (!isReadingMode && mMediaBrowserHelper != null) {
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
                    if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                        mMediaBrowserHelper.getTransportControls().sendCustomAction("autoPlay", null);
                    }
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

    private void updatePlayPauseButtonIcon(boolean isPlaying) {
        if (playPauseButton != null) {
            playPauseButton.setIconResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    private void triggerManualExtraction(WebView view) {
        view.evaluateJavascript("(function() { return document.getElementsByTagName('html')[0].outerHTML; })();", value -> {
            JsonReader reader = new JsonReader(new StringReader(value));
            reader.setLenient(true);
            try {
                if (reader.peek() == JsonToken.STRING) {
                    String extractedHtml = reader.nextString();
                    if (extractedHtml != null && extractedHtml.length() >= 500) {
                        ttsExtractor.processExtraction(currentId, currentLink, null, extractedHtml);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Manual extraction JS callback error", e);
            }
        });
    }

    private final Handler extractionHandler = new Handler(Looper.getMainLooper());
    private Runnable extractionRunnable;

    private class MediaBrowserListener extends MediaControllerCompat.Callback {
        // No longer needed if we use MediaBrowserConnection.onConnected
    }

    private class MediaBrowserConnection extends MediaBrowserHelper {
        public MediaBrowserConnection(Context context) {
            super(context, TtsService.class);
        }

        @Override
        protected void onConnected(@NonNull MediaControllerCompat mediaController) {
            mediaController.registerCallback(mediaControllerCallback);
            if (mediaController.getPlaybackState() != null) {
                isPlaying = mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING;
                updatePlayPauseButtonIcon(isPlaying);
            }
        }

        @Override
        protected void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
            super.onChildrenLoaded(parentId, children);
        }
    }

    private void animateToolbarIcon(int itemId) {
        View view = toolbar.findViewById(itemId);
        if (view != null) {
            android.view.animation.Animation bounce = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.button_bounce);
            view.startAnimation(bounce);
        }
    }
}
