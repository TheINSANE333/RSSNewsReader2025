package xiangze.mmu.rssnewsreader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import xiangze.mmu.rssnewsreader.service.rss.RssFeed;
import xiangze.mmu.rssnewsreader.service.rss.RssReader;

@RunWith(AndroidJUnit4.class)
public class RssReaderTest {

    @Test
    public void testFetchAndParseLiveFeed() {
        // Use a highly stable and open RSS feed URL
        String feedUrl = "https://rss.nytimes.com/services/xml/rss/nyt/Technology.xml";
        RssReader reader = new RssReader(feedUrl);
        
        try {
            RssFeed feed = reader.getFeed(10); // Limit to 10 articles
            
            assertNotNull("Feed should not be null", feed);
            assertNotNull("Feed title should not be null", feed.getTitle());
            assertFalse("Feed title should not be empty", feed.getTitle().trim().isEmpty());
            
            // The feed should contain technology news
            assertTrue("Feed title should contain 'Technology' or 'New York Times'", 
                    feed.getTitle().toLowerCase().contains("technology") || 
                    feed.getTitle().toLowerCase().contains("times"));
            
            assertNotNull("Feed items list should not be null", feed.getRssItems());
            assertFalse("Feed should contain at least one item", feed.getRssItems().isEmpty());
            
            // Check properties of the first item
            assertNotNull("First item title should not be null", feed.getRssItems().get(0).getTitle());
            assertFalse("First item title should not be empty", feed.getRssItems().get(0).getTitle().trim().isEmpty());
            assertNotNull("First item link should not be null", feed.getRssItems().get(0).getLink());
            
        } catch (Exception e) {
            // If the machine is offline, log a message, but fail the test as network is expected for live tests
            e.printStackTrace();
            org.junit.Assert.fail("Failed to fetch or parse live feed: " + e.getMessage());
        }
    }
}
