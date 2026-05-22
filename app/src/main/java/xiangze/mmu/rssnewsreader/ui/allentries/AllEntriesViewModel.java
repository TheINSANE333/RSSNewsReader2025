package xiangze.mmu.rssnewsreader.ui.allentries;


import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import timber.log.Timber;

import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.feed.FeedRepository;
import xiangze.mmu.rssnewsreader.data.playlist.Playlist;
import xiangze.mmu.rssnewsreader.data.playlist.PlaylistRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.model.EntryListItem;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;

import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;

@HiltViewModel
public class AllEntriesViewModel extends ViewModel {

    private Disposable disposableEntries;
    private Disposable disposableCount;

    private final FeedRepository feedRepository;
    private final EntryRepository entryRepository;
    private final PlaylistRepository playlistRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final TtsExtractor ttsExtractor;
    private final TtsPlayer ttsPlayer;
    private final TextUtil textUtil;
    private final MutableLiveData<List<EntryListItem>> allEntries = new MutableLiveData<>();
    private final MutableLiveData<String> toastMessage = new MutableLiveData<>();
    private final MutableLiveData<Integer> unreadCount = new MutableLiveData<>();
    private final MutableLiveData<List<String>> dailySummaryPrompt = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isSummarizing = new MutableLiveData<>();
    private final LiveData<List<EntryListItem>> liveEntries;

    private long id;

    @Inject
    public AllEntriesViewModel(FeedRepository feedRepository, EntryRepository entryRepository, PlaylistRepository playlistRepository, SharedPreferencesRepository sharedPreferencesRepository, TtsExtractor ttsExtractor, TtsPlayer ttsPlayer, TextUtil textUtil) {
        this.feedRepository = feedRepository;
        this.entryRepository = entryRepository;
        this.playlistRepository = playlistRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.ttsExtractor = ttsExtractor;
        this.ttsPlayer = ttsPlayer;
        this.textUtil = textUtil;

        liveEntries = entryRepository.getAllEntriesListLive();

        getEntriesByFeed(0, "all");
    }

    public LiveData<List<EntryListItem>> getLiveEntries() {
        return liveEntries;
    }

    public LiveData<List<String>> getDailySummaryPromptResult() {
        return dailySummaryPrompt;
    }

    public LiveData<Boolean> getIsSummarizing() {
        return isSummarizing;
    }

    public Flowable<List<xiangze.mmu.rssnewsreader.data.feed.Feed>> getFeedsWithUnreadArticles() {
        return feedRepository.getFeedsWithUnreadArticles();
    }

    public void generateDailySummary(List<Long> feedIds) {
        isSummarizing.postValue(true);

        entryRepository.getUnreadEntriesForFeedsEntity(feedIds)
                .firstOrError()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(entries -> {
                    if (entries.isEmpty()) {
                        toastMessage.postValue("No unread articles found in selected feeds.");
                        isSummarizing.postValue(false);
                        return;
                    }
                    String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
                    List<String> prompts = textUtil.getDailySummaryPrompts(entries, targetLang);
                    dailySummaryPrompt.postValue(prompts);
                    isSummarizing.postValue(false);
                }, throwable -> {
                    Timber.e(throwable, "Error generating daily summary prompt");
                    toastMessage.postValue("Failed to generate summary prompt: " + throwable.getMessage());
                    isSummarizing.postValue(false);
                });
    }

    public void resetDailySummaryPrompt() {
        dailySummaryPrompt.postValue(null);
    }

    public String getSortBy() {
        return sharedPreferencesRepository.getSortBy();
    }

    public void setSortBy(String sortBy) {
        sharedPreferencesRepository.setSortBy(sortBy);
    }

