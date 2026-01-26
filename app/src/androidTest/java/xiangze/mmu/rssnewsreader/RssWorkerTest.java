package xiangze.mmu.rssnewsreader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.Configuration;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.TestDriver;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import xiangze.mmu.rssnewsreader.service.rss.RssWorker;

@RunWith(AndroidJUnit4.class)
public class RssWorkerTest {

    private Context context;
    private WorkManager workManager;
    private TestDriver testDriver;

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();

        // 1. Create a Configuration with a custom Factory.
        // This intercepts the request for "RssWorker" and returns "TestRssWorker" instead.
        Configuration config = new Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(new SynchronousExecutor())
                .setWorkerFactory(new WorkerFactory() {
                    @Override
                    public ListenableWorker createWorker(Context appContext, String workerClassName, WorkerParameters workerParameters) {
                        if (workerClassName.equals(RssWorker.class.getName())) {
                            return new TestRssWorker(appContext, workerParameters);
                        }
                        return null; // Let the default system handle other workers
                    }
                })
                .build();

        // 2. Initialize WorkManager for testing
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config);

        // 3. Get instances
        workManager = WorkManager.getInstance(context);
        testDriver = WorkManagerTestInitHelper.getTestDriver(context);
    }

    @Test
    public void testPeriodicWorkEnqueuedAndRuns() throws Exception {
        // --- STEP 1: SCHEDULE THE WORK ---
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(RssWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        workManager.enqueueUniquePeriodicWork("RssWorker", ExistingPeriodicWorkPolicy.UPDATE, request);

        // --- STEP 2: VERIFY IT IS ENQUEUED ---
        UUID workId = request.getId();
        WorkInfo workInfo = workManager.getWorkInfoById(workId).get();

        assertEquals("Work should be in ENQUEUED state initially", WorkInfo.State.ENQUEUED, workInfo.getState());

        // --- STEP 3: SIMULATE TIME PASSING ---
        // This tells the test: "Assume the 15-minute interval has passed."
        // This forces the worker to execute NOW.
        testDriver.setPeriodDelayMet(workId);

        // --- STEP 4: VERIFY IT RAN ---
        workInfo = workManager.getWorkInfoById(workId).get();

        // In a Periodic request, after running successfully, it goes back to ENQUEUED for the next cycle.
        // If it failed (crashed), it would be FAILED.
        assertNotEquals("Work should NOT have failed", WorkInfo.State.FAILED, workInfo.getState());
        assertEquals("Work should be waiting for the next cycle (ENQUEUED)", WorkInfo.State.ENQUEUED, workInfo.getState());

        Log.d("TEST", "Test passed: Worker was scheduled, triggered, and re-queued successfully.");
    }

    // --- DUMMY WORKER CLASS ---
    public static class TestRssWorker extends Worker {
        public TestRssWorker(Context context, WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @Override
        public Result doWork() {

            return Result.success();
        }
    }
}
