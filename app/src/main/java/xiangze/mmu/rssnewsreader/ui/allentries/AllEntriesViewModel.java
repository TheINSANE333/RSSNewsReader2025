package xiangze.mmu.rssnewsreader.ui.allentries;


import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.feed.FeedRepository;
import xiangze.mmu.rssnewsreader.data.playlist.Playlist;
import xiangze.mmu.rssnewsreader.data.playlist.PlaylistRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;
import xiangze.mmu.rssnewsreader.model.EntryInfo;

import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;

@HiltViewModel
public class AllEntriesViewModel extends ViewModel {

    private Disposable disposableEntries;
    private Disposable disposableCount;

    private FeedRepository feedRepository;
    private EntryRepository entryRepository;
    private PlaylistRepository playlistRepository;
    private SharedPreferencesRepository sharedPreferencesRepository;
    private TtsExtractor ttsExtractor;
    private TtsPlayer ttsPlayer;
    private MutableLiveData<List<EntryInfo>> allEntries = new MutableLiveData<>();
    private MutableLiveData<String> toastMessage = new MutableLiveData<>();
    private MutableLiveData<Integer> unreadCount = new MutableLiveData<>();
    private LiveData<List<EntryInfo>> liveEntries;

    private String filter = "all";
    private long id;

    @Inject
    public AllEntriesViewModel(FeedRepository feedRepository, EntryRepository entryRepository, PlaylistRepository playlistRepository, SharedPreferencesRepository sharedPreferencesRepository, TtsExtractor ttsExtractor, TtsPlayer ttsPlayer) {
        this.feedRepository = feedRepository;
        this.entryRepository = entryRepository;
        this.playlistRepository = playlistRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.ttsExtractor = ttsExtractor;
        this.ttsPlayer = ttsPlayer;

        liveEntries = entryRepository.getAllEntriesLive();

        getEntriesByFeed(0, "all");
    }

    public String getSortBy() {
        return sharedPreferencesRepository.getSortBy();
    }

    public void setSortBy(String sortBy) {
        sharedPreferencesRepository.setSortBy(sortBy);
    }

    public void getEntriesByFeed(long id, String filter) {
        if (disposableEntries != null && disposableCount != null && !disposableCount.isDisposed() && !disposableEntries.isDisposed()) {
            disposableEntries.dispose();
            disposableCount.dispose();
        }

        this.id = id;

        disposableEntries = entryRepository.getEntries(id, filter)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<List<EntryInfo>>() {
                    @Override
                    public void accept(List<EntryInfo> entriesInfo) throws Throwable {
                        allEntries.postValue(entriesInfo);
                    }
                });

        disposableCount = entryRepository.getUnreadCount(id, filter)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<Integer>() {
                    @Override
                    public void accept(Integer integer) throws Throwable {
                        unreadCount.postValue(integer);
                    }
                });
    }

    public LiveData<List<EntryInfo>> getAllEntries() {
        return allEntries;
    }

    public LiveData<String> getToastMessage() {
        return toastMessage;
    }

    public LiveData<List<EntryInfo>> getLiveEntries() {
        return liveEntries;
    }

    public LiveData<Integer> getUnreadCount() {
        return unreadCount;
    }

    public void resetToastMessage() {
        toastMessage.postValue(null);
    }

    public void insertPlaylist(List<Long> allEntryIds, long entryId) {
        Date date = new Date();

        Playlist playlist = new Playlist(date, longListToString(allEntryIds));
        playlistRepository.deleteAllPlaylists();
        playlistRepository.insert(playlist);
    }

    public void updateVisitedDate(long entryId) {
        Date date = new Date();
        entryRepository.updateDate(date, entryId);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        disposableEntries.dispose();
        disposableCount.dispose();
    }

    public String longListToString(List<Long> list) {
        String genreIds = "";
        if (!list.isEmpty()) {
            genreIds = list.get(0).toString();
            list.remove(0);
            for (long s : list) {
                genreIds += "," + s;
            }
        }
        return genreIds;
    }

    public void deleteAllVisitedEntries() {
        Completable.fromAction(new Action() {
                    @Override
                    public void run() throws Throwable {
                        long currentId = ttsPlayer.getCurrentId();
                        if (currentId != 0) {
                            List<Long> ids = entryRepository.getAllVisitedEntriesId();
                            if (ids.contains(currentId)) {
                                ttsPlayer.stop();
                            }
                        }

                        entryRepository.deleteAllVisitedEntries();
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {

                    }

                    @Override
                    public void onComplete() {
                        toastMessage.postValue("All visited entries are deleted");
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {

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

                    }
                });
    }
}
