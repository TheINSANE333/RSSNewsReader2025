package xiangze.mmu.rssnewsreader.data.history;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.Date;

@Dao
public interface HistoryDao {

    @Insert
    void insert(History history);

    @Update
    void update(History history);

    @Delete
    void delete(History history);

    @Query("UPDATE history_table SET insertDate = :newDate WHERE feedId = :feedId AND title = :title")
    void updateInsertDateByTitle(long feedId, String title, Date newDate);

    @Query("UPDATE history_table SET insertDate = :newDate WHERE feedId = :feedId AND link = :link")
    void updateInsertDateByLink(long feedId, String link, Date newDate);

    @Query("SELECT id FROM history_table WHERE feedId = :feedId AND title = :title")
    long checkTitle(long feedId, String title);

    @Query("SELECT id FROM history_table WHERE feedId = :feedId AND link = :link")
    long checkLink(long feedId, String link);

    @Query("DELETE FROM history_table WHERE feedId = :feedId AND insertDate < :thirtyDaysAgo")
    void deleteOldHistoriesByFeedId(long feedId, Date thirtyDaysAgo);

    @Query("DELETE FROM history_table WHERE feedId = :feedId")
    void deleteByFeedId(long feedId);
}
