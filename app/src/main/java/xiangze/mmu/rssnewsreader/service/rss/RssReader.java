package xiangze.mmu.rssnewsreader.service.rss;

import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public class RssReader {
    private static final String TAG = "RssReader";
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
            Log.e("RssReader", "Error while fetching or parsing RSS feed: " + e.getMessage() + ". Trying RSS auto-discovery.");

            // --- Tier 2: Try RSS auto-discovery from HTML <link> tags ---
            try {
                RssFeed discoveredFeed = tryAutoDiscoverRssFeed(rssUrl);
                if (discoveredFeed != null) {
                    return discoveredFeed;
                }
            } catch (Exception discoverEx) {
                Log.e(TAG, "RSS auto-discovery failed: " + discoverEx.getMessage());
            }

            // --- Tier 3: Fallback to Web Scraper ---
            Log.d(TAG, "RSS auto-discovery found nothing. Trying Web Scraper fallback.");
            try {
                WebFeedReader webFeedReader = new WebFeedReader(rssUrl);
                return webFeedReader.getFeed();
            } catch (Exception webEx) {
                Log.e("RssReader", "Web Scraper also failed: " + webEx.getMessage());
                // Throw the ORIGINAL exception to show why RSS failed
                throw e;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Attempts to auto-discover RSS/Atom feed URLs from the HTML page's <link> tags.
     * For example: <link rel="alternate" type="application/rss+xml" href="https://bbc.com/feed">
     *
     * @param pageUrl The URL of the HTML page to scan
     * @return A parsed RssFeed if a valid feed URL was discovered, or null if none found
     */
    private RssFeed tryAutoDiscoverRssFeed(String pageUrl) {
        try {
            Log.d(TAG, "Attempting RSS auto-discovery on: " + pageUrl);

            Document doc = Jsoup.connect(pageUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(8000)
                    .followRedirects(true)
                    .get();

            // Look for RSS and Atom feed links in the <head>
            Elements feedLinks = doc.select(
                    "link[rel=alternate][type=application/rss+xml], " +
                    "link[rel=alternate][type=application/atom+xml], " +
                    "link[rel=alternate][type=text/xml]"
            );

            if (feedLinks.isEmpty()) {
                Log.d(TAG, "No RSS/Atom feed links found in HTML head.");
                return null;
            }

            // Collect all discovered feed URLs
            List<String> feedUrls = new ArrayList<>();
            for (Element feedLink : feedLinks) {
                String href = feedLink.attr("abs:href");
                if (href != null && !href.isEmpty()) {
                    feedUrls.add(href);
                    Log.d(TAG, "Discovered feed: " + feedLink.attr("title") + " -> " + href);
                }
            }

            if (feedUrls.isEmpty()) {
                return null;
            }

            // Try each discovered feed URL until one works
            for (String feedUrl : feedUrls) {
                try {
                    Log.d(TAG, "Trying discovered feed URL: " + feedUrl);
                    RssReader discoveredReader = new RssReader(feedUrl);
                    RssFeed feed = discoveredReader.parseDirectFeed(feedUrl);
                    if (feed != null && !feed.getRssItems().isEmpty()) {
                        Log.d(TAG, "Successfully parsed auto-discovered feed: " + feedUrl + " with " + feed.getRssItems().size() + " items.");
                        // Use the original page URL as the feed link (not the RSS feed URL)
                        feed.setLink(pageUrl);
                        return feed;
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "Failed to parse discovered feed URL: " + feedUrl + " - " + ex.getMessage());
                    // Continue trying the next one
                }
            }

            Log.d(TAG, "None of the " + feedUrls.size() + " discovered feed URLs could be parsed.");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error during RSS auto-discovery: " + e.getMessage());
            return null;
        }
    }

    /**
     * Directly parses an RSS/Atom feed URL without any fallback logic.
     * Used internally by auto-discovery to avoid recursive fallback loops.
     */
    private RssFeed parseDirectFeed(String feedUrl) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(feedUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, text/xml");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + responseCode);
            }

            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            RssHandler handler = new RssHandler();

            Reader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            InputSource inputSource = new InputSource(reader);
            saxParser.parse(inputSource, handler);

            return handler.getRssFeed();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
