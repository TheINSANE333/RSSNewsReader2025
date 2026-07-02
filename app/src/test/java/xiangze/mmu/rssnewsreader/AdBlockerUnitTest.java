package xiangze.mmu.rssnewsreader;

import org.junit.Test;
import static org.junit.Assert.*;

import xiangze.mmu.rssnewsreader.service.util.AdBlocker;

public class AdBlockerUnitTest {

    @Test
    public void testAdUrlDetection() {
        assertTrue(AdBlocker.isAd("https://googleads.g.doubleclick.net/pagead/ads"));
        assertTrue(AdBlocker.isAd("https://www.google-analytics.com/analytics.js"));
        assertTrue(AdBlocker.isAd("https://static.criteo.com/js/ld/ld.js"));
    }

    @Test
    public void testCleanUrlDetection() {
        assertFalse(AdBlocker.isAd("https://www.nytimes.com/section/technology"));
        assertFalse(AdBlocker.isAd("https://bbc.co.uk/news/world"));
        assertFalse(AdBlocker.isAd(""));
        assertFalse(AdBlocker.isAd(null));
    }
}
