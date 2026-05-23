package xiangze.mmu.rssnewsreader.data.feed;

import timber.log.Timber;

import androidx.lifecycle.MutableLiveData;

import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.history.History;
import xiangze.mmu.rssnewsreader.data.history.HistoryRepository;
import xiangze.mmu.rssnewsreader.service.rss.RssFeed;
import xiangze.mmu.rssnewsreader.service.rss.RssItem;
import xiangze.mmu.rssnewsreader.service.rss.RssReader;
import xiangze.mmu.rssnewsreader.service.rss.RssWorkManager;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.service.util.AutoSummarizer;
import xiangze.mmu.rssnewsreader.service.util.AutoTranslator;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Provider;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class FeedRepository {

    private final FeedDao feedDao;
    private final EntryRepository entryRepository;
    private final HistoryRepository historyRepository;
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
    private final RssWorkManager rssWorkManager;
    private final SharedPreferencesRepository preferencesRepository;
    private final Provider<TtsExtractor> ttsExtractorProvider;
    private final TextUtil textUtil;
    private final AutoSummarizer autoSummarizer;
    private final AutoTranslator autoTranslator;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    @Inject
    public FeedRepository(FeedDao feedDao, EntryRepository entryRepository, HistoryRepository historyRepository, RssWorkManager rssWorkManager, SharedPreferencesRepository sharedPreferencesRepository,  Provider<TtsExtractor> ttsExtractorProvider, TextUtil textUtil, AutoSummarizer autoSummarizer, AutoTranslator autoTranslator) {
        this.feedDao = feedDao;
        this.entryRepository = entryRepository;
        this.historyRepository = historyRepository;
        this.rssWorkManager = rssWorkManager;
        this.preferencesRepository = sharedPreferencesRepository;
        this.ttsExtractorProvider = ttsExtractorProvider;
        this.textUtil = textUtil;
        this.autoSummarizer = autoSummarizer;
        this.autoTranslator = autoTranslator;
    }

    public List<Feed> getAllStaticFeeds() {
        return feedDao.getAllStaticFeeds();
    }

    public Flowable<List<Feed>> getAllFeeds() {
        return feedDao.getAllFeeds();
    }

    public MutableLiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    public void insert(Feed feed) {
        feedDao.insert(feed);
    }

    public long getFeedIdByLink(String link) {
        return feedDao.getIdByLink(link);
    }

    public void update(Feed feed) {
        compositeDisposable.add(
            feedDao.update(feed)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Timber.d("update onComplete: called");
                    isLoading.postValue(false);
                }, e -> Timber.e(e, "update onError: "))
        );
    }

    public Completable delete(Feed feed) {
        return entryRepository.deleteByFeedId(feed.getId())
                .andThen(feedDao.delete(feed))
                .andThen(Completable.fromAction(() -> historyRepository.deleteByFeedId(feed.getId())))
                .doOnComplete(() -> {
                    if (getFeedCount() == 0 && rssWorkManager.isWorkScheduled()) {
                        rssWorkManager.dequeueRssWorker();
                    }
                    isLoading.postValue(false);
                })
                .doOnError(e -> {
                    Timber.e("delete error: " + e.getMessage());
                    isLoading.postValue(false);
                });
    }

    public int getFeedCount() {
        return feedDao.getFeedCount();
    }

    public void deleteAllFeeds() {
        compositeDisposable.add(
            feedDao.deleteAllFeeds()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Timber.d("deleteAllFeeds onComplete: called");
                    isLoading.postValue(false);
                }, e -> Timber.e(e, "deleteAllFeeds onError: "))
        );
    }

    public io.reactivex.rxjava3.core.Completable addNewFeed(RssFeed feed) {
        StringBuilder sampleText = new StringBuilder();
        if (feed.getTitle() != null) sampleText.append(feed.getTitle()).append(". ");

        // Take up to 20 items to get enough text for accurate language detection
        int count = 0;
        for (RssItem item : feed.getRssItems()) {
            if (count++ >= 20) break;
            if (item.getTitle() != null) sampleText.append(item.getTitle()).append(". ");
        }

        String textToIdentify = android.text.Html.fromHtml(sampleText.toString(), android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replaceAll("(?i)https?://[^\\s]+", "")
                .trim();

        io.reactivex.rxjava3.core.Single<String> languageSingle;
        if (!textToIdentify.isEmpty()) {
            languageSingle = textUtil.identifyLanguageRx(textToIdentify, 0.05f)
                    .map(detected -> {
                        if (detected != null && !detected.equals("und")) {
                            Timber.d("Detected language for feed: " + detected);
                            return detected;
                        }
                        return "Use Language Identifier";
                    })
                    .onErrorReturnItem("Use Language Identifier");
        } else {
            languageSingle = io.reactivex.rxjava3.core.Single.just("Use Language Identifier");
        }

        return languageSingle.flatMapCompletable(language -> io.reactivex.rxjava3.core.Completable.fromAction(() -> {
            String finalLanguage = language;
            if (finalLanguage.equals("Use Language Identifier") && feed.getLanguage() != null && !feed.getLanguage().isEmpty()) {
                 finalLanguage = feed.getLanguage();
            }

            String imageUrl = "https://www.google.com/s2/favicons?sz=64&domain_url=" + feed.getLink();
            Feed newFeed = new Feed(feed.getTitle(), feed.getLink(), feed.getDescription(), imageUrl, finalLanguage);

            feedDao.insert(newFeed);
            long feedId = feedDao.getIdByLink(feed.getLink());

            List<Entry> entriesToPreload = new ArrayList<>();
            for (RssItem rssItem : feed.getRssItems()) {
                Entry entry = new Entry(feedId, rssItem.getTitle(), rssItem.getLink(), rssItem.getDescription(), rssItem.getImageUrl(), rssItem.getCategory(), rssItem.getPubDate());

                long insertedId = entryRepository.insert(feedId, entry);
                if (insertedId > 0 && rssItem.getPriority() > 0) { // Check for successful insertion
                    entry.setPriority(rssItem.getPriority());
                    entriesToPreload.add(entry);
                }
            }

            if (!entriesToPreload.isEmpty()) {
                entryRepository.preloadEntries(entriesToPreload);
            }
            markFeedAsPreloaded(feedId);

            entryRepository.requeueMissingEntries();
            if (entryRepository.hasEmptyContentEntries()) {
                ttsExtractorProvider.get().extractAllEntries();
            } else {
                Timber.d("No entries to extract.");
            }

            if (!rssWorkManager.isWorkScheduled()) {
                rssWorkManager.enqueueRssWorker();
            }
        }));
    }

    public EntryRepository getEntryRepository() {
        return entryRepository;
    }

    public SharedPreferencesRepository getSharedPreferencesRepository() {
        return preferencesRepository;
    }

    public void markFeedAsPreloaded(long feedId) {
        Feed feed = feedDao.getFeedById(feedId);
        if (feed != null && !feed.isPreloaded()) {
            feed.setPreloaded(true);
            compositeDisposable.add(
                feedDao.update(feed)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(() -> Timber.d("markFeedAsPreloaded: Complete"),
                            e -> Timber.e("markFeedAsPreloaded: Error " + e.getMessage()))
            );
        } else {
            Timber.w("markFeedAsPreloaded: Feed not found for ID " + feedId);
        }
    }

    public String refreshEntries() {
        List<Feed> feeds = getAllStaticFeeds();
        ExecutorService executorService = Executors.newFixedThreadPool(4); // Use 4 threads for parallel fetching
        AtomicInteger counter = new AtomicInteger(0); // Use AtomicInteger for thread-safe increments

        for (Feed feed : feeds) {
            executorService.submit(() -> {
                try {
                    Timber.d("Fetching feed: " + feed.getLink());
                    RssReader rssReader = new RssReader(feed.getLink());
                    RssFeed rssFeed = rssReader.getFeed();

                    List<History> histories = new ArrayList<>();
                    for (RssItem rssItem : rssFeed.getRssItems()) {
                        Entry entry = new Entry(feed.getId(), rssItem.getTitle(), rssItem.getLink(), rssItem.getDescription(),
                                rssItem.getImageUrl(), rssItem.getCategory(), rssItem.getPubDate());
                        long insertedId = entryRepository.insert(feed.getId(), entry);
                        if (insertedId > 0) {
                            counter.incrementAndGet(); // Increment the counter atomically
                            entryRepository.updatePriority(1, insertedId);
                        } else {
                            histories.add(new History(entry.getFeedId(), new Date(), entry.getTitle(), textUtil.normalizeUrl(entry.getLink())));
                        }
                    }

                    entryRepository.limitEntriesByFeedId(feed.getId());
                    if (!histories.isEmpty()) {
                        historyRepository.updateHistoriesByFeedId(feed.getId(), histories);
                    }
                    Timber.d("Successfully fetched and processed feed: " + feed.getTitle());
                } catch (Exception e) {
                    Timber.e(e, "Error fetching or processing feed: " + feed.getTitle());
                }
            });
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(10, TimeUnit.MINUTES); // Wait for all threads to finish
        } catch (InterruptedException e) {
            Timber.e(e, "Error awaiting termination of executor service.");
        }

        entryRepository.requeueMissingEntries();
        return "New entries: " + counter.get(); // Use AtomicInteger's get method
    }

    public int getDelayTimeById(long id) {
        return feedDao.getDelayTimeById(id);
    }

    public void updateDelayTimeById(long id, int delayTime) {
        feedDao.updateDelayTimeById(id, delayTime);
    }

    public void updateFeedSettings(String title, String desc, String language, boolean autoSummarize, boolean autoTranslate, int delayTime, float ttsSpeechRate, String link) {
        compositeDisposable.add(
            Completable.fromAction(() -> {
            long feedId = feedDao.getIdByLink(link);
            Feed existingFeed = null;
            if (feedId > 0) {
                existingFeed = feedDao.getFeedById(feedId);
            }

            String finalLanguage = language;
            if (finalLanguage == null || finalLanguage.equals("Use Language Identifier")) {
                StringBuilder sampleText = new StringBuilder();
                if (title != null) sampleText.append(title).append(". ");

                if (feedId > 0) {
                    List<Entry> entries = entryRepository.getStaticEntries(feedId);
                    int count = 0;
                    // Take up to 20 items to get enough text for accurate language detection
                    for (Entry entry : entries) {
                        if (count++ >= 20) break;
                        if (entry.getTitle() != null) sampleText.append(entry.getTitle()).append(". ");
                    }
                }

                String textToIdentify = android.text.Html.fromHtml(sampleText.toString(), android.text.Html.FROM_HTML_MODE_LEGACY)
                        .toString()
                        .replaceAll("(?i)https?://[^\\s]+", "")
                        .trim();
                if (!textToIdentify.isEmpty()) {
                    try {
                        // Using 5% confidence since titles are short and don't form full sentences
                        String detected = textUtil.identifyLanguageRx(textToIdentify, 0.05f).blockingGet();
                        if (detected != null && !detected.equals("und")) {
                            finalLanguage = detected;
                            Timber.d("Detected language for feed update: " + finalLanguage);
                        }
                    } catch (Exception e) {
                        Timber.e(e, "Failed to identify feed language on update");
                    }
                }
            }
            feedDao.updateFeedSettings(title, desc, finalLanguage, autoSummarize, autoTranslate, delayTime, ttsSpeechRate, link);

            // Check if settings were toggled ON and trigger processing
            if (existingFeed != null) {
                boolean summarizedToggledOn = autoSummarize && !existingFeed.isAutoSummarize();
                boolean translatedToggledOn = autoTranslate && !existingFeed.isAutoTranslate();

                if (summarizedToggledOn) {
                    Timber.d("Auto-summarize enabled for feed " + feedId + ". Triggering batch processing.");
                    this.autoSummarizer.runAutoSummarization();
                }
                if (translatedToggledOn) {
                    Timber.d("Auto-translate enabled for feed " + feedId + ". Triggering batch processing.");
                    this.autoTranslator.runAutoTranslation();
                }
            }
        })
        .subscribeOn(Schedulers.io())
        .subscribe(
            () -> {},
            e -> Timber.e(e, "Error updating feed settings")
        )
        );
    }

    public float getTtsSpeechRateById(long id) {
        return feedDao.getTtsSpeechRateById(id);
    }

    public void updateTtsSpeechRateById(long id, float ttsSpeechRate) {
        feedDao.updateTtsSpeechRateById(id, ttsSpeechRate);
    }

    public boolean checkFeedExist(String link) {
        return feedDao.getIdByLink(link) != 0;
    }

    public Feed getFeedById(long id) {
        return feedDao.getFeedById(id);
    }

    public Flowable<List<Feed>> getFeedsWithUnreadArticles() {
        return feedDao.getFeedsWithUnreadArticles();
    }

    public void dispose() {
        compositeDisposable.clear();
    }
}
