package xiangze.mmu.rssnewsreader.ui.feed;

import timber.log.Timber;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.data.feed.FeedRepository;
import xiangze.mmu.rssnewsreader.data.history.HistoryRepository;
import xiangze.mmu.rssnewsreader.service.rss.RssFeed;
import xiangze.mmu.rssnewsreader.service.rss.RssReader;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;

@HiltViewModel
public class FeedViewModel extends ViewModel {

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    private final FeedRepository feedRepository;
    private final EntryRepository entryRepository;
    private final TtsPlayer ttsPlayer;
    private final TtsExtractor ttsExtractor;
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
    private final MutableLiveData<String> toastMessage = new MutableLiveData<>();
    private final MutableLiveData<List<Feed>> allFeeds = new MutableLiveData<>();
    private RssFeed rssFeed;

    @Inject
    public FeedViewModel(FeedRepository feedRepository, EntryRepository entryRepository, HistoryRepository historyRepository, TtsPlayer ttsPlayer, TtsExtractor ttsExtractor) {
        this.feedRepository = feedRepository;
        this.entryRepository = entryRepository;
        this.ttsPlayer = ttsPlayer;
        this.ttsExtractor = ttsExtractor;

        Disposable disposable = feedRepository.getAllFeeds()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(feeds -> allFeeds.postValue(feeds),
                        throwable -> Timber.e(throwable, "Error fetching all feeds"));

        compositeDisposable.add(disposable);
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

    public LiveData<List<Feed>> getAllFeeds() {
        return allFeeds;
    }

    public int getDelayTimeById(long id) {
        return feedRepository.getDelayTimeById(id);
    }

    public void updateDelayTimeById(long id, int delayTime) {
        feedRepository.updateDelayTimeById(id, delayTime);
    }

    public void checkNewFeed(String link, AddFeedCallback addFeedCallback) {
        Completable.fromAction(new Action() {
            @Override
            public void run() throws Throwable {
                if (!feedRepository.checkFeedExist(link)) {
                    int limit = feedRepository.getSharedPreferencesRepository().getEntriesLimitPerFeed();
                    RssReader rssReader = new RssReader(link);
                    rssFeed = rssReader.getFeed(limit);
                    rssFeed.setLink(link);
                    Timber.d(link);
                } else {
                    toastMessage.postValue("This feed has been added before");
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
                        if (rssFeed != null) {
                            addFeedCallback.openLoginDialog(rssFeed);
                            rssFeed = null;
                        }
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Error checking new feed");
                        toastMessage.postValue("Failed: This feed seems to be broken or inaccessible now");
                        isLoading.postValue(false);
                    }
                });
    }

    public void addNewFeed(RssFeed feed) {
        feedRepository.addNewFeed(feed)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        isLoading.postValue(true);
                    }

                    @Override
                    public void onComplete() {
                        toastMessage.postValue("Feed has been successfully added");
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Error adding new feed");
                        toastMessage.postValue("Failed: This feed seems to be broken");
                        isLoading.postValue(false);
                    }
                });
    }

    public void reExtractFeed(long feedId) {
        Completable.fromAction(new Action() {
            @Override
            public void run() throws Throwable {
                long currentEntryId = ttsPlayer.getCurrentId();
                if (currentEntryId > 0) {
                    Entry currentEntry = entryRepository.getEntryById(currentEntryId);
                    if (currentEntry != null && currentEntry.getFeedId() == feedId) {
                        Timber.d("Stopping TTS as current article belongs to the feed being re-extracted");
                        ttsPlayer.stop();
                    }
                }
                entryRepository.reExtractContent(feedId);
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
                        toastMessage.postValue("The extracted content for this feed has been removed");
                        ttsExtractor.extractAllEntries();
                        Timber.d("reExtractFeed completed. Now calling extractAllEntries()");
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e(e, "Error re-extracting feed");
                        toastMessage.postValue("Failed on re-extracting");
                        isLoading.postValue(false);
                    }
                });
    }

    public void deleteFeed(Feed feed) {
        Completable.fromAction(() -> {
            long currentId = ttsPlayer.getCurrentId();
            if (currentId != 0) {
                List<Long> ids = entryRepository.getIdsByFeedId(feed.getId());
                if (ids.contains(currentId)) {
                    ttsPlayer.stop();
                }
            }
        }).andThen(feedRepository.delete(feed))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        isLoading.postValue(true);
                    }

                    @Override
                    public void onComplete() {
                        toastMessage.postValue("Feed has been successfully deleted");
                        isLoading.postValue(false);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Timber.e("deleteFeed onError: " + e.getMessage());
                        toastMessage.postValue("Failed on deleting feed");
                        isLoading.postValue(false);
                    }
                });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.dispose();
    }

    public interface AddFeedCallback {
        void openLoginDialog(RssFeed feed);
    }
}
