package xiangze.mmu.rssnewsreader.service.tts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.NonNull;

public abstract class PlayerAdapter {

    private static final IntentFilter AUDIO_NOISY_INTENT_FILTER =
            new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

    private boolean mAudioNoisyReceiverRegistered = false;
    private final BroadcastReceiver mAudioNoisyReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                        if (isPlayingMediaPlayer()) {
                            pauseMediaPlayer();
                        }
                        if (isPlaying()) {
                            pause();
                        }
                    }
                }
            };

    private final Context mApplicationContext;
    private final AudioManager mAudioManager;
    private final AudioFocusHelper mAudioFocusHelper;

    private boolean mPlayOnAudioFocus = false;

    public PlayerAdapter(@NonNull Context context) {
        mApplicationContext = context.getApplicationContext();
        mAudioManager = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);
        mAudioFocusHelper = new AudioFocusHelper();
    }

    public abstract boolean isPlaying();

    public abstract boolean isPlayingMediaPlayer();

    public final void play() {
        if (mAudioFocusHelper.requestAudioFocus()) {
            registerAudioNoisyReceiver();

            // CRITICAL: Reach back to TtsService to ensure the session is active
            // and its playback state is set to PLAYING.
            if (TtsService.getMediaSession() != null) {
                TtsService.getMediaSession().setActive(true);
            }

            onPlay();
        }
    }

    protected abstract void onPlay();

    public abstract void playMediaPlayer();

    public abstract void pauseMediaPlayer();

    public final void pause() {
        if (!mPlayOnAudioFocus) {
            mAudioFocusHelper.abandonAudioFocus();
        }
        unregisterAudioNoisyReceiver();
        onPause();
    }

    protected abstract void onPause();

    public final void stop() {
        mAudioFocusHelper.abandonAudioFocus();
        unregisterAudioNoisyReceiver();
        onStop();
    }

    protected abstract void onStop();

    private void registerAudioNoisyReceiver() {
        if (!mAudioNoisyReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mApplicationContext.registerReceiver(mAudioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER, Context.RECEIVER_NOT_EXPORTED);
            } else {
                mApplicationContext.registerReceiver(mAudioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER);
            }
            mAudioNoisyReceiverRegistered = true;
        }
    }

    private void unregisterAudioNoisyReceiver() {
        if (mAudioNoisyReceiverRegistered) {
            mApplicationContext.unregisterReceiver(mAudioNoisyReceiver);
            mAudioNoisyReceiverRegistered = false;
        }
    }

    public abstract void setVolume(float volume);

    private final class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {

        private AudioFocusRequest mFocusRequest;

        private boolean requestAudioFocus() {
            // Modern API for API 26+ (Required to beat Spotify's priority)
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(true) // We will handle volume lowering manually
                    .setOnAudioFocusChangeListener(this)
                    .build();

            return mAudioManager.requestAudioFocus(mFocusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        private void abandonAudioFocus() {
            if (mFocusRequest != null) {
                mAudioManager.abandonAudioFocusRequest(mFocusRequest);
                mFocusRequest = null;
            }
        }

        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    // Restore volume if we were ducked
                    setVolume(1.0f);
                    if (mPlayOnAudioFocus && !isPlaying()) {
                        playMediaPlayer();
                        play();
                    }
                    mPlayOnAudioFocus = false;
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Lower the volume (Ducking) - Keep playing but be quiet
                    setVolume(0.2f);
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Temporary loss (e.g. a phone call or Google Assistant)
                    pauseMediaPlayer();
                    if (isPlaying()) {
                        mPlayOnAudioFocus = true;
                        pause();
                    }
                    break;

                case AudioManager.AUDIOFOCUS_LOSS:
                    // Permanent loss (Spotify started playing)
                    mPlayOnAudioFocus = false; // Don't resume automatically
                    setVolume(1.0f); // Reset volume for next time
                    pauseMediaPlayer();
                    pause();
                    break;
            }
        }
    }
}