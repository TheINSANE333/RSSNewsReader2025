package xiangze.mmu.rssnewsreader.data.entry;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RewriteQueriesToDropUnusedColumns;
import androidx.room.RoomWarnings;
import androidx.room.Update;

import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.model.EntryListItem;

import java.util.Date;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;

@Dao
public interface EntryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(Entry entry);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertEntries(List<Entry> entries);

    @Update
    Completable update(Entry entry);

    @Delete
    Completable delete(Entry entry);

    @Query("DELETE FROM entry_table WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM entry_table WHERE id IN (:ids) AND bookmark is not 'Y'")
    void deleteByIds(List<Long> ids);

    @Query("DELETE FROM entry_table WHERE feedId = :feedId")
    Completable deleteByFeedId(long feedId);

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id")
    Flowable<List<EntryInfo>> getAllEntriesInfo();

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE bookmark = 'Y'")
    Flowable<List<EntryInfo>> getEntriesByBookmark();

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE visitedDate is null")
    Flowable<List<EntryInfo>> getEntriesByUnread();

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE visitedDate is not null")
    Flowable<List<EntryInfo>> getEntriesByRead();

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE bookmark = 'Y' AND e.feedId = :id")
    Flowable<List<EntryInfo>> getEntriesByBookmark(long id);

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE visitedDate is null AND e.feedId = :id")
    Flowable<List<EntryInfo>> getEntriesByUnread(long id);

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE visitedDate is not null AND e.feedId = :id")
    Flowable<List<EntryInfo>> getEntriesByRead(long id);

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE e.feedId = :id")
    Flowable<List<EntryInfo>> getEntriesByFeed(long id);

    @RewriteQueriesToDropUnusedColumns
    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.bookmark as bookmark, e.priority as priority, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "SUBSTR(e.summarized, 1, 200) as summarizedSnippet, " +
            "f.id as feedId, f.title as feedTitle, f.imageUrl as feedImageUrl, f.ttsSpeechRate as ttsSpeechRate " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "ORDER BY e.publishedDate DESC")
    LiveData<List<EntryListItem>> getAllEntriesListLive();

    @Query("SELECT e.* FROM entry_table e " +
            "WHERE e.visitedDate is null AND e.feedId IN (:feedIds)")
    Flowable<List<Entry>> getUnreadEntriesForFeedsEntity(List<Long> feedIds);

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "INNER JOIN feed_table f ON e.feedId = f.id " +
            "WHERE e.visitedDate is null AND f.id IN (:feedIds)")
    Flowable<List<EntryInfo>> getUnreadEntriesForFeeds(List<Long> feedIds);

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "INNER JOIN feed_table f ON e.feedId = f.id " +
            "WHERE e.visitedDate is null AND f.autoSummarize = 1")
    Flowable<List<EntryInfo>> getUnreadEntriesForSummarization();

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "ORDER BY e.publishedDate DESC")
    LiveData<List<EntryInfo>> getAllEntriesInfoLive();

    @Query("SELECT e.* " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE f.id = :id")
    List<Entry> getStaticEntriesByFeed(long id);

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "INNER JOIN feed_table f ON e.feedId = f.id " +
            "WHERE (e.summarized_html IS NULL OR e.summarized_html NOT LIKE '%summarized-title%') AND (e.original_html IS NOT NULL AND e.original_html != '') " +
            "AND f.autoSummarize = 1 " +
            "ORDER BY CASE WHEN e.priority = 0 THEN 999999 ELSE e.priority END ASC, e.id DESC")
    List<EntryInfo> getUnsummarizedEntriesInfo();

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "INNER JOIN feed_table f ON e.feedId = f.id " +
            "WHERE (e.translated_html IS NULL OR e.translated_html NOT LIKE '%translated-title%') AND (e.original_html IS NOT NULL AND e.original_html != '') " +
            "AND f.autoTranslate = 1 " +
            "ORDER BY CASE WHEN e.priority = 0 THEN 999999 ELSE e.priority END ASC, e.id DESC")
    List<EntryInfo> getUntranslatedEntriesInfo();

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT e.id as entryId, e.title as entryTitle, e.priority as priority, e.link as entryLink, e.description as entryDescription, e.imageUrl as entryImageUrl, e.publishedDate as entryPublishedDate, e.visitedDate as visitedDate, e.category as entryCategory, e.bookmark as bookmark, " +
            "(e.content IS NOT NULL AND e.content != '') as hasContent, " +
            "(e.original_html IS NOT NULL AND e.original_html != '') as hasOriginalHtml, " +
            "(e.translated_html IS NOT NULL AND e.translated_html != '') as hasTranslated, " +
            "(e.summarized_html IS NOT NULL AND e.summarized_html != '') as hasSummarized, " +
            "f.id as feedId, f.ttsSpeechRate as ttsSpeechRate, f.language as feedLanguage, f.title as feedTitle, f.imageUrl as feedImageUrl " +
            "FROM entry_table e " +
            "LEFT JOIN feed_table f ON e.feedId = f.id " +
            "WHERE e.id = :id")
    EntryInfo getEntryInfoById(long id);

    @Query("SELECT * FROM entry_table WHERE isCached = 1 AND priority > 0 ORDER BY priority ASC")
    List<Entry> getPreloadedEntries();

    @Query("SELECT * FROM entry_table WHERE isCached = 0 AND imageUrl IS NOT NULL AND imageUrl != ''")
    List<Entry> getUncachedEntries();

    @Query("UPDATE entry_table SET isCached = :isCached WHERE id = :entryId")
    void updatePreloadStatus(long entryId, boolean isCached);

    @Query("SELECT id FROM entry_table WHERE id = :id")
    long checkEntryExist(long id);

    @Query("SELECT content FROM entry_table WHERE id = :id")
    String getContentById(long id);

    @Query("SELECT html FROM entry_table WHERE id = :id")
    String getHtmlById(long id);

    @Query("UPDATE entry_table SET visitedDate = :date WHERE id = :entryId")
    void updateDate(Date date, long entryId);

    @Query("UPDATE entry_table SET content = :content WHERE id = :id")
    void updateContent(String content, long id);

    @Query("UPDATE entry_table SET html = :html WHERE id = :id")
    void updateHtml(String html, long id);

    @Query("UPDATE entry_table SET translated_html = :html WHERE id = :id")
    void updateTranslatedHtml(String html, long id);

    @Query("SELECT translated_html FROM entry_table WHERE id = :id")
    String getTranslatedHtmlById(long id);

    @Query("UPDATE entry_table SET summarized_html = :html WHERE id = :id")
    void updateSummarizedHtml(String html, long id);

    @Query("SELECT summarized_html FROM entry_table WHERE id = :id")
    String getSummarizedHtmlById(long id);

    @Query("SELECT id FROM entry_table ORDER BY visitedDate DESC LIMIT 1")
    long getLastVisitedEntryId();

    @Query("SELECT visitedDate FROM entry_table WHERE id = :id")
    Date checkIsVisited(long id);

    @Query("SELECT * FROM entry_table WHERE content is null AND priority != 0 ORDER BY priority ASC LIMIT 1")
    Entry getEmptyEntryOrderByPrior();

    @Query("SELECT * FROM entry_table WHERE content is null")
    Entry getEmptyEntry();

    @Query("SELECT id FROM entry_table WHERE feedId = :id")
    List<Long> getIdsByFeedId(long id);

    @Query("SELECT id FROM entry_table WHERE visitedDate is not null AND bookmark is not 'Y'")
    List<Long> getAllVisitedEntriesId();

    @Query("DELETE FROM entry_table WHERE visitedDate is not null AND bookmark is not 'Y'")
    void deleteAllVisitedEntries();

    @Query("UPDATE entry_table SET priority = 0 WHERE priority != 0")
    void clearPriority();

    @Query("UPDATE entry_table SET priority = :priority WHERE id = :id")
    void updatePriority(int priority, long id);

    @Query("UPDATE entry_table SET sentCountStopAt = :sentCount WHERE id = :id")
    void updateSentCount(int sentCount, long id);

    @Query("UPDATE entry_table SET sentCountStopAt = :sentCount WHERE id = :id")
    void updateSentCountByLink(int sentCount, long id);

    @Query("UPDATE entry_table SET title = :title WHERE feedId = :feedId AND link = :link")
    void updateTitle(long feedId, String title, String link);

    @Query("UPDATE entry_table SET link = :link WHERE feedId = :feedId AND title = :title")
    void updateLink(long feedId, String title, String link);

    @Query("UPDATE entry_table SET bookmark = :bool WHERE id = :id")
    void updateBookmark(String bool, long id);

    @Query("UPDATE entry_table SET content = null, html = null, sentCountStopAt = 0 WHERE feedId = :id")
    void updateContentByFeedId(long id);

    @Query("SELECT * FROM entry_table WHERE feedId = :feedId")
    List<Entry> getEntriesByFeedId(long feedId);

    @Query("SELECT sentCountStopAt FROM entry_table WHERE id = :id")
    int getSentCount(long id);

    @Query("SELECT bookmark FROM entry_table WHERE id = :id")
    String getBookmark(long id);

    @Query("SELECT COUNT(id) FROM entry_table WHERE visitedDate is null AND bookmark = 'Y'")
    Flowable<Integer> getUnreadCountByBookmark();

    @Query("SELECT COUNT(id) FROM entry_table WHERE visitedDate is null AND feedId = :id AND bookmark = 'Y'")
    Flowable<Integer> getUnreadCountByBookmark(long id);

    @Query("SELECT COUNT(id) FROM entry_table WHERE visitedDate is null")
    Flowable<Integer> getUnreadCount();

    @Query("SELECT COUNT(id) FROM entry_table WHERE visitedDate is null AND feedId = :id")
    Flowable<Integer> getUnreadCount(long id);

    @Query("DELETE FROM entry_table WHERE feedId = :feedId AND id NOT IN (SELECT id FROM entry_table WHERE feedId = :feedId ORDER BY publishedDate DESC LIMIT :limit) AND id NOT IN (SELECT id FROM entry_table WHERE bookmark = 'Y' AND feedId = :feedId)")
    void limitEntriesByFeed(long feedId, int limit);

    @Query("UPDATE entry_table SET priority = 1 WHERE content IS NULL AND priority = 0")
    void requeueMissingEntries();

    @Query("SELECT * FROM entry_table WHERE id = :id")
    Entry getEntryById(long id);

    @Query("SELECT * FROM entry_table WHERE id = :id")
    LiveData<Entry> getEntryEntityById(long id);

    @Query("UPDATE entry_table SET translated = :translated, translated_html = :translatedHtml WHERE id = :id")
    void updateTranslatedPair(long id, String translated, String translatedHtml);

    @Query("UPDATE entry_table SET summarized = :summarized, summarized_html = :summarizedHtml WHERE id = :id")
    void updateSummarizedPair(long id, String summarized, String summarizedHtml);

    @Query("UPDATE entry_table SET html = :html, summarized = :summarized, summarized_html = :summarizedHtml WHERE id = :id")
    void updateSummarizedResult(long id, String html, String summarized, String summarizedHtml);

    @Query("UPDATE entry_table SET html = :html, translated = :translated, translated_html = :translatedHtml, title = :title WHERE id = :id")
    void updateTranslatedResult(long id, String html, String translated, String translatedHtml, String title);

    @Query("SELECT e.* FROM entry_table e " +
            "INNER JOIN feed_table f ON e.feedId = f.id " +
            "WHERE (e.original_html IS NOT NULL OR e.html IS NOT NULL) AND (e.translated_html IS NULL OR e.translated_html NOT LIKE '%translated-title%') " +
            "AND f.autoTranslate = 1 " +
            "ORDER BY CASE WHEN e.priority = 0 THEN 999999 ELSE e.priority END ASC, e.id DESC")
    List<Entry> getUntranslatedEntries();

    @Query("SELECT e.* FROM entry_table e " +
            "INNER JOIN feed_table f ON e.feedId = f.id " +
            "WHERE (e.original_html IS NOT NULL OR e.html IS NOT NULL) AND (e.summarized_html IS NULL OR e.summarized_html NOT LIKE '%summarized-title%') " +
            "AND f.autoSummarize = 1 " +
            "ORDER BY CASE WHEN e.priority = 0 THEN 999999 ELSE e.priority END ASC, e.id DESC")
    List<Entry> getUnsummarizedEntries();

    @Query("SELECT original_html FROM entry_table WHERE id = :id")
    String getOriginalHtmlById(long id);

    @Query("UPDATE entry_table SET original_html = :originalHtml WHERE id = :id")
    void updateOriginalHtml(String originalHtml, long id);

    @Query("UPDATE entry_table SET translated = :translated WHERE id = :id")
    void updateTranslated(String translated, long id);

    @Query("UPDATE entry_table SET translated = :translated WHERE id = :id")
    void updateTranslatedText(String translated, long id);

    @Query("UPDATE entry_table SET summarized = :summarized WHERE id = :id")
    void updateSummarized(String summarized, long id);

    @Query("UPDATE entry_table SET summarized = :summarized WHERE id = :id")
    void updateSummarizedText(String summarized, long id);

    @Query("UPDATE entry_table SET summarized = NULL, summarized_html = NULL WHERE id = :id")
    void resetSummarized(long id);

    @Query("UPDATE entry_table SET translated = NULL, translated_html = NULL WHERE id = :id")
    void resetTranslated(long id);

}
