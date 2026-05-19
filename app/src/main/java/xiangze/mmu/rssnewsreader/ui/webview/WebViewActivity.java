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
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.StringReader;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.GlobalState;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.databinding.ActivityWebviewBinding;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.model.ai.AiClient;
import xiangze.mmu.rssnewsreader.ui.chat.ChatActivity;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlaylist;
import xiangze.mmu.rssnewsreader.service.tts.TtsService;
import xiangze.mmu.rssnewsreader.service.util.AutoSummarizer;
import xiangze.mmu.rssnewsreader.service.util.AutoTranslator;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;
import xiangze.mmu.rssnewsreader.ui.feed.ReloadDialog;

import xiangze.mmu.rssnewsreader.service.util.AdBlocker;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

@AndroidEntryPoint
public class WebViewActivity extends AppCompatActivity implements ReloadDialog.ReloadAction, WebViewMenuHandler.MenuActionListener, WebViewListener {
    private final static String TAG = "WebViewActivity";
    private ActivityWebviewBinding binding;
    private WebViewViewModel webViewViewModel;
    private WebViewContentManager contentManager;
    private WebViewMenuHandler menuHandler;
    
    private WebView webView;
    private LinearProgressIndicator loading;
    private MaterialToolbar toolbar;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private TextUtil textUtil;
    private MediaBrowserHelper mMediaBrowserHelper;
    private boolean isPlaying;
    private boolean isReadingMode;
    private long currentId;
    private long feedId;
    private String currentLink;
    private String currentTitle;
    private String lastLoadedHtml = "";
    private long lastLoadedEntryId = 0;
    private String currentLoadToken = "";
    private boolean hasProcessedCurrentToken = false;
    private boolean userManuallySwitchedToOriginal = false;

    @Inject TtsPlayer ttsPlayer;
    @Inject TtsPlaylist ttsPlaylist;
    @Inject TtsExtractor ttsExtractor;
    @Inject SharedPreferencesRepository sharedPreferencesRepository;
    @Inject EntryRepository entryRepository;
    @Inject xiangze.mmu.rssnewsreader.data.feed.FeedRepository feedRepository;

    private final MediaControllerCompat.Callback mediaControllerCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            isPlaying = state.getState() == PlaybackStateCompat.STATE_PLAYING;
            updatePlayPauseButtonIcon(isPlaying);
            
