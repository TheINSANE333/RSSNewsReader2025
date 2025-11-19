package xiangze.mmu.rssnewsreader.data.history;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

public class HistoryRepository {

    private HistoryDao historyDao;

    @Inject
    public HistoryRepository(HistoryDao historyDao) {
        this.historyDao = historyDao;
    }

    public void insert(History history) {
        historyDao.insert(history);
    }

    public void update(History history) {
        historyDao.update(history);
    }

    public void delete(History history) {
        historyDao.delete(history);
    }

    public boolean checkTitleExist(long feedId, String title) {
        long id = historyDao.checkTitle(feedId, title);
        return id != 0;
    }

    public boolean checkLinkExist(long feedId, String link) {
        long id = historyDao.checkLink(feedId, link);
        return id != 0;
    }

    public void deleteByFeedId(long feedId) {
        historyDao.deleteByFeedId(feedId);
    }

    public void updateHistoriesByFeedId(long feedId, List<History> histories) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -30);
        Date thirtyDaysAgo = calendar.getTime();

        // Delete histories that stored more than 30 days
        historyDao.deleteOldHistoriesByFeedId(feedId, thirtyDaysAgo);

        for (History history : histories) {
            if (checkTitleExist(feedId, history.getTitle())) {
                historyDao.updateInsertDateByTitle(feedId, history.getTitle(), history.getInsertDate());
            } else if (checkLinkExist(feedId, history.getLink())) {
                historyDao.updateInsertDateByLink(feedId, history.getLink(), history.getInsertDate());
            } else {
                insert(history);
            }
        }
    }
}
