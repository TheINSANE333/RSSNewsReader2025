package xiangze.mmu.rssnewsreader.service.rss;

import timber.log.Timber;

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

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RssReader {
    
    private String rssUrl;
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.7778.216 Mobile Safari/537.36";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(new CookieJar() {
                private final List<Cookie> cookies = new ArrayList<>();
                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                    this.cookies.addAll(cookies);
                }
                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                    return cookies;
                }
            })
            .build();

    public RssReader(String url) {
        if (url != null) {
            String trimmedUrl = url.trim();
            if (!trimmedUrl.toLowerCase().startsWith("http://") && !trimmedUrl.toLowerCase().startsWith("https://")) {
                trimmedUrl = "https://" + trimmedUrl;
            }
            rssUrl = trimmedUrl.replace("http://", "https://");
        }
    }

    public RssFeed getFeed() throws Exception {
        return getFeed(50); // Default limit
    }

    public RssFeed getFeed(int limit) throws Exception {
        try {
            Timber.d("Fetching RSS feed from: " + rssUrl + " with limit " + limit);
            
            // Tier 1: Try direct parse first (if the URL is already an RSS/Atom feed)
            RssFeed feed = parseDirectFeed(rssUrl);
            if (feed != null && !feed.getRssItems().isEmpty()) {
                // If they want a lot of articles, and the RSS feed is short, 
                // we might want to continue to scraping, but for now let's respect the direct feed.
                return feed;
            }
            
            throw new Exception("Feed is empty or invalid.");

        } catch (Exception e) {
            Timber.e("Initial RSS fetch failed: " + e.getMessage() + ". Trying Web Scraper for: " + rssUrl + " with limit " + limit);

            // --- Tier 2: Try Web Scraper (Now supports pagination) ---
            // We prioritize this over auto-discovery because the user often wants more articles
            // than a standard RSS feed provides, and our scraper can now crawl multiple pages.
            try {
                WebFeedReader webFeedReader = new WebFeedReader(rssUrl);
                RssFeed scrapedFeed = webFeedReader.getFeed(limit);
                if (scrapedFeed != null && !scrapedFeed.getRssItems().isEmpty()) {
                    return scrapedFeed;
                }
            } catch (Exception webEx) {
                Timber.e("Web Scraper fallback failed: " + webEx.getMessage());
            }

            // --- Tier 2.5: Try common RSS feed URL patterns ---
            // Some sites block HTML scraping (403) but serve their RSS feeds normally.
            // Try well-known feed paths derived from the base domain before auto-discovery
            // (which itself requires fetching the HTML page and may also be blocked).
            Timber.d("Trying common RSS feed URL patterns for: " + rssUrl);
            try {
                RssFeed guessedFeed = tryCommonFeedUrls(rssUrl);
                if (guessedFeed != null) {
                    return guessedFeed;
                }
            } catch (Exception guessEx) {
                Timber.e("Common feed URL probing failed: " + guessEx.getMessage());
            }

            // --- Tier 3: Try RSS auto-discovery from HTML <link> tags ---
            Timber.d("Trying RSS auto-discovery as final fallback for: " + rssUrl);
            try {
                RssFeed discoveredFeed = tryAutoDiscoverRssFeed(rssUrl);
                if (discoveredFeed != null) {
                    return discoveredFeed;
                }
            } catch (Exception discoverEx) {
                Timber.e("RSS auto-discovery failed: " + discoverEx.getMessage());
            }

            // If everything failed, throw the original exception
            throw e;
        }
    }

    /**
     * Attempts to auto-discover RSS/Atom feed URLs from the HTML page's <link> tags.
     */
    private RssFeed tryAutoDiscoverRssFeed(String pageUrl) {
        try {
            Timber.d("Attempting RSS auto-discovery on: " + pageUrl);

            Request request = new Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cache-Control", "no-cache")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Timber.w("Auto-discovery page fetch failed: " + response.code());
                    return null;
                }

                String html = response.body().string();
                Document doc = Jsoup.parse(html, pageUrl);

                // Look for RSS and Atom feed links in the <head>
                Elements feedLinks = doc.select(
                        "link[rel=alternate][type=application/rss+xml], " +
                        "link[rel=alternate][type=application/atom+xml], " +
                        "link[rel=alternate][type=text/xml], " +
                        "link[rel=alternate][type=application/xml]"
                );

                if (feedLinks.isEmpty()) {
                    Timber.d("No RSS/Atom feed links found in HTML head for: " + pageUrl);
                    return null;
                }

                // Collect all discovered feed URLs
                List<String> feedUrls = new ArrayList<>();
                for (Element feedLink : feedLinks) {
                    String href = feedLink.attr("abs:href");
                    if (href != null && !href.isEmpty()) {
                        feedUrls.add(href);
                        Timber.d("Discovered feed URL: " + href);
                    }
                }

                // Try each discovered feed URL until one works
                for (String feedUrl : feedUrls) {
                    try {
                        RssFeed feed = parseDirectFeed(feedUrl);
                        if (feed != null && !feed.getRssItems().isEmpty()) {
                            Timber.d("Successfully parsed auto-discovered feed: " + feedUrl);
                            // Use the original page URL as the feed link (not the RSS feed URL)
                            feed.setLink(pageUrl);
                            return feed;
                        }
                    } catch (Exception ex) {
                        Timber.w("Failed to parse discovered feed URL: " + feedUrl + " - " + ex.getMessage());
                    }
                }
            }

            return null;

        } catch (Exception e) {
            Timber.e("Error during RSS auto-discovery: " + e.getMessage());
            return null;
        }
    }

    /**
     * Directly parses an RSS/Atom feed URL using OkHttp for the connection
     * and a SAX parser for the XML.
     */
    private RssFeed parseDirectFeed(String feedUrl) throws Exception {
        Timber.d("Directly parsing feed URL: " + feedUrl);

        Request request = new Request.Builder()
                .url(feedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rss+xml, application/atom+xml, text/xml, application/xml, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code());
            }

            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            RssHandler handler = new RssHandler();

            // OkHttp handles decoding/compression automatically
            try (java.io.InputStream inputStream = response.body().byteStream()) {
                InputSource inputSource = new InputSource(inputStream);
                saxParser.parse(inputSource, handler);
            }

            return handler.getRssFeed();
        } catch (Exception e) {
            Timber.w("Direct parse failed for " + feedUrl + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Tries common RSS/Atom feed URL patterns derived from the base domain.
     * Many sites (especially WordPress) expose feeds at predictable paths like /feed/ or /rss/.
     * This is useful when HTML scraping is blocked (e.g., by Cloudflare returning 403)
     * but the RSS endpoint itself remains accessible.
     */
    private RssFeed tryCommonFeedUrls(String pageUrl) {
        try {
            URL urlObj = new URL(pageUrl);
            String baseUrl = urlObj.getProtocol() + "://" + urlObj.getHost();
            if (urlObj.getPort() != -1 && urlObj.getPort() != urlObj.getDefaultPort()) {
                baseUrl += ":" + urlObj.getPort();
            }

            String[] commonFeedPaths = {
                "/feed/",
                "/feed",
                "/rss/",
                "/rss",
                "/feed/rss/",
                "/feed/rss2/",
                "/feed/atom/",
                "/atom.xml",
                "/rss.xml",
                "/index.xml",
                "/feeds/posts/default",  // Blogger
                "/?feed=rss2",           // WordPress alternative
            };

            for (String path : commonFeedPaths) {
                String feedUrl = baseUrl + path;
                try {
                    Timber.d("Probing common feed URL: " + feedUrl);
                    RssFeed feed = parseDirectFeed(feedUrl);
                    if (feed != null && !feed.getRssItems().isEmpty()) {
                        Timber.i("Successfully found feed at common URL: " + feedUrl);
                        feed.setLink(pageUrl); // Keep the original page URL as the feed link
                        return feed;
                    }
                } catch (Exception ex) {
                    Timber.v("Common feed URL not valid: " + feedUrl + " (" + ex.getMessage() + ")");
                }
            }
        } catch (Exception e) {
            Timber.w("Error building common feed URLs from: " + pageUrl + " - " + e.getMessage());
        }
        return null;
    }
}
