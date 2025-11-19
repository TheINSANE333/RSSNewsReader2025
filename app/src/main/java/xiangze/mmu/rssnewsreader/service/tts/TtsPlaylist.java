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
        result.add(
                new MediaBrowserCompat.MediaItem(
                        metadata.getDescription(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        return result;
    }

    public MediaMetadataCompat getCurrentMetadata() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                entryInfo = entryRepository.getLastVisitedEntry();
                content = entryRepository.getContentById(entryInfo.getEntryId());
                html = entryRepository.getHtmlById(entryInfo.getEntryId());
                String translated = entryRepository.getTranslatedTextById(entryInfo.getEntryId());
                try {
                    feedImage = Picasso.get().load(entryInfo.getFeedImageUrl()).get();
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

        metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, Long.toString(entryInfo.getEntryId()))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, entryInfo.getFeedTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, entryInfo.getEntryTitle())
                .putString("link", entryInfo.getEntryLink())
                .putString("content", content)
                .putString("translated", translated)
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
        return playlistRepository.updatePlaylistToPrevious();
    }

    public boolean skipNext() {
        return playlistRepository.updatePlayListToNext();
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