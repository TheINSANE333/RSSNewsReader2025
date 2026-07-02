package xiangze.mmu.rssnewsreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Date;

import xiangze.mmu.rssnewsreader.data.database.AppDatabase;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryDao;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.data.feed.FeedDao;
import xiangze.mmu.rssnewsreader.data.history.History;
import xiangze.mmu.rssnewsreader.data.history.HistoryDao;

@RunWith(AndroidJUnit4.class)
public class DatabaseIntegrationTest {

    private AppDatabase db;
    private FeedDao feedDao;
    private EntryDao entryDao;
    private HistoryDao historyDao;

    @Before
    public void createDb() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        
        feedDao = db.feedDao();
        entryDao = db.entryDao();
        historyDao = db.historyDao();
    }

    @After
    public void closeDb() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    public void testEssentialFlows() {
        // --- 1. TEST ADD FEED ---
        Feed testFeed = new Feed("Tech News", "https://techcrunch.com/feed/", "Latest technology stories", "http://image.png", "en");
        feedDao.insert(testFeed);
        
        long feedId = feedDao.getIdByLink("https://techcrunch.com/feed/");
        assertTrue("Feed should be inserted with a valid ID", feedId > 0);
        
        Feed savedFeed = feedDao.getFeedById(feedId);
        assertNotNull(savedFeed);
        assertEquals("Tech News", savedFeed.getTitle());

        // --- 2. TEST ARTICLE ENTRY STORAGE ---
        Entry testEntry = new Entry(feedId, "AI Evolution", "https://techcrunch.com/ai-2026", "AI is fast", "http://image2.png", "Tech", new Date());
        long entryId = entryDao.insert(testEntry);
        assertTrue("Entry should be inserted with a valid ID", entryId > 0);

        // --- 3. TEST FULL-TEXT EXTRACTION/CACHING UPDATE ---
        String cleanContent = "Artificial intelligence has evolved significantly in 2026.";
        String cleanHtml = "<p>Artificial intelligence has evolved significantly in 2026.</p>";
        
        // Write the extracted text and set preload status (isCached)
        entryDao.updateContent(cleanContent, entryId);
        entryDao.updateHtml(cleanHtml, entryId);
        entryDao.updatePreloadStatus(entryId, true);
        
        Entry cachedEntry = entryDao.getEntryById(entryId);
        assertNotNull(cachedEntry);
        assertTrue("Preload status isCached should be true", cachedEntry.isCached());
        assertEquals(cleanContent, cachedEntry.getContent());
        assertEquals(cleanHtml, cachedEntry.getHtml());

        // --- 4. TEST AI TRANSLATION AND SUMMARIZATION SAVING ---
        // Write translation and summarization values to the entry
        entryDao.updateTranslatedPair(entryId, "Artifisiell intelligens har utviklet seg.", "<p>Artifisiell intelligens har utviklet seg.</p>");
        entryDao.updateSummarizedPair(entryId, "AI is fast in 2026.", "<p>AI is fast in 2026.</p>");
        
        Entry aiEnhancedEntry = entryDao.getEntryById(entryId);
        assertNotNull(aiEnhancedEntry);
        assertEquals("Artifisiell intelligens har utviklet seg.", aiEnhancedEntry.getTranslated());
        assertEquals("AI is fast in 2026.", aiEnhancedEntry.getSummarized());

        // --- 5. TEST MARK AS READ / HISTORY LOGGING ---
        History testHistory = new History(feedId, new Date(), aiEnhancedEntry.getTitle(), aiEnhancedEntry.getLink());
        historyDao.insert(testHistory);
        
        long historyId = historyDao.checkLink(feedId, aiEnhancedEntry.getLink());
        assertTrue("History log should be generated under the entry link", historyId > 0);

        // --- 6. TEST DELETE ENTRY / FEED REMOVAL ---
        entryDao.deleteByFeedId(feedId).blockingAwait();
        Entry deletedEntry = entryDao.getEntryById(entryId);
        org.junit.Assert.assertNull("Entry should be deleted after clearing feed ID", deletedEntry);
    }
}
