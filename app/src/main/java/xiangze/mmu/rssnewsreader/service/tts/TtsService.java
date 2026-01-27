package xiangze.mmu.rssnewsreader.service.tts;

import android.annotation.SuppressLint;
import android.app.Notification;
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
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;

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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TtsMediaButtonReceiver.handleIntent(mediaSession, intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "created");
        ttsPlayer.initTts(TtsService.this, new TtsPlayerListener(), callback);
        ttsNotification = new TtsNotification(this);

        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(callback);
        mediaSession.setMediaButtonReceiver(null);
        mediaSession.setActive(true);

        mediaSessionInstance = mediaSession;

        PlaybackStateCompat initialState = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_FAST_FORWARD |
                                PlaybackStateCompat.ACTION_REWIND |
                                PlaybackStateCompat.ACTION_STOP
                )
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                .build();
        mediaSession.setPlaybackState(initialState);

        Log.d("TTS", "MediaSession active?" +mediaSession);
        setSessionToken(mediaSession.getSessionToken());
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
                // Priority: Summarized > Translated > Original
                String contentToSpeak;
                boolean useSummarized = false;
                boolean useTranslated = false;

                if (entry.getSummarized() != null && !entry.getSummarized().trim().isEmpty()) {
                    contentToSpeak = entry.getSummarized();
                    useSummarized = true;
                    Log.d(TAG, "Waterfall selection: Summarized Content");
                } else if (entry.getTranslated() != null && !entry.getTranslated().trim().isEmpty()) {
                    contentToSpeak = entry.getTranslated();
                    useTranslated = true;
                    Log.d(TAG, "Waterfall selection: Translated Content");
                } else {
                    contentToSpeak = entry.getContent(); // Original content
                    Log.d(TAG, "Waterfall selection: Original Content");
                }

                // 4. WATERFALL LOGIC: Language Selection
                EntryInfo entryInfo = entryRepository.getEntryInfoById(currentReadingId);
                String feedLanguage = (entryInfo.getFeedLanguage() == null || entryInfo.getFeedLanguage().isEmpty())
                        ? "en" : entryInfo.getFeedLanguage();
                String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
                if (targetLanguage == null || targetLanguage.isEmpty()) targetLanguage = "en";

                // Use target language if we are speaking a summary or a translation
                String languageToUse = (useSummarized || useTranslated) ? targetLanguage : feedLanguage;

                // 5. SYNC STATE: Update SharedPreferences so the UI matches what is being heard
                sharedPreferencesRepository.setIsSummarizedView(currentReadingId, useSummarized);
                sharedPreferencesRepository.setIsTranslatedView(currentReadingId, useTranslated);

                // 6. Setup Media Session and Metadata
                preparedData = ttsPlaylist.getCurrentMetadata();
                if (preparedData == null) {
                    Log.e(TAG, "Metadata is null, cannot proceed with onPrepare");
                    return;
                }

                if (!mediaSession.isActive()) {
                    mediaSession.setActive(true);
                }
                mediaSession.setMetadata(preparedData);

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
                sharedPreferencesRepository.setCurrentReadingEntryId(
                        Long.parseLong(ttsPlaylist.getCurrentMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
                );
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

                sharedPreferencesRepository.setCurrentReadingEntryId(
                        Long.parseLong(ttsPlaylist.getCurrentMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
                );

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
            stateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f, SystemClock.elapsedRealtime());
            mediaSession.setPlaybackState(stateBuilder.build());

            if (ttsPlayer != null) {
                if (state == PlaybackStateCompat.STATE_BUFFERING || state == PlaybackStateCompat.STATE_CONNECTING) {
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> ttsPlayer.showFakeLoading());
                } else {
                    ContextCompat.getMainExecutor(getApplicationContext()).execute(() -> {
                        ttsPlayer.hideFakeLoading();

                        WebViewListener callback = ttsPlayer.getWebViewCallback();
                        if (callback != null) {
                            ContextCompat.getMainExecutor(getApplicationContext()).execute(callback::hideFakeLoading);
                        }
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

                if (Build.VERSION.SDK_INT < 31) {
                    stopForeground(false);
                }

                Notification notification = ttsNotification.getNotification(preparedData, state, getSessionToken());
                ttsNotification.getNotificationManager().notify(TtsNotification.TTS_NOTIFICATION_ID, notification);
            }

            private void moveServiceOutOfStartedState(PlaybackStateCompat state) {
                if (serviceInStartedState) {
                    Log.d(TAG, "notification destroyed");
                    ttsNotification.getNotificationManager().cancelAll();
                    stopForeground(true);
                    serviceInStartedState = false;
                }
            }
        }
    }
    public static MediaSessionCompat getMediaSession() {
        return mediaSession;
    }
}