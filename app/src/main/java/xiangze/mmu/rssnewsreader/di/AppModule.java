package xiangze.mmu.rssnewsreader.di;

import android.app.Application;

import androidx.room.Room;

import xiangze.mmu.rssnewsreader.data.database.AppDatabase;
import xiangze.mmu.rssnewsreader.data.entry.EntryDao;
import xiangze.mmu.rssnewsreader.data.feed.FeedDao;
import xiangze.mmu.rssnewsreader.data.history.HistoryDao;
import xiangze.mmu.rssnewsreader.data.playlist.PlaylistDao;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides
    @Singleton
    public static AppDatabase provideDatabase(Application app, AppDatabase.Callback callback) {
        return Room.databaseBuilder(app, AppDatabase.class, "app_database")
                .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
                .addCallback(callback)
                .allowMainThreadQueries()
                .build();
    }

    @Provides // no need include singleton as room automatically set singleton for DAO
    public static FeedDao provideFeedDao(AppDatabase db) {
        return db.feedDao();
    }

    @Provides
    public static EntryDao provideEntryDao(AppDatabase db) {
        return db.entryDao();
    }

    @Provides
    public static PlaylistDao providePlaylistDao(AppDatabase db) {
        return db.playlistDao();
    }

    @Provides
    public static HistoryDao provideHistoryDao(AppDatabase db) {
        return db.historyDao();
    }
}
