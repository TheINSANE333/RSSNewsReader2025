package my.mmu.rssnewsreader.ui.webview;

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
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import my.mmu.rssnewsreader.R;
import my.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import my.mmu.rssnewsreader.model.EntryInfo;
import my.mmu.rssnewsreader.model.deepseek.ChatActivity;
import my.mmu.rssnewsreader.service.tts.TtsExtractor;
import my.mmu.rssnewsreader.service.tts.TtsPlayer;
import my.mmu.rssnewsreader.service.tts.TtsPlaylist;
import my.mmu.rssnewsreader.service.tts.TtsService;
import my.mmu.rssnewsreader.databinding.ActivityWebviewBinding;
import my.mmu.rssnewsreader.service.util.TextUtil;
import my.mmu.rssnewsreader.ui.feed.ReloadDialog;
import my.mmu.rssnewsreader.data.entry.Entry;
import my.mmu.rssnewsreader.data.entry.EntryRepository;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

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
    private LiveData<Entry> autoTranslationObserver;
    private Observer<Entry> checkAutoTranslated;
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
    private boolean showOfflineButton;
    private boolean clearHistory;
    private MenuItem toggleTranslationButton;
    private boolean isTranslatedView = true;
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (webView.canGoBack()) {
                    browserButton.setVisible(true);
                    webView.goBack();
                } else {
                    finish();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private final MediaControllerCompat.Callback mediaControllerCallback =
            new MediaControllerCompat.Callback() {
                @Override
                public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
                    super.onPlaybackStateChanged(state);
                    isPlaying = state.getState() == PlaybackStateCompat.STATE_PLAYING;
                    updatePlayPauseButtonIcon(isPlaying);
                    Log.d(TAG, "Playback state changed: " + state.getState());
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
        loading.setVisibility(View.INVISIBLE);

        if (webViewViewModel.getOriginalHtmlById(currentId) == null && originalHtml != null) {
            webViewViewModel.updateOriginalHtml(originalHtml, currentId);
            entryRepository.updateOriginalHtml(originalHtml, currentId);
            Log.d(TAG, "Original HTML backed up from method parameter.");
        }

        Document doc = Jsoup.parse(translatedHtml);
        doc.head().append(webViewViewModel.getStyle());
        Objects.requireNonNull(doc.selectFirst("body"))
                .prepend(webViewViewModel.getHtml(
                        entryInfo.getEntryTitle(),
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl()
                ));
        String finalHtml = doc.html();

        webViewViewModel.updateHtml(finalHtml, currentId);
        entryRepository.updateHtml(finalHtml, currentId);

        String translatedContent = textUtil.extractHtmlContent(finalHtml, "--####--");
        webViewViewModel.updateTranslated(translatedContent, currentId);
        webViewViewModel.updateEntryTranslatedField(currentId, translatedContent);
        entryRepository.updateTranslatedText(translatedContent, currentId);

        webView.loadDataWithBaseURL("file///android_res/", finalHtml, "text/html", "UTF-8", null);

        toggleTranslationButton.setVisible(true);
        isTranslatedView = true;
        sharedPreferencesRepository.setIsTranslatedView(currentId, true);

        webViewViewModel.setTranslatedTextReady(currentId, translatedContent);

        Log.d(TAG, "FINAL translatedContent passed to TTS: " + translatedContent);
        Log.d(TAG, "FINAL currentId: " + currentId + ", isTranslatedView: " + isTranslatedView);
    }

    private void translate() {
        Log.d(TAG, "translate: html\n" + webViewViewModel.getHtmlById(currentId));
        makeSnackbar("Translation in progress");
        loading.setVisibility(View.VISIBLE);
        loading.setProgress(0);

        String content = webViewViewModel.getHtmlById(currentId);
        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo == null) {
            makeSnackbar("Entry info could not be loaded.");
            return;
        }

        if (webViewViewModel.getOriginalHtmlById(currentId) == null) {
            webViewViewModel.updateOriginalHtml(content, currentId);
            Log.d(TAG, "Original HTML backed up before translation.");
        }

        String feedLanguage = entryInfo.getFeedLanguage();
        String userConfiguredLang = sharedPreferencesRepository.getDefaultTranslationLanguage();

        textUtil.identifyLanguageRx(content).subscribe(
                identifiedLanguage -> {
                    String sourceLanguage = (userConfiguredLang != null && !userConfiguredLang.isEmpty())
                            ? feedLanguage : identifiedLanguage;

                    Log.d(TAG, "Translating from " + sourceLanguage + " to " + targetLanguage);
                    performTranslation(sourceLanguage, targetLanguage, content, entryInfo.getEntryTitle());
                },
                error -> {
                    Log.e(TAG, "Language identification failed, falling back to feedLanguage");
                    performTranslation(feedLanguage, targetLanguage, content, entryInfo.getEntryTitle());
                }
        );
    }

    private void performTranslation(String sourceLang, String targetLang, String html, String title) {
        Single<String> translationFlow;
        switch (translationMethod) {
            case "lineByLine":
                translationFlow = textUtil.translateHtmlLineByLine(sourceLang, targetLang, html, title, currentId, this::updateLoadingProgress);
                break;
            case "paragraphByParagraph":
                translationFlow = textUtil.translateHtmlByParagraph(sourceLang, targetLang, html, title, currentId, this::updateLoadingProgress);
                break;
            default:
                translationFlow = textUtil.translateHtmlAllAtOnce(sourceLang, targetLang, html, title, currentId, this::updateLoadingProgress);
        }

        final String originalHtml = html;

        translationFlow.subscribe(
                translatedHtml -> {
                    Log.d(TAG, "Translation completed");
                    doWhenTranslationFinish(webViewViewModel.getLastVisitedEntry(), originalHtml, translatedHtml);
                },
                throwable -> {
                    Log.e(TAG, "Translation failed", throwable);
                    loading.setVisibility(View.GONE);
                }
        );
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        webViewViewModel = new ViewModelProvider(this).get(WebViewViewModel.class);

        webViewViewModel.getTranslatedTextReady().observe(this, translatedText -> {
            if (!isReadingMode && isTranslatedView && translatedText != null && !translatedText.trim().isEmpty()) {
                Log.d(TAG, "TTS triggered after LiveData translation update");

                String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");

                ttsPlayer.extract(currentId, feedId, translatedText, lang);
                Log.d(TAG, "LiveData.observe fired, isTranslatedView = " + isTranslatedView);
            }
        });

        isReadingMode = getIntent().getBooleanExtra("read", false);

        if (ttsPlayer.isPlaying() && isReadingMode) {
            ttsPlayer.stop();
        }

        initializeUI();

        targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        translationMethod = sharedPreferencesRepository.getTranslationMethod();
        textUtil = new TextUtil(sharedPreferencesRepository);
        compositeDisposable = new CompositeDisposable();

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
        toggleTranslationButton = toolbar.getMenu().findItem(R.id.toggleTranslation);
        highlightTextButton = toolbar.getMenu().findItem(R.id.highlightText);
        backgroundMusicButton = toolbar.getMenu().findItem(R.id.toggleBackgroundMusic);
        switchReadModeButton = toolbar.getMenu().findItem(R.id.switchReadMode);
        switchPlayModeButton = toolbar.getMenu().findItem(R.id.switchPlayMode);

        toggleTranslationButton.setVisible(false);

        highlightTextButton.setTitle(sharedPreferencesRepository.getHighlightText()
                ? R.string.highlight_text_turn_off : R.string.highlight_text_turn_on);
        backgroundMusicButton.setTitle(sharedPreferencesRepository.getBackgroundMusic()
                ? R.string.background_music_turn_off : R.string.background_music_turn_on);
    }

    private void loadHtmlIntoWebView(String html) {
        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle());

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo != null && !doc.html().contains("class=\"entry-header\"")) {
            doc.selectFirst("body").prepend(
                    webViewViewModel.getHtml(
                            entryInfo.getEntryTitle(),
                            entryInfo.getFeedTitle(),
                            entryInfo.getEntryPublishedDate(),
                            entryInfo.getFeedImageUrl()
                    )
            );
        }

        webView.loadDataWithBaseURL("file///android_res/", doc.html(), "text/html", "UTF-8", null);

        webView.postDelayed(() -> {
            int scrollX = sharedPreferencesRepository.getScrollX(currentId);
            int scrollY = sharedPreferencesRepository.getScrollY(currentId);
            webView.scrollTo(scrollX, scrollY);
        }, 300);

        syncLoadingWithTts();
    }

    private void updateToggleTranslationVisibility() {
        String originalHtml = webViewViewModel.getOriginalHtmlById(currentId);
        String translatedHtml = webViewViewModel.getHtmlById(currentId);

        if (originalHtml != null && translatedHtml != null && !originalHtml.equals(translatedHtml)) {
            toggleTranslationButton.setVisible(true);
        } else {
            toggleTranslationButton.setVisible(false);
        }

        Log.d(TAG, "ToggleTranslationButton visibility set to: " + (originalHtml != null && translatedHtml != null && !originalHtml.equals(translatedHtml)));
    }

    private void initializePlaybackModes() {
        if (isReadingMode) {
            switchReadMode();
        } else {
            switchPlayMode();
        }
    }

    private void loadEntryContent() {
        EntryInfo entryInfo = webViewViewModel.getLastVisitedEntry();
        if (entryInfo == null) {
            makeSnackbar("No article to load.");
            return;
        }

        currentId = entryInfo.getEntryId();
        Entry entry = entryRepository.getEntryById(currentId);

        if (entry == null) {
            makeSnackbar("Failed to load article content.");
            return;
        }

        if (sharedPreferencesRepository.getWebViewMode(currentId)) {
            loadFromBrowserMode(entryInfo);
            return;
        }

        Log.d("DEBUG", "Original: " + entry.getOriginalHtml());
        Log.d("DEBUG", "Translated: " + entry.getTranslated());

        if (entry.getOriginalHtml() != null && entry.getTranslated() != null) {
            toggleTranslationButton.setVisible(true);
            webViewViewModel.updateOriginalHtml(entry.getOriginalHtml(), entry.getId());

            if (entry.getHtml() != null) {
                webViewViewModel.updateHtml(entry.getHtml(), entry.getId());
            }
        } else {
            toggleTranslationButton.setVisible(false);
        }

        if (!sharedPreferencesRepository.hasTranslationToggle(currentId)) {
            if (entry.getTranslated() != null && entry.getHtml() != null) {
                isTranslatedView = true;
            } else {
                isTranslatedView = false;
            }
        } else {
            isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentId);
        }

        Log.d(TAG, "loadEntryContent: isTranslatedView = " + isTranslatedView);

        EntryInfo info = webViewViewModel.getEntryInfoById(currentId);
        if (info == null) {
            makeSnackbar("Feed language info not found.");
            return;
        }

        Log.d("LoadEntry", "entry.getHtml() = " + (entry.getHtml() != null));
        Log.d("LoadEntry", "entry.getTranslated() = " + (entry.getTranslated() != null));
        Log.d("LoadEntry", "isTranslatedView = " + isTranslatedView);

        String html = isTranslatedView
                ? webViewViewModel.getHtmlById(currentId)
                : webViewViewModel.getOriginalHtmlById(currentId);

        Log.d("LoadEntry", "htmlToLoad (translated) = " + (html != null ? html.length() : "null"));

        String contentToRead = isTranslatedView
                ? entry.getTranslated()
                : entry.getContent();

        String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");

        Log.d(TAG, "loadEntryContent - About to speak " + (isTranslatedView ? "Translated" : "Original"));
        Log.d(TAG, "Language to use: " + lang);
        Log.d(TAG, "Text to read: " + contentToRead);

        ttsExtractor.setCurrentLanguage(lang, true);

        if (html != null && !html.trim().isEmpty()) {
            loadHtmlIntoWebView(html);
            ttsPlayer.extract(entry.getId(), entry.getFeedId(), contentToRead, lang);
        } else {
            Log.w(TAG, "HTML missing, skipping load.");
        }

        sharedPreferencesRepository.setCurrentReadingEntryId(currentId);

        observeLiveEntry();
        observeAutoTranslation();

        syncLoadingWithTts();
    }

    private void loadFromBrowserMode(EntryInfo entryInfo) {
        browserButton.setVisible(false);
        offlineButton.setVisible(true);
        webView.loadUrl(entryInfo.getEntryLink());
    }

    private void observeLiveEntry() {
        webViewViewModel.triggerEntryRefresh(currentId);

        webViewViewModel.getLiveEntry().observe(this, entry -> {
            if (entry == null) {
                toggleTranslationButton.setVisible(false);
                makeSnackbar("This article is missing.");
            }
        });

        webViewViewModel.getOriginalHtmlLiveData().observe(this, originalHtml -> {
            updateToggleStateAndWebView(originalHtml, webViewViewModel.getTranslatedHtmlLiveData().getValue());
        });

        webViewViewModel.getTranslatedHtmlLiveData().observe(this, translatedHtml -> {
            updateToggleStateAndWebView(webViewViewModel.getOriginalHtmlLiveData().getValue(), translatedHtml);
        });
    }

    private void updateToggleStateAndWebView(String originalHtml, String translatedHtml) {
        boolean hasOriginal = originalHtml != null && !originalHtml.trim().isEmpty();
        boolean hasTranslated = translatedHtml != null && !translatedHtml.trim().isEmpty();

        toggleTranslationButton.setVisible(hasOriginal && hasTranslated);
        toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");

        String htmlToLoad = isTranslatedView ? translatedHtml : originalHtml;

        Log.d(TAG, "LiveEntry - Current Mode: " + (isTranslatedView ? "Translated" : "Original"));
        Log.d(TAG, "LiveEntry - Original HTML:\n" + originalHtml);
        Log.d(TAG, "LiveEntry - Translated HTML:\n" + translatedHtml);
        Log.d(TAG, "LiveEntry - HTML to Load:\n" + htmlToLoad);

        if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
            loadHtmlToWebView(htmlToLoad);
        } else {
            Log.w(TAG, "Skipped loading empty html in updateToggleStateAndWebView()");
        }
    }

    private void observeAutoTranslation() {
        LiveData<Entry> observer = webViewViewModel.getEntryEntityById(currentId);
        Observer<Entry> checkAutoTranslated = new Observer<Entry>() {
            @Override
            public void onChanged(Entry entry) {
                if (entry != null && entry.getTranslated() != null) {

                    String originalHtmlFromDb = entryRepository.getOriginalHtmlById(currentId);
                    if (originalHtmlFromDb != null) {
                        webViewViewModel.updateOriginalHtml(originalHtmlFromDb, currentId);
                        Log.d(TAG, "Original HTML restored from DB.");
                    }

                    String translatedHtmlFromDb = entry.getHtml();
                    if (translatedHtmlFromDb != null) {
                        webViewViewModel.updateHtml(translatedHtmlFromDb, currentId);
                        Log.d(TAG, "Translated HTML synced from auto translation.");
                    }

                    toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");

                    Log.d(TAG, "AutoTranslation - Final Original:\n" + webViewViewModel.getOriginalHtmlById(currentId));
                    Log.d(TAG, "AutoTranslation - Final Translated:\n" + webViewViewModel.getHtmlById(currentId));

                    webViewViewModel.triggerEntryRefresh(currentId);

                    observer.removeObserver(this);
                }
            }
        };
        observer.observeForever(checkAutoTranslated);
    }

    private void loadHtmlToWebView(String html) {
        if (html == null || html.trim().isEmpty()) {
            return;
        }

        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle());

        EntryInfo entryInfo = webViewViewModel.getEntryInfoById(currentId);
        if (entryInfo != null && !doc.html().contains("class=\"entry-header\"")) {
            doc.selectFirst("body").prepend(
                    webViewViewModel.getHtml(
                            entryInfo.getEntryTitle(),
                            entryInfo.getFeedTitle(),
                            entryInfo.getEntryPublishedDate(),
                            entryInfo.getFeedImageUrl()
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

    private boolean handleOtherToolbarItems(int itemId) {
        switch (itemId) {
            case R.id.chatbot:
                startChat();
                return true;

            case R.id.translate:
                if (targetLanguage == null || targetLanguage.isEmpty()) {
                    showTranslationLanguageDialog(this);
                }
                translate();
                return true;

            case R.id.zoomIn:
                adjustTextZoom(true);
                return true;

            case R.id.zoomOut:
                adjustTextZoom(false);
                return true;

            case R.id.bookmark:
                toggleBookmark();
                return true;

            case R.id.share:
                shareCurrentLink();
                return true;

            case R.id.openInBrowser:
                browserButton.setVisible(false);
                offlineButton.setVisible(true);
                sharedPreferencesRepository.setWebViewMode(currentId, true);
                webView.loadUrl(currentLink);
                hideFakeLoading();
                return true;

            case R.id.exitBrowser:
                sharedPreferencesRepository.setWebViewMode(currentId, false);
                EntryInfo entryInfo = webViewViewModel.getLastVisitedEntry();
                String rebuiltHtml = rebuildHtml(entryInfo);
                loadEntryContent();
                offlineButton.setVisible(false);
                browserButton.setVisible(true);
                hideFakeLoading();
                return true;

            case R.id.reload:
                ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_message);
                dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
                return true;

            case R.id.toggleBackgroundMusic:
                toggleBackgroundMusic();
                return true;

            case R.id.openTtsSetting:
                startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                return true;

            case R.id.toggleTranslation:
                boolean currentMode = sharedPreferencesRepository.getIsTranslatedView(currentId);
                isTranslatedView = !currentMode;
                sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);

                Entry entry = webViewViewModel.getEntryById(currentId);
                if (entry == null) {
                    makeSnackbar("Entry not found.");
                    return true;
                }

                entryInfo = webViewViewModel.getEntryInfoById(currentId);
                if (entryInfo == null) {
                    makeSnackbar("Feed language info not found.");
                    isTranslatedView = currentMode;
                    sharedPreferencesRepository.setIsTranslatedView(currentId, currentMode);
                    return true;
                }

                String translatedHtml = webViewViewModel.getHtmlById(currentId);
                String originalHtml = webViewViewModel.getOriginalHtmlById(currentId);
                String htmlToLoad = isTranslatedView ? translatedHtml : originalHtml;

                Log.d(TAG, "TOGGLE BUTTON PRESSED");
                Log.d(TAG, "Original HTML:\n" + originalHtml);
                Log.d(TAG, "Translated HTML:\n" + translatedHtml);
                Log.d(TAG, "HTML loaded for toggle view:\n" + htmlToLoad);

                if (htmlToLoad != null && !htmlToLoad.trim().isEmpty()) {
                    toggleTranslationButton.setTitle(isTranslatedView ? "Show Original" : "Show Translation");
                    loadHtmlIntoWebView(htmlToLoad);

                    if (isTranslatedView) {
                        String translated = entry.getTranslated();
                        if (translated != null && !translated.trim().isEmpty()) {
                            Log.d(TAG, "ToggleTranslation: Broadcasting translatedTextReady again");
                            webViewViewModel.setTranslatedTextReady(currentId, translated);
                            String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");
                            ttsPlayer.extract(entry.getId(), entry.getFeedId(), translated, lang);
                        } else {
                            Log.w(TAG, "ToggleTranslation: translated content missing, skipping extract");
                        }
                    } else {
                        String original = entry.getContent();
                        String lang = getLanguageForCurrentView(currentId, isTranslatedView, "en");
                        if (original != null && !original.trim().isEmpty()) {
                            Log.d(TAG, "ToggleTranslation: Reading original content");
                            ttsPlayer.extract(entry.getId(), entry.getFeedId(), original, lang);
                        }
                    }
                } else {
                    makeSnackbar("No alternate version available.");
                    isTranslatedView = currentMode;
                    sharedPreferencesRepository.setIsTranslatedView(currentId, currentMode);
                }
                return true;

            default:
                return false;
        }
    }

    private String rebuildHtml(EntryInfo entryInfo) {
        String html = webViewViewModel.getHtmlById(entryInfo.getEntryId());

        Document doc = Jsoup.parse(html);
        doc.head().append(webViewViewModel.getStyle());

        Objects.requireNonNull(doc.selectFirst("body")).prepend(
                webViewViewModel.getHtml(
                        entryInfo.getEntryTitle(),
                        entryInfo.getFeedTitle(),
                        entryInfo.getEntryPublishedDate(),
                        entryInfo.getFeedImageUrl()
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
        toolbar.setNavigationOnClickListener(view -> onBackPressed());

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
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setDisplayZoomControls(false);

        int textZoom = sharedPreferencesRepository.getTextZoom();
        if (textZoom != 0) {
            webView.getSettings().setTextZoom(textZoom);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean isNight = sharedPreferencesRepository.getNight();
            webView.getSettings().setForceDark(isNight
                    ? WebSettings.FORCE_DARK_ON
                    : WebSettings.FORCE_DARK_OFF);
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
        showOfflineButton = false;

        MediaMetadataCompat metadata = ttsPlaylist.getCurrentMetadata();

        content = metadata.getString("content");
        bookmark = metadata.getString("bookmark");
        currentLink = metadata.getString("link");
        currentId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
        updateToggleTranslationVisibility();
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
            browserButton.setVisible(false);
            offlineButton.setVisible(true);
            showOfflineButton = false;
        } else {
            String entryTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE);
            String feedTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE);
            long date = metadata.getLong("date");
            Date publishDate = new Date(date);
            String feedImageUrl = metadata.getString("feedImageUrl");

            isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentId);
            String htmlToLoad = isTranslatedView
                    ? webViewViewModel.getHtmlById(currentId)
                    : webViewViewModel.getOriginalHtmlById(currentId);

            if (htmlToLoad == null) {
                htmlToLoad = metadata.getString("html");
            }

            if (htmlToLoad != null) {
                loadHtmlIntoWebView(htmlToLoad);
            }

            offlineButton.setVisible(false);
            reloadButton.setVisible(true);
            bookmarkButton.setVisible(true);
            translationButton.setVisible(true);
            browserButton.setVisible(true);
            highlightTextButton.setVisible(true);
        }
    }

    @Override
    public void highlightText(String searchText) {
        if (!isReadingMode && sharedPreferencesRepository.getHighlightText()) {
            String text = searchText.trim();
            if (webViewViewModel.endsWithBreak(text)) {
                text = text.substring(0, text.length() - 1);
            }
            Log.d(TAG, "Highlighted text: " + text);
            String finalText = text.trim();
            ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> webView.findAllAsync(finalText));
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
                if (showOfflineButton) {
                    offlineButton.setVisible(true);
                }
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
        if (getIntent().getBooleanExtra("forceOriginal", false)) {
            sharedPreferencesRepository.setIsTranslatedView(currentId, false);
        }

        if (!isReadingMode) {
            mMediaBrowserHelper.getTransportControls().stop();
        }

        finish();
        overridePendingTransition(0, 0);
        startActivity(getIntent());
        overridePendingTransition(0, 0);
    }

    @Override
    public void askForReload(long feedId) {
        ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_suggestion_message);
        dialog.show(getSupportFragmentManager(), ReloadDialog.TAG);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (autoTranslationObserver != null && checkAutoTranslated != null) {
            autoTranslationObserver.removeObserver(checkAutoTranslated);
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
            ttsExtractor.setCallback((WebViewListener) null);
        } else {
            ttsPlayer.setWebViewCallback(null);
            mMediaBrowserHelper.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        ttsPlayer.setWebViewConnected(false);
        ttsPlayer.setUiControlPlayback(false);

        if (webView != null && currentId != 0) {
            sharedPreferencesRepository.setScrollX(currentId, webView.getScrollX());
            sharedPreferencesRepository.setScrollY(currentId, webView.getScrollY());
            sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslatedView);
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
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            Log.d(TAG, "WebClient: onPageCommitVisible - loadingWebView hidden.");
            webViewViewModel.setLoadingState(false);
            if (content != null) {
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
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            loading.setVisibility(View.INVISIBLE);
            Log.d(TAG, "ReadingWebClient: onPageCommitVisible - loadingWebView hidden.");
            webViewViewModel.setLoadingState(false);
        }
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
            if (metadata == null) {
                return;
            }
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
            showOfflineButton = false;

            content = metadata.getString("content");
            bookmark = metadata.getString("bookmark");
            currentLink = metadata.getString("link");
            currentId = Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
            updateToggleTranslationVisibility();
            feedId = metadata.getLong("feedId");

            if (bookmark == null || bookmark.equals("N")) {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
            } else {
                bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
            }

            isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(currentId);
            String htmlToLoad = isTranslatedView
                    ? webViewViewModel.getHtmlById(currentId)
                    : webViewViewModel.getOriginalHtmlById(currentId);

            if (htmlToLoad == null) {
                htmlToLoad = metadata.getString("html");
            }

            boolean isWebViewMode = sharedPreferencesRepository.getWebViewMode(currentId);

            if (isWebViewMode) {
                webView.loadUrl(currentLink);
                Log.d(TAG, "Restoring web view mode: " + currentLink);
                browserButton.setVisible(false);
                offlineButton.setVisible(true);
                showOfflineButton = false;
            } else if (htmlToLoad != null) {
                loadHtmlIntoWebView(htmlToLoad);
                browserButton.setVisible(true);
                offlineButton.setVisible(false);
                showOfflineButton = false;
            } else {
                webView.loadUrl(currentLink);
                Log.d(TAG, "Fallback: loading live URL - " + currentLink);
                browserButton.setVisible(false);
                showOfflineButton = true;
            }


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
}
