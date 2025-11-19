package xiangze.mmu.rssnewsreader.data.playlist;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.Date;

@Entity(tableName = "playlist_table")
public class Playlist {

    @PrimaryKey(autoGenerate = true)
    private long id;
    private Date createdDate;
    private String playlist;

    public Playlist(Date createdDate, String playlist) {
        this.createdDate = createdDate;
        this.playlist = playlist;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }

    public String getPlaylist() {
        return playlist;
    }

    public void setPlaylist(String playlist) {
        this.playlist = playlist;
    }
}
