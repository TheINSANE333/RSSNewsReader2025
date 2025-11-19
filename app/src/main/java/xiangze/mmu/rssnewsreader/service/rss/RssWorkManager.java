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
        if (!isWorkScheduled()) {
            Constraints constraints = new Constraints.Builder()
                 .setRequiredNetworkType(NetworkType.CONNECTED)
                   .build();

            int interval = sharedPreferencesRepository.getJobPeriodic();

            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(RssWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("rssWork", ExistingPeriodicWorkPolicy.KEEP, request);
            Log.d(TAG, "RssWorker scheduled.");
        } else {
            Log.d(TAG, "RssWorker is already scheduled.");
        }
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
