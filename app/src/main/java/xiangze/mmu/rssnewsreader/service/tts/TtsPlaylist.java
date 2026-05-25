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
        if (entryRepository == null) return null;

        EntryInfo entryInfo = null;
        if (playingId != 0) {
            entryInfo = entryRepository.getEntryInfoById(playingId);
        }
        
        if (entryInfo == null) {
            long savedId = sharedPreferencesRepository.getCurrentReadingEntryId();
            if (savedId != -1 && savedId != 0) {
                playingId = savedId;
                entryInfo = entryRepository.getEntryInfoById(playingId);
            }
        }

        if (entryInfo == null) {
            // Try fallback to last visited valid entry
            entryInfo = entryRepository.getLastVisitedEntry();
            if (entryInfo != null) {
                playingId = entryInfo.getEntryId();
                sharedPreferencesRepository.setCurrentReadingEntryId(playingId);
            }
        }

        if (entryInfo == null) {
            // Absolute fallback: Any recent entry
            List<EntryInfo> recentEntries = entryRepository.getAllEntriesInfoList();
            if (recentEntries != null && !recentEntries.isEmpty()) {
                entryInfo = recentEntries.get(0);
                playingId = entryInfo.getEntryId();
                sharedPreferencesRepository.setCurrentReadingEntryId(playingId);
            }
        }

        if (entryInfo == null) {
            playingId = 0;
            sharedPreferencesRepository.setCurrentReadingEntryId(0);
            metadata = null;
            return null;
        }

        long entryId = entryInfo.getEntryId();
        String content = entryRepository.getContentById(entryId);
        String html = entryRepository.getHtmlById(entryId);
        String translated = entryRepository.getTranslatedTextById(entryId);
        String summarized = entryRepository.getSummarizedTextById(entryId);
        Bitmap feedImage = null;

        try {
            String imageUrl = entryInfo.getFeedImageUrl();
            if (imageUrl == null || imageUrl.isEmpty()) {
                imageUrl = entryInfo.getEntryImageUrl();
            }
            
            if (imageUrl != null && !imageUrl.isEmpty()) {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    Timber.d("getCurrentMetadata called on UI thread, skipping synchronous image download to avoid crash.");
                } else {
                    feedImage = Picasso.get().load(imageUrl).get();
                }
            }
        } catch (Exception e) {
            Timber.w("Could not load feed image: " + e.getMessage());
        }

        long dateMillis = entryInfo.getEntryPublishedDate() != null ? entryInfo.getEntryPublishedDate().getTime() : 0L;
        
        String displayImageUrl = entryInfo.getFeedImageUrl();
        if (displayImageUrl == null || displayImageUrl.isEmpty()) {
            displayImageUrl = entryInfo.getEntryImageUrl();
        }

        metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, Long.toString(entryInfo.getEntryId()))
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, entryInfo.getEntryTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, entryInfo.getFeedTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, entryInfo.getFeedTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, entryInfo.getEntryTitle())
                .putString("link", entryInfo.getEntryLink())
                .putString("content", content)
                .putString("translated", translated)
                .putString("summarized", summarized)
                .putString("html", html)
                .putString("language", entryInfo.getFeedLanguage())
                .putLong("date", dateMillis)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, feedImage)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, displayImageUrl)
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