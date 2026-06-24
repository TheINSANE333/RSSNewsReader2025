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
import timber.log.Timber;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
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
import io.reactivex.rxjava3.core.Single;
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
import xiangze.mmu.rssnewsreader.util.ApiKeyPromptDialog;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.graphics.Typeface;
import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetSequence;

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
    private boolean isTtsReady;
    private long currentId;
    private long feedId;
    private String currentLink;
    private String currentTitle;
    private String lastLoadedHtml = "";
    private long lastLoadedEntryId = 0;
    private int loadGeneration = 0; // Monotonically increasing counter to discard stale async results
    private String currentLoadToken = "";
    private boolean hasProcessedCurrentToken = false;
    private boolean userManuallySwitchedToOriginal = false;
    private boolean suppressObserverReload = false;
    private boolean isInitializing = true;
    private boolean ignoreMetadataUntilMatch = false;

    @Inject TtsPlayer ttsPlayer;
    @Inject TtsPlaylist ttsPlaylist;
    @Inject TtsExtractor ttsExtractor;
    @Inject SharedPreferencesRepository sharedPreferencesRepository;
    @Inject EntryRepository entryRepository;
    @Inject xiangze.mmu.rssnewsreader.data.feed.FeedRepository feedRepository;

    private final MediaControllerCompat.Callback mediaControllerCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            isPlaying = (state != null) && (state.getState() == PlaybackStateCompat.STATE_PLAYING);
            updatePlayPauseButtonIcon(isPlaying);
            updateMediaButtonsState(isPlaying);
            
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
                    try {
                        long newId = Long.parseLong(mediaIdStr);
                        
                        // Once we receive a metadata match for our currentId, we can stop ignoring mismatches.
                        if (newId != 0 && newId == currentId) {
                            ignoreMetadataUntilMatch = false;
                        }
                        
                        // Follow the TTS skip if:
                        // 1. We are NOT initializing (prevents flicker on startup)
                        // 2. We aren't waiting for a sync match (prevents revert to stale metadata)
                        // 3. We are in "Play Mode" (isReadingMode = false)
                        // 4. We are in Reading Mode but current article is empty or we are in sync with what's playing
                        if (newId != 0 && newId != currentId) {
                            Timber.d("onMetadataChanged: New ID = " + newId + ", currentId = " + currentId + ", playingId = " + ttsPlaylist.getPlayingId());
                            
                            boolean shouldFollow;
                            if (isInitializing || ignoreMetadataUntilMatch) {
                                Timber.d("Ignoring metadata mismatch during initialization or sync: " + newId + " (Target: " + currentId + ")");
                                shouldFollow = false;
                            } else if (!isReadingMode) {
                                shouldFollow = true; // Always follow in Play Mode
                            } else {
                                // In Reading Mode, only follow if we are in sync with the playlist
                                shouldFollow = (currentId == 0 || newId == ttsPlaylist.getPlayingId());
                            }
                            
                            if (shouldFollow) {
                                currentId = newId;
                                webViewViewModel.setCurrentId(currentId);
                                sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
                                loadEntryContent();
                            } else {
                                Timber.d("Not following metadata change.");
                            }
                        }
                    } catch (NumberFormatException e) {
                        Timber.e("Invalid media ID format: " + mediaIdStr);
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
        loadInitialState(savedInstanceState);
        setupObservers();
        setupBackNavigation();
        
        isInitializing = false;
        
        if (sharedPreferencesRepository.isFirstArticleView()) {
            showFirstViewTooltips();
            sharedPreferencesRepository.setFirstArticleView(false);
        }
    }

    private void showFirstViewTooltips() {
        binding.getRoot().postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            
            ArrayList<TapTarget> targets = new ArrayList<>();
            
            // Safe helper for building common style
            // Note: TapTarget.forToolbarMenuItem can crash if it cannot find the view internally
            
            try {
                targets.add(TapTarget.forToolbarMenuItem(toolbar, R.id.translate, "Translate Article", "Tap here to instantly translate this article into your preferred language using AI.")
                        .cancelable(false).tintTarget(true).outerCircleColor(R.color.primary).targetCircleColor(R.color.onPrimary)
                        .titleTextSize(20).titleTextColor(R.color.onPrimary).descriptionTextSize(16).descriptionTextColor(R.color.onPrimary).textTypeface(Typeface.SANS_SERIF));
            } catch (Exception e) {
                Timber.w("Walkthrough: Translate target skipped: %s", e.getMessage());
            }

            try {
                targets.add(TapTarget.forToolbarMenuItem(toolbar, R.id.summarize, "Summarize Article", "Too long? Tap here to generate a concise summary.")
                        .cancelable(false).tintTarget(true).outerCircleColor(R.color.primary).targetCircleColor(R.color.onPrimary)
                        .titleTextSize(20).titleTextColor(R.color.onPrimary).descriptionTextSize(16).descriptionTextColor(R.color.onPrimary).textTypeface(Typeface.SANS_SERIF));
            } catch (Exception e) {
                Timber.w("Walkthrough: Summarize target skipped: %s", e.getMessage());
            }

            try {
                targets.add(TapTarget.forToolbarOverflow(toolbar, "More Options", "Switch to Play Mode to listen to this article, toggle reading modes, and more.")
                        .cancelable(false).tintTarget(true).outerCircleColor(R.color.primary).targetCircleColor(R.color.onPrimary)
                        .titleTextSize(20).titleTextColor(R.color.onPrimary).descriptionTextSize(16).descriptionTextColor(R.color.onPrimary).textTypeface(Typeface.SANS_SERIF));
            } catch (Exception e) {
                Timber.w("Walkthrough: Overflow target skipped: %s", e.getMessage());
            }

            if (!targets.isEmpty()) {
                new TapTargetSequence(this)
                    .targets(targets)
                    .start();
            }
        }, 1000); // Increased delay for stability
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
        webViewViewModel.getIsTranslatedViewLiveData().observe(this, translated -> {
            if (!suppressObserverReload) loadCurrentViewState();
        });
        webViewViewModel.getIsSummarizedViewLiveData().observe(this, summarized -> {
            if (!suppressObserverReload) loadCurrentViewState();
        });
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

        // Direct article-changed signal from TTS auto-advance.
        // This bypasses the MediaSession metadata callback which can be blocked by
        // ignoreMetadataUntilMatch or reading mode guards, causing screen desync.
        ttsPlayer.getArticleChangedLiveData().observe(this, newId -> {
            if (newId != null && newId != 0 && newId != currentId) {
                Timber.d("articleChangedLiveData: Direct navigation to article " + newId + " (was " + currentId + ")");
                ignoreMetadataUntilMatch = false; // Clear the flag since we're explicitly navigating
                currentId = newId;
                webViewViewModel.setCurrentId(currentId);
                sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
                loadEntryContent();
                
                // Consume the event so it doesn't replay on rotation or recreation
                ttsPlayer.getArticleChangedLiveData().setValue(0L);
            }
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
                // User explicitly selected an article, so clear any pending auto-advance navigation
                // to prevent LiveData replay from immediately overriding this selection
                ttsPlayer.getArticleChangedLiveData().setValue(0L);
            } else {
                // Check if there's already something playing/reading in the background
                long playingId = sharedPreferencesRepository.getCurrentReadingEntryId();
                // If intent has an ID, prioritize it over resumed ID if forceId is not set but ID is present
                long intentId = getIntent().getLongExtra("entry_id", 0);
                if (intentId == 0) intentId = getIntent().getLongExtra("id", 0); // Handle 'id' fallback

                if (intentId != 0) {
                    currentId = intentId;
                } else if (playingId != 0) {
                    currentId = playingId;
                } else {
                    currentId = 0;
                }
            }
        }

        webViewViewModel.setCurrentId(currentId);
        if (currentId != 0) {
            ignoreMetadataUntilMatch = true;
            ttsPlaylist.updatePlayingId(currentId);
            sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
            
            // Move updateDate to background to avoid blocking main thread and DB contention
            compositeDisposable.add(
                io.reactivex.rxjava3.core.Completable.fromAction(() -> entryRepository.updateDate(new Date(), currentId))
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                        () -> Timber.d("Visited date updated successfully"),
                        throwable -> Timber.e(throwable, "Error updating visited date")
                    )
            );
            
            // Failsafe: clear the flag after 3s to prevent it getting permanently stuck,
            // which would block all future auto-advance metadata updates from reaching the UI.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (ignoreMetadataUntilMatch) {
                    Timber.w("ignoreMetadataUntilMatch timeout in loadInitialState - clearing flag");
                    ignoreMetadataUntilMatch = false;
                }
            }, 3000);
        }

        if (isReadingMode) switchReadMode(); else switchPlayMode();
        loadEntryContent();
    }

    private void loadEntryContent() {
        loadEntryContentWithRetry(0);
    }

    private void loadEntryContentWithRetry(int retryCount) {
        compositeDisposable.add(Single.fromCallable(() -> {
            EntryInfo entryInfo = (currentId != 0) ? webViewViewModel.getEntryInfoById(currentId) : webViewViewModel.getLastVisitedEntry();
            if (entryInfo == null) {
                if (retryCount < 3) {
                    // DB might be temporarily locked by async writes, retry
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new RuntimeException("RETRY");
                }
            }
            return entryInfo; // Could still be null after 3 retries
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(entryInfo -> {
            // AUTO-CLOSE LOGIC: If the article was deleted from the DB, don't stay on a blank screen
            if (entryInfo == null) {
                Toast.makeText(this, "Article no longer available", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            boolean isNewArticle = (lastLoadedEntryId != entryInfo.getEntryId());
            lastLoadedEntryId = entryInfo.getEntryId();

            if (isNewArticle) {
                userManuallySwitchedToOriginal = false; // Reset for new article
                lastLoadedHtml = ""; // Reset cache to force reload on new article
            }

            currentId = entryInfo.getEntryId();
            sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
            GlobalState.setCurrentViewingId(currentId); // SYNC GLOBAL STATE
            currentTitle = entryInfo.getEntryTitle();
            feedId = entryInfo.getFeedId();
            currentLink = entryInfo.getEntryLink();

            webViewViewModel.prioritizeEntry(currentId);
            isTtsReady = false;
            updateMediaButtonsState(isPlaying);

            // Capture the target ID and generation for this specific load request.
            // If TTS auto-advances before the async callback fires, the generation
            // will have incremented, and we'll know to use the latest state instead.
            final long targetId = currentId;
            final long targetFeedId = feedId;
            final String targetLink = currentLink;
            final boolean capturedIsNewArticle = isNewArticle;
            final int thisGeneration = ++loadGeneration;

            compositeDisposable.add(Single.fromCallable(() -> entryRepository.getEntryById(targetId))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(entry -> {
                        if (entry == null) return;

                        // If a newer loadEntryContent() call was made while we were on the IO thread,
                        // discard this stale result — the newer call will handle its own loading.
                        if (thisGeneration != loadGeneration) {
                            Timber.d("Discarding stale load result for entry " + targetId + " (generation " + thisGeneration + " vs current " + loadGeneration + ")");
                            return;
                        }
                        Timber.d("Loading entry content for ID: " + targetId + " (Generation: " + thisGeneration + ")");

                        if (sharedPreferencesRepository.getWebViewMode(targetId)) {
                            applyZoomSettings();
                            webView.loadUrl(targetLink);
                            refreshButtonVisibility(entry);
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
                            } else if (capturedIsNewArticle) {
                                webViewViewModel.setIsSummarizedView(false);
                            }

                            if (hasTranslation && !hasSummary) {
                                webViewViewModel.setIsTranslatedView(true);
                            } else if (capturedIsNewArticle) {
                                webViewViewModel.setIsTranslatedView(false);
                            }
                        }

                        // Auto-reload if content is detected as an error message or too short
                        // BUT ONLY if we are actually intending to show the original content and we don't have a better view (summary/translation)
                        // AND only if the content is not already null (to avoid reload loops)
                        boolean isSummarizedMode = Boolean.TRUE.equals(webViewViewModel.getIsSummarizedViewLiveData().getValue());
                        boolean isTranslatedMode = Boolean.TRUE.equals(webViewViewModel.getIsTranslatedViewLiveData().getValue());
                        boolean showingBetterView = (hasSummary && !userManuallySwitchedToOriginal && isSummarizedMode) || 
                                                  (hasTranslation && !userManuallySwitchedToOriginal && isTranslatedMode);

                        if (!sharedPreferencesRepository.getWebViewMode(targetId) 
                            && !showingBetterView 
                            && entry.getContent() != null 
                            && textUtil.isErrorContent(entry.getContent())) {
                            Timber.d("Error content detected for ID: " + targetId + ". Triggering auto re-extraction.");
                            ttsExtractor.resetAndRetry(targetId);
                            showFakeLoading();
                        }

                        loadCurrentViewState(entry);
                        syncLoadingWithTts();
                    }, throwable -> Timber.e(throwable, "Error loading entry content")));
        }, throwable -> {
            if (throwable.getMessage() != null && throwable.getMessage().equals("RETRY")) {
                loadEntryContentWithRetry(retryCount + 1);
            } else {
                Timber.e(throwable, "Error loading entry info");
            }
        }));
    }

    private void loadCurrentViewState() {
        compositeDisposable.add(Single.fromCallable(() -> entryRepository.getEntryById(currentId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::loadCurrentViewState, throwable -> Timber.e(throwable, "Error loading view state")));
    }

    private void loadCurrentViewState(Entry entry) {
        if (entry == null || entry.getId() != currentId) return;

        applyZoomSettings();

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

            // Fallback: if original HTML is missing (e.g. not yet extracted), show the best
            // available processed content rather than loading the raw URL.
            if (htmlToLoad == null || htmlToLoad.trim().isEmpty()) {
                if (entry.getSummarizedHtml() != null && !entry.getSummarizedHtml().trim().isEmpty()) {
                    htmlToLoad = entry.getSummarizedHtml();
                } else if (entry.getTranslatedHtml() != null && !entry.getTranslatedHtml().trim().isEmpty()) {
                    htmlToLoad = entry.getTranslatedHtml();
                }
            }
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
        refreshButtonVisibility(entry);
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
            refreshButtonVisibility(entry);
        });
    }

    private void refreshButtonVisibility() {
        refreshButtonVisibility(null);
    }

    private void refreshButtonVisibility(Entry entry) {
        if (entry == null) {
            compositeDisposable.add(Single.fromCallable(() -> entryRepository.getEntryById(currentId))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::updateUiWithEntry, throwable -> Timber.e(throwable, "Error fetching entry for UI update")));
        } else {
            updateUiWithEntry(entry);
        }
    }

    private void updateUiWithEntry(Entry entry) {
        if (entry == null || entry.getId() != currentId) return;

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
            sumToggle.setIcon(isSummarized ? R.drawable.ic_check : R.drawable.ic_summary);
        }

        boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);
        if (toolbar.getMenu().findItem(R.id.openInBrowser) != null) {
            toolbar.getMenu().findItem(R.id.openInBrowser).setVisible(!isWebViewMode);
        }
        if (toolbar.getMenu().findItem(R.id.exitBrowser) != null) {
            toolbar.getMenu().findItem(R.id.exitBrowser).setVisible(isWebViewMode);
        }
        if (toolbar.getMenu().findItem(R.id.reload) != null) {
            toolbar.getMenu().findItem(R.id.reload).setVisible(true);
        }
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
        boolean isWebViewMode = currentId != 0 && sharedPreferencesRepository.getWebViewMode(currentId);
        if (isWebViewMode) {
            sharedPreferencesRepository.setBrowserTextZoom(zoom);
        } else {
            sharedPreferencesRepository.setTextZoom(zoom);
        }
    }
    @Override public void onToggleHighlight() {
        boolean h = !sharedPreferencesRepository.getHighlightText();
        sharedPreferencesRepository.setHighlightText(h);
        if (!h) webView.clearMatches();
        makeSnackbar(h ? "Highlight ON" : "Highlight OFF");
    }
    @Override public void onOpenInBrowser() { 
        sharedPreferencesRepository.setWebViewMode(currentId, true);
        applyZoomSettings();
        lastLoadedHtml = ""; // Clear cache to force reload when exiting browser mode
        webView.loadUrl(currentLink);
        refreshButtonVisibility();
    }
    @Override public void onExitBrowser() {
        sharedPreferencesRepository.setWebViewMode(currentId, false);
        applyZoomSettings();
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
        suppressObserverReload = true;
        webViewViewModel.setIsTranslatedView(newVal);
        if (newVal) {
            webViewViewModel.setIsSummarizedView(false);
            userManuallySwitchedToOriginal = false;
        } else {
            userManuallySwitchedToOriginal = true;
        }
        suppressObserverReload = false;
        loadEntryContent();
    }

    @Override
    public void onToggleSummarization() {
        Boolean current = webViewViewModel.getIsSummarizedViewLiveData().getValue();
        boolean newVal = (current == null) || !current;
        lastLoadedHtml = ""; // Force reload UI
        suppressObserverReload = true;
        webViewViewModel.setIsSummarizedView(newVal);
        if (newVal) {
            webViewViewModel.setIsTranslatedView(false);
            userManuallySwitchedToOriginal = false;
        } else {
            userManuallySwitchedToOriginal = true;
        }
        suppressObserverReload = false;
        loadEntryContent();
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
        if (!new AiClient(this).hasKey(model)) {
            ApiKeyPromptDialog.show(this, sharedPreferencesRepository, this::translate);
            return;
        }
        
        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;

        // Check for error content before translating
        if (textUtil.isErrorContent(entry.getContent())) {
            Timber.d("Translation requested for error content. Triggering reload.");
            ttsExtractor.resetAndRetry(currentId);
            showFakeLoading();
            return;
        }

        final String sourceHtml = entry.getOriginalHtml() != null ? entry.getOriginalHtml() : entry.getHtml();
        if (sourceHtml == null) return;

        // Check if the HTML source is an error page
        if (textUtil.isErrorHtml(sourceHtml)) {
            Timber.d("Translation requested but HTML is an error page. Triggering reload.");
            ttsExtractor.resetAndRetry(currentId);
            showFakeLoading();
            makeSnackbar("Article failed to load. Re-extracting...");
            return;
        }
        
        final EntryInfo info = webViewViewModel.getEntryInfoById(currentId);
        final String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

        loading.setIndeterminate(false);
        loading.setProgress(0);
        loading.setVisibility(View.VISIBLE);
        compositeDisposable.add(textUtil.identifyLanguageRx(sourceHtml)
            .flatMap(sLang -> {
                if (sLang != null && sLang.equalsIgnoreCase(targetLang)) {
                    return io.reactivex.rxjava3.core.Single.error(new Exception("Article is already in the target language (" + targetLang + ")"));
                }
                return textUtil.translateHtmlAllAtOnce(sLang, targetLang, sourceHtml, info.getEntryTitle(), currentId, p -> runOnUiThread(() -> {
                    loading.setIndeterminate(false);
                    loading.setProgress(p);
                }), true);
            })
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(res -> {
                loading.setProgress(0);
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
                loading.setProgress(0);
                loading.setVisibility(View.GONE);
                makeSnackbar(err.getMessage());
            }));
    }

    private void summarize() {
        String model = sharedPreferencesRepository.getSummarizationModel(); 
        if (!new AiClient(this).hasKey(model)) {
            ApiKeyPromptDialog.show(this, sharedPreferencesRepository, this::summarize);
            return;
        }

        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) return;
        
        // CHECK FOR ERROR CONTENT
        if (textUtil.isErrorContent(entry.getContent())) {
            Timber.d("Manual summarization requested for error content. Triggering reload.");
            ttsExtractor.resetAndRetry(currentId);
            showFakeLoading();
            return; // Exit and wait for reload to finish
        }

        final String sourceHtml = entry.getOriginalHtml() != null ? entry.getOriginalHtml() : entry.getHtml();
        if (sourceHtml == null) return;

        // Check if the HTML source is an error page
        if (textUtil.isErrorHtml(sourceHtml)) {
            Timber.d("Manual summarization requested but HTML is an error page. Triggering reload.");
            ttsExtractor.resetAndRetry(currentId);
            showFakeLoading();
            makeSnackbar("Article failed to load. Re-extracting...");
            return;
        }

        final EntryInfo info = webViewViewModel.getEntryInfoById(currentId);
        final String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
        final int length = sharedPreferencesRepository.getSummaryLength();

        loading.setIndeterminate(false);
        loading.setProgress(0);
        loading.setVisibility(View.VISIBLE);
        compositeDisposable.add(textUtil.identifyLanguageRx(sourceHtml)
            .flatMap(sLang -> textUtil.summarizeHtmlAllAtOnce(sLang, targetLang, sourceHtml, length, currentId, info.getEntryTitle(), p -> runOnUiThread(() -> {
                loading.setIndeterminate(false);
                loading.setProgress(p);
            }), true))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(res -> {
                loading.setProgress(0);
                loading.setVisibility(View.GONE);
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
            }, err -> {
                loading.setProgress(0);
                loading.setVisibility(View.GONE);
                makeSnackbar("Error: " + err.getMessage());
            }));
    }

    private void toggleBookmark() {
        Entry entry = entryRepository.getEntryById(currentId);
        if (entry == null) {
            Timber.w("toggleBookmark: entry is null for ID " + currentId);
            return;
        }
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
        webView.setWebViewClient(new WebClient());
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
        updateMediaButtonsState(isPlaying);
    }

    private void setupReadingNavigation() {
        binding.nextArticleButton.setOnClickListener(v -> {
            if (ttsPlaylist.skipNext()) {
                currentId = ttsPlaylist.getPlayingId();
                ignoreMetadataUntilMatch = true;
                // Failsafe: clear the flag after 3s to prevent permanent blocking
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (ignoreMetadataUntilMatch) {
                        Timber.w("ignoreMetadataUntilMatch timeout in nextArticle - clearing flag");
                        ignoreMetadataUntilMatch = false;
                    }
                }, 3000);
                loadEntryContent();
            }
        });
        binding.previousArticleButton.setOnClickListener(v -> {
            if (ttsPlaylist.skipPrevious()) {
                currentId = ttsPlaylist.getPlayingId();
                ignoreMetadataUntilMatch = true;
                // Failsafe: clear the flag after 3s to prevent permanent blocking
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (ignoreMetadataUntilMatch) {
                        Timber.w("ignoreMetadataUntilMatch timeout in previousArticle - clearing flag");
                        ignoreMetadataUntilMatch = false;
                    }
                }, 3000);
                loadEntryContent();
            }
        });
    }

    // Boilerplate / Infrastructure
    private void initializeWebViewSettings() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        
        applyZoomSettings();

        // Enable pinch-to-zoom
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.getSettings().setAlgorithmicDarkeningAllowed(sharedPreferencesRepository.getNight());
        }
        webView.setWebChromeClient(new WebChromeClient());
    }

    private void applyZoomSettings() {
        boolean isWebViewMode = currentId != 0 && sharedPreferencesRepository.getWebViewMode(currentId);
        int savedTextZoom;
        int savedScale;
        if (isWebViewMode) {
            savedTextZoom = sharedPreferencesRepository.getBrowserTextZoom();
            savedScale = sharedPreferencesRepository.getBrowserZoomScale();
        } else {
            savedTextZoom = sharedPreferencesRepository.getTextZoom();
            savedScale = sharedPreferencesRepository.getZoomScale();
        }

        // Restore text zoom
        if (savedTextZoom > 0) {
            webView.getSettings().setTextZoom(savedTextZoom);
        } else {
            webView.getSettings().setTextZoom(100);
        }

        // Restore scale zoom (pinch-to-zoom)
        if (savedScale > 0) {
            webView.setInitialScale(savedScale);
        } else {
            webView.setInitialScale(0);
        }
    }

    @Override
    public void highlightText(String searchText) {
        if (contentManager != null) {
            contentManager.highlightText(searchText);
        }
    }

    public void finishedSetup() {
        isTtsReady = true;
        runOnUiThread(() -> {
            loading.setVisibility(View.GONE);
            if (!isReadingMode) {
                binding.functionButtons.setVisibility(View.VISIBLE);
                binding.functionButtons.setAlpha(1.0f);
            }
            updateMediaButtonsState(isPlaying);
            refreshButtonVisibility();
        });
    }

    public void showFakeLoading() {
        loading.setProgress(0);
        loading.setIndeterminate(true);
        loading.setVisibility(View.VISIBLE);
    }

    public void hideFakeLoading() {
        loading.setVisibility(View.GONE);
        loading.setIndeterminate(false);
        loading.setProgress(0);
    }

    public void updateLoadingProgress(int p) {
        if (isTtsReady && p < 100) return;
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
            Timber.d("Reload dialog already shown for feed " + fid + ", skipping.");
            return;
        }
        lastHandledReloadFeedId = fid;
        new ReloadDialog(this, fid, R.string.reload_confirmation, R.string.reload_suggestion_message).show(getSupportFragmentManager(), ReloadDialog.TAG); 
    }
    @Override public void makeSnackbar(String m) { Snackbar.make(binding.getRoot(), m, Snackbar.LENGTH_SHORT).show(); }
    @Override public void reload() {
        webViewViewModel.resetEntry(currentId);
        finish();
        Intent reloadIntent = new Intent(this, WebViewActivity.class);
        reloadIntent.putExtra("force_id", false);
        startActivity(reloadIntent);
    }

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

    private void updateMediaButtonsState(boolean playing) {
        // Main control buttons (Play, Skip Next, Skip Previous)
        binding.playPauseButton.setEnabled(isTtsReady);
        binding.skipNextButton.setEnabled(isTtsReady);
        binding.skipPreviousButton.setEnabled(isTtsReady);

        float mainAlpha = isTtsReady ? 1.0f : 0.5f;
        binding.playPauseButton.setAlpha(mainAlpha);
        binding.skipNextButton.setAlpha(mainAlpha);
        binding.skipPreviousButton.setAlpha(mainAlpha);

        // Sub-control buttons (Rewind, Fast Forward)
        // Disabled if either not ready OR not playing
        boolean subEnabled = isTtsReady && playing;
        binding.rewindButton.setEnabled(subEnabled);
        binding.fastForwardButton.setEnabled(subEnabled);

        float subAlpha = subEnabled ? 1.0f : 0.5f;
        binding.rewindButton.setAlpha(subAlpha);
        binding.fastForwardButton.setAlpha(subAlpha);
    }

    @Override public void onStart() { super.onStart(); if (!isReadingMode && mMediaBrowserHelper != null) mMediaBrowserHelper.onStart(); }
    @Override public void onStop() { if (!isReadingMode && mMediaBrowserHelper != null) mMediaBrowserHelper.onStop(); super.onStop(); }
    @Override protected void onPause() {
        sharedPreferencesRepository.setScrollX(currentId, webView.getScrollX());
        sharedPreferencesRepository.setScrollY(currentId, webView.getScrollY());
        super.onPause();
    }
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("current_id", currentId);
        outState.putBoolean("is_reading_mode", isReadingMode);
    }
    @Override protected void onDestroy() {
        if (contentManager != null) {
            contentManager.dispose();
        }
        compositeDisposable.clear();
        xiangze.mmu.rssnewsreader.data.GlobalState.setCurrentViewingId(0);
        super.onDestroy();
    }

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

        @Override
        public void onScaleChanged(WebView view, float oldScale, float newScale) {
            super.onScaleChanged(view, oldScale, newScale);
            // Save scale as percentage
            boolean isWebViewMode = currentId != 0 && sharedPreferencesRepository.getWebViewMode(currentId);
            if (isWebViewMode) {
                sharedPreferencesRepository.setBrowserZoomScale((int) (newScale * 100));
            } else {
                sharedPreferencesRepository.setZoomScale((int) (newScale * 100));
            }
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
            } else if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                final String executionToken = currentLoadToken;
                // Reduce the initial delay from database delay to a faster baseline (e.g., 1s)
                // but still respect if the database asks for something extremely specific.
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

        final long capturedId = currentId;
        final String capturedLink = currentLink;
        final String capturedTitle = currentTitle;

        view.evaluateJavascript("(function() { return document.getElementsByTagName('html')[0].outerHTML; })();", val -> {
            if (!executionToken.equals(currentLoadToken)) {
                Timber.d("Ignoring stale JS callback. Token mismatch.");
                return;
            }
            try (JsonReader r = new JsonReader(new StringReader(val))) {
                r.setLenient(true);
                if (r.peek() == JsonToken.STRING) {
                    String h = r.nextString();
                    if (h != null && h.length() >= 500) {
                        ttsExtractor.processExtraction(capturedId, capturedLink, capturedTitle, h, true);
                    }
                }
            } catch (Throwable t) { 
                Timber.e(t, "Fatal error during JS extraction");
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

            // Force the service to sync metadata with the current article immediately
            if (c.getTransportControls() != null) {
                c.getTransportControls().prepare();
            }
        }
    }
}





