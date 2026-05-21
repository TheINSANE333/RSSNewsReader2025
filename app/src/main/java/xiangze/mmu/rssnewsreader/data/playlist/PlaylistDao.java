package xiangze.mmu.rssnewsreader.data.playlist;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.Date;

@Dao
public interface PlaylistDao {

    @Insert
    void insert(Playlist playlist);

    @Update
    void update(Playlist playlist);

    @Delete
    void delete(Playlist playlist);

    @Query("DELETE FROM playlist_table")
    void deleteAllPlaylists();

    @Query("SELECT playlist FROM playlist_table ORDER BY createdDate DESC LIMIT 1")
    String getLatestPlaylist();

    @Query("SELECT createdDate FROM playlist_table ORDER BY createdDate DESC LIMIT 1")
    Date getLatestPlaylistCreatedDate();
}
