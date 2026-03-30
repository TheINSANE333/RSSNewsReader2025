package xiangze.mmu.rssnewsreader.service.rss;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class RssWorkManager {

    private static final String TAG = "RssWorkManager";
    public static final String refreshWorkerName = "RefreshWorker";

    private Context context;
    private SharedPreferencesRepository sharedPreferencesRepository;

    @Inject
    public RssWorkManager(@ApplicationContext Context context, SharedPreferencesRepository sharedPreferencesRepository) {
        this.context = context;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    public void enqueueRssWorker() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        int interval = sharedPreferencesRepository.getJobPeriodic();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(RssWorker.class, interval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        // Using UPDATE or REPLACE ensures that if settings change, the worker is rescheduled with the new interval.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(refreshWorkerName, ExistingPeriodicWorkPolicy.UPDATE, request);
        Log.d(TAG, "RssWorker scheduled with interval: " + interval + " minutes.");
    }

    public void triggerOneTimeRssWorker() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(RssWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueue(request);
        Log.d(TAG, "One-time RssWorker triggered.");
    }

    public void triggerPreloadWorker() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Prefer Wi-Fi for image preloading
                .build();

        androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(PreloadWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueue(request);
        Log.d(TAG, "PreloadWorker triggered.");
    }

    public void dequeueRssWorker() {
        WorkManager.getInstance(context).cancelUniqueWork(refreshWorkerName);
    }

    public boolean isWorkScheduled() {
        try {
            List<WorkInfo> workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(refreshWorkerName)
                    .get();

            for (WorkInfo workInfo : workInfos) {
                WorkInfo.State state = workInfo.getState();
                if (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING) {
                    return true;
                }
            }
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Error checking work state.", e);
        }
        return false;
    }
}
