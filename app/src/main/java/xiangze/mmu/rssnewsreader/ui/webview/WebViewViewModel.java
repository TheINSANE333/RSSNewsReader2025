package xiangze.mmu.rssnewsreader.ui.webview;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class WebViewViewModel extends ViewModel {

    private final EntryRepository entryRepository;
    private final xiangze.mmu.rssnewsreader.data.playlist.PlaylistRepository playlistRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private long currentId;

    private final MutableLiveData<String> originalHtmlLiveData = new MutableLiveData<>();

    private final MutableLiveData<String> translatedHtmlLiveData = new MutableLiveData<>();

    private final MutableLiveData<String> summarizedHtmlLiveData = new MutableLiveData<>();

    private final MutableLiveData<String> translatedTextReady = new MutableLiveData<>();

    private final MutableLiveData<String> summarizedTextReady = new MutableLiveData<>();

    private final MutableLiveData<Boolean> loadingState = new MutableLiveData<>();

    private final MutableLiveData<Long> entryIdTrigger = new MutableLiveData<>();

    public LiveData<Boolean> getLoadingState() {
        return loadingState;
    }

    public void setLoadingState(boolean isLoading) {
        setLiveDataValue(loadingState, isLoading);
    }

    private <T> void setLiveDataValue(MutableLiveData<T> liveData, T value) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            liveData.setValue(value);
        } else {
            liveData.postValue(value);
        }
    }

    private final MutableLiveData<Long> currentIdLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isTranslatedViewLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isSummarizedViewLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> snackbarMessageLiveData = new MutableLiveData<>();

    public LiveData<Long> getCurrentIdLiveData() { return currentIdLiveData; }
    public LiveData<Boolean> getIsTranslatedViewLiveData() { return isTranslatedViewLiveData; }
    public LiveData<Boolean> getIsSummarizedViewLiveData() { return isSummarizedViewLiveData; }
    public LiveData<String> getSnackbarMessageLiveData() { return snackbarMessageLiveData; }

    public void setCurrentId(long id) {
        this.currentId = id;
        setLiveDataValue(currentIdLiveData, id);
        triggerEntryRefresh(id);
    }

    public void setIsTranslatedView(boolean isTranslated) {
        sharedPreferencesRepository.setIsTranslatedView(currentId, isTranslated);
        setLiveDataValue(isTranslatedViewLiveData, isTranslated);
    }

    public void setIsSummarizedView(boolean isSummarized) {
        sharedPreferencesRepository.setIsSummarizedView(currentId, isSummarized);
        setLiveDataValue(isSummarizedViewLiveData, isSummarized);
    }

    public void makeSnackbar(String message) {
        setLiveDataValue(snackbarMessageLiveData, message);
    }

    public String rebuildHtml(EntryInfo entryInfo, boolean isNightMode) {
        if (entryInfo == null) return null;
        String html = getHtmlById(entryInfo.getEntryId());
        if (html == null) return null;

        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        doc.head().append(getStyle(isNightMode));

        String titleClass = null;
        Boolean isSummarized = isSummarizedViewLiveData.getValue();
        Boolean isTranslated = isTranslatedViewLiveData.getValue();
        
        if (isSummarized != null && isSummarized) titleClass = "summarized-title";
        else if (isTranslated != null && isTranslated) titleClass = "translated-title";

        org.jsoup.nodes.Element body = doc.selectFirst("body");
        if (body != null) {
            body.prepend(
                    getHtml(
                            entryInfo.getEntryTitle(),
                            entryInfo.getFeedTitle(),
                            entryInfo.getEntryPublishedDate(),
                            entryInfo.getFeedImageUrl(),
                            isNightMode,
                            titleClass
                    )
            );
        }

        return doc.html();
    }

    public void reExtract(long id) {
        if (id <= 0) return;
        entryRepository.updateHtml(null, id);
        entryRepository.updateOriginalHtml(null, id);
        entryRepository.updateTranslatedText(null, id);
        entryRepository.updateTranslatedHtml(null, id);
        entryRepository.updateSummarizedText(null, id);
        entryRepository.updateSummarizedHtml(null, id);
        entryRepository.updateContent(null, id);
        entryRepository.updateSentCount(0, id);
        entryRepository.updatePriority(1, id);
        clearViewData();
        setIsTranslatedView(false);
        setIsSummarizedView(false);
        triggerEntryRefresh(id);
    }

    @Inject
    public WebViewViewModel(EntryRepository entryRepository, 
                            xiangze.mmu.rssnewsreader.data.playlist.PlaylistRepository playlistRepository,
                            SharedPreferencesRepository sharedPreferencesRepository) {
        this.entryRepository = entryRepository;
        this.playlistRepository = playlistRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    public void resetEntry(long id) {
        reExtract(id);
    }

    public void clearLiveEntryCache(long id) {
        Entry entry = entryRepository.getEntryById(id);
        if (entry != null) {
            entry.setTranslated(null);
            entry.setSummarized(null);
            entry.setHtml(null);
            entry.setContent(null);
        }
    }

    public void updateHtml(String html, long id) {
        entryRepository.updateHtml(html, id);
        setLiveDataValue(originalHtmlLiveData, html);
    }

    public void updateTranslatedHtml(String html, long id) {
        entryRepository.updateTranslatedHtml(html, id);
        setLiveDataValue(translatedHtmlLiveData, html);
    }

    public void clearViewData() {
        setLiveDataValue(originalHtmlLiveData, null);
        setLiveDataValue(translatedHtmlLiveData, null);
        setLiveDataValue(summarizedHtmlLiveData, null);
        setLiveDataValue(translatedTextReady, null);
        setLiveDataValue(summarizedTextReady, null);
    }

    public void updateSummarizedHtml(String html, long id) {
        entryRepository.updateSummarizedHtml(html, id);
        setLiveDataValue(summarizedHtmlLiveData, html);
    }

    public void updateContent(String content, long id) {
        entryRepository.updateContent(content, id);
    }

    public void updateBookmark(String bool, long id) {
        entryRepository.updateBookmark(bool, id);
    }

    public EntryInfo getLastVisitedEntry() {
        return entryRepository.getLastVisitedEntry();
    }

    public String getHtmlById(long id) {
        return entryRepository.getHtmlById(id);
    }

    public String getTranslatedHtmlById(long id) {
        return entryRepository.getTranslatedHtmlById(id);
    }

    public String getSummarizedHtmlById(long id) {
        return entryRepository.getSummarizedHtmlById(id);
    }

    public String getStyle(boolean isNightMode) {
        String textColor = isNightMode ? "#E2E2E6" : "#1B1B1F";
        String highlightColor = isNightMode ? "#FFD700" : "#FFFF00"; // Gold or Yellow
        return "<style>\n" +
                "    @font-face {\n" +
                "        font-family: open_sans;\n" +
                "        src: url(\"file:///android_res/font/open_sans.ttf\")\n" +
                "    }\n" +
                "    body {\n" +
                "        font-family: open_sans;\n" +
                "        text-align: justify;\n" +
                "        font-size: 0.875em;\n" +
                "        color: " + textColor + ";\n" +
                "    }\n" +
                "    .tts-highlight {\n" +
                "        background-color: " + highlightColor + " !important;\n" +
                "        color: black !important;\n" +
                "        border-radius: 2px;\n" +
                "    }\n" +
                "    mark.tts-highlight {\n" +
                "        background-color: " + highlightColor + " !important;\n" +
                "        color: black !important;\n" +
                "        border-radius: 2px;\n" +
                "    }\n" +
                "</style>";
    }

    @SuppressLint("SimpleDateFormat")
    public String getHtml(String entryTitle, String feedTitle, Date publishDate, String feedImageUrl, boolean isNightMode) {
        return getHtml(entryTitle, feedTitle, publishDate, feedImageUrl, isNightMode, null);
    }

    @SuppressLint("SimpleDateFormat")
    public String getHtml(String entryTitle, String feedTitle, Date publishDate, String feedImageUrl, boolean isNightMode, String titleClass) {
        String textColor = isNightMode ? "#E2E2E6" : "#1B1B1F";
        String classAttr = (titleClass != null && !titleClass.isEmpty()) ? " class=\"" + titleClass + "\"" : "";
        return "<div class=\"entry-header\" style=\"color: " + textColor + "\">" +
                "  <div style=\"display: flex; align-items: center;\">" +
                "    <img style=\"margin-right: 10px; width: 20px; height: 20px\" src=" + feedImageUrl + ">" +
                "    <p style=\"font-size: 0.75em\">" + feedTitle + "</p>" +
                "  </div>" +
                "  <p" + classAttr + " style=\"margin:0; font-size: 1.25em; font-weight:bold\">" + entryTitle + "</p>" +
                "  <p style=\"font-size: 0.75em;\">" + new SimpleDateFormat("EEE, d MMM yyyy 'at' hh:mm aaa").format(publishDate) + "</p>" +
                "</div>";
    }

    public boolean endsWithBreak(String text) {
        if (text == null || text.isEmpty()) return false;

        // 1. Handle non-period terminators first (always end a sentence)
        if (text.endsWith("?") || text.endsWith("!") || text.endsWith("！") ||
                text.endsWith("？") || text.endsWith("。")) {
            return true;
        }

        // 2. Handle the period "."
        if (text.endsWith(".")) {
            // Define patterns that should NOT be treated as a sentence end
            // This regex looks for:
            // - Titles: Dr. Mr. Ms. Prof. etc.
            // - Currency/Decimals: RM followed by digits and a dot, or just any digit before the dot
            // - Single letters: Initials like A. B.
            // - 1-3 letter abbreviations (New Rule)
            String lowerText = text.toLowerCase();

            // Check for common abbreviations and 1-3 letter patterns
            if (lowerText.endsWith("dr.") ||
                    lowerText.endsWith("mr.") ||
                    lowerText.endsWith("ms.") ||
                    lowerText.endsWith("mrs.") ||
                    lowerText.endsWith("prof.") ||
                    lowerText.endsWith("inc.") ||
                    lowerText.endsWith("ltd.") ||
                    text.matches(".*\\b[a-zA-Z]{1,3}\\.$")) {
                return false;
            }

            // Check for numbers (e.g., RM1.5 or 10.0)
            // regex: .*\d\.$ matches any string ending in a digit then a period
            return !text.matches(".*\\d\\.$");

            // Otherwise, it's a standard sentence-ending period
        }

        return false;
    }

    public EntryInfo getEntryInfoById(long id) {
        return entryRepository.getEntryInfoById(id);
    }

    public void updateOriginalHtml(String html, long id) {
        entryRepository.updateOriginalHtml(html, id);
        setLiveDataValue(originalHtmlLiveData, html);
    }

    public LiveData<String> getOriginalHtmlLiveData() {
        return originalHtmlLiveData;
    }

    public LiveData<String> getTranslatedHtmlLiveData() {
        return translatedHtmlLiveData;
    }

    public LiveData<String> getSummarizedHtmlLiveData() { return summarizedHtmlLiveData; }

    public String getOriginalHtmlById(long id) {
        return entryRepository.getOriginalHtmlById(id);
    }

    public void triggerEntryRefresh(long entryId) {
        setLiveDataValue(entryIdTrigger, entryId);
    }

    public void prioritizeEntry(long entryId) {
        entryRepository.updatePriority(1, entryId);
        prioritizeNextArticle(entryId);
    }

    private void prioritizeNextArticle(long currentId) {
        String latestPlaylist = playlistRepository.getLatestPlaylist();
        if (latestPlaylist == null || latestPlaylist.isEmpty()) return;

        List<Long> playlist = playlistRepository.stringToLongList(latestPlaylist);
        int currentIndex = playlist.indexOf(currentId);
        if (currentIndex != -1 && currentIndex < playlist.size() - 1) {
            long nextId = playlist.get(currentIndex + 1);
            entryRepository.updatePriority(2, nextId);
        }
    }

    public LiveData<Entry> getLiveEntry() {
        return Transformations.switchMap(entryIdTrigger, id ->
                entryRepository.getEntryEntityById(id)
        );
    }

    public LiveData<Entry> getEntryEntityById(long entryId) {
        return entryRepository.getEntryEntityById(entryId);
    }

    public Entry getEntryById(long entryId) {
        return entryRepository.getEntryById(entryId);
    }

    public String getTranslatedById(long id) {
        Entry entry = getEntryById(id);
        return (entry != null) ? entry.getTranslated() : null;
    }

    public String getSummarizedById(long id) {
        Entry entry = getEntryById(id);
        return (entry != null) ? entry.getSummarized() : null;
    }

    public void updateTranslated(String text, long entryId) {
        entryRepository.updateTranslated(text, entryId);
    }

    public void updateSummarized(String text, long entryId) {
        entryRepository.updateSummarized(text, entryId);
    }

    public void updateEntryTranslatedField(long entryId, String translatedContent) {
        Entry entry = entryRepository.getEntryById(entryId);
        if (entry != null) {
            entry.setTranslated(translatedContent);
        }
    }

    public void updateEntrySummarizedField(long entryId, String summarizedContent) {
        Entry entry = entryRepository.getEntryById(entryId);
        if (entry != null) {
            Log.d("SET SUMMARIZED", summarizedContent);
            entry.setSummarized(summarizedContent);
        }
    }

    public LiveData<String> getTranslatedTextReady() {
        return translatedTextReady;
    }

    public LiveData<String> getSummarizedTextReady() {
        return summarizedTextReady;
    }

    public void setTranslatedTextReady(long id, String text) {
        if (text != null && !text.trim().isEmpty()) {
            setLiveDataValue(translatedTextReady, text);
        }
    }

    public void setSummarizedTextReady(long id, String text) {
        if (text != null && !text.trim().isEmpty()) {
            setLiveDataValue(summarizedTextReady, text);
        }
    }
}