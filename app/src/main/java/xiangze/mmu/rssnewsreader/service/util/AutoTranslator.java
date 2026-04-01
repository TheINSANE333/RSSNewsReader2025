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
import xiangze.mmu.rssnewsreader.service.ai.TranslationWorker;

@Singleton
public class AutoTranslator {

    private static final String TAG = "AutoTranslator";
    private final EntryRepository entryRepository;
    private final TextUtil textUtil;
    private final SharedPreferencesRepository prefs;
    private final Context context;
    
    public static final Set<Long> processingIds = Collections.synchronizedSet(new HashSet<>());
    public static final Set<Long> failedSessionIds = Collections.synchronizedSet(new HashSet<>());

    @Inject
    public AutoTranslator(@ApplicationContext Context context, EntryRepository entryRepository, TextUtil textUtil, SharedPreferencesRepository prefs) {
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

    public void runAutoTranslation(@Nullable Runnable onComplete) {
        if (!prefs.getAutoTranslate()) {
            Log.d(TAG, "Auto-translate disabled by user.");
            if (onComplete != null) onComplete.run();
            return;
        }

        Log.d(TAG, "Enqueuing batch translation work");
        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(TranslationWorker.class)
                .addTag("BatchTranslation")
                .build();
        
        WorkManager.getInstance(context).enqueueUniqueWork(
                "BatchTranslationWork",
                androidx.work.ExistingWorkPolicy.KEEP,
                workRequest
        );
        
        if (onComplete != null) {
            onComplete.run();
        }
    }

    public void runAutoTranslation() {
        runAutoTranslation(null);
    }
}