    public void getEntriesByFeed(long id, String filter) {
        if (disposableEntries != null && !disposableEntries.isDisposed()) {
            disposableEntries.dispose();
        }
        if (disposableCount != null && !disposableCount.isDisposed()) {
            disposableCount.dispose();
        }

        this.id = id;

        // Note: For simplicity, keeping repository.getEntries as EntryInfo for now if it is used elsewhere, 
        // but converting here or changing it there. Let's see EntryRepository again.
        // Actually, let's update AllEntriesViewModel to use EntryListItem consistently.
        
        disposableEntries = entryRepository.getEntries(id, filter)
                .map(entriesInfo -> {
                    List<EntryListItem> list = new java.util.ArrayList<>();
                    for (EntryInfo info : entriesInfo) {
                        EntryListItem item = new EntryListItem();
                        // Copy fields - this is a bit slow but safer for now than changing all Flowables
                        copyToListItem(info, item);
                        list.add(item);
                    }
                    return list;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(entriesList -> allEntries.postValue(entriesList),
                        throwable -> Timber.e(throwable, "Error fetching entries"));

        disposableCount = entryRepository.getUnreadCount(id, filter)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(integer -> unreadCount.postValue(integer),
                        throwable -> Timber.e(throwable, "Error fetching unread count"));
    }

    private void copyToListItem(EntryInfo info, EntryListItem item) {
        item.setEntryId(info.getEntryId());
        item.setEntryTitle(info.getEntryTitle());
        item.setEntryLink(info.getEntryLink());
        item.setEntryDescription(info.getEntryDescription());
        item.setEntryImageUrl(info.getEntryImageUrl());
        item.setEntryPublishedDate(info.getEntryPublishedDate());
        item.setVisitedDate(info.getVisitedDate());
        item.setBookmark(info.getBookmark());
        item.setPriority(info.getPriority());
        item.setHasContent(info.isHasContent());
        item.setHasOriginalHtml(info.isHasOriginalHtml());
        item.setHasTranslated(info.isHasTranslated());
        item.setHasSummarized(info.isHasSummarized());
        item.setFeedId(info.getFeedId());
        item.setFeedTitle(info.getFeedTitle());
        item.setFeedImageUrl(info.getFeedImageUrl());
        item.setSummarizedSnippet(info.getSummarizedSnippet());
    }

    public LiveData<List<EntryListItem>> getAllEntries() {
        return allEntries;
    }

    public LiveData<String> getToastMessage() {
        return toastMessage;
    }

    public LiveData<Integer> getUnreadCount() {
        return unreadCount;
    }

    public void resetToastMessage() {
        toastMessage.postValue(null);
    }

    public void insertPlaylist(List<Long> allEntryIds, long entryId) {
        Completable.fromAction(() -> {
            Date date = new Date();
            Playlist playlist = new Playlist(date, longListToString(allEntryIds));
            playlistRepository.deleteAllPlaylists();
            playlistRepository.insert(playlist);
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {}
                    @Override
                    public void onComplete() {
                        Timber.d("Playlist inserted successfully");
                    }
                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Error inserting playlist");
                    }
                });
    }

    public void updateVisitedDate(long entryId) {
        Completable.fromAction(() -> {
            Date date = new Date();
            entryRepository.updateDate(date, entryId);
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {}
                    @Override
                    public void onComplete() {
                        Timber.d("Visited date updated successfully");
                    }
                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Error updating visited date");
                    }
                });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (disposableEntries != null) {
            disposableEntries.dispose();
        }
        if (disposableCount != null) {
            disposableCount.dispose();
        }
    }

    public String longListToString(List<Long> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i));
            if (i < list.size() - 1) {
                sb.append(",");
            }
        }
        return sb.toString();
    }

    public void deleteAllVisitedEntries() {
        Completable.fromAction(() -> {
            long currentId = ttsPlayer.getCurrentId();
            if (currentId != 0) {
                List<Long> ids = entryRepository.getAllVisitedEntriesId();
                if (ids.contains(currentId)) {
                    ttsPlayer.stop();
                }
            }

            entryRepository.deleteAllVisitedEntries();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {}
                    @Override
                    public void onComplete() {
                        toastMessage.postValue("All visited entries are deleted");
                    }
                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Error deleting visited entries");
                    }
                });
    }


    public void insertEntry(EntryInfo entryInfo) {
        Completable.fromAction(() -> entryRepository.insert(entryInfo))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {}
                    @Override
                    public void onComplete() {
                        toastMessage.postValue("Entry restored");
                    }
                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Failed to restore entry");
                    }
                });
    }

    public void deleteEntry(long id) {
        Completable.fromAction(new Action() {
                    @Override
                    public void run() throws Throwable {
                        long currentId = ttsPlayer.getCurrentId();
                        if (currentId == id) {
                            ttsPlayer.stop();
                        }
                        entryRepository.deleteById(id);
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@io.reactivex.rxjava3.annotations.NonNull Disposable d) {
                    }

                    @Override
                    public void onComplete() {
                        toastMessage.postValue("All selected entries are deleted");
                    }

                    @Override
                    public void onError(@io.reactivex.rxjava3.annotations.NonNull Throwable e) {
                        Timber.e(e, "Error deleting entry");
                    }
                });
    }

    public void refreshEntries(SwipeRefreshLayout swipeRefreshLayout) {
        Completable.fromAction(new Action() {
                    @Override
                    public void run() throws Throwable {
                        String text = feedRepository.refreshEntries();
                        toastMessage.postValue(text);
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                    }

                    @Override
                    public void onComplete() {
                        swipeRefreshLayout.setRefreshing(false);
                        ttsExtractor.extractAllEntries();
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Error refreshing entries");
                    }
                });
    }

    public void updateBookmark(String bool, long id) {
        Completable.fromAction(new Action() {
                    @Override
                    public void run() throws Throwable {
                        entryRepository.updateBookmark(bool, id);
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {

                    }

                    @Override
                    public void onComplete() {
                        if (bool.equals("Y")) {
                            toastMessage.postValue("Bookmark complete");
                        } else {
                            toastMessage.postValue("Bookmark removed");
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Error updating bookmark");
                    }
                });
    }
}
