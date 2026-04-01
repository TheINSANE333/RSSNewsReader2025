package xiangze.mmu.rssnewsreader.service.ai;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
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
import xiangze.mmu.rssnewsreader.service.util.AutoTranslator;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;

@HiltWorker
public class TranslationWorker extends ListenableWorker {
    private static final String TAG = "TranslationWorker";
    private static final String CHANNEL_ID = "ai_processing_channel";
    private static final int NOTIFICATION_ID = 1002;

    private final EntryRepository entryRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private Disposable currentDisposable;

    @AssistedInject
    public TranslationWorker(
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
            Log.d(TAG, "Starting batch translation worker");
            createNotificationChannel();
            
            currentDisposable = io.reactivex.rxjava3.core.Single.fromCallable(entryRepository::getAllUntranslatedEntries)
                    .flatMap(entries -> {
                        if (entries == null || entries.isEmpty()) {
                            Log.d(TAG, "No untranslated entries found");
                            return io.reactivex.rxjava3.core.Single.just(Result.success());
                        }

                        Log.d(TAG, "Found " + entries.size() + " potential entries to translate");
                        return processEntries(entries)
                                .andThen(io.reactivex.rxjava3.core.Single.just(Result.success()));
                    })
                    .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                    .subscribe(
                            completer::set,
                            throwable -> {
                                Log.e(TAG, "Batch translation failed with fatal error", throwable);
                                completer.set(Result.failure());
                            }
                    );
            return "TranslationWorker";
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
                    int progress = entries.indexOf(entryInfo) + 1;
                    int total = entries.size();
                    return translateEntry(entryInfo)
                            .doOnSubscribe(d -> updateNotification(progress, total, entryInfo.getEntryTitle()))
                            .delay(500, TimeUnit.MILLISECONDS);
                });
    }

    private io.reactivex.rxjava3.core.Completable translateEntry(EntryInfo entryInfo) {
        if (AutoTranslator.isProcessing(entryInfo.getEntryId())) {
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
        String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
        
        return textUtil.identifyLanguageRx(finalHtmlSource)
                .doOnSubscribe(d -> AutoTranslator.processingIds.add(entryInfo.getEntryId()))
                .doFinally(() -> AutoTranslator.processingIds.remove(entryInfo.getEntryId()))
                .flatMap(sourceLang -> {
                    if (sourceLang != null && sourceLang.equalsIgnoreCase(targetLanguage)) {
                        return io.reactivex.rxjava3.core.Single.error(new Exception("Already in target language"));
                    }
                    return textUtil.translateHtmlAllAtOnce(sourceLang, targetLanguage, finalHtmlSource, entryInfo.getEntryTitle(), entryInfo.getEntryId(), p -> {}, false);
                })
                .flatMapCompletable(translatedRaw -> io.reactivex.rxjava3.core.Completable.fromAction(() -> {
                    TextUtil.AiResponse aiRes = textUtil.parseAiResponse(translatedRaw, entryInfo.getEntryTitle());
                    
                    String finalHtml = textUtil.formatAiResponseToHtml(
                            aiRes.title,
                            aiRes.content,
                            entryInfo.getFeedTitle(),
                            entryInfo.getEntryPublishedDate(),
                            entryInfo.getFeedImageUrl(),
                            sharedPreferencesRepository.getNight(),
                            "translated-title"
                    );

                    entryRepository.updateTranslatedHtml(finalHtml, entryInfo.getEntryId());
                    
                    String translatedContent = textUtil.extractHtmlContent(finalHtml, "--####--");
                    entryRepository.updateTranslatedText(translatedContent, entryInfo.getEntryId());
                    
                    Log.d(TAG, "Translated entry: " + entryInfo.getEntryTitle());
                }))
                .doOnError(e -> Log.e(TAG, "Failed to translate entry: " + entryInfo.getEntryTitle(), e))
                .onErrorComplete();
    }

    private void updateNotification(int progress, int total, String title) {
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "AI Processing", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setContentTitle("Translating Articles")
                .setContentText(progress + "/" + total + ": " + title)
                .setSmallIcon(R.drawable.ic_auto_translate)
                .setProgress(total, progress, false)
                .setOngoing(true);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
}
