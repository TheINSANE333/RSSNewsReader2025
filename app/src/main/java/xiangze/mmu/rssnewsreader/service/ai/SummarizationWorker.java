package xiangze.mmu.rssnewsreader.service.ai;

//import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
//import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.app.NotificationCompat;
import androidx.hilt.work.HiltWorker;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import io.reactivex.rxjava3.disposables.Disposable;
import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.service.util.AutoSummarizer;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;

@HiltWorker
public class SummarizationWorker extends ListenableWorker {
    private static final String TAG = "SummarizationWorker";
    private static final String CHANNEL_ID = "ai_processing_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final EntryRepository entryRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private Disposable currentDisposable;

    @AssistedInject
    public SummarizationWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            EntryRepository entryRepository,
            TextUtil textUtil,
            SharedPreferencesRepository sharedPreferencesRepository) {
        super(context, workerParams);
        this.entryRepository = entryRepository;
        this.textUtil = textUtil;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        return CallbackToFutureAdapter.getFuture(completer -> {
            Log.d(TAG, "Starting batch summarization worker");
            createNotificationChannel();
            
            currentDisposable = io.reactivex.rxjava3.core.Single.fromCallable(entryRepository::getAllUnsummarizedEntries)
                    .flatMap(entries -> {
                        if (entries == null || entries.isEmpty()) {
                            Log.d(TAG, "No unsummarized entries found");
                            return io.reactivex.rxjava3.core.Single.just(Result.success());
                        }

                        Log.d(TAG, "Found " + entries.size() + " potential entries to summarize");
                        
                        return processEntries(entries)
                                .andThen(io.reactivex.rxjava3.core.Single.just(Result.success()));
                    })
                    .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                    .subscribe(
                            completer::set,
                            throwable -> {
                                Log.e(TAG, "Batch summarization failed with fatal error", throwable);
                                completer.set(Result.failure());
                            }
                    );
            return "SummarizationWorker";
        });
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, 
                    "AI Processing", 
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows progress of AI summarization and translation");
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onStopped() {
        super.onStopped();
        if (currentDisposable != null && !currentDisposable.isDisposed()) {
            currentDisposable.dispose();
        }
    }

    private io.reactivex.rxjava3.core.Completable processEntries(List<EntryInfo> entries) {
        return io.reactivex.rxjava3.core.Observable.fromIterable(entries)
                .concatMapCompletable(entryInfo -> {
                    // Check if auto-summarize is enabled for this feed
                    // We need to fetch the latest feed info to be sure
                    return io.reactivex.rxjava3.core.Single.fromCallable(() -> {
                        // The entryInfo already has the feed info joined if the query was correct
                        // but let's be safe and check the flag from the entryInfo if possible
                        // or just rely on the fact that AutoSummarizer checked the global flag.
                        // Actually, getUnsummarizedEntriesInfo now joins with feed_table.
                        return entryInfo;
                    })
                    .flatMapCompletable(info -> {
                        // In a real app we might want to check per-feed autoSummarize here
                        // if it's not already filtered by the query.
                        int progress = entries.indexOf(info) + 1;
                        int total = entries.size();
                        return summarizeEntry(info)
                                .doOnSubscribe(d -> updateNotification(progress, total, info.getEntryTitle()))
                                .delay(500, TimeUnit.MILLISECONDS);
                    });
                });
    }

    private io.reactivex.rxjava3.core.Completable summarizeEntry(EntryInfo entryInfo) {
        if (AutoSummarizer.isProcessing(entryInfo.getEntryId())) {
            Log.d(TAG, "Skipping entry, already being processed: " + entryInfo.getEntryTitle());
            return io.reactivex.rxjava3.core.Completable.complete();
        }

        String htmlSource = entryRepository.getOriginalHtmlById(entryInfo.getEntryId());
        if (htmlSource == null || htmlSource.trim().isEmpty()) {
            htmlSource = entryRepository.getHtmlById(entryInfo.getEntryId());
        }

        if (htmlSource == null || htmlSource.trim().isEmpty()) {
            Log.w(TAG, "Skipping entry, no HTML content: " + entryInfo.getEntryTitle());
            return io.reactivex.rxjava3.core.Completable.complete();
        }

        final String finalHtmlSource = htmlSource;
        int summaryLength = sharedPreferencesRepository.getSummaryLength();
        
        return textUtil.summarizeHtmlRx(finalHtmlSource, entryInfo.getEntryTitle(), summaryLength, false)
                .doOnSubscribe(d -> AutoSummarizer.processingIds.add(entryInfo.getEntryId()))
                .doFinally(() -> AutoSummarizer.processingIds.remove(entryInfo.getEntryId()))
                .flatMapCompletable(summaryRaw -> io.reactivex.rxjava3.core.Completable.fromAction(() -> {
                    TextUtil.AiResponse aiRes = textUtil.parseAiResponse(summaryRaw, entryInfo.getEntryTitle());
                    
                    String finalHtml = textUtil.formatAiResponseToHtml(
                            aiRes.title,
                            aiRes.content,
                            entryInfo.getFeedTitle(),
                            entryInfo.getEntryPublishedDate(),
                            entryInfo.getFeedImageUrl(),
                            sharedPreferencesRepository.getNight(),
                            "summarized-title"
                    );

                    String summarizedContent = textUtil.extractHtmlContent(finalHtml, "--####--");
                    entryRepository.updateSummarizedPair(entryInfo.getEntryId(), summarizedContent, finalHtml);
                    
                    Log.d(TAG, "Summarized entry: " + entryInfo.getEntryTitle());
                }))
                .doOnError(e -> Log.e(TAG, "Failed to summarize entry: " + entryInfo.getEntryTitle(), e))
                .onErrorComplete();
    }

    private void updateNotification(int progress, int total, String title) {
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "AI Processing", NotificationManager.IMPORTANCE_LOW);
//            notificationManager.createNotificationChannel(channel);
//        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setContentTitle("Summarizing Articles")
                .setContentText(progress + "/" + total + ": " + title)
                .setSmallIcon(R.drawable.ic_auto_mode)
                .setProgress(total, progress, false)
                .setOngoing(true);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
}
