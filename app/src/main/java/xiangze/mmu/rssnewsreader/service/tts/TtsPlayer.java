package xiangze.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

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

    private int sentenceCounter;
    private List<String> sentences = new CopyOnWriteArrayList<>();

    private int currentState;
    private long currentId = 0;
    private long feedId = 0;
    private String language;
    private boolean isInit = false;
    private boolean isPreparing = false;
    private boolean actionNeeded = false;
    private boolean isPausedManually;
    private boolean webViewConnected = false;
    private boolean uiControlPlayback = false;
    private boolean isManualSkip = false;
    private boolean isArticleFinished = false;
    private boolean isSettingUpNewArticle = false;
    private boolean isSentenceSplittingInProgress = false;
    private MediaPlayer mediaPlayer;
    private String currentUtteranceID = null;
    private String lastContent = null;
    private String lastViewMode = null;
    private long lastCurrentId = -1;
    private int currentExtractionId = 0;
    private boolean hasSpokenAfterSetup = false;
    private PlaybackUiListener playbackUiListener;
    private int currentExtractProgress = 0;
    private long lastHandledReloadId = -1;

    private final MutableLiveData<String> highlightTextLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> finishedSetupLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> snackbarMessageLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> loadingProgressLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> askForReloadLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> showFakeLoadingLiveData = new MutableLiveData<>();

    public LiveData<String> getHighlightTextLiveData() { return highlightTextLiveData; }
    public LiveData<Boolean> getFinishedSetupLiveData() { return finishedSetupLiveData; }
    public LiveData<String> getSnackbarMessageLiveData() { return snackbarMessageLiveData; }
    public LiveData<Integer> getLoadingProgressLiveData() { return loadingProgressLiveData; }
    public LiveData<Long> getAskForReloadLiveData() { return askForReloadLiveData; }
    public LiveData<Boolean> getShowFakeLoadingLiveData() { return showFakeLoadingLiveData; }

    @Inject
    public TtsPlayer(@ApplicationContext Context context, TtsExtractor ttsExtractor, EntryRepository entryRepository, SharedPreferencesRepository sharedPreferencesRepository) {
        super(context);
        this.ttsExtractor = ttsExtractor;
        this.entryRepository = entryRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.context = context;
        this.isPausedManually = sharedPreferencesRepository.getIsPausedManually();
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void initTts(TtsService ttsService, PlaybackStateListener listener, MediaSessionCompat.Callback callback) {
        this.listener = listener;
        this.callback = callback;
        tts = new TextToSpeech(ttsService, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "initTts successful");
                isInit = true;

                if (actionNeeded) {
                    Log.d(TAG, "Deferred auto-play activated — TTS is now ready");
                    setupTts();
                    actionNeeded = false;
                }
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                final int extractionId = currentExtractionId;
                if (utteranceId != null) {
                    try {
                        int index = Integer.parseInt(utteranceId);
                        if (extractionId != currentExtractionId) {
                            Log.d(TAG, "Ignoring stale onStart for extractionId: " + extractionId);
                            return;
                        }
                        // Accessing sentences.size() and sentences.get() on a CopyOnWriteArrayList is thread-safe
                        if (index >= 0 && index < sentences.size()) {
                            String sentenceToHighlight = sentences.get(index);
                            highlightTextLiveData.postValue(sentenceToHighlight);
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Invalid utterance ID format: " + utteranceId);
                    }
                }
            }

            @Override
            public void onDone(String utteranceId) {
                final int extractionId = currentExtractionId;
                
                if (currentUtteranceID != null && !currentUtteranceID.equals(utteranceId)) {
                    Log.d(TAG, "Ignoring stale onDone for utteranceId: " + utteranceId + " (Current: " + currentUtteranceID + ")");
                    return;
                }

                if (extractionId != currentExtractionId) {
                    Log.d(TAG, "Ignoring stale onDone for extractionId: " + extractionId + " (Current: " + currentExtractionId + ")");
                    return;
                }

                if (isArticleFinished) {
                    Log.d(TAG, "Already finished article, skipping duplicate onDone");
                    return;
                }

                if (sentenceCounter < sentences.size() - 1) {
                    sentenceCounter++;
                    if (sentenceCounter < sentences.size()) {
                        speak();
                        entryRepository.updateSentCount(sentenceCounter, currentId);
                        Log.d(TAG, "Finished [#" + (sentenceCounter - 1) + "], speaking [#" + sentenceCounter + "]");
                    } else {
                        Log.d(TAG, "sentenceCounter became out of bounds after increment, stopping.");
                    }
                } else {
                    if (isSentenceSplittingInProgress) {
                        Log.d(TAG, "Reached end of current batch, but splitting is still in progress. Waiting...");
                        // We don't increment sentenceCounter here, we wait for extractToTts to resume us
                    } else {
                        Log.d(TAG, "Finished last sentence. Moving to next article.");
                        entryRepository.updateSentCount(0, currentId);
                        sentenceCounter = 0;
                        isArticleFinished = true;
                        callback.onSkipToNext();
                    }
                }
            }

            @Override
            @Deprecated
            public void onError(String s) {
                Log.d("TTS", "onError: " + s);
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                Log.e(TAG, "TTS Error for utterance " + utteranceId + ", code: " + errorCode);
            }
        });
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
        setUiControlPlayback(false);
        if (playbackUiListener != null) {
            playbackUiListener.onPlaybackPaused();
        }
    }

    public void pauseTts() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        setPausedManually(true);
        setNewState(PlaybackStateCompat.STATE_PAUSED);
        setUiControlPlayback(false);
        if (playbackUiListener != null) {
            playbackUiListener.onPlaybackPaused();
        }
    }

    public void extract(long currentId, long feedId, String content, String language) {
        extract(currentId, feedId, content, language, null);
    }

    public void extract(long currentId, long feedId, String content, String language, String viewMode) {
        Log.d(TAG, "Switching to new article: ID=" + currentId + " ViewMode=" + viewMode);

        if (currentId != this.lastCurrentId) {
            lastContent = null;
            lastViewMode = null;
            this.lastCurrentId = currentId;
        }

        boolean wasPlayingIntent = (currentState == PlaybackStateCompat.STATE_PLAYING) || (tts != null && tts.isSpeaking()) || isArticleFinished;
        isPausedManually = !wasPlayingIntent && sharedPreferencesRepository.getIsPausedManually();
        sharedPreferencesRepository.setIsPausedManually(isPausedManually);
        Log.d(TAG, "Detected isPausedManually = " + isPausedManually + " (wasPlayingIntent=" + wasPlayingIntent + ", state=" + currentState + ", finished=" + isArticleFinished + ")");

        String resolvedLanguage = (language != null && language.equals("Use Language Identifier")) ? null : language;
        if (content != null && content.equals(this.lastContent) && currentId == this.currentId && 
            (resolvedLanguage == null ? this.language == null : resolvedLanguage.equals(this.language)) &&
            (viewMode != null && viewMode.equals(this.lastViewMode))) {
            Log.d(TAG, "Content, language, viewMode and ID are identical to last extraction, skipping redundant extraction.");
            isArticleFinished = false;
            hasSpokenAfterSetup = false; // Reset to allow auto-play on redundant calls
            finishedSetupLiveData.postValue(true);
            return;
        }

        currentExtractionId++;
        final int extractionId = currentExtractionId;

        // Reset Extractor state for any pending background work
        ttsExtractor.cancelExtraction();

        if (tts != null && tts.isSpeaking()) {
            Log.d(TAG, "stop current TTS");
            tts.stop();
        }

        isPreparing = true;
        isSettingUpNewArticle = true;
        sentences.clear();
        isArticleFinished = false;

        // Force reset setup flag after 10s if it's still stuck, to allow manual play
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (extractionId == currentExtractionId && isSettingUpNewArticle) {
                Log.w(TAG, "Extraction timeout - forcing isSettingUpNewArticle = false for ID: " + extractionId);
                isSettingUpNewArticle = false;
                isPreparing = false;
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
            Log.d(TAG, "[extract] Locked language = " + language);
        }

        if (content != null) {
            if (!content.equals(lastContent)) {
                Log.d(TAG, "Content changed, resetting sentence counter to 0");
                entryRepository.updateSentCount(0, currentId);
            }
            lastContent = content;

            // Run extraction in background
            new Thread(() -> extractToTts(content, language, extractionId)).start();
        } else {
            // Content is null - check if we already have it in DB before triggering background extraction
            xiangze.mmu.rssnewsreader.data.entry.Entry dbEntry = entryRepository.getEntryById(currentId);
            if (dbEntry != null && dbEntry.getContent() != null && !dbEntry.getContent().trim().isEmpty()) {
                 Log.d(TAG, "Content found in DB, using that instead of background extraction.");
                 String dbContent = dbEntry.getContent();
                 lastContent = dbContent;
                 new Thread(() -> extractToTts(dbContent, language, extractionId)).start();
            } else {
                 Log.d(TAG, "No content in DB, triggering background extraction.");
                 ttsExtractor.setCallback(this);
                 ttsExtractor.prioritize();
            }
        }
    }

    @Override
    public void extractToTts(String content, String language) {
        extractToTts(content, language, currentExtractionId);
    }

    private void extractToTts(String content, String language, final int extractionId) {
        if (content == null || content.trim().isEmpty()) {
            Log.w(TAG, "extractToTts: No content provided.");
            if (extractionId == currentExtractionId) {
                isPreparing = false;
                isSettingUpNewArticle = false;
            }
            return;
        }

        String[] raw = content.split(Pattern.quote(ttsExtractor.delimiter));
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

                    String sentence = sentenceList.get(i);
                    if (sentence.length() >= TextToSpeech.getMaxSpeechInputLength()) {
                        BreakIterator iterator = BreakIterator.getSentenceInstance();
                        iterator.setText(sentence);
                        int start = iterator.first();
                        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                            sentences.add(sentence.substring(start, end));
                        }
                    } else {
                        sentences.add(sentence);
                    }

                    // If we were at the end of the list and stopped, but more sentences are now available, resume speaking
                    if (firstBatchSignaled && !isSpeaking() && !isPausedManually && currentState == PlaybackStateCompat.STATE_PLAYING && sentenceCounter == sentences.size() - 2) {
                        Log.d(TAG, "Resuming playback as more sentences arrived.");
                        sentenceCounter++;
                        speak();
                    }

                    // Signal ready after a small batch of sentences are processed (e.g., 5 sentences)
                    if (!firstBatchSignaled && sentences.size() >= 5) {
                        firstBatchSignaled = true;
                        int savedProgress = entryRepository.getSentCount(currentId);
                        sentenceCounter = Math.min(savedProgress, sentences.size() - 1);
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
                    Log.w(TAG, "Extraction failed: no sentences found. Resetting state.");
                    askForReloadLiveData.postValue(feedId);
                    actionNeeded = false;
                    isPreparing = false;
                    isSettingUpNewArticle = false;
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

            Log.d(TAG, "[setupTts] currentLanguage = " + language + ", isLockedByTtsPlayer = " + ttsExtractor.isLocked() + ", ttsExtractor.language = " + ttsExtractor.getCurrentLanguage());
            
            isPreparing = false;

            finishedSetupLiveData.postValue(true);
            loadingProgressLiveData.postValue(100);
            showFakeLoadingLiveData.postValue(false);

            if (sentences == null || sentences.isEmpty()) {
                Log.w(TAG, "No content to read in setupTts(), skipping...");
                isSettingUpNewArticle = false;
                return;
            }

            if (language != null && !language.isEmpty()) {
                try {
                    Log.d(TAG, "Setting TTS language to: " + language);
                    setLanguage(Locale.forLanguageTag(language), true);
                } catch (Exception e) {
                    Log.d(TAG, "Invalid locale " + e.getMessage());
                    setLanguage(Locale.ENGLISH, true);
                }
            }

            // Move this out of postDelayed for more immediate action
            isSettingUpNewArticle = false;

            if (sentences.size() > 0 && !isPausedManually && !hasSpokenAfterSetup) {
                hasSpokenAfterSetup = true;
                Log.d(TAG, "Auto-speaking from setupTts with 300ms delay");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (extractionId == currentExtractionId) {
                        isManualSkip = true; // Force flush for new extraction
                        speak();
                    }
                }, 300);
            } else {
                Log.d(TAG, "TTS ready, but paused manually or no content. Waiting for user to resume.");
            }
        });
    }

    private void identifyLanguage(String sentence, boolean fromService) {
        float confidenceThreshold = (float) sharedPreferencesRepository.getConfidenceThreshold() / 100;

        LanguageIdentifier languageIdentifier = LanguageIdentification.getClient(
                new LanguageIdentificationOptions.Builder()
                        .setConfidenceThreshold(confidenceThreshold)
                        .build());
        languageIdentifier.identifyLanguage(sentence)
                .addOnSuccessListener(languageCode -> {
                    if (languageCode.equals("und")) {
                        Log.i(TAG, "Can't identify language.");
                        setLanguage(Locale.ENGLISH, fromService);
                    } else {
                        Log.i(TAG, "Language: " + languageCode);
                        setLanguage(Locale.forLanguageTag(languageCode), fromService);
                    }
                    if (!fromService) {
                        doSpeak(sentence);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Language identification failed", e);
                    setLanguage(Locale.ENGLISH, fromService);
                    if (!fromService) {
                        doSpeak(sentence);
                    }
                });
    }

    private void setLanguage(Locale locale, boolean fromService) {
        if (tts == null) {
            return;
        }
        int result = tts.setLanguage(locale);

        Log.d(TAG, "setLanguage() called with: " + locale.toString());
        Log.d(TAG, "setLanguage() result: " + result);

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.d(TAG, "Language not supported" + locale);
            snackbarMessageLiveData.postValue("Language not installed. Required language: " + locale.getDisplayLanguage());
            tts.setLanguage(Locale.ENGLISH);
        }
        else {
            Log.d(TAG, "Language successfully set to: " + locale);
        }

        if (fromService) {
            callback.onCustomAction("playFromService", null);
        } else {
            Log.d(TAG, "Language set. Waiting for speak() to be called.");
        }
    }

    public void speak() {
        if (isSettingUpNewArticle) {
            Log.d(TAG, "TTS setup in progress, skipping speak()");
            return;
        }

        if (!isInit || tts == null) {
            Log.d(TAG, "speak() skipped — TTS not initialized yet. Waiting for init.");
            actionNeeded = true;
            return;
        }
        
        if (sentenceCounter < 0) sentenceCounter = 0;

        if (sentences == null || sentences.size() == 0 || sentenceCounter >= sentences.size()) {
            Log.d(TAG, "No sentences ready or counter out of bounds, skipping speak(). size=" + (sentences == null ? "null" : sentences.size()) + ", counter=" + sentenceCounter);
            return;
        }

        String sentence = sentences.get(sentenceCounter);
        Log.d(TAG, "speak() with language = " + language + ", sentence = " + sentence);

        if (language == null) {
            identifyLanguage(sentence, false);
        } else {
            doSpeak(sentence);
        }
    }

    private void doSpeak(String sentence) {
        Log.d(TAG, "TTS Speaking [#" + sentenceCounter + "]: " + sentence);
        int queueMode = isManualSkip ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        
        // Reset manual skip flag immediately after use so subsequent automatic plays use QUEUE_ADD
        if (isManualSkip) {
            isManualSkip = false;
        }

        // Pass sentenceCounter as utteranceId to track progress in onStart
        String utteranceId = String.valueOf(sentenceCounter);
        currentUtteranceID = utteranceId;
        
        int result = tts.speak(sentence, queueMode, null, utteranceId);
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "tts.speak returned ERROR for [#" + sentenceCounter + "]");
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
                String utteranceId = String.valueOf(sentenceCounter);
                currentUtteranceID = utteranceId;
                
                Log.d(TAG, "FastForward to [#" + sentenceCounter + "]: " + sentence);
                tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
                
                setUiControlPlayback(true);
                setNewState(PlaybackStateCompat.STATE_PLAYING);
            } else {
                Log.d(TAG, "fastForward: sentenceCounter became out of bounds after increment");
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
                String utteranceId = String.valueOf(sentenceCounter);
                currentUtteranceID = utteranceId;
                
                Log.d(TAG, "FastRewind to [#" + sentenceCounter + "]: " + sentence);
                tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
                
                setUiControlPlayback(true);
                setNewState(PlaybackStateCompat.STATE_PLAYING);
            } else {
                Log.d(TAG, "fastRewind: sentenceCounter became out of bounds after decrement");
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
        if (tts != null && !isPausedManually) {
            speak();
            setNewState(PlaybackStateCompat.STATE_PLAYING);
        }
    }

    @Override
    protected void onPause() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        setNewState(PlaybackStateCompat.STATE_PAUSED);
    }

    @Override
    protected void onStop() {
        stopMediaPlayer();
        Log.d(TAG, " player stopped");
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isInit = false;
        }
        currentId = 0;
        setNewState(PlaybackStateCompat.STATE_STOPPED);
    }

    private void setNewState(@PlaybackStateCompat.State int state) {
        if (listener != null) {
            currentState = state;
            final PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(getAvailableActions());
            stateBuilder.setState(currentState, 0, 1.0f, SystemClock.elapsedRealtime());
            listener.onPlaybackStateChange(stateBuilder.build());
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

    public int getCurrentExtractProgress() {
        return currentExtractProgress;
    }
}