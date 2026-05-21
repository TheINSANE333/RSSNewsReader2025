package xiangze.mmu.rssnewsreader.data.database;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;


import android.util.Log;

import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryDao;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.data.feed.FeedDao;
import xiangze.mmu.rssnewsreader.data.history.History;
import xiangze.mmu.rssnewsreader.data.history.HistoryDao;
import xiangze.mmu.rssnewsreader.data.playlist.Playlist;
import xiangze.mmu.rssnewsreader.data.playlist.PlaylistDao;

import javax.inject.Inject;
import javax.inject.Provider;

@Database(entities = {Feed.class, Entry.class, Playlist.class, History.class}, version = 8)
@androidx.room.TypeConverters({TypeConverters.class})
// make this abstract to let room do the implementation
public abstract class AppDatabase extends RoomDatabase {

    public abstract FeedDao feedDao();
    public abstract EntryDao entryDao();
    public abstract PlaylistDao playlistDao();
    public abstract HistoryDao historyDao();

    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                // Delete duplicates in entry_table (keep only the one with the smallest ID for each feedId+link pair)
                database.execSQL("DELETE FROM entry_table WHERE id NOT IN (SELECT MIN(id) FROM entry_table GROUP BY feedId, link)");

                // Delete duplicates in feed_table
                database.execSQL("DELETE FROM feed_table WHERE id NOT IN (SELECT MIN(id) FROM feed_table GROUP BY link)");

                // Create unique indices
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_entry_table_feedId_link ON entry_table (feedId, link)");
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_feed_table_link ON feed_table (link)");

                Log.d("DatabaseMigration", "Migration from v7 to v8 completed successfully.");
            } catch (Exception e) {
                Log.e("DatabaseMigration", "Migration v7 to v8 failed: " + e.getMessage());
            }
        }
    };

    // Migration from version 2 to 3
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                //2023 to 2024 version
                database.execSQL("ALTER TABLE entry_table ADD COLUMN priority INTEGER NOT NULL DEFAULT 0");
                database.execSQL("ALTER TABLE entry_table ADD COLUMN sentCountStopAt INTEGER NOT NULL DEFAULT 0");
                database.execSQL("ALTER TABLE entry_table ADD COLUMN bookmark TEXT DEFAULT ''");
                database.execSQL("ALTER TABLE feed_table ADD COLUMN delayTime INTEGER NOT NULL DEFAULT 0");
                database.execSQL("ALTER TABLE feed_table ADD COLUMN ttsSpeechRate REAL NOT NULL DEFAULT 1.0");
                database.execSQL("ALTER TABLE history_table ADD COLUMN feedId INTEGER NOT NULL DEFAULT 0");
                database.execSQL("ALTER TABLE playlist_table ADD COLUMN createdDate INTEGER DEFAULT NULL");

                //2024 to 2025 version
                database.execSQL("ALTER TABLE entry_table ADD COLUMN isCached INTEGER NOT NULL DEFAULT 0");
                database.execSQL("ALTER TABLE feed_table ADD COLUMN isPreloaded INTEGER NOT NULL DEFAULT 0");

                Log.d("DatabaseMigration", "Migration from v2 to v3 completed successfully.");
            } catch (Exception e) {
                Log.e("DatabaseMigration", "Migration failed: " + e.getMessage());
            }
        }
    };

    // Migration from version 3 to 4
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE entry_table ADD COLUMN original_html TEXT");
                Log.d("DatabaseMigration", "Migration from v3 to v4 completed successfully.");
            } catch (Exception e) {
                Log.e("DatabaseMigration", "Migration v3 to v4 failed: " + e.getMessage());
            }
        }
    };

    // Migration from version 4 to 5
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE entry_table ADD COLUMN translated TEXT");
                Log.d("DatabaseMigration", "Migration from v4 to v5 completed successfully.");
            } catch (Exception e) {
                Log.e("DatabaseMigration", "Migration v4 to v5 failed: " + e.getMessage());
            }
        }
    };

    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {

            database.execSQL(
                    "ALTER TABLE entry_table ADD COLUMN translated_html TEXT"
            );

            database.execSQL(
                    "ALTER TABLE entry_table ADD COLUMN summarized_html TEXT"
            );

            database.execSQL(
                    "ALTER TABLE entry_table ADD COLUMN summarized TEXT"
            );
        }
    };

    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE feed_table ADD COLUMN autoSummarize INTEGER NOT NULL DEFAULT 1"
            );
            database.execSQL(
                    "ALTER TABLE feed_table ADD COLUMN autoTranslate INTEGER NOT NULL DEFAULT 1"
            );
        }
    };


    public static class Callback extends RoomDatabase.Callback {

        private Provider<AppDatabase> database;

        @Inject
        public Callback(Provider<AppDatabase> db) {
            super();
            this.database = db;
        }

        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
        }
    }
}
