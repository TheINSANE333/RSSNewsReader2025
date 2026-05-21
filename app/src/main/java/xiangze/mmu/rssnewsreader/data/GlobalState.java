package xiangze.mmu.rssnewsreader.data;

public class GlobalState {
    private static volatile long currentViewingId = 0;

    public static void setCurrentViewingId(long id) {
        currentViewingId = id;
    }

    public static long getCurrentViewingId() {
        return currentViewingId;
    }
}
