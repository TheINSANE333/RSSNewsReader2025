package xiangze.mmu.rssnewsreader.model;

import androidx.room.Ignore;

import java.util.Comparator;
import java.util.Date;
import java.util.Objects;

public class EntryInfo {

    // Entry
    private long entryId;
    private String entryTitle;
    private String entryLink;
    private String entryDescription;
    private String entryImageUrl;
    private String entryCategory;
    private Date entryPublishedDate;
    private Date visitedDate;
    private String bookmark;
    private int priority;
    
    // Status flags
    private boolean hasContent;
    private boolean hasOriginalHtml;
    private boolean hasTranslated;
    private boolean hasSummarized;

    // Feed
    private long feedId;
    private float ttsSpeechRate;
    private String feedLanguage;
    private String feedTitle;
    private String feedImageUrl;

    @androidx.room.Ignore
    private String summarizedSnippet;

    @Ignore
    private boolean selected;

    @Ignore
    private boolean isLoading = false;

    @Ignore
    public EntryInfo(String entryTitle, String entryLink, String entryDescription, String entryImageUrl, String entryCategory, Date entryPublishedDate, Date visitedDate, String bookmark, long feedId, float ttsSpeechRate, String feedLanguage, String feedTitle, String feedImageUrl) {
        this.entryTitle = entryTitle;
        this.entryDescription = entryDescription;
        this.entryLink = entryLink;
        this.entryImageUrl = entryImageUrl;
        this.entryCategory = entryCategory;
        this.entryPublishedDate = entryPublishedDate;
        this.visitedDate = visitedDate;
        this.bookmark = bookmark;
        this.feedId = feedId;
        this.ttsSpeechRate = ttsSpeechRate;
        this.feedLanguage = feedLanguage;
        this.feedTitle = feedTitle;
        this.feedImageUrl = feedImageUrl;
        this.selected = false;
    }

    public EntryInfo() {
        // Required by Room
    }

    public long getEntryId() {
        return entryId;
    }

    public void setEntryId(long entryId) {
        this.entryId = entryId;
    }

    public String getEntryTitle() {
        return entryTitle;
    }

    public void setEntryTitle(String entryTitle) {
        this.entryTitle = entryTitle;
    }

    public String getEntryLink() {
        return entryLink;
    }

    public void setEntryLink(String entryLink) {
        this.entryLink = entryLink;
    }

    public String getEntryImageUrl() {
        return entryImageUrl;
    }

    public void setEntryImageUrl(String entryImageUrl) {
        this.entryImageUrl = entryImageUrl;
    }

    public String getEntryDescription() {
        return entryDescription;
    }

    public void setEntryDescription(String entryDescription) {
        this.entryDescription = entryDescription;
    }

    public String getEntryCategory() {
        return entryCategory;
    }

    public void setEntryCategory(String entryCategory) {
        this.entryCategory = entryCategory;
    }

    public Date getEntryPublishedDate() {
        if (entryPublishedDate == null) {
            return new Date();
        }
        return entryPublishedDate;
    }

    public void setEntryPublishedDate(Date entryPublishedDate) {
        this.entryPublishedDate = entryPublishedDate;
    }

    public Date getVisitedDate() {
        return visitedDate;
    }

    public void setVisitedDate(Date visitedDate) {
        this.visitedDate = visitedDate;
    }

    public long getFeedId() {
        return feedId;
    }

    public void setFeedId(long feedId) {
        this.feedId = feedId;
    }

    public float getTtsSpeechRate() {
        return ttsSpeechRate;
    }

    public void setTtsSpeechRate(float ttsSpeechRate) {
        this.ttsSpeechRate = ttsSpeechRate;
    }

    public String getFeedLanguage() {
        return feedLanguage;
    }

    public void setFeedLanguage(String feedLanguage) {
        this.feedLanguage = feedLanguage;
    }

    public String getFeedTitle() {
        return feedTitle;
    }

    public void setFeedTitle(String feedTitle) {
        this.feedTitle = feedTitle;
    }

    public String getFeedImageUrl() {
        return feedImageUrl;
    }

    public void setFeedImageUrl(String feedImageUrl) {
        this.feedImageUrl = feedImageUrl;
    }

    public String getBookmark() {
        return bookmark;
    }

    public void setBookmark(String bookmark) {
        this.bookmark = bookmark;
    }

    public boolean isLoading() {
        return isLoading;
    }

    public void setLoading(boolean isLoading) {
        this.isLoading = isLoading;
    }


    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryId, entryTitle, entryLink, entryDescription, entryImageUrl, entryCategory, entryPublishedDate, feedTitle, feedImageUrl);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EntryInfo entryInfo = (EntryInfo) o;
        return entryId == entryInfo.entryId &&
                priority == entryInfo.priority &&
                hasContent == entryInfo.hasContent &&
                hasOriginalHtml == entryInfo.hasOriginalHtml &&
                hasTranslated == entryInfo.hasTranslated &&
                hasSummarized == entryInfo.hasSummarized &&
                Objects.equals(entryTitle, entryInfo.entryTitle) &&
                Objects.equals(entryLink, entryInfo.entryLink) &&
                Objects.equals(entryDescription, entryInfo.entryDescription) &&
                Objects.equals(entryImageUrl, entryInfo.entryImageUrl) &&
                Objects.equals(entryCategory, entryInfo.entryCategory) &&
                Objects.equals(entryPublishedDate, entryInfo.entryPublishedDate) &&
                Objects.equals(feedTitle, entryInfo.feedTitle) &&
                Objects.equals(feedImageUrl, entryInfo.feedImageUrl) &&
                Objects.equals(visitedDate, entryInfo.visitedDate) &&
                Objects.equals(bookmark, entryInfo.bookmark);
    }

    public static class LatestComparator implements Comparator<EntryInfo> {
        @Override
        public int compare(EntryInfo entryInfo, EntryInfo t1) {
            return t1.getEntryPublishedDate().compareTo(entryInfo.getEntryPublishedDate());
        }
    }

    public static class OldestComparator implements Comparator<EntryInfo> {
        @Override
        public int compare(EntryInfo entryInfo, EntryInfo t1) {
            return entryInfo.getEntryPublishedDate().compareTo(t1.getEntryPublishedDate());
        }
    }

    public String getSummarizedSnippet() { return summarizedSnippet; }
    public void setSummarizedSnippet(String summarizedSnippet) { this.summarizedSnippet = summarizedSnippet; }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isHasContent() { return hasContent; }
    public void setHasContent(boolean hasContent) { this.hasContent = hasContent; }

    public boolean isHasOriginalHtml() { return hasOriginalHtml; }
    public void setHasOriginalHtml(boolean hasOriginalHtml) { this.hasOriginalHtml = hasOriginalHtml; }

    public boolean isHasTranslated() { return hasTranslated; }
    public void setHasTranslated(boolean hasTranslated) { this.hasTranslated = hasTranslated; }

    public boolean isHasSummarized() { return hasSummarized; }
    public void setHasSummarized(boolean hasSummarized) { this.hasSummarized = hasSummarized; }
}
