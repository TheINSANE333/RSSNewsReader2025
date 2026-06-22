package xiangze.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import timber.log.Timber;

import androidx.core.content.ContextCompat;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewActivity;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewListener;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.io.File;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;


@Singleton
public class TtsPlayer extends PlayerAdapter implements TtsPlayerListener {

    public static final String TAG = TtsPlayer.class.getSimpleName();

    private TextToSpeech tts;
    private PlaybackStateListener listener;
    private MediaSessionCompat.Callback callback;
    private Context context;
    private final TtsExtractor ttsExtractor;
    private final EntryRepository entryRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final xiangze.mmu.rssnewsreader.service.util.TextUtil textUtil;
    private final PowerManager.WakeLock wakeLock;
    private final android.net.wifi.WifiManager.WifiLock wifiLock;

    private int sentenceCounter;
    private List<String> sentences = new CopyOnWriteArrayList<>();
    private List<String> originalSentences = new CopyOnWriteArrayList<>();

    private int currentState;
    private int processingSentenceIndex = -1;
    private long currentId = 0;
    private long feedId = 0;
    private String language;
    private boolean isInit = false;
    private boolean actionNeeded = false;
    private boolean isPausedManually;
    private boolean webViewConnected = false;
    private boolean uiControlPlayback = false;
    private boolean isManualSkip = false;
    private MediaPlayer mediaPlayer;
    private String currentUtteranceID = null;
    private float ttsVolume = 1.0f;
    private String lastContent = null;
    private String lastViewMode = null;
    private long lastCurrentId = -1;
    private long lastAutoRetriedId = -1;
    private volatile int currentExtractionId = 0;
    private volatile int pendingExtractorId = -1;
    private volatile boolean isSentenceSplittingInProgress = false;
    private volatile boolean isWaitingForArticleCompletion = false;
    private volatile boolean isSettingUpNewArticle = false;
    private volatile boolean isArticleFinished = false;
    private volatile boolean isPreparing = false;
    private boolean hasSpokenAfterSetup = false;
    private PlaybackUiListener playbackUiListener;
    private int currentExtractProgress = 0;
    private long lastHandledReloadId = -1;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    private final MutableLiveData<String> highlightTextLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> finishedSetupLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> snackbarMessageLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> loadingProgressLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> askForReloadLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> showFakeLoadingLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> articleChangedLiveData = new MutableLiveData<>();

    public LiveData<String> getHighlightTextLiveData() { return highlightTextLiveData; }
    public LiveData<Boolean> getFinishedSetupLiveData() { return finishedSetupLiveData; }
    public LiveData<String> getSnackbarMessageLiveData() { return snackbarMessageLiveData; }
    public LiveData<Integer> getLoadingProgressLiveData() { return loadingProgressLiveData; }
    public LiveData<Long> getAskForReloadLiveData() { return askForReloadLiveData; }
    public LiveData<Boolean> getShowFakeLoadingLiveData() { return showFakeLoadingLiveData; }
    public MutableLiveData<Long> getArticleChangedLiveData() { return articleChangedLiveData; }

    @SuppressLint("InvalidWakeLockTag")
    @Inject
    public TtsPlayer(@ApplicationContext Context context, TtsExtractor ttsExtractor, EntryRepository entryRepository, SharedPreferencesRepository sharedPreferencesRepository, xiangze.mmu.rssnewsreader.service.util.TextUtil textUtil) {
        super(context);
        this.ttsExtractor = ttsExtractor;
        this.entryRepository = entryRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.textUtil = textUtil;
        this.context = context;
        this.isPausedManually = sharedPreferencesRepository.getIsPausedManually();
        
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RSSReader:TtsWakeLock");

        android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RSSReader:TtsWifiLock");
    }

