package xiangze.mmu.rssnewsreader.data.history;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.Date;

@Entity(tableName = "history_table")
public class History {

    @PrimaryKey(autoGenerate = true)
    private long id;
    private long feedId;
    private Date insertDate;
    private String title;
    private String link;

    public History(long feedId, Date insertDate, String title, String link) {
        this.feedId = feedId;
        this.insertDate = insertDate;
        this.title = title;
        this.link = link;
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

    public Date getInsertDate() {
        return insertDate;
    }

    public void setInsertDate(Date insertDate) {
        this.insertDate = insertDate;
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
}
