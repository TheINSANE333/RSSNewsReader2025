package xiangze.mmu.rssnewsreader.service.rss;

import android.util.Log;

import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public class RssReader {
    private String rssUrl;
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    public RssReader(String url) {
        rssUrl = url.replace("http://", "https://");;
    }

    public RssFeed getFeed() throws Exception {
        HttpURLConnection connection = null;
        try {
            // Create a connection to the RSS URL
            URL url = new URL(rssUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/rss+xml, text/xml");

            // Check response code
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("RssReader", "Failed to fetch RSS feed: HTTP " + responseCode);
                throw new Exception("Failed to fetch RSS feed: HTTP " + responseCode);
            }

            // Parse the RSS feed using a SAX parser
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            RssHandler handler = new RssHandler();

            // Parse the input stream
            Reader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            InputSource inputSource = new InputSource(reader);
            saxParser.parse(inputSource, handler);

            // Return the parsed feed
            RssFeed feed = handler.getRssFeed();

            // Validate the parsed feed
            if (feed == null || feed.getRssItems().isEmpty()) {
                Log.e("RssReader", "Parsed RSS feed is empty or invalid.");
                throw new Exception("Parsed RSS feed is empty or invalid.");
            }

            return feed;

        } catch (Exception e) {
            Log.e("RssReader", "Error while fetching or parsing RSS feed: " + e.getMessage() + ". Trying Web Scraper fallback.");
            try {
                // Fallback to Web Scraper
                WebFeedReader webFeedReader = new WebFeedReader(rssUrl);
                return webFeedReader.getFeed();
            } catch (Exception webEx) {
                Log.e("RssReader", "Web Scraper also failed: " + webEx.getMessage());
                // Throw the ORIGINAL exception to show why RSS failed, or maybe a combined one
                throw e;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