    @Override
    public void setVolume(float volume) {
        this.ttsVolume = volume;
        if (mediaPlayer != null) {
            float baseVolume = (float) sharedPreferencesRepository.getBackgroundMusicVolume() / 100;
            float targetVolume = baseVolume * volume;
            mediaPlayer.setVolume(targetVolume, targetVolume);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void initTts(TtsService ttsService, PlaybackStateListener listener, MediaSessionCompat.Callback callback) {
        this.listener = listener;
        this.callback = callback;

        if (tts != null) {
            return;
        }

        // Initialize TTS. Note: onInit callback may happen on the main thread or a binder thread.
        // We assign to the class variable 'tts' immediately so it's available in the callback.
        tts = new TextToSpeech(ttsService, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Timber.d("initTts successful");
                isInit = true;

                // CRITICAL: Bind TTS to the correct Audio Attributes.
                AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();

                // Re-check tts for null in case shutdown was called quickly
                if (tts != null) {
                    try {
                        tts.setAudioAttributes(playbackAttributes);
                    } catch (Exception e) {
                        Timber.e("Error setting audio attributes: " + e.getMessage());
                    }
                }

                if (actionNeeded) {
                    Timber.d("Deferred auto-play activated — TTS is now ready");
                    setupTts();
                    actionNeeded = false;
                }
            } else {
                Timber.e("TTS Initialization failed with status: " + status);
            }
        });

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                cancelTimeout();
                final int extractionId = currentExtractionId;
                if (utteranceId != null) {
                    try {
                        int index = Integer.parseInt(utteranceId);
                        if (extractionId != currentExtractionId) {
                            Timber.d("Ignoring stale onStart for extractionId: " + extractionId);
                            return;
                        }
                        // Accessing sentences.size() and sentences.get() on a CopyOnWriteArrayList is thread-safe
                        if (index >= 0 && index < sentences.size()) {
                            String sentenceToHighlight = originalSentences.size() > index ? originalSentences.get(index) : sentences.get(index);
                            highlightTextLiveData.postValue(sentenceToHighlight);
                            
                            // Save progress immediately as we start speaking this sentence
                            if (currentId > 0) {
                                sentenceCounter = index;
                                entryRepository.updateSentCount(index, currentId);
                                sharedPreferencesRepository.setCurrentReadingEntryId(currentId);
                            }
                        }
                    } catch (NumberFormatException e) {
                        Timber.e("Invalid utterance ID format: " + utteranceId);
                    }
                }
            }

            @Override
            public void onDone(String utteranceId) {
                cancelTimeout();
                handleOnDone(utteranceId);
            }

