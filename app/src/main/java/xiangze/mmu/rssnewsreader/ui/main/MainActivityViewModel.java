package xiangze.mmu.rssnewsreader.ui.main;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.data.feed.FeedRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;

@HiltViewModel
public class MainActivityViewModel extends ViewModel {

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    private final FeedRepository feedRepository;
    private final EntryRepository entryRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;

    private final MutableLiveData<List<Feed>> allFeeds = new MutableLiveData<>();

    @Inject
    public MainActivityViewModel(FeedRepository feedRepository, EntryRepository entryRepository, SharedPreferencesRepository sharedPreferencesRepository) {
        this.feedRepository = feedRepository;
        this.entryRepository = entryRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;

        Disposable disposable = feedRepository.getAllFeeds()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(feeds -> allFeeds.postValue(feeds),
                        throwable -> Log.e("MainActivityViewModel", "Error fetching feeds", throwable));

        compositeDisposable.add(disposable);
    }

    public LiveData<List<Feed>> getAllFeeds() {
        return allFeeds;
    }

    public List<Feed> getAllStaticFeeds() {
        return feedRepository.getAllStaticFeeds();
    }

    public List<Entry> getAllStaticEntries(long id) {
        return entryRepository.getStaticEntries(id);
    }

    public long getFeedIdByLink(String link) {
        return feedRepository.getFeedIdByLink(link);
    }

    public void addFeedUsingOPML(Feed feed) {
        if (!feedRepository.checkFeedExist(feed.getLink())) {
            feedRepository.insert(feed);
        }
    }

    public void addEntry(long feedId, Entry entry) {
        Completable.fromAction(() -> entryRepository.insert(feedId, entry))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Log.d("MainActivityViewModel", "Entry added successfully");
                }, throwable -> {
                    Log.e("MainActivityViewModel", "Error adding entry", throwable);
                });
    }

    public boolean getNight() {
        return sharedPreferencesRepository.getNight();
    }

    public void setNight(boolean isNight) {
        sharedPreferencesRepository.setNight(isNight);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.dispose();
    }
}
