package xiangze.mmu.rssnewsreader.ui.allentries;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;

import java.util.ArrayList;
import java.util.Date;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.schedulers.Schedulers;

@HiltViewModel
public class EntryItemViewModel extends ViewModel {

    private EntryRepository entryRepository;
    private TtsPlayer ttsPlayer;
    private MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
    private MutableLiveData<String> toastMessage = new MutableLiveData<>();

    @Inject
    public EntryItemViewModel(EntryRepository entryRepository, TtsPlayer ttsPlayer) {
        this.entryRepository = entryRepository;
        this.ttsPlayer = ttsPlayer;
    }

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    public LiveData<String> getToastMessage() {
        return toastMessage;
    }

    public void resetToastMessage() {
        toastMessage.postValue(null);
    }

    public void updateVisitedDate(long id, boolean unread) {
        Completable.fromAction(new Action() {
            @Override
            public void run() throws Throwable {
                if (unread) {
                    entryRepository.updateDate(new Date(), id);
                } else {
                    entryRepository.updateDate(null, id);
                }
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        isLoading.postValue(true);
                    }

                    @Override
                    public void onComplete() {
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Log.e("EntryItemViewModel", "Error updating visited date", e);
                        isLoading.postValue(false);
                    }
                });
    }

    public void deleteEntriesByIds(ArrayList<Long> ids, long id, boolean above) {
        Completable.fromAction(new Action() {
                    @Override
                    public void run() throws Throwable {
                        int index = ids.indexOf(id);

                        if (above) {
                            ids.subList(index, ids.size()).clear();
                        } else {
                            if (index < ids.size()) {
                                ids.subList(0, index + 1).clear();
                            }
                        }

                        long currentId = ttsPlayer.getCurrentId();
                        if (currentId != 0 && ids.contains(currentId) && !entryRepository.isBookmark(currentId)) {
                            ttsPlayer.stop();
                        }
                        entryRepository.deleteByIds(ids);
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@io.reactivex.rxjava3.annotations.NonNull Disposable d) {
                        isLoading.postValue(true);
                    }

                    @Override
                    public void onComplete() {
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@io.reactivex.rxjava3.annotations.NonNull Throwable e) {
                        Log.e("EntryItemViewModel", "Error deleting entries by ids", e);
                        isLoading.postValue(false);
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
                        isLoading.postValue(true);
                    }

                    @Override
                    public void onComplete() {
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@io.reactivex.rxjava3.annotations.NonNull Throwable e) {
                        Log.e("EntryItemViewModel", "Error deleting entry", e);
                        isLoading.postValue(false);
                    }
                });
    }
}
