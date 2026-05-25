package xiangze.mmu.rssnewsreader.service.tts;

import timber.log.Timber;

import android.graphics.Bitmap;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;

import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.playlist.PlaylistRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import com.squareup.picasso.Picasso;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TtsPlaylist {

    private final EntryRepository entryRepository;
    private final PlaylistRepository playlistRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private MediaMetadataCompat metadata;
    private volatile long playingId;

    @Inject
    public TtsPlaylist(EntryRepository entryRepository, PlaylistRepository playlistRepository, SharedPreferencesRepository sharedPreferencesRepository) {
        this.entryRepository = entryRepository;
        this.playlistRepository = playlistRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    public List<MediaBrowserCompat.MediaItem> getMediaItems() {
        List<MediaBrowserCompat.MediaItem> result = new ArrayList<>();
        if (metadata != null) {
            result.add(
                    new MediaBrowserCompat.MediaItem(
                            metadata.getDescription(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return result;
    }

    public MediaMetadataCompat getCurrentMetadata() {
        final EntryInfo[] localEntryInfo = new EntryInfo[1];
        final String[] localContent = new String[1];
        final String[] localHtml = new String[1];
        final String[] localTranslated = new String[1];
        final String[] localSummarized = new String[1];
        final Bitmap[] localFeedImage = new Bitmap[1];

        Thread thread = new Thread(() -> {
            if (entryRepository == null) return;
            if (playingId != 0) {
                localEntryInfo[0] = entryRepository.getEntryInfoById(playingId);
            } else {
                long savedId = sharedPreferencesRepository.getCurrentReadingEntryId();
                if (savedId != -1) {
                    playingId = savedId;
                    localEntryInfo[0] = entryRepository.getEntryInfoById(playingId);
                }

                if (localEntryInfo[0] == null) {
                    localEntryInfo[0] = entryRepository.getLastVisitedEntry();
                    if (localEntryInfo[0] != null) {
                        playingId = localEntryInfo[0].getEntryId();
                    }
                }
            }

            if (localEntryInfo[0] == null) return;

            long entryId = localEntryInfo[0].getEntryId();
            localContent[0] = entryRepository.getContentById(entryId);
            localHtml[0] = entryRepository.getHtmlById(entryId);
            localTranslated[0] = entryRepository.getTranslatedTextById(entryId);
            localSummarized[0] = entryRepository.getSummarizedTextById(entryId);

            try {
                String imageUrl = localEntryInfo[0].getFeedImageUrl();
                if (imageUrl != null && !imageUrl.isEmpty()) {
                    localFeedImage[0] = Picasso.get().load(imageUrl).get();
                }
            } catch (Exception e) {
                // Catching Exception to cover Picasso's ResponseException (for 404s) and other IO issues.
                // Log as Warning instead of Error for 404s to reduce log noise.
                Timber.w("Could not load feed image: " + e.getMessage());
            }
        });
        thread.start();
        try {
            thread.join(5000); // 5 second max wait
        } catch (InterruptedException e) {
            Timber.e(e, "Metadata thread interrupted");
        }

        if (localEntryInfo[0] == null) return null;

        EntryInfo entryInfo = localEntryInfo[0];
        String content = localContent[0];
        String html = localHtml[0];
        String translated = localTranslated[0];
        Bitmap feedImage = localFeedImage[0];
        long dateMillis = entryInfo.getEntryPublishedDate() != null ? entryInfo.getEntryPublishedDate().getTime() : 0L;

        metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, Long.toString(entryInfo.getEntryId()))
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, entryInfo.getEntryTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, entryInfo.getFeedTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, entryInfo.getFeedTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, entryInfo.getEntryTitle())
                .putString("link", entryInfo.getEntryLink())
                .putString("content", content)
                .putString("translated", translated)
                .putString("summarized", localSummarized[0])
                .putString("html", html)
                .putString("language", entryInfo.getFeedLanguage())
                .putLong("date", dateMillis)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, feedImage)
                .putString("feedImageUrl", entryInfo.getFeedImageUrl())
                .putString("entryImageUrl", entryInfo.getEntryImageUrl())
                .putString("bookmark", entryInfo.getBookmark())
                .putLong("feedId", entryInfo.getFeedId())
                .putString("ttsSpeechRate", Float.toString(entryInfo.getTtsSpeechRate()))
                .build();
        return metadata;
    }

    public boolean skipPrevious() {
        long newId = playlistRepository.updatePlaylistToPrevious(playingId);
        if (newId != 0) {
            this.playingId = newId;
            return true;
        }
        return false;
    }

    public boolean skipNext() {
        long newId = playlistRepository.updatePlayListToNext(playingId);
        if (newId != 0) {
            this.playingId = newId;
            return true;
        }
        return false;
    }

    public void updatePlayingId(long id) {
        this.playingId = id;
    }

    public long getPlayingId() {
        return playingId;
    }
}