package xiangze.mmu.rssnewsreader;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Date;
import xiangze.mmu.rssnewsreader.data.entry.Entry;

public class EntryModelTest {

    @Test
    public void testEntryInitialization() {
        Date now = new Date();
        Entry entry = new Entry(10L, "AI breakthrough", "https://example.com/ai", "Short description", "https://example.com/image.jpg", "Tech", now);

        assertEquals(10L, entry.getFeedId());
        assertEquals("AI breakthrough", entry.getTitle());
        assertEquals("https://example.com/ai", entry.getLink());
        assertEquals("Short description", entry.getDescription());
        assertEquals("Tech", entry.getCategory());
        assertEquals(now, entry.getPublishedDate());
        assertNull(entry.getVisitedDate());
        assertNull(entry.getBookmark());
    }

    @Test
    public void testEntryStateTransitions() {
        Date now = new Date();
        Entry entry = new Entry(10L, "AI breakthrough", "https://example.com/ai", "Short description", null, "Tech", now);
        
        entry.setVisitedDate(now);
        entry.setBookmark("Important");
        entry.setTranslated("Translated Title");
        entry.setSummarized("Summary Body");

        assertNotNull(entry.getVisitedDate());
        assertEquals("Important", entry.getBookmark());
        assertEquals("Translated Title", entry.getTranslated());
        assertEquals("Summary Body", entry.getSummarized());
    }
}
