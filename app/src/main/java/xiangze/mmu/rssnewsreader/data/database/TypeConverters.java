package xiangze.mmu.rssnewsreader.data.database;

import androidx.room.TypeConverter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class TypeConverters {

    @TypeConverter
    public List<String> stringToList(String genreIds) {
        List<String> list = new ArrayList<>();

        String[] array = genreIds.split(",");

        for (String s : array) {
            if (!s.isEmpty()) {
                list.add(s);
            }
        }
        return list;
    }

    @TypeConverter
    public String stringListToString(List<String> list) {
        String genreIds = "";
        for (String s : list) {
            genreIds += "," + s;
        }
        return genreIds;
    }

    @TypeConverter
    public Date timestampToDate(Long value) {
        return value == null ? null : new Date(value);
    }

    @TypeConverter
    public Long dateToTimestamp(Date date) {
        if (date == null) {
            return null;
        } else {
            return date.getTime();
        }
    }
}