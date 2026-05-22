package xiangze.mmu.rssnewsreader.service.rss;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import timber.log.Timber;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.SimpleDateFormat;

public class WebFeedReader {
    private String url;

    // Minimum score a candidate needs to be included
    private static final int MIN_SCORE_THRESHOLD = 2;

    // Regex for article-style URL patterns containing dates
    private static final Pattern DATE_URL_PATTERN = Pattern.compile(
            "/(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");

    // Regex for article slug patterns (long hyphenated path segments)
    private static final Pattern SLUG_URL_PATTERN = Pattern.compile(
            "/[a-z0-9]+-[a-z0-9]+(?:-[a-z0-9]+){2,}");

    // Regex for article ID patterns (/article/12345 or /news/12345678)
    private static final Pattern ID_URL_PATTERN = Pattern.compile(
            "/(?:article|story|news|post|p)/[a-z0-9-]+$", Pattern.CASE_INSENSITIVE);

    // URL path segments that indicate category/section pages
    private static final Set<String> CATEGORY_PATH_SEGMENTS = new HashSet<>();
    static {
        String[] segments = {
            "category", "categories", "tag", "tags", "author", "authors",
            "page", "search", "login", "signup", "register", "account",
            "archive", "archives", "feed", "rss", "sitemap", "about",
            "contact", "privacy", "terms", "help", "faq", "subscribe",
            "newsletter", "profile", "settings", "admin", "wp-admin",
            "wp-content", "wp-includes", "cdn", "static", "assets",
            "video", "videos", "gallery", "galleries", "podcast", "podcasts",
            "live", "watch"
        };
        for (String s : segments) CATEGORY_PATH_SEGMENTS.add(s);
    }

    // Words in link text that suggest non-article links
    private static final String[] NAV_LINK_INDICATORS = {
        "privacy policy", "terms of use", "terms & conditions", "terms and conditions",
        "contact us", "about us", "login", "log in", "sign up", "sign in",
        "home", "menu", "accessibility", "cookie policy", "cookie settings",
        "facebook", "twitter", "instagram", "linkedin", "youtube", "tiktok",
        "skip to content", "skip to main", "read more", "click here",
        "subscribe", "rss feed", "newsletter", "more stories", "see all",
        "show more", "load more", "view all", "all rights reserved",
        "copyright", "advertise", "careers", "jobs", "work for us",
        "download our app", "get the app", "follow us"
    };

    // CSS selectors for semantic article containers (ordered by specificity)
    private static final String ARTICLE_CONTAINER_SELECTOR =
            "article, " +
            "[role=article], " +
            "[class*=article], [class*=story], [class*=headline], " +
            "[class*=news-item], [class*=news-card], [class*=post-card], " +
            "[class*=card][class*=news], [class*=teaser], " +
            "[data-testid*=article], [data-testid*=story], [data-testid*=card]";

    // CSS selectors for main content areas
    private static final String MAIN_CONTENT_SELECTOR =
            "main, [role=main], " +
            "[class*=main-content], [class*=content-area], [class*=feed], " +
            "[class*=news-list], [class*=story-list], [class*=article-list], " +
            "[class*=headlines], [class*=top-stories]";

    public WebFeedReader(String url) {
        this.url = url.startsWith("http") ? url : "https://" + url;
    }

    public RssFeed getFeed() throws Exception {
        Timber.d("Attempting to scrape feed from: " + url);
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000)
                .followRedirects(true)
                .get();

        RssFeed feed = new RssFeed();
        feed.setTitle(doc.title());
        feed.setLink(url);
        feed.setDescription("Web Scraped Feed from " + url);

        // Try to detect language from HTML lang attribute
        String lang = doc.select("html").attr("lang");
        feed.setLanguage(lang != null && !lang.isEmpty() ? lang.split("-")[0] : "en");

        URL baseUrlObj = new URL(url);
        String host = baseUrlObj.getHost();

        // Collect candidates using multiple strategies
        List<ArticleCandidate> candidates = new ArrayList<>();
        Set<String> visitedLinks = new HashSet<>();

        // Strategy 1: Extract from semantic article containers (highest quality)
        extractFromArticleContainers(doc, host, candidates, visitedLinks);

        // Strategy 2: Extract from main content area headings
        extractFromContentHeadings(doc, host, candidates, visitedLinks);

        // Strategy 3: Scan remaining links with heuristic scoring
        extractFromAllLinks(doc, host, candidates, visitedLinks);

        // Sort by score descending
        Collections.sort(candidates, (a, b) -> Integer.compare(b.score, a.score));

