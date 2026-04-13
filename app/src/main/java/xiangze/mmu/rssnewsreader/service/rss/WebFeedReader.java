package xiangze.mmu.rssnewsreader.service.rss;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import android.util.Log;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.SimpleDateFormat;

public class WebFeedReader {
    private String url;
    private static final String TAG = "WebFeedReader";

    public WebFeedReader(String url) {
        this.url = url.startsWith("http") ? url : "https://" + url;
    }

    public RssFeed getFeed() throws Exception {
        Log.d(TAG, "Attempting to scrape feed from: " + url);
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get();

        RssFeed feed = new RssFeed();
        feed.setTitle(doc.title());
        feed.setLink(url);
        feed.setDescription("Web Scraped Feed from " + url);
        feed.setLanguage("en"); 

        ArrayList<RssItem> items = new ArrayList<>();
        Set<String> visitedLinks = new HashSet<>();

        // Select all potential links
        Elements allLinks = doc.select("a[href]");
        
        // Get the host for domain checking
        URL baseUrlObj = new URL(url);
        String host = baseUrlObj.getHost();

        for (Element link : allLinks) {
            String href = link.attr("abs:href"); // Get absolute URL
            String title = link.text().trim();
            
            // Fallback for title
            if (title.isEmpty()) {
                title = link.attr("title");
            }
            if (title.isEmpty()) {
                Element img = link.selectFirst("img");
                if (img != null) {
                    title = img.attr("alt");
                }
            }
            
            // Clean title
            title = title.replaceAll("\\s+", " ").trim();

            if (isValidArticleLink(href, title, host)) {
                if (!visitedLinks.contains(href)) {
                    RssItem item = new RssItem();
                    item.setTitle(title);
                    item.setLink(href);
                    
                    String date = extractDate(link, href);
                    if (date != null) {
                        item.setPubDate(date);
                    } else {
                        // Default to now if not found (handled by RssItem getter if null)
                        item.setPubDate((String) null); 
                    }
                    
                    items.add(item);
                    visitedLinks.add(href);
                }
            }
        }
        
        feed.setRssItems(items);
        
        Log.d(TAG, "Scraped " + items.size() + " items.");

        if (items.isEmpty()) {
             throw new Exception("No articles found on the page.");
        }
        
        return feed;
    }

    private String extractDate(Element link, String url) {
        // 1. Try URL Regex
        // Matches /2024/05/20 or /2024-05-20 or /24/05/20
        Pattern p = Pattern.compile("/(\\d{4})[-/](\\d{2})[-/](\\d{2})");
        Matcher m = p.matcher(url);
        if (m.find()) {
            String year = m.group(1);
            String month = m.group(2);
            String day = m.group(3);
            return formatToRssDate(year, month, day);
        }

        // 2. Try nearby <time> tag
        // Look up to 3 levels
        Element parent = link.parent();
        for (int i = 0; i < 3; i++) {
            if (parent == null) break;
            
            Element timeElement = parent.selectFirst("time");
            if (timeElement != null) {
                String datetime = timeElement.attr("datetime"); // ISO 8601 usually
                if (!datetime.isEmpty()) {
                    return convertIsoToRssDate(datetime);
                }
            }
            parent = parent.parent();
        }
        
        return null;
    }

    private String formatToRssDate(String year, String month, String day) {
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH);
            java.util.Date date = inputFormat.parse(year + "-" + month + "-" + day);
            
            SimpleDateFormat outputFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH);
            return outputFormat.format(date);
        } catch (Exception e) {
            return null;
        }
    }
    
    private String convertIsoToRssDate(String isoDate) {
        try {
            java.util.Date date = null;
            // Handle various ISO formats roughly
            if (isoDate.length() >= 10) {
                 // Try simple YYYY-MM-DD
                 if (isoDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                     return formatToRssDate(isoDate.substring(0, 4), isoDate.substring(5, 7), isoDate.substring(8, 10));
                 }
                 
                 // Try ISO with T
                 try {
                     // 2023-10-25T12:00:00Z
                     date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ENGLISH).parse(isoDate);
                 } catch (Exception e) {
                     try {
                        // 2023-10-25T12:00:00+05:00 or basic
                        // Fallback to minimal parsing
                        date = new SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).parse(isoDate.substring(0, 10));
                     } catch (Exception e2) {}
                 }
            }

            if (date != null) {
                SimpleDateFormat outputFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH);
                return outputFormat.format(date);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private boolean isValidArticleLink(String href, String title, String host) {
        if (href == null || href.isEmpty() || title == null || title.length() < 15) { // Increased threshold
            return false;
        }
        
        if (href.startsWith("javascript:") || href.startsWith("mailto:") || href.startsWith("tel:") || href.startsWith("#")) {
            return false;
        }

        try {
            URL target = new URL(href);
            if (!target.getHost().equals(host) && !target.getHost().endsWith("." + host)) {
                 return false; 
            }
        } catch (Exception e) {
            return false;
        }

        String lowerTitle = title.toLowerCase();
        // Exclusion list
        String[] excluded = {
            "privacy policy", "terms of use", "terms & conditions", "contact us", "about us", "login", "sign up", 
            "sign in", "home", "menu", "accessibility", "cookie policy", "facebook", "twitter", "instagram",
            "linkedin", "youtube", "skip to content", "read more", "click here", "subscribe", "rss feed", "newsletter"
        };
        
        for (String ex : excluded) {
            if (lowerTitle.contains(ex) && title.length() < 30) { // Allow if it's part of a longer sentence title
                return false;
            }
        }

        return true;
    }
}
