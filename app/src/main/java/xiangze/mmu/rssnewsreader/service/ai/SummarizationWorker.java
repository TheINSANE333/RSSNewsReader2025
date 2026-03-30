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
            
            currentDisposable = io.reactivex.rxjava3.core.Single.fromCallable(entryRepository::getAllUnsummarizedEntries)
                    .flatMap(entries -> {
                        if (entries == null || entries.isEmpty()) {
                            return io.reactivex.rxjava3.core.Single.just(Result.success());
                        }

                        Log.d(TAG, "Found " + entries.size() + " entries to summarize");
                        
                        return processEntries(entries)
                                .andThen(io.reactivex.rxjava3.core.Single.just(Result.success()));
                    })
                    .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                    .subscribe(
                            completer::set,
                            throwable -> {
                                Log.e(TAG, "Batch summarization failed", throwable);
                                completer.set(Result.failure());
                            }
                    );
            return "SummarizationWorker";
        });
    }

    @Override
    public void onStopped() {
        super.onStopped();
        if (currentDisposable != null && !currentDisposable.isDisposed()) {
            currentDisposable.dispose();
        }
    }

    private io.reactivex.rxjava3.core.Completable processEntries(List<EntryInfo> entries) {
        io.reactivex.rxjava3.core.Completable completable = io.reactivex.rxjava3.core.Completable.complete();
        
        for (int i = 0; i < entries.size(); i++) {
            EntryInfo entryInfo = entries.get(i);
            int progress = i + 1;
            int total = entries.size();
            
            completable = completable.concatWith(
                    summarizeEntry(entryInfo)
                            .doOnSubscribe(d -> updateNotification(progress, total, entryInfo.getEntryTitle()))
                            .delay(500, TimeUnit.MILLISECONDS)
            );
        }
        
        return completable;
    }

    private io.reactivex.rxjava3.core.Completable summarizeEntry(EntryInfo entryInfo) {
        String html = entryRepository.getOriginalHtmlById(entryInfo.getEntryId());
        if (html == null || html.trim().isEmpty()) {
            return io.reactivex.rxjava3.core.Completable.complete();
        }

        int summaryLength = sharedPreferencesRepository.getSummaryLength();
        
        return textUtil.summarizeHtmlRx(html, entryInfo.getEntryTitle(), summaryLength)
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

                    entryRepository.updateSummarizedHtml(finalHtml, entryInfo.getEntryId());
                    
                    String summarizedContent = textUtil.extractHtmlContent(finalHtml, "--####--");
                    entryRepository.updateSummarizedText(summarizedContent, entryInfo.getEntryId());
                    
                    Log.d(TAG, "Summarized entry: " + entryInfo.getEntryTitle());
                }))
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
