package xiangze.mmu.rssnewsreader.ui.webview;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
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
        loadingState.postValue(isLoading);
    }

    @Inject
    public WebViewViewModel(EntryRepository entryRepository, xiangze.mmu.rssnewsreader.data.playlist.PlaylistRepository playlistRepository) {
        this.entryRepository = entryRepository;
        this.playlistRepository = playlistRepository;
    }

    public void resetEntry(long id) {
        entryRepository.updateHtml(null, id);
        entryRepository.updateOriginalHtml(null, id);
        entryRepository.updateTranslatedText(null, id);
        entryRepository.updateTranslated(null, id);
        entryRepository.updateTranslatedHtml(null, id);
        entryRepository.updateSummarized(null, id);
        entryRepository.updateSummarizedHtml(null, id);
        entryRepository.updateSummarizedText(null, id);
        entryRepository.updateContent(null, id);
        entryRepository.updateSentCountByLink(0, id);
        entryRepository.updatePriority(1, id);
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
        translatedHtmlLiveData.postValue(html);
    }

    public void updateTranslatedHtml(String html, long id) {
        entryRepository.updateTranslatedHtml(html, id);
        translatedHtmlLiveData.postValue(html);
    }

    public void updateSummarizedHtml(String html, long id) {
        entryRepository.updateSummarizedHtml(html, id);
        summarizedHtmlLiveData.postValue(html);
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
                "</style>";
    }

    @SuppressLint("SimpleDateFormat")
    public String getHtml(String entryTitle, String feedTitle, Date publishDate, String feedImageUrl, boolean isNightMode) {
        String textColor = isNightMode ? "#E2E2E6" : "#1B1B1F";
        return "<div class=\"entry-header\" style=\"color: " + textColor + "\">" +
                "  <div style=\"display: flex; align-items: center;\">" +
                "    <img style=\"margin-right: 10px; width: 20px; height: 20px\" src=" + feedImageUrl + ">" +
                "    <p style=\"font-size: 0.75em\">" + feedTitle + "</p>" +
                "  </div>" +
                "  <p style=\"margin:0; font-size: 1.25em; font-weight:bold\">" + entryTitle + "</p>" +
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
            String lowerText = text.toLowerCase();

            // Check for common abbreviations
            if (lowerText.endsWith("dr.") ||
                    lowerText.endsWith("mr.") ||
                    lowerText.endsWith("ms.") ||
                    lowerText.endsWith("mrs.") ||
                    lowerText.endsWith("prof.") ||
                    lowerText.endsWith("inc.") ||
                    lowerText.endsWith("ltd.")) {
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
        originalHtmlLiveData.postValue(html);
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
        entryIdTrigger.postValue(entryId);
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
            translatedTextReady.postValue(text);
        }
    }

    public void setSummarizedTextReady(long id, String text) {
        if (text != null && !text.trim().isEmpty()) {
            summarizedTextReady.postValue(text);
        }
    }
}
