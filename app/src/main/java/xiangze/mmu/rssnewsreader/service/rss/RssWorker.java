package xiangze.mmu.rssnewsreader.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
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

    private final FeedRepository feedRepository;
    private final TtsExtractor ttsExtractor;
    private final Context context;

    @AssistedInject
    public RssWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            FeedRepository feedRepository,
            TtsExtractor ttsExtractor
    ) {
        super(context, workerParams);
        this.context = context;
        this.feedRepository = feedRepository;
        this.ttsExtractor = ttsExtractor;
    }

    @NonNull
    @Override
    public Result doWork() {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Log.d(TAG, "Starting RSS refresh...");

            // 1. Refresh feeds
            String text = feedRepository.refreshEntries();

            // 2. Notify user
            RssNotification rssNotification = new RssNotification(context);
            rssNotification.sendNotification(text);

            // 3. Requeue missing entries
            feedRepository.getEntryRepository().requeueMissingEntries();

            // 4. TTS extraction (blocking)
            if (feedRepository.getEntryRepository().hasEmptyContentEntries()) {
                ttsExtractor.extractAllEntries();
            } else {
                Log.d(TAG, "No entries to extract in RssWorker.");
            }

            // 5. Run translation + summarization in parallel
            Future<?> translationFuture = executor.submit(() -> {
                Log.d(TAG, "Starting auto translation...");
                AutoTranslator autoTranslator = new AutoTranslator(
                        feedRepository.getEntryRepository(),
                        new TextUtil(feedRepository.getSharedPreferencesRepository()),
                        feedRepository.getSharedPreferencesRepository()
                );
                autoTranslator.runAutoTranslation();
                Log.d(TAG, "Auto translation completed.");
            });

            Future<?> summarizationFuture = executor.submit(() -> {
                Log.d(TAG, "Starting auto summarization...");
                AutoSummarizer autoSummarizer = new AutoSummarizer(
                        feedRepository.getEntryRepository(),
                        new TextUtil(feedRepository.getSharedPreferencesRepository()),
                        feedRepository.getSharedPreferencesRepository()
                );
                autoSummarizer.runAutoSummarization();
                Log.d(TAG, "Auto summarization completed.");
            });

            // 6. Wait for both tasks to complete
            translationFuture.get();
            summarizationFuture.get();

            Log.d(TAG, "RssWorker completed successfully.");
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Error in RSS refresh", e);
            return Result.retry();

        } finally {
            executor.shutdown();
        }
    }
}