        // Filter and convert to RssItems
        ArrayList<RssItem> items = new ArrayList<>();
        for (ArticleCandidate candidate : candidates) {
            if (candidate.score >= MIN_SCORE_THRESHOLD && items.size() < 50) {
                RssItem item = candidate.toRssItem();
                if (item.isValid()) {
                    items.add(item);
                    Timber.d("  +" + candidate.score + " | " + candidate.title + " -> " + candidate.href);
                }
            }
        }

        feed.setRssItems(items);
        Timber.d("Scraped " + items.size() + " items (from " + candidates.size() + " candidates).");

        if (items.isEmpty()) {
            throw new Exception("No articles found on the page.");
        }

        return feed;
    }

    // =========================================================================
    // Strategy 1: Extract from <article> and other semantic containers
    // =========================================================================
    private void extractFromArticleContainers(Document doc, String host,
                                               List<ArticleCandidate> candidates, Set<String> visited) {
        Elements containers = doc.select(ARTICLE_CONTAINER_SELECTOR);
        Timber.d("Found " + containers.size() + " article containers.");

        for (Element container : containers) {
            // Find the primary link in this container
            Element primaryLink = findPrimaryLink(container);
            if (primaryLink == null) continue;

            String href = primaryLink.attr("abs:href");
            if (href.isEmpty() || visited.contains(href)) continue;
            if (!isSameDomain(href, host)) continue;

            // Extract title from heading or link text
            String title = extractTitle(container, primaryLink);
            if (title == null || title.length() < 10) continue;

            ArticleCandidate candidate = new ArticleCandidate(href, title);
            candidate.score += 3; // Found inside semantic article container

            // Extract additional metadata
            candidate.imageUrl = extractImage(container);
            candidate.description = extractDescription(container);
            candidate.pubDate = extractDate(container, primaryLink, href);

            // Apply URL-based scoring
            candidate.score += scoreUrl(href, host);

            // Title length bonus
            if (title.length() > 40) candidate.score += 1;

            // Has image bonus
            if (candidate.imageUrl != null) candidate.score += 1;

            // Has date bonus
            if (candidate.pubDate != null) candidate.score += 1;

            // Check for nav-link indicators
            if (isNavLinkText(title)) {
                candidate.score -= 3;
            }

            candidates.add(candidate);
            visited.add(href);
        }
    }

    // =========================================================================
    // Strategy 2: Extract from headings inside main content area
    // =========================================================================
    private void extractFromContentHeadings(Document doc, String host,
                                             List<ArticleCandidate> candidates, Set<String> visited) {
        // Find main content area
        Elements mainContent = doc.select(MAIN_CONTENT_SELECTOR);
        Element contentRoot = mainContent.isEmpty() ? doc.body() : mainContent.first();
        if (contentRoot == null) return;

        // Look for links inside headings (h1-h4) - these are almost always article headlines
        Elements headingLinks = contentRoot.select("h1 a[href], h2 a[href], h3 a[href], h4 a[href]");
        Timber.d("Found " + headingLinks.size() + " heading links in content area.");

        for (Element link : headingLinks) {
            String href = link.attr("abs:href");
            if (href.isEmpty() || visited.contains(href)) continue;
            if (!isSameDomain(href, host)) continue;

            String title = link.text().trim();
            if (title.isEmpty() || title.length() < 10) continue;

            ArticleCandidate candidate = new ArticleCandidate(href, title);
            candidate.score += 2; // Found inside a heading (strong signal)

            // Look for metadata in parent container
            Element parentContainer = findParentContainer(link);
            if (parentContainer != null) {
                candidate.imageUrl = extractImage(parentContainer);
                candidate.description = extractDescription(parentContainer);
                candidate.pubDate = extractDate(parentContainer, link, href);
                if (candidate.imageUrl != null) candidate.score += 1;
            } else {
                candidate.pubDate = extractDateFromUrl(href);
            }

            candidate.score += scoreUrl(href, host);

            if (title.length() > 40) candidate.score += 1;

            if (isNavLinkText(title)) {
                candidate.score -= 3;
            }

            candidates.add(candidate);
            visited.add(href);
        }
    }

    // =========================================================================
    // Strategy 3: Score all remaining links heuristically
    // =========================================================================
    private void extractFromAllLinks(Document doc, String host,
                                      List<ArticleCandidate> candidates, Set<String> visited) {
        Elements allLinks = doc.select("a[href]");

        for (Element link : allLinks) {
            String href = link.attr("abs:href");
            if (href.isEmpty() || visited.contains(href)) continue;
            if (!isSameDomain(href, host)) continue;

            String title = extractLinkTitle(link);
            if (title == null || title.length() < 15) continue;

            // Skip links from nav, footer, sidebar, header elements
            if (isInsideNavOrFooter(link)) continue;

            ArticleCandidate candidate = new ArticleCandidate(href, title);

            // URL scoring
            int urlScore = scoreUrl(href, host);
            candidate.score += urlScore;

            // Title length scoring
            if (title.length() > 50) {
                candidate.score += 2;
            } else if (title.length() > 30) {
                candidate.score += 1;
            }

            // Check nearby elements for metadata
            Element parent = findParentContainer(link);
            if (parent != null) {
                candidate.imageUrl = extractImage(parent);
                candidate.description = extractDescription(parent);
                candidate.pubDate = extractDate(parent, link, href);

                if (candidate.imageUrl != null) candidate.score += 1;
                if (candidate.pubDate != null) candidate.score += 1;
            } else {
                candidate.pubDate = extractDateFromUrl(href);
            }

            // Nav link penalty
            if (isNavLinkText(title)) {
                candidate.score -= 3;
            }

            // Short category-like URL penalty
            if (urlScore < 0) {
                candidate.score -= 1;
            }

            candidates.add(candidate);
            visited.add(href);
        }
    }

    // =========================================================================
    // Helper: Find the primary (most important) link in a container
    // =========================================================================
    private Element findPrimaryLink(Element container) {
        // Prefer link inside a heading
        Element headingLink = container.selectFirst("h1 a[href], h2 a[href], h3 a[href], h4 a[href]");
        if (headingLink != null) return headingLink;

        // Fall back to first link with text or the container itself if it's an <a>
        if (container.tagName().equals("a") && container.hasAttr("href")) {
            return container;
        }

        // First link with meaningful text
        Elements links = container.select("a[href]");
        for (Element link : links) {
            String text = link.text().trim();
            if (text.length() >= 10) return link;
        }

        return links.isEmpty() ? null : links.first();
    }

    // =========================================================================
    // Helper: Extract the best title from a container or link
    // =========================================================================
    private String extractTitle(Element container, Element link) {
        // Try heading text first
        Element heading = container.selectFirst("h1, h2, h3, h4");
        if (heading != null) {
            String headingText = heading.text().trim();
            if (headingText.length() >= 10) return cleanTitle(headingText);
        }

        // Try aria-label or title attribute on the link
        String ariaLabel = link.attr("aria-label");
        if (ariaLabel != null && ariaLabel.length() >= 10) return cleanTitle(ariaLabel);

        // Fall back to link text
        String linkText = link.text().trim();
        if (linkText.length() >= 10) return cleanTitle(linkText);

        return null;
    }

    // =========================================================================
    // Helper: Extract link title with fallbacks
    // =========================================================================
    private String extractLinkTitle(Element link) {
        String title = link.text().trim();

        if (title.isEmpty()) {
            title = link.attr("title");
        }
        if (title.isEmpty()) {
            title = link.attr("aria-label");
        }
        if (title.isEmpty()) {
            Element img = link.selectFirst("img");
            if (img != null) {
                title = img.attr("alt");
            }
        }

        return title.isEmpty() ? null : cleanTitle(title);
    }

    // =========================================================================
    // Helper: Clean title text
    // =========================================================================
    private String cleanTitle(String title) {
        return title.replaceAll("\\s+", " ").trim();
    }

    // =========================================================================
    // Helper: Extract image from container
    // =========================================================================
    private String extractImage(Element container) {
        Element img = container.selectFirst("img[src], img[data-src]");
        if (img != null) {
            String src = img.attr("abs:src");
            if (src.isEmpty()) src = img.attr("abs:data-src");
            if (src.isEmpty()) {
                // Try srcset
                String srcset = img.attr("srcset");
                if (!srcset.isEmpty()) {
                    src = srcset.split(",")[0].trim().split("\\s")[0];
                }
            }
            if (!src.isEmpty() && (src.startsWith("http://") || src.startsWith("https://"))) {
                return src;
            }
        }

        // Try background-image in style
        Element bgElement = container.selectFirst("[style*=background-image]");
        if (bgElement != null) {
            String style = bgElement.attr("style");
            Matcher m = Pattern.compile("url\\(['\"]?([^'\"\\)]+)['\"]?\\)").matcher(style);
            if (m.find()) {
                return m.group(1);
            }
        }

        return null;
    }

    // =========================================================================
    // Helper: Extract description from container
    // =========================================================================
    private String extractDescription(Element container) {
        // Look for <p> tags that might be article summaries
        Elements paragraphs = container.select("p");
        for (Element p : paragraphs) {
            String text = p.text().trim();
            if (text.length() > 30 && text.length() < 500) {
                return text;
            }
        }

        // Check for elements with summary/description class
        Element summary = container.selectFirst(
                "[class*=summary], [class*=description], [class*=excerpt], [class*=standfirst], [class*=dek]");
        if (summary != null) {
            String text = summary.text().trim();
            if (text.length() > 20) return text;
        }

        return null;
    }

    // =========================================================================
    // Helper: Extract date from container, link, or URL
    // =========================================================================
    private String extractDate(Element container, Element link, String href) {
        // 1. Try <time> element in container
        Element timeElement = container.selectFirst("time[datetime]");
        if (timeElement != null) {
            String datetime = timeElement.attr("datetime");
            if (!datetime.isEmpty()) {
                String converted = convertIsoToRssDate(datetime);
                if (converted != null) return converted;
            }
        }

        // 2. Try <time> element without datetime attr
        if (timeElement == null) {
            timeElement = container.selectFirst("time");
        }
        if (timeElement != null) {
            String text = timeElement.text().trim();
            if (!text.isEmpty()) {
                // Try parsing common text date formats
                String converted = tryParseTextDate(text);
                if (converted != null) return converted;
            }
        }

        // 3. Try URL date pattern
        return extractDateFromUrl(href);
    }

    private String extractDateFromUrl(String href) {
        Matcher m = DATE_URL_PATTERN.matcher(href);
        if (m.find()) {
            String year = m.group(1);
            String month = m.group(2);
            String day = m.group(3);
            // Pad single-digit month/day
            if (month.length() == 1) month = "0" + month;
            if (day.length() == 1) day = "0" + day;
            return formatToRssDate(year, month, day);
        }
        return null;
    }

    private String tryParseTextDate(String text) {
        // Try a few common date patterns in text
        String[][] formats = {
            {"MMM dd, yyyy", null},
            {"dd MMM yyyy", null},
            {"MMMM dd, yyyy", null},
            {"yyyy-MM-dd", null},
        };
        for (String[] fmt : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt[0], java.util.Locale.ENGLISH);
                sdf.setLenient(false);
                java.util.Date date = sdf.parse(text);
                if (date != null) {
                    return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z",
                            java.util.Locale.ENGLISH).format(date);
                }
            } catch (Exception e) {
                // Try next format
            }
        }
        return null;
    }

    // =========================================================================
    // Helper: Score a URL based on article-likelihood patterns
    // =========================================================================
    private int scoreUrl(String href, String host) {
        int score = 0;

        try {
            URL urlObj = new URL(href);
            String path = urlObj.getPath();

            if (path == null || path.isEmpty() || path.equals("/")) {
                return -3; // Root URL, definitely not an article
            }

            // Strip trailing slash for consistent analysis
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }

            String[] segments = path.split("/");
            // segments[0] is empty string before the leading /
            int segmentCount = 0;
            for (String s : segments) {
                if (!s.isEmpty()) segmentCount++;
            }

            // Date in URL is a strong article signal
            if (DATE_URL_PATTERN.matcher(href).find()) {
                score += 3;
            }

            // Article slug pattern (multiple-hyphenated-words)
            if (SLUG_URL_PATTERN.matcher(path.toLowerCase()).find()) {
                score += 2;
            }

            // Article ID pattern
            if (ID_URL_PATTERN.matcher(path).find()) {
                score += 2;
            }

            // Deeper paths are more likely articles
            if (segmentCount >= 3) {
                score += 1;
            } else if (segmentCount == 1) {
                // Single segment like /sports or /news is likely a category
                String segment = "";
                for (String s : segments) {
                    if (!s.isEmpty()) { segment = s.toLowerCase(); break; }
                }

                // Check if the single segment is a known category word
                if (CATEGORY_PATH_SEGMENTS.contains(segment)) {
                    score -= 3;
                } else if (segment.length() < 15 && !segment.contains("-")) {
                    // Short single-word path segment, likely a section
                    score -= 2;
                }
            }

            // URL path contains category-like segments
            for (String seg : segments) {
                if (!seg.isEmpty() && CATEGORY_PATH_SEGMENTS.contains(seg.toLowerCase())) {
                    // Having a category segment in the middle is okay if path is deep
                    if (segmentCount <= 2) score -= 1;
                    break;
                }
            }

            // Check for query parameters with page/category indicators
            String query = urlObj.getQuery();
            if (query != null) {
                if (query.contains("page=") || query.contains("category=") || query.contains("tag=")) {
                    score -= 2;
                }
            }

            // Fragment-only URLs
            if (urlObj.getRef() != null && path.equals("/")) {
                score -= 3;
            }

        } catch (Exception e) {
            Timber.w("Error scoring URL: " + href);
        }

        return score;
    }

    // =========================================================================
    // Helper: Check if link is inside nav, footer, sidebar, or header
    // =========================================================================
    private boolean isInsideNavOrFooter(Element link) {
        Element current = link.parent();
        int depth = 0;
        while (current != null && depth < 10) {
            String tag = current.tagName().toLowerCase();
            if (tag.equals("nav") || tag.equals("footer") || tag.equals("header") || tag.equals("aside")) {
                return true;
            }

            String classAttr = current.className().toLowerCase();
            String role = current.attr("role").toLowerCase();

            if (classAttr.contains("nav") || classAttr.contains("footer") ||
                classAttr.contains("sidebar") || classAttr.contains("header") ||
                classAttr.contains("menu") || classAttr.contains("breadcrumb") ||
                classAttr.contains("social") || classAttr.contains("widget")) {
                return true;
            }
            if (role.equals("navigation") || role.equals("banner") ||
                role.equals("contentinfo") || role.equals("complementary")) {
                return true;
            }

            current = current.parent();
            depth++;
        }
        return false;
    }

    // =========================================================================
    // Helper: Check if link text matches nav-link patterns
    // =========================================================================
    private boolean isNavLinkText(String title) {
        String lower = title.toLowerCase();
        for (String indicator : NAV_LINK_INDICATORS) {
            // For short titles, exact or near-exact match
            if (title.length() < 30 && lower.contains(indicator)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Helper: Check if URL belongs to the same domain
    // =========================================================================
    private boolean isSameDomain(String href, String host) {
        if (href.startsWith("javascript:") || href.startsWith("mailto:") ||
            href.startsWith("tel:") || href.startsWith("#")) {
            return false;
        }

        try {
            URL target = new URL(href);
            String targetHost = target.getHost();
            return targetHost.equals(host) || targetHost.endsWith("." + host) || host.endsWith("." + targetHost);
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Helper: Find meaningful parent container for context extraction
    // =========================================================================
    private Element findParentContainer(Element element) {
        Element current = element.parent();
        int depth = 0;
        while (current != null && depth < 5) {
            String tag = current.tagName().toLowerCase();
            if (tag.equals("article") || tag.equals("section")) return current;

            String classAttr = current.className().toLowerCase();
            if (classAttr.contains("article") || classAttr.contains("story") ||
                classAttr.contains("card") || classAttr.contains("item") ||
                classAttr.contains("post") || classAttr.contains("teaser") ||
                classAttr.contains("headline")) {
                return current;
            }

            // If it has both an image and text, it's likely a container
            if (current.selectFirst("img") != null && current.select("a").size() <= 3) {
                return current;
            }

            current = current.parent();
            depth++;
        }
        return null;
    }

    // =========================================================================
    // Date formatting utilities
    // =========================================================================
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
            if (isoDate.length() >= 10) {
                if (isoDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return formatToRssDate(isoDate.substring(0, 4), isoDate.substring(5, 7), isoDate.substring(8, 10));
                }

                try {
                    date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ENGLISH).parse(isoDate);
                } catch (Exception e) {
                    try {
                        date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ENGLISH).parse(isoDate);
                    } catch (Exception e2) {
                        try {
                            date = new SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).parse(isoDate.substring(0, 10));
                        } catch (Exception e3) { /* give up */ }
                    }
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

    // =========================================================================
    // Inner class: Article candidate with scoring
    // =========================================================================
    private static class ArticleCandidate {
        String href;
        String title;
        String description;
        String imageUrl;
        String pubDate;
        int score = 0;

        ArticleCandidate(String href, String title) {
            this.href = href;
            this.title = title;
        }

        RssItem toRssItem() {
            RssItem item = new RssItem();
            item.setTitle(title);
            item.setLink(href);
            if (description != null) item.setDescription(description);
            if (imageUrl != null) item.setImageUrl(imageUrl);
            if (pubDate != null) {
                item.setPubDate(pubDate);
            } else {
                item.setPubDate((String) null);
            }
            return item;
        }
    }
}