            if (isPlaying) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if (metadata != null) {
                String mediaIdStr = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                if (mediaIdStr != null) {
                    long newId = Long.parseLong(mediaIdStr);
                    // Follow the TTS skip if:
                    // 1. We are in "Play Mode" (isReadingMode = false)
                    // 2. We were already viewing what WAS playing (sync mode)
                    // 3. The new ID matches what the playlist says is playing
                    if (newId != currentId && newId != 0) {
                        Log.d(TAG, "onMetadataChanged: New ID = " + newId + ", currentId = " + currentId + ", playingId = " + ttsPlaylist.getPlayingId());
                        
                        boolean shouldFollow = !isReadingMode || currentId == 0 || newId == ttsPlaylist.getPlayingId();
                        
                        if (shouldFollow) {
                            currentId = newId;
                            webViewViewModel.setCurrentId(currentId);
                            sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
                            loadEntryContent();
                        } else {
                            Log.d(TAG, "Ignoring metadata change as user might be browsing a different article manually in Reading Mode.");
                        }
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webViewViewModel = new ViewModelProvider(this).get(WebViewViewModel.class);
        binding = ActivityWebviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initializeFields();
        setupObservers();
        setupBackNavigation();
        loadInitialState(savedInstanceState);
    }

    private void initializeFields() {
        webView = binding.webview;
        loading = binding.loadingWebView;
        toolbar = binding.toolbar;
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        textUtil = new TextUtil(sharedPreferencesRepository);
        contentManager = new WebViewContentManager(webView, webViewViewModel, sharedPreferencesRepository, this);
        menuHandler = new WebViewMenuHandler(this, webViewViewModel, sharedPreferencesRepository, toolbar, this);
        menuHandler.setupMenu();
        
        initializeWebViewSettings();
    }

    private void setupObservers() {
        webViewViewModel.getCurrentIdLiveData().observe(this, id -> currentId = id);
        webViewViewModel.getIsTranslatedViewLiveData().observe(this, translated -> loadCurrentViewState());
        webViewViewModel.getIsSummarizedViewLiveData().observe(this, summarized -> loadCurrentViewState());
        webViewViewModel.getSnackbarMessageLiveData().observe(this, this::makeSnackbar);
        
        // TtsPlayer Observers
        ttsPlayer.getHighlightTextLiveData().observe(this, contentManager::highlightText);
        ttsPlayer.getFinishedSetupLiveData().observe(this, finished -> { if (finished != null && finished) finishedSetup(); });
        ttsPlayer.getLoadingProgressLiveData().observe(this, this::updateLoadingProgress);
        ttsPlayer.getAskForReloadLiveData().observe(this, this::askForReload);
        ttsPlayer.getSnackbarMessageLiveData().observe(this, this::makeSnackbar);
        ttsPlayer.getShowFakeLoadingLiveData().observe(this, show -> {
            if (show != null) { if (show) showFakeLoading(); else hideFakeLoading(); }
        });

        webViewViewModel.getTranslatedTextReady().observe(this, text -> handleProcessedTextReady(text, true));
        webViewViewModel.getSummarizedTextReady().observe(this, text -> handleProcessedTextReady(text, false));
        
        observeLiveEntry();
    }

    private void handleProcessedTextReady(String text, boolean isTranslation) {
        if (!isReadingMode && text != null && !text.trim().isEmpty()) {
            boolean active = isTranslation ? 
                Boolean.TRUE.equals(webViewViewModel.getIsTranslatedViewLiveData().getValue()) :
                Boolean.TRUE.equals(webViewViewModel.getIsSummarizedViewLiveData().getValue());
            if (active) {
                String lang = getLanguageForCurrentView(currentId, active, "en");
                String viewMode = isTranslation ? "translated" : "summarized";
                ttsPlayer.extract(currentId, feedId, text, lang, viewMode);
                if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                    mMediaBrowserHelper.getTransportControls().prepare();
                }
            }
        }
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.article_pop_enter, R.anim.article_pop_exit);
                }
            }
        });
    }

    private void loadInitialState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            currentId = savedInstanceState.getLong("current_id");
            isReadingMode = savedInstanceState.getBoolean("is_reading_mode");
        } else {
            isReadingMode = getIntent().getBooleanExtra("read", false);
            boolean forceId = getIntent().getBooleanExtra("force_id", false);
            
            if (forceId) {
                currentId = getIntent().getLongExtra("entry_id", 0);
            } else {
                // Check if there's already something playing/reading in the background
                long playingId = sharedPreferencesRepository.getCurrentReadingEntryId();
                if (playingId != 0) {
                    currentId = playingId;
                } else {
                    currentId = getIntent().getLongExtra("entry_id", 0);
                }
            }
        }

        webViewViewModel.setCurrentId(currentId);
        if (currentId != 0) {
            ttsPlaylist.updatePlayingId(currentId);
            sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
            entryRepository.updateDate(new Date(), currentId);
        }

        if (isReadingMode) switchReadMode(); else switchPlayMode();
        loadEntryContent();
    }

    private void loadEntryContent() {
        EntryInfo entryInfo = (currentId != 0) ? webViewViewModel.getEntryInfoById(currentId) : webViewViewModel.getLastVisitedEntry();
        if (entryInfo == null) { makeSnackbar("No article to load."); return; }

        boolean isNewArticle = (lastLoadedEntryId != entryInfo.getEntryId());
        lastLoadedEntryId = entryInfo.getEntryId();

        if (isNewArticle) {
            userManuallySwitchedToOriginal = false; // Reset for new article
            lastLoadedHtml = ""; // Reset cache to force reload on new article
        }

        currentId = entryInfo.getEntryId();
        GlobalState.setCurrentViewingId(currentId); // SYNC GLOBAL STATE
        currentTitle = entryInfo.getEntryTitle();
        feedId = entryInfo.getFeedId();
        currentLink = entryInfo.getEntryLink();

        webViewViewModel.prioritizeEntry(currentId);

        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;

        // Auto-reload if content is detected as an error message or too short
        if (!sharedPreferencesRepository.getWebViewMode(currentId) && textUtil.isErrorContent(entry.getContent())) {
            Log.d(TAG, "Error content detected for ID: " + currentId + ". Triggering auto re-extraction.");
            ttsExtractor.resetAndRetry(currentId);
            showFakeLoading();
        }

        if (sharedPreferencesRepository.getWebViewMode(currentId)) {
            webView.loadUrl(currentLink);
            refreshButtonVisibility();
            return;
        }

        boolean hasSummary = entry.getSummarizedHtml() != null && !entry.getSummarizedHtml().trim().isEmpty();
        boolean hasTranslation = entry.getTranslatedHtml() != null && !entry.getTranslatedHtml().trim().isEmpty();

        // Only auto-summarize if the user hasn't explicitly said they want the original content for this article
        if (!userManuallySwitchedToOriginal) {
            // For new articles, always set the correct state based on available content.
            // For same-article refreshes, only promote to summarized/translated, never demote.
            if (hasSummary) {
                webViewViewModel.setIsSummarizedView(true);
            } else if (isNewArticle) {
                webViewViewModel.setIsSummarizedView(false);
            }
            
            if (hasTranslation && !hasSummary) {
                webViewViewModel.setIsTranslatedView(true);
            } else if (isNewArticle) {
                webViewViewModel.setIsTranslatedView(false);
            }
        }
        
        loadCurrentViewState();
        syncLoadingWithTts();
    }

    private void loadCurrentViewState() {
        loadCurrentViewState(null);
    }

    private void loadCurrentViewState(Entry entry) {
        if (entry == null) {
            entry = entryRepository.getEntryById(currentId);
        }
        if (entry == null || entry.getId() != currentId) return;

        String htmlToLoad;
        String contentToRead;
        boolean isSummarized = Boolean.TRUE.equals(webViewViewModel.getIsSummarizedViewLiveData().getValue());
        boolean isTranslated = Boolean.TRUE.equals(webViewViewModel.getIsTranslatedViewLiveData().getValue());

        if (isSummarized) {
            htmlToLoad = entry.getSummarizedHtml();
            contentToRead = entry.getSummarized();
        } else if (isTranslated) {
            htmlToLoad = entry.getTranslatedHtml();
            contentToRead = entry.getTranslated();
        } else {
            htmlToLoad = entry.getOriginalHtml();
            if (htmlToLoad == null || htmlToLoad.trim().isEmpty()) htmlToLoad = entry.getHtml();
            contentToRead = entry.getContent();
        }

        if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
            if (!htmlToLoad.equals(lastLoadedHtml)) {
                lastLoadedHtml = htmlToLoad;
                contentManager.loadHtml(htmlToLoad, currentId, ttsPlayer.isSpeaking());
            }
        } else {
            lastLoadedHtml = ""; // Reset since we are loading a URL
            webView.loadUrl(currentLink);
            showFakeLoading();
        }

        if (contentToRead != null && !contentToRead.trim().isEmpty()) {
            String lang = getLanguageForCurrentView(currentId, isSummarized || isTranslated, "en");
            String viewMode = isSummarized ? "summarized" : (isTranslated ? "translated" : "original");
            
            // Only extract if this is still the current article the user is looking at
            if (entry.getId() == currentId) {
                ttsPlayer.extract(currentId, feedId, contentToRead, lang, viewMode);
                if (mMediaBrowserHelper != null && mMediaBrowserHelper.getTransportControls() != null) {
                    mMediaBrowserHelper.getTransportControls().prepare();
                }
            }
        } else {
            if (entry.getId() == currentId) {
                ttsPlayer.extract(currentId, feedId, null, "en", "original");
            }
        }
        refreshButtonVisibility();
    }

    private void observeLiveEntry() {
        webViewViewModel.getLiveEntry().observe(this, entry -> {
            if (entry == null || entry.getId() != currentId) return;

            // If we are in WebView mode, DO NOT auto-switch or reload content based on extracted state.
            if (sharedPreferencesRepository.getWebViewMode(currentId)) {
                return;
            }

            boolean isSummarized = Boolean.TRUE.equals(webViewViewModel.getIsSummarizedViewLiveData().getValue());
            boolean isTranslated = Boolean.TRUE.equals(webViewViewModel.getIsTranslatedViewLiveData().getValue());

            // Auto-switch to summarized view if it just became available, we are in original view,
            // AND the user hasn't manually chosen to see the original content.
            if (!isSummarized && !isTranslated && !userManuallySwitchedToOriginal) {
                boolean hasSummary = entry.getSummarizedHtml() != null && !entry.getSummarizedHtml().trim().isEmpty();
                if (hasSummary) {
                    webViewViewModel.setIsSummarizedView(true);
                    isSummarized = true; // Update local state to trigger the reload below
                }
            }

            // Auto-switch to translated view if it just became available, we are in original view,
            // AND the user hasn't manually chosen to see the original content.
            if (!isSummarized && !isTranslated && !userManuallySwitchedToOriginal) {
                boolean hasTranslation = entry.getTranslatedHtml() != null && !entry.getTranslatedHtml().trim().isEmpty();
                if (hasTranslation) {
                    webViewViewModel.setIsTranslatedView(true);
                    isTranslated = true; // Update local state to trigger the reload below
                }
            }

            String currentContentInDb = isSummarized ? entry.getSummarizedHtml() : 
                                       (isTranslated ? entry.getTranslatedHtml() : 
                                       (entry.getOriginalHtml() != null ? entry.getOriginalHtml() : entry.getHtml()));

            if (currentContentInDb != null && !currentContentInDb.equals(lastLoadedHtml)) {
                loadCurrentViewState(entry);
            }
            refreshButtonVisibility();
        });
    }

    private void refreshButtonVisibility() {
        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;

        boolean hasOriginal = entry.getOriginalHtml() != null || entry.getHtml() != null;
        boolean hasTranslated = entry.getTranslatedHtml() != null;
        boolean hasSummarized = entry.getSummarizedHtml() != null;

        boolean isSummarized = Boolean.TRUE.equals(webViewViewModel.getIsSummarizedViewLiveData().getValue());
        boolean isTranslated = Boolean.TRUE.equals(webViewViewModel.getIsTranslatedViewLiveData().getValue());

        MenuItem transToggle = toolbar.getMenu().findItem(R.id.toggleTranslation);
        MenuItem sumToggle = toolbar.getMenu().findItem(R.id.toggleSummarization);
        MenuItem summarizeItem = toolbar.getMenu().findItem(R.id.summarize);
        MenuItem translateItem = toolbar.getMenu().findItem(R.id.translate);

        if (summarizeItem != null) {
            if (hasSummarized) {
                summarizeItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            } else {
                summarizeItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }
        }

        if (translateItem != null) {
            if (hasTranslated) {
                translateItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            } else {
                translateItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }
        }

        if (transToggle != null) {
            transToggle.setVisible(hasOriginal && hasTranslated);
            transToggle.setTitle(isTranslated ? "Show Original" : "Show Translation");
            transToggle.setIcon(isTranslated ? R.drawable.ic_letter_switch : R.drawable.ic_language);
        }
        if (sumToggle != null) {
            sumToggle.setVisible(hasOriginal && hasSummarized);
            sumToggle.setTitle(isSummarized ? "Show Original" : "Show Summary");
            sumToggle.setIcon(isSummarized ? R.drawable.ic_checkmark : R.drawable.ic_summary);
        }
        
        boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);
        toolbar.getMenu().findItem(R.id.openInBrowser).setVisible(!isWebViewMode);
        toolbar.getMenu().findItem(R.id.exitBrowser).setVisible(isWebViewMode);
        toolbar.getMenu().findItem(R.id.reload).setVisible(true);
    }

    @Override public void onTranslate() { translate(); }
    @Override public void onSummarize() { summarize(); }
    @Override public void onChat() { startActivity(new Intent(this, ChatActivity.class)); }
    @Override public void onToggleBookmark() { toggleBookmark(); }
    @Override public void onShare() { shareLink(); }
    @Override public void onToggleBackgroundMusic() { toggleMusic(); }
    @Override public void onAdjustTextZoom(boolean zoomIn) { 
        int zoom = webView.getSettings().getTextZoom() + (zoomIn ? 10 : -10);
        webView.getSettings().setTextZoom(zoom);
        sharedPreferencesRepository.setTextZoom(zoom);
    }
    @Override public void onToggleHighlight() {
        boolean h = !sharedPreferencesRepository.getHighlightText();
        sharedPreferencesRepository.setHighlightText(h);
        if (!h) webView.clearMatches();
        makeSnackbar(h ? "Highlight ON" : "Highlight OFF");
    }
    @Override public void onOpenInBrowser() { 
        sharedPreferencesRepository.setWebViewMode(currentId, true);
        lastLoadedHtml = ""; // Clear cache to force reload when exiting browser mode
        webView.loadUrl(currentLink);
        refreshButtonVisibility();
    }
    @Override public void onExitBrowser() {
        sharedPreferencesRepository.setWebViewMode(currentId, false);
        lastLoadedHtml = ""; // Ensure we force a fresh load of the extracted HTML
        userManuallySwitchedToOriginal = false; // Reset to allow auto-summarization logic
        loadEntryContent();
    }
    @Override public void onSwitchPlayMode() { isReadingMode = false; switchPlayMode(); }
    @Override public void onSwitchReadMode() { isReadingMode = true; switchReadMode(); }

    @Override
    public void onToggleTranslation() {
        Boolean current = webViewViewModel.getIsTranslatedViewLiveData().getValue();
        boolean newVal = (current == null) || !current;
        lastLoadedHtml = ""; // Force reload UI
        webViewViewModel.setIsTranslatedView(newVal);
        if (newVal) {
            webViewViewModel.setIsSummarizedView(false);
            userManuallySwitchedToOriginal = false;
        } else {
            userManuallySwitchedToOriginal = true;
        }
    }

    @Override
    public void onToggleSummarization() {
        Boolean current = webViewViewModel.getIsSummarizedViewLiveData().getValue();
        boolean newVal = (current == null) || !current;
        lastLoadedHtml = ""; // Force reload UI
        webViewViewModel.setIsSummarizedView(newVal);
        if (newVal) {
            webViewViewModel.setIsTranslatedView(false);
            userManuallySwitchedToOriginal = false;
        } else {
            userManuallySwitchedToOriginal = true;
        }
    }

    @Override
    public void onShowReloadDialog() {
        EntryInfo info = webViewViewModel.getEntryInfoById(currentId);
        if (info != null) {
            ReloadDialog dialog = new ReloadDialog(this, info.getFeedId(), R.string.reload_confirmation, R.string.reload_message);
            dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
        }
    }

    @Override
    public void onReload() {
        reload();
    }

    @Override
    public void onReExtract() {
        if (webViewViewModel != null && currentId != 0) {
            GlobalState.setCurrentViewingId(0);
            webViewViewModel.reExtract(currentId);
            loadEntryContent();
            makeSnackbar("Clearing content and re-extracting...");
        }
    }

    private void translate() {
        String model = sharedPreferencesRepository.getTranslationModel(); 
        if (!new AiClient(this).hasKey(model)) return;
        
        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;
        final String sourceHtml = entry.getOriginalHtml() != null ? entry.getOriginalHtml() : entry.getHtml();
        if (sourceHtml == null) return;
        
        final EntryInfo info = webViewViewModel.getEntryInfoById(currentId);
        final String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

        loading.setVisibility(View.VISIBLE);
        compositeDisposable.add(textUtil.identifyLanguageRx(sourceHtml)
            .flatMap(sLang -> {
                if (sLang != null && sLang.equalsIgnoreCase(targetLang)) {
                    return io.reactivex.rxjava3.core.Single.error(new Exception("Article is already in the target language (" + targetLang + ")"));
                }
                return textUtil.translateHtmlAllAtOnce(sLang, targetLang, sourceHtml, info.getEntryTitle(), currentId, p -> runOnUiThread(() -> loading.setProgress(p)), true);
            })
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(res -> {
                loading.setVisibility(View.GONE);
                TextUtil.ProcessedAiResponse processed = textUtil.processAiResponse(
                    res, 
                    info.getEntryTitle(), 
                    info.getFeedTitle(), 
                    info.getEntryPublishedDate(), 
                    info.getFeedImageUrl(), 
                    sharedPreferencesRepository.getNight(), 
                    "translated-title"
                );
                
                webViewViewModel.updateTranslated(processed.contentToRead, currentId);
                webViewViewModel.updateTranslatedHtml(processed.html, currentId);
                webViewViewModel.setIsTranslatedView(true);
                loadCurrentViewState();
            }, err -> {
                loading.setVisibility(View.GONE);
                makeSnackbar(err.getMessage());
            }));
    }

    private void summarize() {
        String model = sharedPreferencesRepository.getSummarizationModel(); 
        if (!new AiClient(this).hasKey(model)) return;

        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;
        
        // CHECK FOR ERROR CONTENT
        if (textUtil.isErrorContent(entry.getContent())) {
            Log.d(TAG, "Manual summarization requested for error content. Triggering reload.");
            ttsExtractor.resetAndRetry(currentId);
            showFakeLoading();
            return; // Exit and wait for reload to finish
        }

        final String sourceHtml = entry.getOriginalHtml() != null ? entry.getOriginalHtml() : entry.getHtml();
        if (sourceHtml == null) return;

        final EntryInfo info = webViewViewModel.getEntryInfoById(currentId);
        final String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
        final int length = sharedPreferencesRepository.getSummaryLength();

        loading.setVisibility(View.VISIBLE);
        compositeDisposable.add(textUtil.identifyLanguageRx(sourceHtml)
            .flatMap(sLang -> textUtil.summarizeHtmlAllAtOnce(sLang, targetLang, sourceHtml, length, currentId, info.getEntryTitle(), p -> runOnUiThread(() -> loading.setProgress(p)), true))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(res -> {
                TextUtil.ProcessedAiResponse processed = textUtil.processAiResponse(
                    res, 
                    info.getEntryTitle(), 
                    info.getFeedTitle(), 
                    info.getEntryPublishedDate(), 
                    info.getFeedImageUrl(), 
                    sharedPreferencesRepository.getNight(), 
                    "summarized-title"
                );
                
                webViewViewModel.updateSummarized(processed.contentToRead, currentId);
                webViewViewModel.updateSummarizedHtml(processed.html, currentId);
                webViewViewModel.setIsSummarizedView(true);
                loadCurrentViewState();
            }, err -> makeSnackbar("Error: " + err.getMessage())));
    }

    private void toggleBookmark() {
        Entry entry = entryRepository.getEntryById(currentId);
        String newVal = "Y".equals(entry.getBookmark()) ? "N" : "Y";
        webViewViewModel.updateBookmark(newVal, currentId);
        makeSnackbar(newVal.equals("Y") ? "Bookmarked" : "Removed");
    }

    private void shareLink() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, currentLink);
        startActivity(Intent.createChooser(i, null));
    }

    private void toggleMusic() {
        boolean m = !sharedPreferencesRepository.getBackgroundMusic();
        sharedPreferencesRepository.setBackgroundMusic(m);
        if (m) ttsPlayer.setupMediaPlayer(false); else ttsPlayer.stopMediaPlayer();
    }

    private void switchReadMode() {
        binding.functionButtonsReading.setVisibility(View.VISIBLE);
        binding.functionButtons.setVisibility(View.GONE);
        webView.setWebViewClient(new ReadingWebClient());
        setupReadingNavigation();
    }

    private void switchPlayMode() {
        binding.functionButtonsReading.setVisibility(View.GONE);
        binding.functionButtons.setVisibility(View.VISIBLE);
        webView.setWebViewClient(new WebClient());
        if (mMediaBrowserHelper == null) {
            mMediaBrowserHelper = new MediaBrowserConnection(this);
        }
        setupMediaButtons();
        mMediaBrowserHelper.onStart();
    }

    private void setupMediaButtons() {
        binding.playPauseButton.setOnClickListener(v -> {
            if (mMediaBrowserHelper != null) {
                MediaControllerCompat.TransportControls controls = mMediaBrowserHelper.getTransportControls();
                if (controls != null) {
                    if (isPlaying) controls.pause();
                    else controls.play();
                }
            }
        });
        binding.skipNextButton.setOnClickListener(v -> {
            if (mMediaBrowserHelper != null) {
                MediaControllerCompat.TransportControls controls = mMediaBrowserHelper.getTransportControls();
                if (controls != null) controls.skipToNext();
            }
        });
        binding.skipPreviousButton.setOnClickListener(v -> {
            if (mMediaBrowserHelper != null) {
                MediaControllerCompat.TransportControls controls = mMediaBrowserHelper.getTransportControls();
                if (controls != null) controls.skipToPrevious();
            }
        });
        binding.fastForwardButton.setOnClickListener(v -> {
            if (mMediaBrowserHelper != null) {
                MediaControllerCompat.TransportControls controls = mMediaBrowserHelper.getTransportControls();
                if (controls != null) controls.fastForward();
            }
        });
        binding.rewindButton.setOnClickListener(v -> {
            if (mMediaBrowserHelper != null) {
                MediaControllerCompat.TransportControls controls = mMediaBrowserHelper.getTransportControls();
                if (controls != null) controls.rewind();
            }
        });
    }

    private void setupReadingNavigation() {
        binding.nextArticleButton.setOnClickListener(v -> {
            if (ttsPlaylist.skipNext()) {
                currentId = ttsPlaylist.getPlayingId();
                loadEntryContent();
            }
        });
        binding.previousArticleButton.setOnClickListener(v -> {
            if (ttsPlaylist.skipPrevious()) {
                currentId = ttsPlaylist.getPlayingId();
                loadEntryContent();
            }
        });
    }

    // Boilerplate / Infrastructure
    private void initializeWebViewSettings() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.getSettings().setAlgorithmicDarkeningAllowed(sharedPreferencesRepository.getNight());
        }
        webView.setWebChromeClient(new WebChromeClient());
    }

    @Override
    public void highlightText(String searchText) {
        if (contentManager != null) {
            contentManager.highlightText(searchText);
        }
    }

    public void finishedSetup() {
        runOnUiThread(() -> {
            loading.setVisibility(View.INVISIBLE);
            if (!isReadingMode) {
                binding.functionButtons.setVisibility(View.VISIBLE);
                binding.functionButtons.setAlpha(1.0f);
            }
            refreshButtonVisibility();
        });
    }

    public void showFakeLoading() {
        loading.setIndeterminate(true);
        loading.setVisibility(View.VISIBLE);
    }

    public void hideFakeLoading() {
        loading.setVisibility(View.GONE);
        loading.setIndeterminate(false);
    }

    public void updateLoadingProgress(int p) {
        loading.setIndeterminate(false);
        loading.setProgress(p);
        if (p < 100) {
            loading.setVisibility(View.VISIBLE);
        } else {
            hideFakeLoading();
        }
    }
    public void syncLoadingWithTts() { /* Logic to sync */ }
    private long lastHandledReloadFeedId = -1;

    public void askForReload(long fid) { 
        if (fid == lastHandledReloadFeedId) {
            Log.d(TAG, "Reload dialog already shown for feed " + fid + ", skipping.");
            return;
        }
        lastHandledReloadFeedId = fid;
        new ReloadDialog(this, fid, R.string.reload_confirmation, R.string.reload_suggestion_message).show(getSupportFragmentManager(), ReloadDialog.TAG); 
    }
    @Override public void makeSnackbar(String m) { Snackbar.make(binding.getRoot(), m, Snackbar.LENGTH_SHORT).show(); }
    @Override public void reload() { webViewViewModel.resetEntry(currentId); finish(); startActivity(getIntent()); }

    private String getLanguageForCurrentView(long id, boolean p, String d) {
        if (p) {
            return sharedPreferencesRepository.getDefaultTranslationLanguage();
        }
        EntryInfo info = webViewViewModel.getEntryInfoById(id);
        return (info != null && info.getFeedLanguage() != null) ? info.getFeedLanguage() : d;
    }

    private void updatePlayPauseButtonIcon(boolean p) {
        binding.playPauseButton.setIconResource(p ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    @Override public void onStart() { super.onStart(); if (!isReadingMode && mMediaBrowserHelper != null) mMediaBrowserHelper.onStart(); }
    @Override public void onStop() { if (!isReadingMode && mMediaBrowserHelper != null) mMediaBrowserHelper.onStop(); super.onStop(); }
    @Override protected void onPause() {
        sharedPreferencesRepository.setScrollX(currentId, webView.getScrollX());
        sharedPreferencesRepository.setScrollY(currentId, webView.getScrollY());
        super.onPause();
    }
    @Override protected void onDestroy() { compositeDisposable.dispose(); super.onDestroy(); }

    private class WebClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            currentLoadToken = java.util.UUID.randomUUID().toString();
            hasProcessedCurrentToken = false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (AdBlocker.isAd(url)) {
                return AdBlocker.createEmptyResource();
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override public void onPageFinished(WebView v, String u) { 
            super.onPageFinished(v, u);
            // Hide common ad/clutter elements via JS injection
            v.evaluateJavascript("(function() { " +
                    "  var selectors = ['.ad-banner', '.social-share', '#cookie-consent', '.advertisement', '.sidebar', 'header.masthead', '.footer-ads'];" +
                    "  selectors.forEach(function(s) {" +
                    "    var elements = document.querySelectorAll(s);" +
                    "    elements.forEach(function(el) { el.style.display = 'none'; });" +
                    "  });" +
                    "})();", null);

            if (u != null && u.startsWith("file:///android_res/")) {
                // If we are coming back from background and it's already speaking,
                // sync the UI to the current speaking position.
                if (ttsPlayer.isSpeaking()) {
                    String currentHighlight = ttsPlayer.getHighlightTextLiveData().getValue();
                    if (currentHighlight != null && !currentHighlight.isEmpty()) {
                        contentManager.highlightText(currentHighlight);
                    }
                }
            } else if (u != null) {
                final String executionToken = currentLoadToken;
                // Reduce the initial delay from database delay to a faster baseline (e.g., 1s)
                // but still respect if the database asks for something extremely specific.
                int dbDelay = feedRepository.getDelayTimeById(feedId);
                int delay = Math.min(dbDelay, 1); 
                new Handler(Looper.getMainLooper()).postDelayed(() -> checkReadyState(v, executionToken, 1), delay * 1000L);
            }
        }
    }
    private class ReadingWebClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            currentLoadToken = java.util.UUID.randomUUID().toString();
            hasProcessedCurrentToken = false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (AdBlocker.isAd(url)) {
                return AdBlocker.createEmptyResource();
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override public void onPageFinished(WebView v, String u) {
            super.onPageFinished(v, u);
            // Element hiding script
            v.evaluateJavascript("(function() { " +
                    "  var selectors = ['.ad-banner', '.social-share', '#cookie-consent', '.advertisement', '.sidebar', 'header.masthead', '.footer-ads'];" +
                    "  selectors.forEach(function(s) {" +
                    "    var elements = document.querySelectorAll(s);" +
                    "    elements.forEach(function(el) { el.style.display = 'none'; });" +
                    "  });" +
                    "})();", null);

            if (u != null && u.startsWith("file:///android_res/")) {
                if (ttsPlayer.isSpeaking()) {
                    String currentHighlight = ttsPlayer.getHighlightTextLiveData().getValue();
                    if (currentHighlight != null && !currentHighlight.isEmpty()) {
                        contentManager.highlightText(currentHighlight);
                    }
                }
            } else if (u != null) {
                final String executionToken = currentLoadToken;
                int dbDelay = feedRepository.getDelayTimeById(feedId);
                int delay = Math.min(dbDelay, 1);
                new Handler(Looper.getMainLooper()).postDelayed(() -> checkReadyState(v, executionToken, 1), delay * 1000L);
            }
        }
    }

    private void checkReadyState(WebView view, String executionToken, int attempt) {
        if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) return;

        view.evaluateJavascript("(function() { return document.readyState; })();", value -> {
            if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) return;

            // Trigger extraction faster: if 'complete' or 'interactive', we can usually extract safely.
            if (value != null && (value.contains("complete") || value.contains("interactive"))) {
                // Reduced from 5000ms to 1000ms for much faster response
                new Handler(Looper.getMainLooper()).postDelayed(() -> extractHtml(view, executionToken), 1000);
            } else {
                if (attempt < 15) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> checkReadyState(view, executionToken, attempt + 1), 1000);
                } else {
                    // Fail-safe: extract anyway if we've waited too long
                    extractHtml(view, executionToken);
                }
            }
        });
    }

    private void extractHtml(WebView view, String executionToken) {
        if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) return;
        hasProcessedCurrentToken = true;

        view.evaluateJavascript("(function() { return document.getElementsByTagName('html')[0].outerHTML; })();", val -> {
            try (JsonReader r = new JsonReader(new StringReader(val))) {
                r.setLenient(true);
                if (r.peek() == JsonToken.STRING) {
                    String h = r.nextString();
                    if (h != null && h.length() >= 500) ttsExtractor.processExtraction(currentId, currentLink, currentTitle, h);
                }
            } catch (Throwable t) { 
                Log.e(TAG, "Fatal error during JS extraction", t);
                makeSnackbar("JS extraction crashed: " + t.getClass().getSimpleName());
            }
        });
    }

    private class MediaBrowserConnection extends MediaBrowserHelper {
        public MediaBrowserConnection(Context c) { super(c, TtsService.class); }
        @Override protected void onConnected(@NonNull MediaControllerCompat c) {
            c.registerCallback(mediaControllerCallback);
            mediaControllerCallback.onMetadataChanged(c.getMetadata());
            mediaControllerCallback.onPlaybackStateChanged(c.getPlaybackState());
        }
    }
}
