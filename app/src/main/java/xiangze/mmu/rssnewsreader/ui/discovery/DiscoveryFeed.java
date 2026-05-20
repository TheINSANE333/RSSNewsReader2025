package xiangze.mmu.rssnewsreader.ui.discovery;

public class DiscoveryFeed {
    private String title;
    private String url;
    private String description;
    private String faviconUrl;

    public DiscoveryFeed(String title, String url, String description, String faviconUrl) {
        this.title = title;
        this.url = url;
        this.description = description;
        this.faviconUrl = faviconUrl;
    }

    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public String getDescription() { return description; }
    public String getFaviconUrl() { return faviconUrl; }
}
