package xiangze.mmu.rssnewsreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xiangze.mmu.rssnewsreader.data.ai.Message;
import xiangze.mmu.rssnewsreader.data.database.AppDatabase;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryDao;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.data.feed.FeedDao;
import xiangze.mmu.rssnewsreader.data.history.History;
import xiangze.mmu.rssnewsreader.data.history.HistoryDao;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.ai.AiClient;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;

@RunWith(AndroidJUnit4.class)
public class UseCasesTest {

    private Context context;
    private AppDatabase db;
    private FeedDao feedDao;
    private EntryDao entryDao;
    private HistoryDao historyDao;
    private SharedPreferencesRepository prefsRepo;
    private TextUtil textUtil;

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        
        feedDao = db.feedDao();
        entryDao = db.entryDao();
        historyDao = db.historyDao();
        prefsRepo = new SharedPreferencesRepository(context);
        textUtil = new TextUtil(prefsRepo);
    }

    @After
    public void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    // --- UC-01: Change Settings ---
    @Test
    public void testUC01_ChangeSettings() {
        // Update user settings
        prefsRepo.setTtsSubstitutions(new HashMap<>());
        
        // Assert settings persist successfully
        Map<String, String> substitutions = new HashMap<>();
        substitutions.put("AI", "Artificial Intelligence");
        prefsRepo.setTtsSubstitutions(substitutions);
        
        Map<String, String> retrieved = prefsRepo.getTtsSubstitutions();
        assertNotNull(retrieved);
        assertEquals("Artificial Intelligence", retrieved.get("AI"));
    }

    // --- UC-02 & UC-03: Load & Save App State (OPML / Settings Configuration) ---
    @Test
    public void testUC02_UC03_LoadSaveAppState() {
        // Verifying preference states serialization
        String testApiKey = "gsk_testapikey1234567890";
        prefsRepo.setGroqApiKey(testApiKey);
        
        String savedKey = prefsRepo.getGroqApiKey();
        assertEquals(testApiKey, savedKey);
    }

    // --- UC-04: Add New Feeds ---
    @Test
    public void testUC04_AddNewFeeds() {
        Feed feed = new Feed("Test Feed", "https://example.com/feed.xml", "Description", "http://image.png", "en");
        feedDao.insert(feed);
        
        long id = feedDao.getIdByLink("https://example.com/feed.xml");
        assertTrue("Feed should be added and return valid ID", id > 0);
    }

    // --- UC-05: Edit Feed ---
    @Test
    public void testUC05_EditFeed() {
        Feed feed = new Feed("Test Feed", "https://example.com/feed.xml", "Description", "http://image.png", "en");
        feedDao.insert(feed);
        
        // Update setting values
        feedDao.updateFeedSettings("Updated Feed Title", "New Description", "no", true, true, 30, 1.0f, "https://example.com/feed.xml");
        
        long id = feedDao.getIdByLink("https://example.com/feed.xml");
        Feed updatedFeed = feedDao.getFeedById(id);
        assertNotNull(updatedFeed);
        assertEquals("Updated Feed Title", updatedFeed.getTitle());
        assertTrue(updatedFeed.isAutoSummarize());
    }

    // --- UC-06: Refresh Feed ---
    @Test
    public void testUC06_RefreshFeed() {
        Feed feed = new Feed("Test Feed", "https://example.com/feed.xml", "Description", "http://image.png", "en");
        feedDao.insert(feed);
        long feedId = feedDao.getIdByLink("https://example.com/feed.xml");
        
        Entry entry = new Entry(feedId, "Title", "https://example.com/1", "desc", null, "cat", new Date());
        entryDao.insert(entry);
        
        // Refresh simulation - deletes old articles
        entryDao.deleteByFeedId(feedId).blockingAwait();
        List<Entry> entries = entryDao.getStaticEntriesByFeed(feedId);
        assertTrue("Articles list should be empty after refresh-delete", entries.isEmpty());
    }

    // --- UC-07: Delete Feed ---
    @Test
    public void testUC07_DeleteFeed() {
        Feed feed = new Feed("Test Feed", "https://example.com/feed.xml", "Description", "http://image.png", "en");
        feedDao.insert(feed);
        long feedId = feedDao.getIdByLink("https://example.com/feed.xml");
        feed.setId(feedId);
        
        // Delete operations
        entryDao.deleteByFeedId(feedId).blockingAwait();
        feedDao.delete(feed).blockingAwait();
        
        Feed deletedFeed = feedDao.getFeedById(feedId);
        assertNull("Feed should be null after delete", deletedFeed);
    }

    // --- UC-08: View News Article ---
    @Test
    public void testUC08_ViewNewsArticle() {
        Feed feed = new Feed("Test Feed", "https://example.com/feed.xml", "Description", "http://image.png", "en");
        feedDao.insert(feed);
        long feedId = feedDao.getIdByLink("https://example.com/feed.xml");
        
        Entry entry = new Entry(feedId, "Title", "https://example.com/1", "desc", null, "cat", new Date());
        long entryId = entryDao.insert(entry);
        
        Entry viewed = entryDao.getEntryById(entryId);
        assertNotNull(viewed);
        assertEquals("Title", viewed.getTitle());
    }

    // --- UC-09: Translate/Summarize News Article ---
    @Test
    public void testUC09_TranslateSummarizeNewsArticle() {
        Feed feed = new Feed("Test Feed", "https://example.com/feed.xml", "Description", "http://image.png", "en");
        feedDao.insert(feed);
        long feedId = feedDao.getIdByLink("https://example.com/feed.xml");
        
        Entry entry = new Entry(feedId, "Title", "https://example.com/1", "desc", null, "cat", new Date());
        long entryId = entryDao.insert(entry);
        
        // Save translated text
        entryDao.updateTranslatedPair(entryId, "Translated text", "<p>Translated text</p>");
        entryDao.updateSummarizedPair(entryId, "Summarized text", "<p>Summarized text</p>");
        
        Entry result = entryDao.getEntryById(entryId);
        assertEquals("Translated text", result.getTranslated());
        assertEquals("Summarized text", result.getSummarized());
    }

    // --- UC-10: Share News Article ---
    @Test
    public void testUC10_ShareNewsArticle() {
        Feed feed = new Feed("Test Feed", "https://example.com/feed.xml", "Description", "http://image.png", "en");
        feedDao.insert(feed);
        long feedId = feedDao.getIdByLink("https://example.com/feed.xml");
        
        Entry entry = new Entry(feedId, "Title", "https://example.com/1", "desc", null, "cat", new Date());
        long entryId = entryDao.insert(entry);
        
        Entry shareTarget = entryDao.getEntryById(entryId);
        assertNotNull(shareTarget);
        // Assert we get the correct URL and title to verify intent payload formatting
        assertEquals("https://example.com/1", shareTarget.getLink());
        assertEquals("Title", shareTarget.getTitle());
    }

    // --- UC-11: Text-to-speech (TTS) ---
    @Test
    public void testUC11_TextToSpeech() {
        String input = "This is a sentence. And here is another.";
        String delimited = textUtil.splitIntoSentences(input, "|");
        
        // Verify sentence boundary tokenization to support step-by-step speech highlights
        assertEquals("This is a sentence.|And here is another.", delimited);
    }

    // --- UC-12: Chatbot ---
    @Test
    public void testUC12_Chatbot() {
        String originalKey = prefsRepo.getGroqApiKey();
        // Clear or write invalid key to test exception handling
        prefsRepo.setGroqApiKey("gsk_invalidkey123");
        
        try {
            AiClient aiClient = new AiClient(context);
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("user", "Hello Chatbot"));
            
            aiClient.getChatResponse(messages, "gemma2-9b-it");
            fail("Should throw exception on invalid key connection");
        } catch (IOException e) {
            // Success: Chatbot handles key authentication failure gracefully
            assertNotNull(e.getMessage());
        } finally {
            prefsRepo.setGroqApiKey(originalKey);
        }
    }

    // --- UC-13: Daily Summarization ---
    @Test
    public void testUC13_DailySummarization() {
        Feed feed = new Feed("Test Feed", "https://example.com/feed.xml", "Description", "http://image.png", "en");
        feed.setAutoSummarize(true);
        feedDao.insert(feed);
        long feedId = feedDao.getIdByLink("https://example.com/feed.xml");
        
        Entry entry = new Entry(feedId, "Title", "https://example.com/1", "desc", null, "cat", new Date());
        entry.setOriginalHtml("<html><body>Some long content</body></html>");
        entryDao.insert(entry);
        
        // Find unsummarized items for autoSummarize feeds (daily summaries queue)
        List<Entry> unsummarized = entryDao.getUnsummarizedEntries();
        assertFalse(unsummarized.isEmpty());
        assertEquals(feedId, unsummarized.get(0).getFeedId());
    }
}
