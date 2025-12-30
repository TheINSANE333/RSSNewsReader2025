package xiangze.mmu.rssnewsreader.data.entry;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.Nullable;

import java.util.Date;

@Entity(tableName = "entry_table")
public class Entry {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private long feedId;
    private int priority;
    private String title;
    private String link;
    private String description;
    private String content;
    private String html;
    private String translatedHtml;
    private String summarizedHtml;
    private String imageUrl;
    private String category;
    private Date publishedDate;
    private Date visitedDate;
    private String bookmark;
    private int sentCountStopAt;
    @ColumnInfo(defaultValue = "0")
    private boolean isCached;
    @ColumnInfo(name = "original_html")
    private String originalHtml;
    @Nullable
    @ColumnInfo(name = "translated")
    private String translated;
    @Nullable
    @ColumnInfo(name = "summarized")
    private String summarized;

    public Entry(long feedId, String title, String link, String description, String imageUrl, String category, Date publishedDate) {
        this.feedId = feedId;
        this.title = title;
        this.link = link;
        this.description = description;
        this.imageUrl = imageUrl;
        this.category = category;
        this.publishedDate = publishedDate;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getFeedId() {
        return feedId;
    }

    public void setFeedId(long feedId) {
        this.feedId = feedId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getHtml() {
        return html;
    }

    public void setHtml(String html) {
        this.html = html;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Date getPublishedDate() {
        return publishedDate;
    }

    public void setPublishedDate(Date publishedDate) {
        this.publishedDate = publishedDate;
    }

    public Date getVisitedDate() {
        return visitedDate;
    }

    public void setVisitedDate(Date visitedDate) {
        this.visitedDate = visitedDate;
    }

    public int getSentCountStopAt() {
        return sentCountStopAt;
    }

    public void setSentCountStopAt(int sentCountStopAt) {
        this.sentCountStopAt = sentCountStopAt;
    }

    public String getBookmark() {
        return bookmark;
    }

    public void setBookmark(String bookmark) {
        this.bookmark = bookmark;
    }

    public boolean isCached() {
        return isCached;
    }

    public void setCached(boolean cached) {
        isCached = cached;
    }

    public String getOriginalHtml() {
        return originalHtml;
    }

    public void setOriginalHtml(String originalHtml) {
        this.originalHtml = originalHtml;
    }

    public String getTranslated() {
        return translated;
    }

    public void setTranslated(String translated) {
        this.translated = translated;
    }

    public String getSummarized() {
        return summarized;
    }

    public void setSummarized(String summarized) {
        this.summarized = summarized;
    }

    public String getTranslatedHtml() {
        return translatedHtml;
    }

    public void setTranslatedHtml(String translatedHtml) {
        this.translatedHtml = translatedHtml;
    }

    public String getSummarizedHtml() {
        return summarizedHtml;
    }

    public void setSummarizedHtml(String summarizedHtml) {
        this.summarizedHtml = summarizedHtml;
    }

}
