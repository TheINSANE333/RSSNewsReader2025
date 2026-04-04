package xiangze.mmu.rssnewsreader.ui.allentries;


import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.util.Log;

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

    private final FeedRepository feedRepository;
    private final EntryRepository entryRepository;
    private final PlaylistRepository playlistRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final TtsExtractor ttsExtractor;
    private final TtsPlayer ttsPlayer;
    private final MutableLiveData<List<EntryInfo>> allEntries = new MutableLiveData<>();
    private final MutableLiveData<String> toastMessage = new MutableLiveData<>();
    private final MutableLiveData<Integer> unreadCount = new MutableLiveData<>();
    private final LiveData<List<EntryInfo>> liveEntries;

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
        if (disposableEntries != null && !disposableEntries.isDisposed()) {
            disposableEntries.dispose();
        }
        if (disposableCount != null && !disposableCount.isDisposed()) {
            disposableCount.dispose();
        }

        this.id = id;

        disposableEntries = entryRepository.getEntries(id, filter)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(entriesInfo -> allEntries.postValue(entriesInfo),
                        throwable -> Log.e("AllEntriesViewModel", "Error fetching entries", throwable));

        disposableCount = entryRepository.getUnreadCount(id, filter)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(integer -> unreadCount.postValue(integer),
                        throwable -> Log.e("AllEntriesViewModel", "Error fetching unread count", throwable));
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
                        Log.d("AllEntriesViewModel", "Playlist inserted successfully");
                    }
                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.e("AllEntriesViewModel", "Error inserting playlist", e);
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
                        Log.d("AllEntriesViewModel", "Visited date updated successfully");
                    }
                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.e("AllEntriesViewModel", "Error updating visited date", e);
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
                        Log.e("AllEntriesViewModel", "Error deleting visited entries", e);
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
                        Log.e("AllEntriesViewModel", "Failed to restore entry", e);
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
                        Log.e("AllEntriesViewModel", "Error deleting entry", e);
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
                        Log.e("AllEntriesViewModel", "Error refreshing entries", e);
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
                        Log.e("AllEntriesViewModel", "Error updating bookmark", e);
                    }
                });
    }
}
