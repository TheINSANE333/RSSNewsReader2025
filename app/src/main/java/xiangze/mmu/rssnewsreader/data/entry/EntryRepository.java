package xiangze.mmu.rssnewsreader.data.entry;

import timber.log.Timber;

import androidx.lifecycle.LiveData;

import xiangze.mmu.rssnewsreader.data.history.History;
import xiangze.mmu.rssnewsreader.data.history.HistoryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class EntryRepository {

    private final EntryDao entryDao;
    private final HistoryRepository historyRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final TextUtil textUtil;
    private static final int MAX_CACHE_SIZE = 100;
    private final Map<Long, Entry> entryCache = new LinkedHashMap<Long, Entry>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, xiangze.mmu.rssnewsreader.data.entry.Entry> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    @Inject
    public EntryRepository(EntryDao entryDao, HistoryRepository historyRepository, SharedPreferencesRepository sharedPreferencesRepository, TextUtil textUtil) {
        this.entryDao = entryDao;
        this.historyRepository = historyRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.textUtil = textUtil;
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

    public Flowable<List<EntryInfo>> getUnreadEntriesForSummarization() {
        return entryDao.getUnreadEntriesForSummarization();
    }

    public Flowable<List<EntryInfo>> getUnreadEntriesForFeeds(List<Long> feedIds) {
        return entryDao.getUnreadEntriesForFeeds(feedIds);
    }

    public Flowable<List<Entry>> getUnreadEntriesForFeedsEntity(List<Long> feedIds) {
        return entryDao.getUnreadEntriesForFeedsEntity(feedIds);
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

    public List<EntryInfo> getAllUnsummarizedEntries() {
        return entryDao.getUnsummarizedEntriesInfo();
    }

    public List<EntryInfo> getUnsummarizedEntriesByFeed(long feedId) {
        return entryDao.getUnsummarizedEntriesByFeed(feedId);
    }

    public List<EntryInfo> getAllUntranslatedEntries() {
        return entryDao.getUntranslatedEntriesInfo();
    }

    public List<EntryInfo> getUntranslatedEntriesByFeed(long feedId) {
        return entryDao.getUntranslatedEntriesByFeed(feedId);
    }

    public List<Entry> getUncachedEntries() {
        return entryDao.getUncachedEntries();
    }

    public void markAsCached(long id) {
        entryDao.updatePreloadStatus(id, true);
    }

    public EntryInfo getEntryInfoById(long id) {
        return entryDao.getEntryInfoById(id);
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
        String normalizedLink = textUtil.normalizeUrl(entry.getLink());
        
        // Handle updated link or title if the site modifies them
        if (historyRepository.checkTitleExist(feedId, entry.getTitle())) {
            if (!historyRepository.checkLinkExist(feedId, normalizedLink)) {
                entryDao.updateLink(feedId, entry.getTitle(), entry.getLink());
            }
            Timber.d("Skipped inserting duplicate entry (by title): " + entry.getTitle());
            return -1; // Entry already exists, no new insertion
        } else if (historyRepository.checkLinkExist(feedId, normalizedLink)) {
            if (!historyRepository.checkTitleExist(feedId, entry.getTitle())) {
                entryDao.updateTitle(feedId, entry.getTitle(), entry.getLink());
            }
            Timber.d("Skipped inserting duplicate entry (by link): " + entry.getTitle());
            return -1; // Entry already exists, no new insertion
        } else {
            // If not in history, insert into history and database
            historyRepository.insert(new History(entry.getFeedId(), new Date(), entry.getTitle(), normalizedLink));
            long id = entryDao.insert(entry);

            if (id > 0) {
                // Update entry ID and cache it
                entry.setId(id);
                entryCache.put(id, entry); // Add to cache
                Timber.d("Inserted and cached entry: " + entry.getTitle());
                return id; // Return the new entry ID
            } else {
                Timber.e("Failed to insert entry: " + entry.getTitle());
                return -1; // Indicate insertion failure
            }
        }
    }

    public void insert(EntryInfo info) {
        Entry entry = getEntryById(info.getEntryId());
        if (entry == null) {
            entry = new Entry(info.getFeedId(), info.getEntryTitle(), info.getEntryLink(), info.getEntryDescription(), info.getEntryImageUrl(), info.getEntryCategory(), info.getEntryPublishedDate());
            entry.setId(info.getEntryId());
        }
        entry.setBookmark(info.getBookmark());
        entry.setVisitedDate(info.getVisitedDate());
        
        historyRepository.insert(new History(entry.getFeedId(), new Date(), entry.getTitle(), textUtil.normalizeUrl(entry.getLink())));
        entryDao.insert(entry);
        entryCache.remove(entry.getId());
    }

    public void update(Entry entry) {
        compositeDisposable.add(
            entryDao.update(entry)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    () -> Timber.d("update onComplete: called"),
                    e -> Timber.e(e, "update onError: ")
                )
        );
    }

    public void delete(Entry entry) {
        compositeDisposable.add(
            entryDao.delete(entry)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    () -> Timber.d("delete onComplete: called"),
                    e -> Timber.e(e, "delete onError: ")
                )
        );
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

    public Completable deleteByFeedId(long feedId) {
        return entryDao.deleteByFeedId(feedId);
    }

    public void preloadEntries(List<Entry> entries) {
        for (Entry entry : entries) {
            if (!entry.isCached()) {
                entry.setCached(true);
                entryDao.updatePreloadStatus(entry.getId(), true);
                entryCache.put(entry.getId(), entry);
                Timber.d("Preloaded and cached entry: " + entry.getTitle());
            } else {
                Timber.d("Preload skipped: Entry is already cached.");
            }
        }
    }

    public void preloadEntry(Entry entry) {
        if (entry == null) {
            Timber.w("Preload skipped: Entry is null.");
            return;
        }

        if (!entry.isCached()) {
            entry.setCached(true);
            entryDao.updatePreloadStatus(entry.getId(), true);
            Timber.d("Preloaded entry: " + entry.getTitle());
        } else {
            Timber.d("Preload skipped: Entry is already cached.");
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
        compositeDisposable.add(
            Completable.fromAction(() -> {
                Timber.d("updateSentCount: " + sentCount + " for ID: " + id);
                entryDao.updateSentCount(sentCount, id);
            }).subscribeOn(Schedulers.io())
              .subscribe(
                  () -> {},
                  throwable -> Timber.e(throwable, "Error updating sent count")
              )
        );
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
        Timber.d("limitEntriesByFeedId: limit=" + limit);
        entryDao.limitEntriesByFeed(feedId, limit);
    }

    public LiveData<List<EntryInfo>> getAllEntriesListLive() {
        return entryDao.getAllEntriesListLive();
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

    public void updateSummarizedResult(long id, String html, String summarized, String summarizedHtml) {
        entryDao.updateSummarizedResult(id, html, summarized, summarizedHtml);
    }

    public void updateSummarizedPair(long id, String summarized, String summarizedHtml) {
        entryDao.updateSummarizedPair(id, summarized, summarizedHtml);
        Entry entry = entryCache.get(id);
        if (entry != null) {
            entry.setSummarized(summarized);
            entry.setSummarizedHtml(summarizedHtml);
        }
    }

    public void updateTranslatedPair(long id, String translated, String translatedHtml) {
        entryDao.updateTranslatedPair(id, translated, translatedHtml);
        Entry entry = entryCache.get(id);
        if (entry != null) {
            entry.setTranslated(translated);
            entry.setTranslatedHtml(translatedHtml);
        }
    }

    public void updateTranslatedResult(long id, String html, String translated, String translatedHtml, String title) {
        entryDao.updateTranslatedResult(id, html, translated, translatedHtml, title);
    }

    public Entry getEntryById(long id) {
        return entryDao.getEntryById(id);
    }

    public void updateTranslatedText(String translatedContent, long entryId) {
        entryDao.updateTranslated(translatedContent, entryId);

        Entry entry = getEntryById(entryId);
        if (entry != null) {
            entry.setTranslated(translatedContent);
            entryCache.put(entryId, entry);
            Timber.d("Cache updated with translated text for entry ID: " + entryId);
        }
    }

    public void updateSummarizedText(String summarizedContent, long entryId) {
        entryDao.updateSummarized(summarizedContent, entryId);

        Entry entry = getEntryById(entryId);
        if (entry != null) {
            entry.setSummarized(summarizedContent);
            entryCache.put(entryId, entry);
            Timber.d("Cache updated with summarized text for entry ID: " + entryId);
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

    public void resetSummarized(long id) {
        entryDao.resetSummarized(id);
    }

    public void resetTranslated(long id) {
        entryDao.resetTranslated(id);
    }

    public void dispose() {
        compositeDisposable.clear();
    }

}
