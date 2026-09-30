package xiangze.mmu.rssnewsreader.ui.main;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.hilt.work.HiltWorkerFactory;
import androidx.preference.PreferenceManager;
import androidx.work.Configuration;

import javax.inject.Inject;

import dagger.hilt.android.HiltAndroidApp;
import timber.log.Timber;
import xiangze.mmu.rssnewsreader.BuildConfig;
import xiangze.mmu.rssnewsreader.util.AppLogger;

@HiltAndroidApp
public class MainApplication extends Application implements Configuration.Provider {

    @Inject
    HiltWorkerFactory workerFactory;

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize the in-app debug log system (persists across sessions)
        AppLogger.init(this);

        // Plant AppLogger as the Timber tree — it extends DebugTree so it
        // still prints to logcat in debug builds, but also captures WARN+
        // entries into an in-memory ring buffer for the debug log viewer.
        if (BuildConfig.DEBUG) {
            AppLogger.setMinPriority(android.util.Log.WARN);
            Timber.plant(new AppLogger());
        } else {
            // In release builds, only capture errors for debugging
            AppLogger.setMinPriority(android.util.Log.ERROR);
            Timber.plant(new AppLogger());
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isNight = sharedPreferences.getBoolean("night", false);
        if (isNight) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }

    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build();
    }
}

