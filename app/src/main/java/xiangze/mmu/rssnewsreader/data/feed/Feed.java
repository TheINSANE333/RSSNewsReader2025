package xiangze.mmu.rssnewsreader.data.feed;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.Objects;

@Entity(tableName = "feed_table",
        indices = {@Index(value = {"link"}, unique = true)})
public class Feed {

    @PrimaryKey(autoGenerate = true)
    private long id;
    private int delayTime;
    private float ttsSpeechRate;
    private String title;
    private String link;
    private String description;
    private String imageUrl;
    private String language;
    @ColumnInfo(defaultValue = "0")
    private boolean isPreloaded;
    @ColumnInfo(defaultValue = "1")
    private boolean autoSummarize = true;
    @ColumnInfo(defaultValue = "1")
    private boolean autoTranslate = true;

    public Feed(String title, String link, String description, String imageUrl, String language) {
        this.title = title;
        this.link = link;
        this.description = description;
        this.imageUrl = imageUrl;
        this.language = language;
        this.autoSummarize = true;
        this.autoTranslate = true;
    }

    @Ignore
    public Feed(String title, String link, String description, String imageUrl, String language, int delayTime, float ttsSpeechRate) {
        this.title = title;
        this.link = link;
        this.description = description;
        this.imageUrl = imageUrl;
        this.language = language;
        this.delayTime = delayTime;
        this.ttsSpeechRate = ttsSpeechRate;
        this.autoSummarize = true;
        this.autoTranslate = true;
    }

    @Ignore
    public Feed(String title, String link, String description, String imageUrl, String language, int delayTime, float ttsSpeechRate, boolean autoSummarize, boolean autoTranslate) {
        this.title = title;
        this.link = link;
        this.description = description;
        this.imageUrl = imageUrl;
        this.language = language;
        this.delayTime = delayTime;
        this.ttsSpeechRate = ttsSpeechRate;
        this.autoSummarize = autoSummarize;
        this.autoTranslate = autoTranslate;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getDelayTime() {
        return delayTime;
    }

    public void setDelayTime(int delayTime) {
        this.delayTime = delayTime;
    }

    public float getTtsSpeechRate() {
        return ttsSpeechRate;
    }

    public void setTtsSpeechRate(float ttsSpeechRate) {
        this.ttsSpeechRate = ttsSpeechRate;
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

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Feed feed = (Feed) o;
        return id == feed.id && Objects.equals(title, feed.title) && Objects.equals(link, feed.link) && Objects.equals(description, feed.description) && Objects.equals(imageUrl, feed.imageUrl) && Objects.equals(language, feed.language) && Objects.equals(ttsSpeechRate, feed.ttsSpeechRate) && autoSummarize == feed.autoSummarize && autoTranslate == feed.autoTranslate;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, title, link, description, imageUrl, language, autoSummarize, autoTranslate, delayTime);
        result = 31 * result + Float.floatToIntBits(ttsSpeechRate);
        return result;
    }

    public boolean isPreloaded() {
        return isPreloaded;
    }

    public void setPreloaded(boolean preloaded) {
        isPreloaded = preloaded;
    }

    public boolean isAutoSummarize() {
        return autoSummarize;
    }

    public void setAutoSummarize(boolean autoSummarize) {
        this.autoSummarize = autoSummarize;
    }

    public boolean isAutoTranslate() {
        return autoTranslate;
    }

    public void setAutoTranslate(boolean autoTranslate) {
        this.autoTranslate = autoTranslate;
    }
}
