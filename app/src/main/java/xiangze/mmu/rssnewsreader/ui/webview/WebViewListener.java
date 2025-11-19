package xiangze.mmu.rssnewsreader.ui.webview;

public interface WebViewListener {
    void highlightText(String searchText);
    void finishedSetup();
    void makeSnackbar(String message);
    void reload();
    void askForReload(long feedId);
    void showFakeLoading();
    void hideFakeLoading();
    void updateLoadingProgress(int progress);
}
