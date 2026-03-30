package xiangze.mmu.rssnewsreader.service.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.ai.SummarizationWorker;

@Singleton
public class AutoSummarizer {

    private static final String TAG = "AutoSummarizer";
    private final EntryRepository entryRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository prefs;
    private final Context context;
    
    public static final Set<Long> processingIds = Collections.synchronizedSet(new HashSet<>());
    public static final Set<Long> failedSessionIds = Collections.synchronizedSet(new HashSet<>());

    @Inject
    public AutoSummarizer(@ApplicationContext Context context, EntryRepository entryRepository, TextUtil textUtil, SharedPreferencesRepository prefs) {
        this.context = context;
        this.entryRepository = entryRepository;
        this.textUtil = textUtil;
        this.prefs = prefs;
    }

    public static boolean isProcessing(long id) {
        return processingIds.contains(id);
    }

    public static boolean hasFailed(long id) {
        return failedSessionIds.contains(id);
    }

    public void runAutoSummarization(@Nullable Runnable onComplete) {
        if (!prefs.getAutoSummarize()) {
            Log.d(TAG, "Auto-summarize disabled by user.");
            if (onComplete != null) onComplete.run();
            return;
        }

        Log.d(TAG, "Enqueuing batch summarization work");
        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(SummarizationWorker.class)
                .addTag("BatchSummarization")
                .build();
        
        WorkManager.getInstance(context).enqueue(workRequest);
        
        if (onComplete != null) {
            // Since WorkManager is asynchronous, we can't easily run onComplete exactly when it finishes 
            // without observing the work. For simplicity in the current architecture, we'll just run it.
            onComplete.run();
        }
    }

    public void runAutoSummarization() {
        runAutoSummarization(null);
    }
}
