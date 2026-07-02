package xiangze.mmu.rssnewsreader;

import org.junit.Test;
import static org.junit.Assert.*;

import xiangze.mmu.rssnewsreader.data.feed.Feed;

public class FeedModelTest {

    @Test
    public void testFeedCreationAndProperties() {
        Feed feed = new Feed("Tech Crunch", "https://techcrunch.com/feed/", "Latest Tech", "https://techcrunch.com/logo.png", "en");
        
        assertEquals("Tech Crunch", feed.getTitle());
        assertEquals("https://techcrunch.com/feed/", feed.getLink());
        assertEquals("Latest Tech", feed.getDescription());
        assertEquals("https://techcrunch.com/logo.png", feed.getImageUrl());
        assertEquals("en", feed.getLanguage());
        assertTrue(feed.isAutoSummarize());
        assertTrue(feed.isAutoTranslate());
    }

    @Test
    public void testFeedSettingsToggle() {
        Feed feed = new Feed("Tech Crunch", "https://techcrunch.com/feed/", "Latest Tech", "https://techcrunch.com/logo.png", "en");
        
        feed.setAutoSummarize(false);
        feed.setAutoTranslate(false);
        feed.setTtsSpeechRate(1.25f);

        assertFalse(feed.isAutoSummarize());
        assertFalse(feed.isAutoTranslate());
        assertEquals(1.25f, feed.getTtsSpeechRate(), 0.001f);
    }
}
