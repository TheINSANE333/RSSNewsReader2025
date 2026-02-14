package xiangze.mmu.rssnewsreader.service.tts;

import android.graphics.Bitmap;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;

import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.playlist.PlaylistRepository;
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
    private MediaMetadataCompat metadata;
    private EntryInfo entryInfo;
    private String content;
    private String html;
    private Bitmap feedImage;
    private long playingId;
    private String translated;

    @Inject
    public TtsPlaylist(EntryRepository entryRepository, PlaylistRepository playlistRepository) {
        this.entryRepository = entryRepository;
        this.playlistRepository = playlistRepository;
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

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (entryRepository == null) return;
                if (playingId != 0) {
                    localEntryInfo[0] = entryRepository.getEntryInfoById(playingId);
                } else {
                    localEntryInfo[0] = entryRepository.getLastVisitedEntry();
                    if (localEntryInfo[0] != null) {
                        playingId = localEntryInfo[0].getEntryId();
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
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (localEntryInfo[0] == null) return null;

        this.entryInfo = localEntryInfo[0];
        this.content = localContent[0];
        this.html = localHtml[0];
        this.translated = localTranslated[0];
        this.feedImage = localFeedImage[0];

        metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, Long.toString(entryInfo.getEntryId()))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, entryInfo.getFeedTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, entryInfo.getEntryTitle())
                .putString("link", entryInfo.getEntryLink())
                .putString("content", content)
                .putString("translated", translated)
                .putString("summarized", localSummarized[0])
                .putString("html", html)
                .putString("language", entryInfo.getFeedLanguage())
                .putLong("date", entryInfo.getEntryPublishedDate().getTime())
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
        long newId = playlistRepository.updatePlaylistToPrevious();
        if (newId != 0) {
            this.playingId = newId;
            return true;
        }
        return false;
    }

    public boolean skipNext() {
        long newId = playlistRepository.updatePlayListToNext();
        if (newId != 0) {
            this.playingId = newId;
            return true;
        }
        return false;
    }

    public void updatePlayingIdToLatest() {
        this.playingId = entryRepository.getLastVisitedEntryId();
    }

    public void updatePlayingId(long id) {
        this.playingId = id;
    }

    public long getPlayingId() {
        return playingId;
    }
}