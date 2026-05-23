package xiangze.mmu.rssnewsreader.data.feed;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;

@Dao
public interface FeedDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(Feed feed);

    @Update
    Completable update(Feed feed);

    @Delete
    Completable delete(Feed feed);

    @Query("DELETE FROM feed_table")
    Completable deleteAllFeeds();

    @Query("SELECT * FROM feed_table")
    Flowable<List<Feed>> getAllFeeds();

    @Query("SELECT * FROM feed_table")
    List<Feed> getAllStaticFeeds();

    @Query("SELECT id FROM feed_table WHERE link = :link")
    long getIdByLink(String link);

    @Query("SELECT delayTime FROM feed_table WHERE id = :id")
    int getDelayTimeById(long id);

    @Query("UPDATE feed_table SET delayTime = :delayTime WHERE id = :id")
    void updateDelayTimeById(long id, int delayTime);

    @Query("SELECT COUNT(*) FROM feed_table")
    int getFeedCount();

    @Query("UPDATE feed_table SET title = :title, description = :desc, language = :language, autoSummarize = :autoSummarize, autoTranslate = :autoTranslate, delayTime = :delayTime, ttsSpeechRate = :ttsSpeechRate WHERE link = :link")
    void updateFeedSettings(String title, String desc, String language, boolean autoSummarize, boolean autoTranslate, int delayTime, float ttsSpeechRate, String link);

    @Query("SELECT ttsSpeechRate FROM feed_table WHERE id = :id")
    float getTtsSpeechRateById(long id);

    @Query("UPDATE feed_table SET ttsSpeechRate = :ttsSpeechRate WHERE id = :id")
    void updateTtsSpeechRateById(long id, float ttsSpeechRate);

    @Query("SELECT * FROM feed_table WHERE id = :feedId")
    Feed getFeedById(long feedId);

    @Query("SELECT DISTINCT f.* FROM feed_table f INNER JOIN entry_table e ON f.id = e.feedId WHERE e.visitedDate IS NULL")
    Flowable<List<Feed>> getFeedsWithUnreadArticles();
}
