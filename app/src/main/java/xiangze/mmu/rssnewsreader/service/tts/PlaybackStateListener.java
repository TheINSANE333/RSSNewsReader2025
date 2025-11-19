package xiangze.mmu.rssnewsreader.service.tts;

import android.support.v4.media.session.PlaybackStateCompat;

public abstract class PlaybackStateListener {

    public abstract void onPlaybackStateChange(PlaybackStateCompat state);
}
