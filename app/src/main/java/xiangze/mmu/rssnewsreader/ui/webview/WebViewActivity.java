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
import xiangze.mmu.rssnewsreader.model.ai.ChatActivity;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlaylist;
import xiangze.mmu.rssnewsreader.service.tts.TtsService;
import xiangze.mmu.rssnewsreader.service.util.AutoSummarizer;
import xiangze.mmu.rssnewsreader.service.util.AutoTranslator;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;
import xiangze.mmu.rssnewsreader.ui.feed.ReloadDialog;

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
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if (metadata != null) {
                String mediaIdStr = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                if (mediaIdStr != null) {
                    long newId = Long.parseLong(mediaIdStr);
                    // Only switch if it's a legitimate ID change and it matches what we think we are viewing
                    // OR if it's a legitimate next article skip.
                    if (newId != currentId && newId != 0) {
                        if (newId == GlobalState.getCurrentViewingId() || ttsPlaylist.getPlayingId() == newId) {
                            currentId = newId;
                            webViewViewModel.setCurrentId(currentId);
                            sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
                            loadEntryContent();
                        } else {
                            Log.d(TAG, "Ignoring metadata change for ID: " + newId + ". Current viewing: " + GlobalState.getCurrentViewingId());
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

        if (currentId != entryInfo.getEntryId()) {
            userManuallySwitchedToOriginal = false; // Reset for new article
        }

        currentId = entryInfo.getEntryId();
        GlobalState.setCurrentViewingId(currentId); // SYNC GLOBAL STATE
        currentTitle = entryInfo.getEntryTitle();
        feedId = entryInfo.getFeedId();
        currentLink = entryInfo.getEntryLink();

        webViewViewModel.prioritizeEntry(currentId);

        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;

        if (sharedPreferencesRepository.getWebViewMode(currentId)) {
            webView.loadUrl(currentLink);
            refreshButtonVisibility();
            return;
        }

        boolean hasSummary = entry.getSummarized() != null && !entry.getSummarized().trim().isEmpty();
        boolean hasTranslation = entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty();

        // Only auto-summarize if the user hasn't explicitly said they want the original content for this article
        if (!userManuallySwitchedToOriginal) {
            webViewViewModel.setIsSummarizedView(hasSummary);
            webViewViewModel.setIsTranslatedView(!hasSummary && hasTranslation);
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
                contentManager.loadHtml(htmlToLoad, currentId);
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
                    // The observer on isSummarizedViewLiveData will call loadCurrentViewState()
                    return; 
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
        webViewViewModel.reExtract(currentId);
        loadEntryContent();
        makeSnackbar("Clearing content and re-extracting...");
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
            .flatMap(sLang -> textUtil.translateHtmlAllAtOnce(sLang, targetLang, sourceHtml, info.getEntryTitle(), currentId, p -> runOnUiThread(() -> loading.setProgress(p)), true))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(res -> {
                TextUtil.AiResponse aiRes = textUtil.parseAiResponse(res, info.getEntryTitle());
                String finalHtml = textUtil.formatAiResponseToHtml(aiRes.title, aiRes.content, info.getFeedTitle(), info.getEntryPublishedDate(), info.getFeedImageUrl(), sharedPreferencesRepository.getNight(), "translated-title");
                
                // Properly split AI response into sentences and prepend title
                String splitRes = textUtil.splitIntoSentences(aiRes.content, TtsExtractor.DELIMITER);
                String contentToRead = aiRes.title + TtsExtractor.DELIMITER + splitRes;
                
                webViewViewModel.updateTranslated(contentToRead, currentId);
                webViewViewModel.updateTranslatedHtml(finalHtml, currentId);
                webViewViewModel.setIsTranslatedView(true);
                loadCurrentViewState();
            }, err -> makeSnackbar("Error: " + err.getMessage())));
    }

    private void summarize() {
        String model = sharedPreferencesRepository.getSummarizationModel(); 
        if (!new AiClient(this).hasKey(model)) return;

        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;
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
                TextUtil.AiResponse aiRes = textUtil.parseAiResponse(res, info.getEntryTitle());
                String finalHtml = textUtil.formatAiResponseToHtml(aiRes.title, aiRes.content, info.getFeedTitle(), info.getEntryPublishedDate(), info.getFeedImageUrl(), sharedPreferencesRepository.getNight(), "summarized-title");
                
                // Properly split AI response into sentences and prepend title
                String splitRes = textUtil.splitIntoSentences(aiRes.content, TtsExtractor.DELIMITER);
                String contentToRead = aiRes.title + TtsExtractor.DELIMITER + splitRes;
                
                webViewViewModel.updateSummarized(contentToRead, currentId);
                webViewViewModel.updateSummarizedHtml(finalHtml, currentId);
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
        mMediaBrowserHelper = new MediaBrowserConnection(this);
        setupMediaButtons();
    }

    private void setupMediaButtons() {
        binding.playPauseButton.setOnClickListener(v -> {
            if (isPlaying) mMediaBrowserHelper.getTransportControls().pause();
            else mMediaBrowserHelper.getTransportControls().play();
        });
        binding.skipNextButton.setOnClickListener(v -> mMediaBrowserHelper.getTransportControls().skipToNext());
        binding.skipPreviousButton.setOnClickListener(v -> mMediaBrowserHelper.getTransportControls().skipToPrevious());
        binding.fastForwardButton.setOnClickListener(v -> mMediaBrowserHelper.getTransportControls().fastForward());
        binding.rewindButton.setOnClickListener(v -> mMediaBrowserHelper.getTransportControls().rewind());
    }

    private void setupReadingNavigation() {
        binding.nextArticleButton.setOnClickListener(v -> { if (ttsPlaylist.skipNext()) loadEntryContent(); });
        binding.previousArticleButton.setOnClickListener(v -> { if (ttsPlaylist.skipPrevious()) loadEntryContent(); });
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

    public void showFakeLoading() { loading.setVisibility(View.VISIBLE); loading.setProgress(0); }
    public void hideFakeLoading() { loading.setVisibility(View.GONE); }
    public void updateLoadingProgress(int p) { loading.setProgress(p); if (p >= 100) hideFakeLoading(); }
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

        @Override public void onPageFinished(WebView v, String u) { 
            super.onPageFinished(v, u);
            if (u != null && !u.startsWith("file:///android_res/")) {
                final String executionToken = currentLoadToken;
                int delay = feedRepository.getDelayTimeById(feedId);
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

        @Override public void onPageFinished(WebView v, String u) {
            super.onPageFinished(v, u);
            if (u != null && !u.startsWith("file:///android_res/")) {
                final String executionToken = currentLoadToken;
                int delay = feedRepository.getDelayTimeById(feedId);
                new Handler(Looper.getMainLooper()).postDelayed(() -> checkReadyState(v, executionToken, 1), delay * 1000L);
            }
        }
    }

    private void checkReadyState(WebView view, String executionToken, int attempt) {
        if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) return;

        view.evaluateJavascript("(function() { return document.readyState; })();", value -> {
            if (!executionToken.equals(currentLoadToken) || hasProcessedCurrentToken) return;

            if (value != null && value.contains("complete")) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> extractHtml(view, executionToken), 5000);
            } else if (value != null && value.contains("interactive")) {
                if (attempt < 15) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> checkReadyState(view, executionToken, attempt + 1), 2000);
                } else {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> extractHtml(view, executionToken), 5000);
                }
            } else {
                if (attempt < 20) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> checkReadyState(view, executionToken, attempt + 1), 2000);
                } else {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> extractHtml(view, executionToken), 5000);
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
            } catch (Exception e) { Log.e(TAG, "Ex err", e); }
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