            @Override
            @Deprecated
            public void onError(String s) {
                cancelTimeout();
                Timber.d("onError: " + s);
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                cancelTimeout();
                Timber.e("TTS Error for utterance %s, code: %d", utteranceId, errorCode);
                final int extractionId = currentExtractionId;
                if (extractionId == currentExtractionId) {
                    Timber.d("Recovering from TTS error by treating as 'done' for utterance: " + utteranceId);
                    handleOnDone(utteranceId);
                }
            }
        });
    }

    private void scheduleTimeout(final String utteranceId) {
        cancelTimeout();
        timeoutRunnable = () -> {
            Timber.w("Utterance timeout reached for: " + utteranceId + ". Forcing recovery.");
            handleOnDone(utteranceId);
        };
        // 45 seconds timeout should be plenty even for long paragraphs
        timeoutHandler.postDelayed(timeoutRunnable, 45000);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void handleOnDone(String utteranceId) {
        final int extractionId = currentExtractionId;
        
        if (currentUtteranceID != null && !currentUtteranceID.equals(utteranceId)) {
            Timber.d("Ignoring stale onDone for utteranceId: " + utteranceId + " (Current: " + currentUtteranceID + ")");
            return;
        }

        if (extractionId != currentExtractionId) {
            Timber.d("Ignoring stale onDone for extractionId: " + extractionId + " (Current: " + currentExtractionId + ")");
            return;
        }

        if (isArticleFinished) {
            Timber.d("Already finished article, skipping duplicate onDone for utteranceId: " + utteranceId);
            return;
        }

        int currentSentencesSize = sentences.size();
        Timber.d("handleOnDone: utteranceId=" + utteranceId + ", sentenceCounter=" + sentenceCounter + ", sentencesSize=" + currentSentencesSize + ", splittingInProgress=" + isSentenceSplittingInProgress);

        if (sentenceCounter < currentSentencesSize - 1) {
            sentenceCounter++;
            if (sentenceCounter < sentences.size()) {
                if (!isPausedManually && currentState == PlaybackStateCompat.STATE_PLAYING) {
                    speak();
                    Timber.d("Finished [#" + (sentenceCounter - 1) + "], speaking [#" + sentenceCounter + "]");
                } else {
                    Timber.d("Sentence finished but player is paused/stopped (state=" + currentState + "). Counter incremented to: " + sentenceCounter);
                }
            } else {
                Timber.d("sentenceCounter became out of bounds after increment, stopping (N-1 logic).");
            }
        } else {
            if (isSentenceSplittingInProgress) {
                sentenceCounter++;
                Timber.d("Reached end of current batch, waiting for sentence #" + sentenceCounter + ". Splitting is still in progress.");
                isWaitingForArticleCompletion = true;
                // Transition to buffering while we wait for more sentences
                setNewState(PlaybackStateCompat.STATE_BUFFERING);
            } else {
                Timber.d("Finished last sentence of article (ID: " + currentId + "). Moving to next article.");
                isWaitingForArticleCompletion = false;
                
                // Transition to buffering immediately to give UI feedback
                setNewState(PlaybackStateCompat.STATE_BUFFERING);
                
                entryRepository.updateSentCount(0, currentId);
                sentenceCounter = 0;
                isArticleFinished = true;
                // Notify that article finished — the actual new ID will be emitted after skipNext()
                if (callback != null) {
                    callback.onSkipToNext();
                } else {
                    Timber.e("Callback is null in handleOnDone, cannot skip to next article!");
                    setNewState(PlaybackStateCompat.STATE_PAUSED);
                }
            }
        }
    }

    public interface PlaybackUiListener {
        void onPlaybackStarted();
        void onPlaybackPaused();
    }

    public void setPlaybackUiListener(PlaybackUiListener listener) {
        this.playbackUiListener = listener;
    }

    public void stopTtsPlayback() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }

        currentId = -1;
        isPreparing = false;
        isArticleFinished = false;
        isSettingUpNewArticle = false;
        sentences.clear();
        originalSentences.clear();
        sentenceCounter = 0;
        setUiControlPlayback(false);
        setNewState(PlaybackStateCompat.STATE_PAUSED);
        if (playbackUiListener != null) {
            playbackUiListener.onPlaybackPaused();
        }
    }

    public void pauseTts() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        processingSentenceIndex = -1;
        setPausedManually(true);
        setNewState(PlaybackStateCompat.STATE_PAUSED);
        setUiControlPlayback(false);
        if (playbackUiListener != null) {
            playbackUiListener.onPlaybackPaused();
        }
    }

    public void extract(long currentId, long feedId, String content, String language) {
        extract(currentId, feedId, content, language, null, true);
    }

    public synchronized void extract(long currentId, long feedId, String content, String language, String viewMode, boolean isMandatory) {
        Timber.d("Switching to new article: ID=" + currentId + " ViewMode=" + viewMode + " Mandatory=" + isMandatory);

        if (currentId == this.currentId && !isMandatory && 
            getViewModePriority(viewMode) < getViewModePriority(this.lastViewMode)) {
            Timber.d("Already have " + this.lastViewMode + " for this article, ignoring non-mandatory " + viewMode + " request.");
            return;
        }

        if (content == null && currentId == this.currentId && pendingExtractorId != -1) {
            Timber.d("Already waiting for extractor for current article ID: " + currentId + ", skipping redundant extraction.");
            return;
        }

        boolean isNewArticle = currentId != this.currentId;

        if (currentId != this.lastCurrentId) {
            lastContent = null;
            lastViewMode = null;
            this.lastCurrentId = currentId;
            this.lastAutoRetriedId = -1;
        }

        // CRITICAL FIX: Respect isPausedManually correctly. 
        // We only want to auto-unpause if the user explicitly triggered a "next" while ALREADY playing.
        // If they were paused, we should stay paused.
        isPausedManually = sharedPreferencesRepository.getIsPausedManually();
        Timber.d("Detected isPausedManually = " + isPausedManually + " (state=" + currentState + ", finished=" + isArticleFinished + ")");

        String resolvedLanguage = (language != null && language.equals("Use Language Identifier")) ? null : language;
        boolean isSameViewMode = (viewMode == null && this.lastViewMode == null) || (viewMode != null && viewMode.equals(this.lastViewMode));
        
        if (content != null && content.equals(this.lastContent) && currentId == this.currentId && 
            (resolvedLanguage == null ? this.language == null : resolvedLanguage.equals(this.language)) &&
            isSameViewMode) {
            Timber.d("Content, language, viewMode and ID are identical to last extraction, skipping redundant extraction.");
            isArticleFinished = false;
            // Only reset hasSpokenAfterSetup if we are NOT currently speaking or setting up
            if (!isSpeaking() && !isSettingUpNewArticle) {
                hasSpokenAfterSetup = false;
            }
            showFakeLoadingLiveData.postValue(false);
            finishedSetupLiveData.postValue(true);
            
            // If not paused manually, ensure we start playing since extraction was skipped
            if (!isPausedManually()) {
                Timber.d("Extraction skipped but not paused manually, triggering play()");
                play();
            }
            return;
        }

        currentExtractionId++;
        final int extractionId = currentExtractionId;

        // Reset Extractor state for any pending background work
        ttsExtractor.cancelExtraction();

        if (tts != null && tts.isSpeaking()) {
            Timber.d("stop current TTS");
            tts.stop();
        }

        isPreparing = true;
        setNewState(PlaybackStateCompat.STATE_BUFFERING);
        isSettingUpNewArticle = true;
        isWaitingForArticleCompletion = false;
        sentences.clear();
        originalSentences.clear();
        sentenceCounter = 0;
        isArticleFinished = false;
        showFakeLoadingLiveData.postValue(true);

        // Force reset setup flag after 10s if it's still stuck, to allow manual play
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (extractionId == currentExtractionId && isSettingUpNewArticle) {
                Timber.w("Extraction timeout - forcing isSettingUpNewArticle = false for ID: " + extractionId);
                isSettingUpNewArticle = false;
                isPreparing = false;
                showFakeLoadingLiveData.postValue(false);
                finishedSetupLiveData.postValue(true);
                setNewState(PlaybackStateCompat.STATE_PAUSED);
            }
        }, 10000);

        if (language != null && language.equals("Use Language Identifier")) {
            this.language = null;
        } else {
            this.language = language;
        }
        this.currentId = currentId;
        this.feedId = feedId;
        this.lastViewMode = viewMode;
        hasSpokenAfterSetup = false;

        if (language != null && !language.isEmpty()) {
            ttsExtractor.setCurrentLanguage(language, true);
            Timber.d("[extract] Locked language = " + language);
        }

        if (content != null) {
            if (!isNewArticle && !content.equals(lastContent)) {
                Timber.d("Content changed for SAME article, resetting sentence counter to 0");
                entryRepository.updateSentCount(0, currentId);
            }
            lastContent = content; // Update lastContent here
            pendingExtractorId = -1; // New content provided directly, cancel any pending extractor callback

            // Run extraction in background
            new Thread(() -> extractToTts(content, language, extractionId)).start();
        } else {
            // Content is null - check if we already have it in DB before triggering background extraction
            new Thread(() -> {
                xiangze.mmu.rssnewsreader.data.entry.Entry dbEntry = entryRepository.getEntryById(this.currentId);
                if (dbEntry != null && dbEntry.getContent() != null && !dbEntry.getContent().trim().isEmpty()) {
                    Timber.d("Content found in DB, using that instead of background extraction.");
                    String dbContent = dbEntry.getContent();
                    lastContent = dbContent;
                    pendingExtractorId = -1;
                    extractToTts(dbContent, language, extractionId);
                } else {
                    Timber.d("No content in DB, triggering background extraction.");
                    pendingExtractorId = extractionId; // Mark this extractionId as waiting for the extractor
                    ttsExtractor.setCallback(this);
                    ttsExtractor.prioritize();
                }
            }).start();
        }
    }

    private int getViewModePriority(String viewMode) {
        if ("summarized".equals(viewMode)) return 2;
        if ("translated".equals(viewMode)) return 1;
        return 0; // original or null
    }

    public void extract(long currentId, long feedId, String content, String language, String viewMode) {
        extract(currentId, feedId, content, language, viewMode, true);
    }

    @Override
    public synchronized void extractToTts(String content, String language) {
        if (pendingExtractorId != -1) {
            Timber.d("TtsExtractor callback received for pending ID: " + pendingExtractorId);
            lastContent = content;
            extractToTts(content, language, pendingExtractorId);
            pendingExtractorId = -1;
        } else {
            Timber.d("TtsExtractor callback received but no pending ID. Ignoring to avoid race conditions.");
        }
    }

    private void extractToTts(String content, String language, final int extractionId) {
        if (extractionId != currentExtractionId) {
            Timber.d("Ignoring stale extractToTts call for extractionId: " + extractionId + " (Current: " + currentExtractionId + ")");
            return;
        }

        if (content == null || content.trim().isEmpty()) {
            Timber.w("extractToTts: No content provided.");
            if (extractionId == currentExtractionId) {
                isPreparing = false;
                isSettingUpNewArticle = false;
                showFakeLoadingLiveData.postValue(false);
                finishedSetupLiveData.postValue(true);
                setNewState(PlaybackStateCompat.STATE_PAUSED);
            }
            return;
        }

        if (content.contains("Extraction Failed")) {
            if (currentId != lastAutoRetriedId) {
                lastAutoRetriedId = currentId;
                Timber.d("Detected extraction failure message for ID: " + currentId + ". Triggering auto-retry.");

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (extractionId == currentExtractionId) {
                        isPreparing = true;
                        isSettingUpNewArticle = true;
                        showFakeLoadingLiveData.postValue(true);
                        if (isInit && tts != null) {
                            tts.speak("Extraction earlier failed, please wait while I re-extract", TextToSpeech.QUEUE_FLUSH, null, "retry_notice");
                        }
                    }
                });

                ttsExtractor.setCallback(this);
                ttsExtractor.resetAndRetry(currentId);
                return;
            } else {
                Timber.d("Already auto-retried this ID (" + currentId + "). Proceeding to speak the failure message.");
            }
        }

        String[] raw = content.split(Pattern.quote(TtsExtractor.DELIMITER));
        List<String> sentenceList = new ArrayList<>(raw.length);
        for(String part : raw) {
            String trimmed = part.trim();
            if(! trimmed.isEmpty()) {
                sentenceList.add(trimmed);
            }
        }
        int totalSentences = sentenceList.size();

        new Thread(() -> {
            isSentenceSplittingInProgress = true;
            boolean firstBatchSignaled = false;
            try {
                for (int i = 0; i < sentenceList.size(); i++) {
                    if (extractionId != currentExtractionId) return;

                    String originalSentence = sentenceList.get(i);
                    String sentence = textUtil.applyTtsSubstitutions(originalSentence);
                    if (sentence.length() >= TextToSpeech.getMaxSpeechInputLength()) {
                        BreakIterator iterator = BreakIterator.getSentenceInstance();
                        iterator.setText(sentence);
                        int start = iterator.first();
                        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                            sentences.add(sentence.substring(start, end));
                            originalSentences.add(originalSentence); // Add the full original sentence for highlighting
                        }
                    } else {
                        sentences.add(sentence);
                        originalSentences.add(originalSentence);
                    }

                    // If we are waiting for sentences to catch up (either during playback or at start)
                    if (isWaitingForArticleCompletion && !isPausedManually && 
                        (currentState == PlaybackStateCompat.STATE_PLAYING || currentState == PlaybackStateCompat.STATE_BUFFERING) && 
                        sentenceCounter < sentences.size()) {
                        Timber.d("Resuming playback as more sentences arrived for counter " + sentenceCounter);
                        isWaitingForArticleCompletion = false;
                        speak();
                    }

                    // Signal ready after a small batch of sentences are processed (e.g., 5 sentences)
                    if (!firstBatchSignaled && (sentences.size() >= 5 || i == sentenceList.size() - 1)) {
                        firstBatchSignaled = true;
                        int savedProgress = entryRepository.getSentCount(currentId);
                        sentenceCounter = savedProgress; // NO CLAMP HERE
                        if (isInit) {
                            setupTts(extractionId);
                        } else {
                            actionNeeded = true;
                        }
                    }

                    currentExtractProgress = (int) (((double) (i + 1) / totalSentences) * 100);

                    if (i % 3 == 0 || i == sentenceList.size() - 1) {
                        loadingProgressLiveData.postValue(currentExtractProgress);
                    }
                }

                if (extractionId != currentExtractionId) return;

                if (sentences.isEmpty()) {
                    Timber.w("Extraction failed: no sentences found. Resetting state.");
                    askForReloadLiveData.postValue(feedId);
                    actionNeeded = false;
                    isPreparing = false;
                    isSettingUpNewArticle = false;
                    showFakeLoadingLiveData.postValue(false);
                    finishedSetupLiveData.postValue(true);
                    setNewState(PlaybackStateCompat.STATE_PAUSED);
                } else if (!firstBatchSignaled) {
                    // If the article is very short and we haven't signaled yet
                    int savedProgress = entryRepository.getSentCount(currentId);
                    sentenceCounter = Math.min(savedProgress, sentences.size() - 1);
                    if (isInit) {
                        setupTts(extractionId);
                    } else {
                        actionNeeded = true;
                    }
                }
            } finally {
                if (extractionId == currentExtractionId) {
                    isSentenceSplittingInProgress = false;
                    // ALWAYS ensure this flag is reset when the thread finishes, 
                    // even if firstBatchSignaled was false (short articles) 
                    // and setupTts wasn't called yet or if it's waiting on init.
                    isSettingUpNewArticle = false;

                    if (isWaitingForArticleCompletion) {
                        isWaitingForArticleCompletion = false;
                        Timber.d("Splitting finished and we were waiting for it (counter=" + sentenceCounter + ", total=" + sentences.size() + "). Moving to next article.");
                        entryRepository.updateSentCount(0, currentId);
                        sentenceCounter = 0;
                        isArticleFinished = true;
                        if (callback != null) {
                            callback.onSkipToNext();
                        } else {
                            Timber.e("Callback is null in extractToTts finally block, cannot skip to next!");
                        }
                    }
                }
            }
        }).start();
    }

    private void setupTts() {
        setupTts(currentExtractionId);
    }

    private void setupTts(final int extractionId) {
        ContextCompat.getMainExecutor(context).execute(() -> {
            if (extractionId != currentExtractionId) return;

            Timber.d("[setupTts] currentLanguage = " + language + ", isLockedByTtsPlayer = " + ttsExtractor.isLocked() + ", ttsExtractor.language = " + ttsExtractor.getCurrentLanguage());
            
            isPreparing = false;

            finishedSetupLiveData.postValue(true);
            loadingProgressLiveData.postValue(100);
            showFakeLoadingLiveData.postValue(false);

            if (sentences == null || sentences.isEmpty()) {
                Timber.w("No content to read in setupTts(), skipping...");
                isSettingUpNewArticle = false;
                setNewState(PlaybackStateCompat.STATE_PAUSED);
                return;
            }

            if (language != null && !language.isEmpty()) {
                try {
                    Timber.d("Setting TTS language to: " + language);
                    setLanguage(Locale.forLanguageTag(language), false);
                } catch (Exception e) {
                    Timber.d("Invalid locale " + e.getMessage());
                    setLanguage(Locale.ENGLISH, false);
                }
            }

            // Move this out of postDelayed for more immediate action
            isSettingUpNewArticle = false;

            if (sentences.size() > 0 && !isPausedManually && !hasSpokenAfterSetup) {
                hasSpokenAfterSetup = true;
                Timber.d("Auto-speaking from setupTts with 300ms delay");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (extractionId == currentExtractionId) {
                        isManualSkip = true; // Force flush for new extraction
                        play();
                    }
                }, 300);
            } else {
                Timber.d("TTS ready, but paused manually or no content. Waiting for user to resume.");
                setNewState(PlaybackStateCompat.STATE_PAUSED);
            }
        });
    }

    private void identifyLanguage(String sentence, int index, boolean fromService) {
        float confidenceThreshold = (float) sharedPreferencesRepository.getConfidenceThreshold() / 100;

        LanguageIdentifier languageIdentifier = LanguageIdentification.getClient(
                new LanguageIdentificationOptions.Builder()
                        .setConfidenceThreshold(confidenceThreshold)
                        .build());
        languageIdentifier.identifyLanguage(sentence)
                .addOnSuccessListener(languageCode -> {
                    if (languageCode.equals("und")) {
                        Timber.i("Can't identify language.");
                        setLanguage(Locale.ENGLISH, fromService);
                    } else {
                        Timber.i("Language: " + languageCode);
                        setLanguage(Locale.forLanguageTag(languageCode), fromService);
                    }
                    if (!fromService) {
                        doSpeak(sentence, index);
                    }
                })
                .addOnFailureListener(e -> {
                    Timber.e(e, "Language identification failed");
                    setLanguage(Locale.ENGLISH, fromService);
                    if (!fromService) {
                        doSpeak(sentence, index);
                    }
                });
    }

    private void setLanguage(Locale locale, boolean fromService) {
        if (tts == null) {
            return;
        }
        int result = tts.setLanguage(locale);

        Timber.d("setLanguage() called with: " + locale.toString());
        Timber.d("setLanguage() result: " + result);

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Timber.d("Language not supported" + locale);
            snackbarMessageLiveData.postValue("Language not installed. Required language: " + locale.getDisplayLanguage());
            tts.setLanguage(Locale.ENGLISH);
        }
        else {
            Timber.d("Language successfully set to: " + locale);
        }

        if (fromService) {
            callback.onCustomAction("playFromService", null);
        } else {
            Timber.d("Language set. Waiting for speak() to be called.");
        }
    }

    public void speak() {
        ContextCompat.getMainExecutor(context).execute(() -> {
            if (isSettingUpNewArticle) {
                Timber.d("TTS setup in progress, skipping speak()");
                return;
            }

            if (isArticleFinished) {
                Timber.d("speak() called but article is finished. Skipping to next.");
                if (callback != null) {
                    callback.onSkipToNext();
                }
                return;
            }

            if (!isInit || tts == null) {
                Timber.d("speak() skipped — TTS not initialized yet. Waiting for init.");
                actionNeeded = true;
                return;
            }
            
            if (sentenceCounter < 0) sentenceCounter = 0;

            if (sentences == null || sentences.size() == 0 || sentenceCounter >= sentences.size()) {
                if (isSentenceSplittingInProgress) {
                    Timber.d("Waiting for splitting to catch up to counter " + sentenceCounter);
                    isWaitingForArticleCompletion = true;
                    setNewState(PlaybackStateCompat.STATE_BUFFERING);
                } else {
                    Timber.d("No sentences ready or counter out of bounds, skipping speak(). size=" + (sentences == null ? "null" : sentences.size()) + ", counter=" + sentenceCounter);
                    if (sentenceCounter >= sentences.size() && sentences.size() > 0) {
                        // Truly out of bounds
                        sentenceCounter = 0;
                        entryRepository.updateSentCount(0, currentId);
                    }
                }
                return;
            }

            // GUARD: Prevent multiple calls for the same sentence while it's already playing or being processed
            if (sentenceCounter == processingSentenceIndex && !isManualSkip && (isSpeaking() || currentState == PlaybackStateCompat.STATE_PLAYING)) {
                Timber.d("Already processing or speaking sentence [#" + sentenceCounter + "], skipping redundant speak() call.");
                return;
            }
            processingSentenceIndex = sentenceCounter;

            String sentence = sentences.get(sentenceCounter);
            int index = sentenceCounter;
            Timber.d("speak() [#" + index + "] with language = " + language + ", sentence = " + sentence);

            if (language == null) {
                identifyLanguage(sentence, index, false);
            } else {
                doSpeak(sentence, index);
            }
        });
    }

    private void doSpeak(String sentence, int index) {
        Timber.d("TTS Speaking [#" + index + "]: " + sentence);
        int queueMode = TextToSpeech.QUEUE_FLUSH;
        
        // Reset manual skip flag immediately after use so subsequent automatic plays work normally
        if (isManualSkip) {
            isManualSkip = false;
        }

        // Pass index as utteranceId to track progress in onStart
        String utteranceId = String.valueOf(index);
        currentUtteranceID = utteranceId;

        // Apply volume
        android.os.Bundle params = new android.os.Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume);
        
        scheduleTimeout(utteranceId);
        int result = tts.speak(sentence, queueMode, params, utteranceId);
        if (result == TextToSpeech.ERROR) {
            Timber.e("tts.speak returned ERROR for [#" + index + "]. Attempting recovery.");
            // Wait a short bit then try to treat as 'done' to skip this sentence
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (currentUtteranceID != null && currentUtteranceID.equals(utteranceId)) {
                    handleOnDone(utteranceId);
                }
            }, 500);
        }
        
        setUiControlPlayback(true);
        setNewState(PlaybackStateCompat.STATE_PLAYING);
        if (playbackUiListener != null) {
            playbackUiListener.onPlaybackStarted();
        }
    }

    public void fastForward() {
        if (tts != null && sentences != null && !sentences.isEmpty() && sentenceCounter < sentences.size() - 1) {
            isManualSkip = true;
            sentenceCounter++;
            
            // Re-verify bounds after increment in case sentences changed
            if (sentenceCounter < sentences.size()) {
                entryRepository.updateSentCount(sentenceCounter, currentId);
                
                String sentence = sentences.get(sentenceCounter);
                int index = sentenceCounter;
                processingSentenceIndex = index; // Update guard
                String utteranceId = String.valueOf(index);
                currentUtteranceID = utteranceId;

                android.os.Bundle params = new android.os.Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume);
                
                Timber.d("FastForward to [#" + index + "]: " + sentence);
                scheduleTimeout(utteranceId);
                tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
                
                setUiControlPlayback(true);
                setNewState(PlaybackStateCompat.STATE_PLAYING);
            } else {
                Timber.d("fastForward: sentenceCounter became out of bounds after increment");
                isManualSkip = true;
                entryRepository.updateSentCount(0, currentId);
                callback.onSkipToNext();
            }
        } else {
            isManualSkip = true;
            entryRepository.updateSentCount(0, currentId);
            callback.onSkipToNext();
        }
    }

    public void fastRewind() {
        if (tts != null && sentences != null && !sentences.isEmpty() && sentenceCounter > 0) {
            isManualSkip = true;
            sentenceCounter--;
            
            // Re-verify bounds after decrement in case sentences changed
            if (sentenceCounter >= 0 && sentenceCounter < sentences.size()) {
                entryRepository.updateSentCount(sentenceCounter, currentId);
                
                String sentence = sentences.get(sentenceCounter);
                int index = sentenceCounter;
                processingSentenceIndex = index; // Update guard
                String utteranceId = String.valueOf(index);
                currentUtteranceID = utteranceId;

                android.os.Bundle params = new android.os.Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume);
                
                Timber.d("FastRewind to [#" + index + "]: " + sentence);
                scheduleTimeout(utteranceId);
                tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
                
                setUiControlPlayback(true);
                setNewState(PlaybackStateCompat.STATE_PLAYING);
            } else {
                Timber.d("fastRewind: sentenceCounter became out of bounds after decrement");
            }
        }
    }

    @Override
    public boolean isPlayingMediaPlayer() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public void setupMediaPlayer(boolean forced) {
        if (forced) {
            stopMediaPlayer();
        }

        if (mediaPlayer == null && sharedPreferencesRepository.getBackgroundMusic()) {
            if (sharedPreferencesRepository.getBackgroundMusicFile().equals("default")) {
                mediaPlayer = MediaPlayer.create(context, R.raw.pianomoment);
            } else {
                File savedFile = new File(context.getFilesDir(), "user_file.mp3");
                if (savedFile.exists()) {
                    mediaPlayer = MediaPlayer.create(context, Uri.parse(savedFile.getAbsolutePath()));
                } else {
                    mediaPlayer = MediaPlayer.create(context, R.raw.pianomoment);
                }
            }
            mediaPlayer.setLooping(true);
            changeMediaPlayerVolume();
        }
        playMediaPlayer();
    }

    @Override
    public void playMediaPlayer() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.start();
    }

    @Override
    public void pauseMediaPlayer() {
        if (mediaPlayer != null) mediaPlayer.pause();
    }

    public void changeMediaPlayerVolume() {
        if (mediaPlayer != null) {
            float volume = (float) sharedPreferencesRepository.getBackgroundMusicVolume() / 100;
            mediaPlayer.setVolume(volume, volume);
        }
    }

    public void stopMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public boolean isPlaying() {
        return tts != null && tts.isSpeaking();
    }

    @Override
    protected void onPlay() {
        Timber.d("onPlay called. isPausedManually=" + isPausedManually + ", ttsReady=" + (tts != null));
        setPausedManually(false);
        if (tts != null) {
            speak();
            setNewState(PlaybackStateCompat.STATE_PLAYING);
        } else {
            Timber.d("onPlay: TTS is null, triggering onPrepare via callback");
            if (callback != null) {
                callback.onPrepare();
            }
        }
    }

    @Override
    protected void onPause() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        processingSentenceIndex = -1;
        if (currentId > 0) {
            entryRepository.updateSentCount(sentenceCounter, currentId);
        }
        setNewState(PlaybackStateCompat.STATE_PAUSED);
    }

    @Override
    protected void onStop() {
        stopMediaPlayer();
        Timber.d(" player stopped");
        processingSentenceIndex = -1;
        if (currentId > 0) {
            entryRepository.updateSentCount(sentenceCounter, currentId);
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isInit = false;
        }
        currentId = 0;
        isArticleFinished = false;
        setNewState(PlaybackStateCompat.STATE_STOPPED);
        releaseWakeLock();
        releaseWifiLock();
    }

    private void setNewState(@PlaybackStateCompat.State int state) {
        Timber.d("setNewState: changing state from " + currentState + " to " + state);
        if (listener != null) {
            currentState = state;
            final PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(getAvailableActions());
            stateBuilder.setState(currentState, 0, 1.0f, SystemClock.elapsedRealtime());
            listener.onPlaybackStateChange(stateBuilder.build());
        }

        // Manage Locks to prevent CPU/Network sleep during playback and preparation
        if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
            if (!wakeLock.isHeld()) {
                Timber.d("Acquiring WakeLock for TTS playback/buffering");
                wakeLock.acquire(30 * 60 * 1000L); // 30 minute timeout to prevent indefinite hold
            }
            if (!wifiLock.isHeld()) {
                Timber.d("Acquiring WifiLock for TTS playback/buffering");
                wifiLock.acquire();
            }
        } else {
            releaseWakeLock();
            releaseWifiLock();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock.isHeld()) {
            Timber.d("Releasing WakeLock");
            wakeLock.release();
        }
    }

    private void releaseWifiLock() {
        if (wifiLock.isHeld()) {
            Timber.d("Releasing WifiLock");
            wifiLock.release();
        }
    }

    @PlaybackStateCompat.Actions
    private long getAvailableActions() {
        long actions = PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_REWIND
                | PlaybackStateCompat.ACTION_FAST_FORWARD
                | PlaybackStateCompat.ACTION_PLAY_PAUSE;
        switch (currentState) {
            case PlaybackStateCompat.STATE_STOPPED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                actions |= PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP;
                break;
            default:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
        }
        return actions;
    }

    public void setTtsSpeechRate(float speechRate) {
        if (tts == null) {
            Timber.w("setTtsSpeechRate: TTS is null, cannot set speech rate.");
            return;
        }
        if (speechRate == 0) {
            try {
                int systemRate = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.TTS_DEFAULT_RATE);
                speechRate = systemRate / 100.0f;
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
                speechRate = 1.0f;
            }
        }
        tts.setSpeechRate(speechRate);
    }

    public void showFakeLoading() {
        showFakeLoadingLiveData.postValue(true);
    }

    public void hideFakeLoading() {
        showFakeLoadingLiveData.postValue(false);
    }

    public boolean ttsIsNull() {
        return tts == null;
    }

    public boolean isWebViewConnected() {
        return webViewConnected;
    }

    public void setWebViewConnected(boolean isConnected) {
        this.webViewConnected = isConnected;
    }

    public boolean isUiControlPlayback() {
        return uiControlPlayback;
    }

    public void setUiControlPlayback(boolean isUiControlPlayback) {
        this.uiControlPlayback = isUiControlPlayback;
    }

    public long getCurrentId() {
        return currentId;
    }

    public boolean isPausedManually() {
        return isPausedManually;
    }

    public void setPausedManually(boolean isPaused) {
        sharedPreferencesRepository.setIsPausedManually(isPaused);
        isPausedManually = isPaused;
    }

    public boolean isPreparing() {
        return isPreparing;
    }

    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    public boolean isArticleFinished() {
        return isArticleFinished;
    }

    public int getCurrentExtractProgress() {
        return currentExtractProgress;
    }

    public String getLastViewMode() {
        return lastViewMode;
    }
}