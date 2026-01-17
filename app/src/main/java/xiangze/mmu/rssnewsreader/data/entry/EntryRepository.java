package xiangze.mmu.rssnewsreader.data.entry;

import android.util.Log;

import androidx.lifecycle.LiveData;

import xiangze.mmu.rssnewsreader.data.history.History;
import xiangze.mmu.rssnewsreader.data.history.HistoryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class EntryRepository {

    private static final String TAG = "EntryRepository";
    private final EntryDao entryDao;
    private final HistoryRepository historyRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final Map<Long, Entry> entryCache = new HashMap<>();

    @Inject
    public EntryRepository(EntryDao entryDao, HistoryRepository historyRepository, SharedPreferencesRepository sharedPreferencesRepository) {
        this.entryDao = entryDao;
        this.historyRepository = historyRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    public List<Entry> getStaticEntries(long id) {
        return entryDao.getStaticEntriesByFeed(id);
    }

    public Flowable<List<EntryInfo>> getEntries(long id, String filter) {
        if (id == 0) {
            switch (filter) {
                case "bookmark":
                    return entryDao.getEntriesByBookmark();
                case "read":
                    return entryDao.getEntriesByRead();
                case "unread":
                    return entryDao.getEntriesByUnread();
                default:
                    return entryDao.getAllEntriesInfo();
            }
        } else {
            switch (filter) {
                case "bookmark":
                    return entryDao.getEntriesByBookmark(id);
                case "read":
                    return entryDao.getEntriesByRead(id);
                case "unread":
                    return entryDao.getEntriesByUnread(id);
                default:
                    return entryDao.getEntriesByFeed(id);
            }
        }
    }

    public long getLastVisitedEntryId() {
        return entryDao.getLastVisitedEntryId();
    }

    public boolean checkIsVisited(long id) {
        Date date = entryDao.checkIsVisited(id);
        return date != null;
    }

    public void clearPriority() {
        entryDao.clearPriority();
    }

    public void updatePriority(int priority, long id) {
        entryDao.updatePriority(priority, id);
    }

    public EntryInfo getLastVisitedEntry() {
        long id = getLastVisitedEntryId();
        return entryDao.getEntryInfoById(id);
    }

    public Entry getEmptyContentEntry() {
        Entry entry = entryDao.getEmptyEntryOrderByPrior();

        if (entry == null) {
            entry = entryDao.getEmptyEntry();
        }

        return entry;
    }

    public void updateContent(String content, long id) {
        entryDao.updateContent(content, id);
    }

    public void updateHtml(String html, long id) {
        entryDao.updateHtml(html, id);
    }

    public void updateTitle(String title, long id, String link) {
        entryDao.updateTitle(id, title, link);
    }

    public void updateTranslatedHtml(String html, long id) {
        entryDao.updateTranslatedHtml(html, id);
    }

    public void updateSummarizedHtml(String html, long id) {
        entryDao.updateSummarizedHtml(html, id);
    }

    public String getTranslatedHtmlById(long id) {
        return entryDao.getTranslatedHtmlById(id);
    }

    public String getSummarizedHtmlById(long id) {
        return entryDao.getSummarizedHtmlById(id);
    }

    public List<Long> getIdsByFeedId(long id) {
        return entryDao.getIdsByFeedId(id);
    }

    public String getContentById(long id) {
        return entryDao.getContentById(id);
    }

    public String getHtmlById(long id) {
        return entryDao.getHtmlById(id);
    }

    public void updateDate(Date date, long entryId) {
        entryDao.updateDate(date, entryId);
    }

    public long insert(long feedId, Entry entry) {
        // Handle updated link or title if the site modifies them
        if (historyRepository.checkTitleExist(feedId, entry.getTitle())) {
            if (!historyRepository.checkLinkExist(feedId, entry.getLink())) {
                entryDao.updateLink(feedId, entry.getTitle(), entry.getLink());
            }
            Log.d(TAG, "Skipped inserting duplicate entry: " + entry.getTitle());
            return -1; // Entry already exists, no new insertion
        } else if (historyRepository.checkLinkExist(feedId, entry.getLink())) {
            if (!historyRepository.checkTitleExist(feedId, entry.getTitle())) {
                entryDao.updateTitle(feedId, entry.getTitle(), entry.getLink());
            }
            Log.d(TAG, "Skipped inserting duplicate entry: " + entry.getTitle());
            return -1; // Entry already exists, no new insertion
        } else {
            // If not in history, insert into history and database
            historyRepository.insert(new History(entry.getFeedId(), new Date(), entry.getTitle(), entry.getLink()));
            long id = entryDao.insert(entry);

            if (id > 0) {
                // Update entry ID and cache it
                entry.setId(id);
                entryCache.put(id, entry); // Add to cache
                Log.d(TAG, "Inserted and cached entry: " + entry.getTitle());
                return id; // Return the new entry ID
            } else {
                Log.e(TAG, "Failed to insert entry: " + entry.getTitle());
                return -1; // Indicate insertion failure
            }
        }
    }


    public void update(Entry entry) {
        entryDao.update(entry)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        Log.d(TAG, "update onSubscribe: called");
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "update onComplete: called");
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.d(TAG, "update onError: " + e.getMessage());
                    }
                });
    }

    public void delete(Entry entry) {
        entryDao.delete(entry)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        Log.d(TAG, "delete onSubscribe: called");
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "delete onComplete: called");
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.d(TAG, "delete onError: " + e.getMessage());
                    }
                });
    }

    public EntryInfo getEntryInfoById(long entryId) {
        return entryDao.getEntryInfoById(entryId);
    }

    public boolean checkIdExist(long id) {
        return entryDao.checkEntryExist(id) != 0;
    }

    public List<Long> getAllVisitedEntriesId() {
        return entryDao.getAllVisitedEntriesId();
    }

    public void deleteAllVisitedEntries() {
        entryDao.deleteAllVisitedEntries();
    }

    public void deleteByIds(List<Long> ids) {
        entryDao.deleteByIds(ids);
    }

    public void deleteById(long id) {
        entryDao.deleteById(id);
    }

    public void deleteByFeedId(long feedId) {
        entryDao.deleteByFeedId(feedId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        Log.d(TAG, "deleteAllEntries onSubscribe: called");
                    }

                    @Override
                    public void onComplete() {
                        Log.d(TAG, "deleteAllEntries onComplete: called");
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.d(TAG, "deleteAllEntries onError: " + e.getMessage());
                    }
                });
    }

    public void preloadEntries(List<Entry> entries) {
        for (Entry entry : entries) {
            if (!entry.isCached()) {
                entry.setCached(true);
                entryDao.updatePreloadStatus(entry.getId(), true);
                entryCache.put(entry.getId(), entry);
                Log.d(TAG, "Preloaded and cached entry: " + entry.getTitle());
            } else {
                Log.d(TAG, "Preload skipped: Entry is already cached.");
            }
        }
    }

    public void preloadEntry(Entry entry) {
        if (entry == null) {
            Log.w(TAG, "Preload skipped: Entry is null.");
            return;
        }

        if (!entry.isCached()) {
            entry.setCached(true);
            entryDao.updatePreloadStatus(entry.getId(), true);
            Log.d(TAG, "Preloaded entry: " + entry.getTitle());
        } else {
            Log.d(TAG, "Preload skipped: Entry is already cached.");
        }
    }

    public List<Entry> getPreloadedEntries() {
        return entryDao.getPreloadedEntries();
    }

    public Entry getCachedEntry(long entryId) {
        return entryCache.getOrDefault(entryId, null);
    }

    public boolean hasEmptyContentEntries() {
        return entryDao.getEmptyEntryOrderByPrior() != null || entryDao.getEmptyEntry() != null;
    }

    public void requeueMissingEntries() {
        entryDao.requeueMissingEntries();
    }

    public void updateSentCount(int sentCount, long id) {
        Log.d(TAG, "speak: " + sentCount);
        entryDao.updateSentCount(sentCount, id);
    }

    public void updateSentCountByLink(int sentCount, long id) {
        entryDao.updateSentCountByLink(sentCount, id);
    }

    public int getSentCount(long id) {
        return entryDao.getSentCount(id);
    }

    public void updateBookmark(String bool, long id) {
        entryDao.updateBookmark(bool, id);
    }

    public void reExtractContent(long feedId) {
        entryDao.updateContentByFeedId(feedId);

        List<Entry> entries = entryDao.getEntriesByFeedId(feedId);
        for (Entry entry : entries) {
            entry.setContent(null);
            entry.setHtml(null);
            entry.setOriginalHtml(null);
            entry.setTranslated(null);
            entry.setTranslatedHtml(null);
            entry.setSummarized(null);
            entry.setSummarizedHtml(null);
            entry.setSentCountStopAt(0);
            entry.setCached(false);
            update(entry);
            entryCache.remove(entry.getId());
        }
    }

    public boolean isBookmark(long id) {
        String bool = entryDao.getBookmark(id);
        return bool != null && !bool.equals("N");
    }

    public Flowable<Integer> getUnreadCount(long id, String filter) {
        if (id == 0) {
            switch (filter) {
                case "bookmark":
                    return entryDao.getUnreadCountByBookmark();
                case "read":
                    return Flowable.just(0);
                default:
                    return entryDao.getUnreadCount();
            }
        } else {
            switch (filter) {
                case "bookmark":
                    return entryDao.getUnreadCountByBookmark(id);
                case "read":
                    return Flowable.just(0);
                default:
                    return entryDao.getUnreadCount(id);
            }
        }
    }

    public void limitEntriesByFeedId(long feedId) {
        int limit = sharedPreferencesRepository.getEntriesLimitPerFeed();
        Log.d("test", "" + limit);
        entryDao.limitEntriesByFeed(feedId, limit);
    }

    public LiveData<List<EntryInfo>> getAllEntriesLive() {
        return entryDao.getAllEntriesInfoLive();
    }

    public List<Entry> getUntranslatedEntries() {
        return entryDao.getUntranslatedEntries();
    }

    public List<Entry> getUnsummarizedEntries() {
        return entryDao.getUnsummarizedEntries();
    }

    public void updateOriginalHtml(String originalHtml, long id) {
        entryDao.updateOriginalHtml(originalHtml, id);
    }

    public String getOriginalHtmlById(long id) {
        return entryDao.getOriginalHtmlById(id);
    }

    public void updateTranslated(String translated, long id) {
        entryDao.updateTranslated(translated, id);
    }

    public void updateSummarized(String summarized, long id) {
        entryDao.updateSummarized(summarized, id);
    }

    public LiveData<Entry> getEntryEntityById(long id) {
        return entryDao.getEntryEntityById(id);
    }

    public Entry getEntryById(long id) {
        return entryDao.getEntryById(id);
    }

    public void updateTranslatedText(String translatedContent, long entryId) {
        entryDao.updateTranslatedText(translatedContent, entryId);

        Entry entry = getEntryById(entryId);
        if (entry != null) {
            entry.setTranslated(translatedContent);
            entryCache.put(entryId, entry);
            Log.d(TAG, "Cache updated with translated text for entry ID: " + entryId);
        }
    }

    public void updateSummarizedText(String summarizedContent, long entryId) {
        entryDao.updateSummarizedText(summarizedContent, entryId);

        Entry entry = getEntryById(entryId);
        if (entry != null) {
            entry.setSummarized(summarizedContent);
            entryCache.put(entryId, entry);
            Log.d(TAG, "Cache updated with summarized text for entry ID: " + entryId);
        }
    }

    public String getTranslatedTextById(long id) {
        Entry entry = entryDao.getEntryById(id);
        return (entry != null) ? entry.getTranslated() : null;
    }

    public String getSummarizedTextById(long id) {
        Entry entry = entryDao.getEntryById(id);
        return (entry != null) ? entry.getSummarized() : null;
    }

}
