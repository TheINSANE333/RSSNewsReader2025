package xiangze.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import timber.log.Timber;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import xiangze.mmu.rssnewsreader.data.GlobalState;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewListener;

@AndroidEntryPoint
public class TtsService extends MediaBrowserServiceCompat {

    private static final String TAG = "TtsService";

    @Inject
    TtsPlayer ttsPlayer;
    @Inject
    TtsPlaylist ttsPlaylist;
    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;
    @Inject
    EntryRepository entryRepository;
    private TtsNotification ttsNotification;
    private static MediaSessionCompat mediaSession;
    private MediaMetadataCompat preparedData;
    private boolean serviceInStartedState;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Timber.d("onStartCommand: intent=" + intent);
        if (intent != null && Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            // Android O+ requires startForeground within 5 seconds of startForegroundService()
            if (!serviceInStartedState) {
                MediaMetadataCompat data = preparedData;
                if (data == null) {
                    // Create a placeholder if no metadata is ready yet
                    data = new MediaMetadataCompat.Builder()
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Loading...")
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "RSS News Reader")
                            .build();
                }
                
                PlaybackStateCompat state = mediaSession.getController().getPlaybackState();
                if (state == null) {
                    state = new PlaybackStateCompat.Builder()
                            .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                            .build();
                }
                
                Notification notification = ttsNotification.getNotification(data, state, getSessionToken());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification);
                }
                serviceInStartedState = true;
            }

            // Use the standard MediaButtonReceiver to handle the intent
            TtsMediaButtonReceiver.handleIntent(mediaSession, intent);
        }
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Timber.d("onCreate: Initializing TtsService");

        // 1. Notification First (Prevents NPE)
        ttsNotification = new TtsNotification(this);

        // 2. Setup Media Button Receiver Component (Custom one)
        ComponentName mbrComponent = new ComponentName(getPackageName(), TtsMediaButtonReceiver.class.getName());

        // 3. Initialize MediaSession with Receiver Component
        mediaSession = new MediaSessionCompat(this, TAG, mbrComponent, null);

        // 4. Create the PendingIntent for Hardware Buttons
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(mbrComponent);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent mbrPendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, piFlags);

        // 5. Configure Session Properties
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setMediaButtonReceiver(mbrPendingIntent);
        mediaSession.setCallback(callback);

        // 6. Set Initial Playback State
        PlaybackStateCompat initialState = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_FAST_FORWARD | PlaybackStateCompat.ACTION_REWIND |
                        PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                .build();
        mediaSession.setPlaybackState(initialState);

        // 7. LOAD INITIAL METADATA (Prevents the "null" parse crash in UI)
        preparedData = ttsPlaylist.getCurrentMetadata();
        if (preparedData != null) {
            mediaSession.setMetadata(preparedData);
        }

        // 8. Initialize Player
        ttsPlayer.initTts(this, new TtsPlayerListener(), callback);

        // 9. Activate
        mediaSession.setActive(true);
        setSessionToken(mediaSession.getSessionToken());

        Timber.d("onCreate: Service ready. active=" + mediaSession.isActive());
    }

    @Override
    public void onDestroy() {
        Timber.d("destroyed");
        ttsPlayer.stop();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot("success", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        preparedData = ttsPlaylist.getCurrentMetadata();
        result.sendResult(ttsPlaylist.getMediaItems());
    }

    private final MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {

        @SuppressLint("CheckResult")
        @Override
        public void onPrepare() {
            onPrepare(false);
        }

        private void onPrepare(final boolean ignoreViewingId) {
            Timber.d("onPrepare called - ignoreViewingId=" + ignoreViewingId);

            long currentReadingId = sharedPreferencesRepository.getCurrentReadingEntryId();
            long currentViewingId = GlobalState.getCurrentViewingId();

            if (!ignoreViewingId && currentViewingId != 0 && currentViewingId != currentReadingId) {
                currentReadingId = currentViewingId;
                sharedPreferencesRepository.setCurrentReadingEntryId(currentReadingId);
                ttsPlaylist.updatePlayingId(currentReadingId);
            }

            // 1. SYNC UI FEEDBACK: Show buffering only if we are actually changing articles or not already playing
            if (ttsPlayer.getCurrentId() != currentReadingId || !ttsPlayer.isPlaying()) {
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING);
            }

            // Update Metadata immediately so title changes instantly
            ttsPlaylist.updatePlayingId(currentReadingId);
            preparedData = ttsPlaylist.getCurrentMetadata();
            if (preparedData != null) {
                if (!mediaSession.isActive()) {
                    mediaSession.setActive(true);
                }
                mediaSession.setMetadata(preparedData);
            } else {
                mediaSession.setMetadata(null);
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
                return;
            }

            // 1.5 IMMEDIATE STATE SYNC: If paused manually, show PAUSED instead of BUFFERING
            if (ttsPlayer.isPausedManually()) {
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            }

            final long targetReadingId = currentReadingId;

            // 2. BACKGROUND PROCESSING: Handle heavy extraction and setup off-thread
            Completable.fromAction(() -> {
                // Initialize TTS Engine and Player
                if (!ttsPlayer.isPausedManually()) {
                    ttsPlayer.setupMediaPlayer(false);
                }
                if (ttsPlayer.ttsIsNull()) {
                    ttsPlayer.initTts(TtsService.this, new TtsPlayerListener(), callback);
                }

                Entry entry = entryRepository.getEntryById(targetReadingId);
                if (entry == null) {
                    Timber.w("Entry not found for ID: " + targetReadingId);
                    if (ttsPlayer.getCurrentId() == targetReadingId) {
                        ttsPlayer.stopTtsPlayback();
                    }
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(this::onSkipToNext);
                    return;
                }

                // Metadata already updated early (Step 1)
                
                // 3. WATERFALL LOGIC: Content Selection
                // Priority: Preference > Summarized > Translated > Original
                String contentToSpeak;
                boolean useSummarized = false;
                boolean useTranslated = false;

                boolean hasSummary = entry.getSummarized() != null && !entry.getSummarized().trim().isEmpty();
                boolean hasTranslation = entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty();

                if (sharedPreferencesRepository.hasSummarizationToggle(targetReadingId) ||
                        sharedPreferencesRepository.hasTranslationToggle(targetReadingId)) {
                    // USE SAVED PREFERENCE
                    useSummarized = sharedPreferencesRepository.getIsSummarizedView(targetReadingId) && hasSummary;
                    useTranslated = !useSummarized && sharedPreferencesRepository.getIsTranslatedView(targetReadingId) && hasTranslation;
                } else {
                    // NO PREFERENCE: Use Data Priority
                    if (hasSummary) {
                        useSummarized = true;
                    } else if (hasTranslation) {
                        useTranslated = true;
                    }
                }

                if (useSummarized) {
                    contentToSpeak = entry.getSummarized();
                    Timber.d("Selection: Summarized Content");
                } else if (useTranslated) {
                    contentToSpeak = entry.getTranslated();
                    Timber.d("Selection: Translated Content");
                } else {
                    contentToSpeak = entry.getContent(); // Original content
                    Timber.d("Selection: Original Content");
                }

                // 4. WATERFALL LOGIC: Language Selection
                EntryInfo entryInfo = entryRepository.getEntryInfoById(targetReadingId);
                String feedLanguage = (entryInfo.getFeedLanguage() == null || entryInfo.getFeedLanguage().isEmpty())
                        ? "en" : entryInfo.getFeedLanguage();
                String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
                if (targetLanguage == null || targetLanguage.isEmpty()) targetLanguage = "en";

                // Use target language if we are speaking a summary or a translation
                String languageToUse = (useSummarized || useTranslated) ? targetLanguage : feedLanguage;

                // 5. SYNC STATE: (Removed forcing of prefs here, as it overrides user manual choice)
                // sharedPreferencesRepository.setIsSummarizedView(targetReadingId, useSummarized);
                // sharedPreferencesRepository.setIsTranslatedView(targetReadingId, useTranslated);

                // 6. Setup Media Session (Metadata already updated early in step 2.5)
                preparedData = ttsPlaylist.getCurrentMetadata();
                if (preparedData == null) {
                    Timber.e("Metadata is null, cannot proceed with onPrepare");
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                    return;
                }

                // Apply speech rate settings
                String rateStr = preparedData.getString("ttsSpeechRate");
                float rate = (rateStr != null) ? Float.parseFloat(rateStr) : 1.0f;
                ttsPlayer.setTtsSpeechRate(rate);

                // 7. Extract and Play
                long mediaId = Long.parseLong(preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
                long feedId = preparedData.getLong("feedId");

                // Safety check to ensure we aren't loading content for a different article
                if (mediaId != targetReadingId) {
                    Timber.d("Skipping extract() — mediaId mismatch");
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                    return;
                }

                String viewMode = useSummarized ? "summarized" : (useTranslated ? "translated" : "original");
                
                if (ttsPlayer.getCurrentId() != mediaId || ttsPlayer.isArticleFinished()) {
                    ttsPlayer.stopTtsPlayback();
                }
                ttsPlayer.extract(mediaId, feedId, contentToSpeak, languageToUse, viewMode, false);

                if (!ttsPlayer.isPausedManually()) {
                    ttsPlayer.play();
                }

                Timber.d("TTS Extraction complete for ID: " + mediaId + " Language: " + languageToUse);

            }).subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                    () -> Timber.d("onPrepare execution successful"),
                    throwable -> Timber.e(throwable, "Error in onPrepare: ")
            );
        }

        @Override
        public void onPlay() {
            Timber.d("onPlay called");
            ttsPlayer.setPausedManually(false);
            if (ttsPlayer.isPreparing()) {
                Timber.d("onPlay: Player is preparing, show feedback and wait for setupTts()");
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.showFakeLoading());
                return;
            }
            ttsPlayer.setupMediaPlayer(false);
            play();
        }

        @Override
        public void onPause() {
            Timber.d("onPause called");
            if (ttsPlayer != null) {
                ttsPlayer.setPausedManually(true);
                ttsPlayer.pauseTts();
                ttsPlayer.pauseMediaPlayer();
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }
        }

        @Override
        public void onStop() {
            Timber.d("onStop called");

            if (ttsPlayer != null) {
                ttsPlayer.stop();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            ttsPlaylist.updatePlayingId(0);
            mediaSession.setActive(false);
            stopSelf();
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        }

        @Override
        public void onSkipToNext() {
            Timber.d("onSkipToNext called");

            if (ttsPlayer != null) {
                ttsPlayer.stopTtsPlayback();
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING);
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.showFakeLoading());
            }

            if (ttsPlaylist.skipNext()) {
                preparedData = ttsPlaylist.getCurrentMetadata();
                if (preparedData != null) {
                    sharedPreferencesRepository.setCurrentReadingEntryId(
                            Long.parseLong(preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
                    );
                    mediaSession.setMetadata(preparedData);
                }
                onPrepare(true);
            } else {
                if (ttsPlayer != null) {
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
                    ttsPlayer.stopMediaPlayer();
                }
            }
        }

        @Override
        public void onSkipToPrevious() {
            Timber.d("onSkipToPrevious called");

            if (ttsPlayer != null) {
                ttsPlayer.stopTtsPlayback();
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING);
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.showFakeLoading());
            }

            if (ttsPlaylist.skipPrevious()) {
                preparedData = ttsPlaylist.getCurrentMetadata();
                if (preparedData != null) {
                    sharedPreferencesRepository.setCurrentReadingEntryId(
                            Long.parseLong(preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
                    );
                    mediaSession.setMetadata(preparedData);
                }
                onPrepare(true);
            } else {
                if (ttsPlayer != null) {
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
                    ttsPlayer.stopMediaPlayer();
                }
            }
        }

        @Override
        public void onFastForward() {
            Timber.d("onFastForward called");

            if (ttsPlayer != null) {
                ttsPlayer.fastForward();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onRewind() {
            Timber.d("onRewind called");

            if (ttsPlayer != null) {
                ttsPlayer.fastRewind();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            super.onCustomAction(action, extras);
            Timber.d("onCustomAction: Action = " + action);
            switch (action) {
                case "autoPlay":
                    play();
                    break;
                case "playFromService":
                    if (preparedData == null) {
                        onPrepare();
                    } else {
                        if (!ttsPlayer.isUiControlPlayback()) {
                            ttsPlayer.play();
                        }
                    }
                    break;
                default:
                    Timber.w("Unhandled custom action: " + action);
            }
        }

        private void play() {
            long currentReadingId = sharedPreferencesRepository.getCurrentReadingEntryId();
            
            // Check if preparedData actually matches the article we want to play
            boolean isMetadataStale = true;
            if (preparedData != null) {
                String mediaIdStr = preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                if (mediaIdStr != null) {
                    try {
                        long preparedMediaId = Long.parseLong(mediaIdStr);
                        if (preparedMediaId == currentReadingId) {
                            isMetadataStale = false;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (isMetadataStale || ttsPlayer.getCurrentId() != currentReadingId) {
                Timber.d("play: Metadata stale or player mismatch. Calling onPrepare.");
                onPrepare();
            } else {
                ttsPlayer.play();
                ttsPlayer.setUiControlPlayback(false);
            }
        }

        public void onPlayPause() {
            Timber.d("onPlayPause called");
            if (ttsPlayer.isPlaying()) {
                onPause();
            } else {
                onPlay();
            }
        }

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
            Timber.d("onMediaButtonEvent: intent=" + mediaButtonIntent);
            return super.onMediaButtonEvent(mediaButtonIntent);
        }

        private void updatePlaybackState(int state) {
            PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(
                    PlaybackStateCompat.ACTION_PLAY |
                            PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_STOP |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_FAST_FORWARD |
                            PlaybackStateCompat.ACTION_REWIND
            );
            stateBuilder.setState(state, 0, 1.0f, SystemClock.elapsedRealtime());
            mediaSession.setPlaybackState(stateBuilder.build());

            if (ttsPlayer != null) {
                if (state == PlaybackStateCompat.STATE_BUFFERING || state == PlaybackStateCompat.STATE_CONNECTING) {
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.showFakeLoading());
                } else {
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> {
                        ttsPlayer.hideFakeLoading();
                    });
                }
            }
            Timber.d("PlaybackState updated to: " + state);
        }
    };

    public class TtsPlayerListener extends PlaybackStateListener {

        private final ServiceManager serviceManager;

        TtsPlayerListener() {
            serviceManager = new ServiceManager();
        }

        @Override
        public void
        onPlaybackStateChange(PlaybackStateCompat state) {
            if (mediaSession != null) {
                mediaSession.setPlaybackState(state);

                // Force metadata update on state change to ensure UI is in sync
                if (preparedData != null) {
                    mediaSession.setMetadata(preparedData);
                }

                switch (state.getState()) {
                    case PlaybackStateCompat.STATE_PLAYING:
                    case PlaybackStateCompat.STATE_BUFFERING:
                        if (!mediaSession.isActive()) {
                            mediaSession.setActive(true);
                        }
                        serviceManager.moveServiceToStartedState(state);
                        break;
                    case PlaybackStateCompat.STATE_PAUSED:
                        serviceManager.updateNotificationForPause(state);
                        break;
                    case PlaybackStateCompat.STATE_STOPPED:
                        serviceManager.moveServiceOutOfStartedState(state);
                        break;
                }
            }
        }

        class ServiceManager {

            private final Intent intent = new Intent(TtsService.this, TtsService.class);

            private void moveServiceToStartedState(PlaybackStateCompat state) {
                Timber.d("notification to play/buffer");
                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());

                if (!serviceInStartedState) {
                    ContextCompat.startForegroundService(TtsService.this, intent);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification);
                    }
                    serviceInStartedState = true;
                } else {
                    // Even if already started, ensure we are in foreground for buffering/playing
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification);
                    }
                }
            }

            private void updateNotificationForPause(PlaybackStateCompat state) {
                Timber.d("notification to pause");
                
                // Keep the service in foreground even when paused to prevent system from killing it during screen timeout.
                // This is especially important for news reader apps where the user might pause for a long time.
                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification);
                }
                
                // We still notify just in case startForeground didn't refresh it enough (unlikely but safe)
                ttsNotification.getNotificationManager().notify(TtsNotification.TTS_NOTIFICATION_ID, notification);
            }

            private void moveServiceOutOfStartedState(PlaybackStateCompat state) {
                if (serviceInStartedState) {
                    Timber.d("notification destroyed");
                    ttsNotification.getNotificationManager().cancelAll();
                    ServiceCompat.stopForeground(TtsService.this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                    serviceInStartedState = false;
                }
            }
        }
    }
    public static MediaSessionCompat getMediaSession() {
        return mediaSession;
    }
}