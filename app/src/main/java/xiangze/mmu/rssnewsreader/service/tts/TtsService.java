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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
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
    private static MediaSessionCompat mediaSessionInstance;

//    @Override
//    public int onStartCommand(Intent intent, int flags, int startId) {
//        TtsMediaButtonReceiver.handleIntent(mediaSession, intent);
//        return super.onStartCommand(intent, flags, startId);
//    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            // This helper is smart: it finds your mediaSession and
            // manually triggers the correct Callback method (onPlay, onPause, etc.)
            MediaButtonReceiver.handleIntent(mediaSession, intent);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Initializing TtsService");

        // 1. Notification First (Prevents NPE)
        ttsNotification = new TtsNotification(this);

        // 2. Setup Media Button Receiver Component
        ComponentName mbrComponent = new ComponentName(getPackageName(), TtsMediaButtonReceiver.class.getName());

        // 3. Initialize MediaSession with Receiver Component
        mediaSession = new MediaSessionCompat(this, TAG, mbrComponent, null);

        // 4. Create the PendingIntent for Hardware Buttons
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(mbrComponent);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent mbrPendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, flags);

        // 5. Configure Session Properties
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
        mediaSessionInstance = mediaSession;
        setSessionToken(mediaSession.getSessionToken());

        Log.d(TAG, "onCreate: Service ready. active=" + mediaSession.isActive());
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "destroyed");
        ttsPlayer.stop();
        mediaSession.release();
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
            Log.d(TAG, "onPrepare called - Resolving Waterfall Content");

            Completable.fromAction(() -> {
                // 1. Initialize TTS Engine and Player
                if (!ttsPlayer.isPausedManually()) {
                    ttsPlayer.setupMediaPlayer(false);
                }
                if (ttsPlayer.ttsIsNull()) {
                    ttsPlayer.initTts(TtsService.this, new TtsPlayerListener(), callback);
                }

                // 2. Fetch the current Entry from Database
                long currentReadingId = sharedPreferencesRepository.getCurrentReadingEntryId();
                Entry entry = entryRepository.getEntryById(currentReadingId);

                if (entry == null) {
                    Log.w(TAG, "Entry not found for ID: " + currentReadingId);
                    return;
                }

                // 3. WATERFALL LOGIC: Content Selection
                // Priority: Preference > Summarized > Translated > Original
                String contentToSpeak;
                boolean useSummarized = false;
                boolean useTranslated = false;

                boolean hasSummary = entry.getSummarized() != null && !entry.getSummarized().trim().isEmpty();
                boolean hasTranslation = entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty();

                if (sharedPreferencesRepository.hasSummarizationToggle(currentReadingId) ||
                        sharedPreferencesRepository.hasTranslationToggle(currentReadingId)) {
                    // USE SAVED PREFERENCE
                    useSummarized = sharedPreferencesRepository.getIsSummarizedView(currentReadingId) && hasSummary;
                    useTranslated = !useSummarized && sharedPreferencesRepository.getIsTranslatedView(currentReadingId) && hasTranslation;
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
                    Log.d(TAG, "Selection: Summarized Content");
                } else if (useTranslated) {
                    contentToSpeak = entry.getTranslated();
                    Log.d(TAG, "Selection: Translated Content");
                } else {
                    contentToSpeak = entry.getContent(); // Original content
                    Log.d(TAG, "Selection: Original Content");
                }

                // 4. WATERFALL LOGIC: Language Selection
                EntryInfo entryInfo = entryRepository.getEntryInfoById(currentReadingId);
                String feedLanguage = (entryInfo.getFeedLanguage() == null || entryInfo.getFeedLanguage().isEmpty())
                        ? "en" : entryInfo.getFeedLanguage();
                String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
                if (targetLanguage == null || targetLanguage.isEmpty()) targetLanguage = "en";

                // Use target language if we are speaking a summary or a translation
                String languageToUse = (useSummarized || useTranslated) ? targetLanguage : feedLanguage;

                // 5. SYNC STATE: (Removed forcing of prefs here, as it overrides user manual choice)
                // sharedPreferencesRepository.setIsSummarizedView(currentReadingId, useSummarized);
                // sharedPreferencesRepository.setIsTranslatedView(currentReadingId, useTranslated);

                // 6. Setup Media Session and Metadata
                preparedData = ttsPlaylist.getCurrentMetadata();
                if (preparedData == null) {
                    Log.e(TAG, "Metadata is null, cannot proceed with onPrepare");
                    return;
                }

                if (!mediaSession.isActive()) {
                    mediaSession.setActive(true);
                }
//                mediaSession.setMetadata(preparedData);

//                mediaSession.setMetadata(new MediaMetadataCompat.Builder()
//                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "RSS News Reader")
//                        .build());
                // Map preparedData fields to the MediaMetadataCompat Builder
//                mediaSession.setMetadata(new MediaMetadataCompat.Builder()
//                        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
//                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, preparedData.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
//                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, preparedData.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
//                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "RSS News Reader") // Static or from preparedData
//                        // Add more keys if needed, like durations or display icons
//                        .build());

                MediaMetadataCompat newMetadata =
                        new MediaMetadataCompat.Builder(preparedData)   // clone EVERYTHING
                                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "RSS News Reader")
                                .build();

                mediaSession.setMetadata(newMetadata);

                        // Apply speech rate settings
                String rateStr = preparedData.getString("ttsSpeechRate");
                float rate = (rateStr != null) ? Float.parseFloat(rateStr) : 1.0f;
                ttsPlayer.setTtsSpeechRate(rate);

                // 7. Extract and Play
                long mediaId = Long.parseLong(preparedData.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID));
                long feedId = preparedData.getLong("feedId");

                // Safety check to ensure we aren't loading content for a different article
                if (mediaId != currentReadingId) {
                    Log.d(TAG, "Skipping extract() — mediaId mismatch");
                    return;
                }

                ttsPlayer.stopTtsPlayback();
                ttsPlayer.extract(mediaId, feedId, contentToSpeak, languageToUse);

                if (!ttsPlayer.isPausedManually()) {
                    ttsPlayer.speak();
                }

                Log.d(TAG, "TTS Extraction complete for ID: " + mediaId + " Language: " + languageToUse);

            }).subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                    () -> Log.d(TAG, "onPrepare execution successful"),
                    throwable -> Log.e(TAG, "Error in onPrepare: ", throwable)
            );
        }

        @Override
        public void onPlay() {
            Log.d(TAG, "onPlay called");
            if (!ttsPlayer.isPreparing()) {
                ttsPlayer.setPausedManually(false);
                ttsPlayer.setupMediaPlayer(false);
                play();
            }
        }

        @Override
        public void onPause() {
            Log.d(TAG, "onPause called");
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
            Log.d(TAG, "onStop called");

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
            Log.d(TAG, "onSkipToNext called");

            if (ttsPlayer != null) {
                ttsPlayer.stopTtsPlayback();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.showFakeLoading());
            }

            if (ttsPlaylist.skipNext()) {
                preparedData = null;
                MediaMetadataCompat metadata = ttsPlaylist.getCurrentMetadata();
                if (metadata != null) {
                    sharedPreferencesRepository.setCurrentReadingEntryId(
                            Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
                    );
                }
                onPrepare();
            } else {
                if (ttsPlayer != null) {
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
                    ttsPlayer.stopMediaPlayer();
                }
            }
        }

        @Override
        public void onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious called");

            if (ttsPlayer != null) {
                ttsPlayer.stopTtsPlayback();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.showFakeLoading());
            }

            if (ttsPlaylist.skipPrevious()) {
                preparedData = null;
                MediaMetadataCompat metadata = ttsPlaylist.getCurrentMetadata();
                if (metadata != null) {
                    sharedPreferencesRepository.setCurrentReadingEntryId(
                            Long.parseLong(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
                    );
                }
                onPrepare();
            } else {
                if (ttsPlayer != null) {
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
                    ttsPlayer.stopMediaPlayer();
                }
            }
        }

        @Override
        public void onFastForward() {
            Log.d(TAG, "onFastForward called");

            if (ttsPlayer != null) {
                ttsPlayer.fastForward();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onRewind() {
            Log.d(TAG, "onRewind called");

            if (ttsPlayer != null) {
                ttsPlayer.fastRewind();
                ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.hideFakeLoading());
            }

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            super.onCustomAction(action, extras);
            Log.d(TAG, "onCustomAction: Action = " + action);
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
                    Log.w(TAG, "Unhandled custom action: " + action);
            }
        }

        private void play() {
            if (preparedData == null) {
                onPrepare();
            } else {
                ttsPlayer.play();
                ttsPlayer.setUiControlPlayback(false);
            }
        }

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
            Log.d("MediaSession", "Media button event received: " + mediaButtonIntent);
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
            Log.d("TTS", "PlaybackState updated to: " + state);
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
            mediaSession.setPlaybackState(state);

            switch (state.getState()) {
                case PlaybackStateCompat.STATE_PLAYING:
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

        class ServiceManager {

            private final Intent intent = new Intent(TtsService.this, TtsService.class);

            private void moveServiceToStartedState(PlaybackStateCompat state) {
                Log.d(TAG, "notification to play");
                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());

                if (!serviceInStartedState) {
                    ContextCompat.startForegroundService(TtsService.this, intent);
                    startForeground(TtsNotification.TTS_NOTIFICATION_ID, notification);
                    serviceInStartedState = true;
                } else {
                    ttsNotification.getNotificationManager().notify(TtsNotification.TTS_NOTIFICATION_ID, notification);
                }
            }

            private void updateNotificationForPause(PlaybackStateCompat state) {

                Log.d(TAG, "notification to pause");

                ServiceCompat.stopForeground(TtsService.this, ServiceCompat.STOP_FOREGROUND_DETACH);

                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());
                ttsNotification.getNotificationManager().notify(TtsNotification.TTS_NOTIFICATION_ID, notification);
            }

            private void moveServiceOutOfStartedState(PlaybackStateCompat state) {
                if (serviceInStartedState) {
                    Log.d(TAG, "notification destroyed");
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