package xiangze.mmu.rssnewsreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import xiangze.mmu.rssnewsreader.service.rss.RssFeed;
import xiangze.mmu.rssnewsreader.service.rss.RssHandler;

@RunWith(AndroidJUnit4.class)
public class RssHandlerTest {

    @Test
    public void testParseMockXmlFeed() throws Exception {
        String mockXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<rss version=\"2.0\">\n" +
                "  <channel>\n" +
                "    <title>Mock Tech News</title>\n" +
                "    <link>https://example.com/tech</link>\n" +
                "    <description>Latest mock tech stories</description>\n" +
                "    <language>en-us</language>\n" +
                "    <item>\n" +
                "      <title>AI Revolution in 2026</title>\n" +
                "      <link>https://example.com/tech/ai-2026</link>\n" +
                "      <description>AI technology is advancing rapidly.</description>\n" +
                "      <pubDate>Tue, 30 Jun 2026 12:00:00 GMT</pubDate>\n" +
                "      <category>Technology</category>\n" +
                "    </item>\n" +
                "  </channel>\n" +
                "</rss>";

        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser parser = factory.newSAXParser();
        RssHandler handler = new RssHandler();
        
        parser.parse(new ByteArrayInputStream(mockXml.getBytes()), handler);
        RssFeed feed = handler.getRssFeed();

        assertNotNull("RssFeed should not be null after parsing", feed);
        assertEquals("Mock Tech News", feed.getTitle());
        assertEquals("https://example.com/tech", feed.getLink());
        assertEquals("Latest mock tech stories", feed.getDescription());
        assertEquals("en-us", feed.getLanguage());

        assertNotNull("Feed items list should not be null", feed.getRssItems());
        assertEquals(1, feed.getRssItems().size());
        assertEquals("AI Revolution in 2026", feed.getRssItems().get(0).getTitle());
        assertEquals("https://example.com/tech/ai-2026", feed.getRssItems().get(0).getLink());
        assertEquals("AI technology is advancing rapidly.", feed.getRssItems().get(0).getDescription());
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US);
        java.util.Date expectedDate = sdf.parse("Tue, 30 Jun 2026 12:00:00 GMT");
        assertEquals(expectedDate, feed.getRssItems().get(0).getPubDate());
        assertEquals("Technology", feed.getRssItems().get(0).getCategory());
    }
}
