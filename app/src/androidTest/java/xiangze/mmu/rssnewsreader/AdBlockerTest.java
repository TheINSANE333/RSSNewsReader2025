package xiangze.mmu.rssnewsreader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import xiangze.mmu.rssnewsreader.service.util.AdBlocker;

@RunWith(AndroidJUnit4.class)
public class AdBlockerTest {

    @Test
    public void testIsAdWithAdDomains() {
        assertTrue(AdBlocker.isAd("https://doubleclick.net/pagead/ads?client=ca-pub"));
        assertTrue(AdBlocker.isAd("http://googleadservices.com/pagead/conversion.js"));
        assertTrue(AdBlocker.isAd("https://facebook.net/ads/tracker"));
        assertTrue(AdBlocker.isAd("https://quantserve.com/pixel.gif"));
    }

    @Test
    public void testIsAdWithValidDomains() {
        assertFalse(AdBlocker.isAd("https://techcrunch.com/2026/06/30/new-startups"));
        assertFalse(AdBlocker.isAd("https://nytimes.com/services/xml/rss/nyt/Technology.xml"));
        assertFalse(AdBlocker.isAd("https://github.com/TheINSANE333/RSSNewsReader2025"));
    }

    @Test
    public void testIsAdWithNullOrEmpty() {
        assertFalse(AdBlocker.isAd(null));
        assertFalse(AdBlocker.isAd(""));
    }
}
