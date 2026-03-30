package xiangze.mmu.rssnewsreader.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xiangze.mmu.rssnewsreader.data.feed.FeedRepository;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.service.util.AutoSummarizer;
import xiangze.mmu.rssnewsreader.service.util.AutoTranslator;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public class RssWorker extends Worker {

    public static final String TAG = "RssWorker";

    private FeedRepository feedRepository;
    private TtsExtractor ttsExtractor;
    private Context context;

    ExecutorService executor = Executors.newFixedThreadPool(2);

    @AssistedInject
    public RssWorker(@Assisted @NonNull Context context, @Assisted @NonNull WorkerParameters workerParams, FeedRepository feedRepository, TtsExtractor ttsExtractor) {
        super(context, workerParams);
        this.context = context;
        this.feedRepository = feedRepository;
        this.ttsExtractor = ttsExtractor;
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "Starting RSS refresh...");
            String text = feedRepository.refreshEntries();
            RssNotification rssNotification = new RssNotification(context);
            rssNotification.sendNotification(text);
            feedRepository.getEntryRepository().requeueMissingEntries();
            if (feedRepository.getEntryRepository().hasEmptyContentEntries()) {
                ttsExtractor.extractAllEntries();
            } else {
                Log.d(TAG, "No entries to extract in RssWorker.");
            }

            AutoSummarizer autoSummarizer = new AutoSummarizer(
                    context,
                    feedRepository.getEntryRepository(),
                    new TextUtil(feedRepository.getSharedPreferencesRepository()),
                    feedRepository.getSharedPreferencesRepository()
            );

            AutoTranslator autoTranslator = new AutoTranslator(
                    context,
                    feedRepository.getEntryRepository(),
                    new TextUtil(feedRepository.getSharedPreferencesRepository()),
                    feedRepository.getSharedPreferencesRepository()
            );

            Future<?> summarizeFuture = executor.submit(() -> {
                autoSummarizer.runAutoSummarization();
            });

            Future<?> translateFuture = executor.submit(() -> {
                autoTranslator.runAutoTranslation();
            });

            try {
                summarizeFuture.get();   // wait for summarization
                translateFuture.get();   // wait for translation
            } catch (Exception e) {
                throw new RuntimeException("Parallel processing failed", e);
            } finally {
                executor.shutdown();
            }

            // Trigger Image Preloading
            Constraints preloadConstraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build();
            OneTimeWorkRequest preloadRequest = new OneTimeWorkRequest.Builder(PreloadWorker.class)
                    .setConstraints(preloadConstraints)
                    .build();
            WorkManager.getInstance(context).enqueue(preloadRequest);
            Log.d(TAG, "Scheduled PreloadWorker for images.");

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error in RSS refresh: " + e.getMessage());
            return Result.retry();
        }
    }
}

