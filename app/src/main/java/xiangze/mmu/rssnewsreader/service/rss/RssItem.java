package xiangze.mmu.rssnewsreader.service.rss;

import timber.log.Timber;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RssItem {

    private String title;
    private String description;
    private String link;
    private String imageUrl;
    private Date pubDate;
    private String category;
    private int priority;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Date getPubDate() {
        if (pubDate == null) {
            pubDate = new Date();
        }
        return pubDate;
    }

    public void setPubDate(Date pubDate) {
        this.pubDate = pubDate;
    }

    public void setPubDate(String pubDate) {
        if (pubDate == null || pubDate.isEmpty()) {
            this.pubDate = new Date();
            return;
        }

        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
            this.pubDate = dateFormat.parse(pubDate);
        } catch (ParseException e) {
            Timber.e("Failed to parse date: " + pubDate + ". " + e.getMessage());
            if (this.pubDate == null) {
                this.pubDate = new Date();
            }
        }
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isValid() {
        return title != null && !title.isEmpty() && link != null && !link.isEmpty();
    }
}