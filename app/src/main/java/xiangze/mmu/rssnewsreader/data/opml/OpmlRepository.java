package xiangze.mmu.rssnewsreader.data.opml;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.data.feed.FeedRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.rss.RssWorkManager;

@Singleton
public class OpmlRepository {
    private static final String TAG = "OpmlRepository";
    private final Context context;
    private final FeedRepository feedRepository;
    private final EntryRepository entryRepository;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final RssWorkManager rssWorkManager;

    @Inject
    public OpmlRepository(@ApplicationContext Context context,
                          FeedRepository feedRepository,
                          EntryRepository entryRepository,
                          SharedPreferencesRepository sharedPreferencesRepository,
                          RssWorkManager rssWorkManager) {
        this.context = context;
        this.feedRepository = feedRepository;
        this.entryRepository = entryRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.rssWorkManager = rssWorkManager;
    }

    public void importOpml(Uri uri, OnImportCompleteListener listener) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(inputStream, null);
            int eventType = parser.getEventType();
            @SuppressLint("SimpleDateFormat") SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            long feedId = 0;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.getName().equals("setting")) {
                    importSettings(parser);
                } else if (eventType == XmlPullParser.START_TAG && parser.getName().equals("outline")) {
                    feedId = importFeed(parser);
                } else if (eventType == XmlPullParser.START_TAG && parser.getName().equals("entry")) {
                    importEntry(parser, feedId, formatter);
                }
                eventType = parser.next();
            }
            if (inputStream != null) inputStream.close();
            if (listener != null) listener.onImportComplete(true, null);
        } catch (IOException | XmlPullParserException | ParseException e) {
            Log.e(TAG, "Import failed", e);
            if (listener != null) listener.onImportComplete(false, e.getMessage());
        }
    }

    private void importSettings(XmlPullParser parser) {
        String jobPeriodic = parser.getAttributeValue(null, "jobPeriodic");
        String highlightText = parser.getAttributeValue(null, "highlightText");
        String textZoom = parser.getAttributeValue(null, "textZoom");
        String sortBy = parser.getAttributeValue(null, "sortBy");
        String backgroundMusic = parser.getAttributeValue(null, "backgroundMusic");
        String backgroundMusicVolume = parser.getAttributeValue(null, "backgroundMusicVolume");
        String entriesLimitPerFeed = parser.getAttributeValue(null, "entriesLimitPerFeed");
        String confidenceThreshold = parser.getAttributeValue(null, "confidenceThreshold");
        String defaultTranslationLanguage = parser.getAttributeValue(null, "defaultTranslationLanguage");
        String translationMethod = parser.getAttributeValue(null, "translationMethod");
        String summaryLength = parser.getAttributeValue(null, "summaryLength");
        String night = parser.getAttributeValue(null, "night");
        String autoTranslate = parser.getAttributeValue(null, "autoTranslate");
        String autoSummarize = parser.getAttributeValue(null, "autoSummarize");
        String backgroundMusicFile = parser.getAttributeValue(null, "backgroundMusicFile");
        String aiModel = parser.getAttributeValue(null, "ai_model");
        String translationModel = parser.getAttributeValue(null, "translation_model");
        String summarizationModel = parser.getAttributeValue(null, "summarization_model");
        String chatbotModel = parser.getAttributeValue(null, "chatbot_model");
        String groqApiKey = parser.getAttributeValue(null, "groq_api_key");
        String abbreviationList = parser.getAttributeValue(null, "abbreviation_list");
        String customTranslationPrompt = parser.getAttributeValue(null, "customTranslationPrompt");
        String customSummarizationPrompt = parser.getAttributeValue(null, "customSummarizationPrompt");
        String aiLimitTpm = parser.getAttributeValue(null, "ai_limit_tpm");
        String aiLimitRpd = parser.getAttributeValue(null, "ai_limit_rpd");
        String aiLimitTpd = parser.getAttributeValue(null, "ai_limit_tpd");

        if (jobPeriodic != null && !jobPeriodic.isEmpty()) {
            sharedPreferencesRepository.setJobPeriodic(jobPeriodic);
            rssWorkManager.enqueueRssWorker();
        }
        if (highlightText != null && !highlightText.isEmpty()) {
            sharedPreferencesRepository.setHighlightText(highlightText.equals("true"));
        }
        if (textZoom != null && !textZoom.isEmpty()) {
            sharedPreferencesRepository.setTextZoom(Integer.parseInt(textZoom));
        }
        if (sortBy != null && !sortBy.isEmpty()) {
            sharedPreferencesRepository.setSortBy(sortBy);
        }
        if (backgroundMusic != null && !backgroundMusic.isEmpty()) {
            sharedPreferencesRepository.setBackgroundMusic(backgroundMusic.equals("true"));
        }
        if (backgroundMusicVolume != null && !backgroundMusicVolume.isEmpty()) {
            sharedPreferencesRepository.setBackgroundMusicVolume(Integer.parseInt(backgroundMusicVolume));
        }
        if (entriesLimitPerFeed != null && !entriesLimitPerFeed.isEmpty()) {
            sharedPreferencesRepository.setEntriesLimitPerFeed(Integer.parseInt(entriesLimitPerFeed));
        }
        if (confidenceThreshold != null && !confidenceThreshold.isEmpty()) {
            sharedPreferencesRepository.setConfidenceThreshold(Integer.parseInt(confidenceThreshold));
        }
        if (defaultTranslationLanguage != null && !defaultTranslationLanguage.isEmpty()) {
            sharedPreferencesRepository.setDefaultTranslationLanguage(defaultTranslationLanguage);
        }
        if (translationMethod != null && !translationMethod.isEmpty()) {
            sharedPreferencesRepository.setTranslationMethod(translationMethod);
        }
        if (summaryLength != null && !summaryLength.isEmpty()) {
            sharedPreferencesRepository.setSummaryLength(Integer.parseInt(summaryLength));
        }
        if (night != null && !night.isEmpty()) {
            sharedPreferencesRepository.setNight(night.equals("true"));
        }
        if (autoTranslate != null && !autoTranslate.isEmpty()) {
            sharedPreferencesRepository.setAutoTranslate(autoTranslate.equals("true"));
        }
        if (autoSummarize != null && !autoSummarize.isEmpty()) {
            sharedPreferencesRepository.setAutoSummarize(autoSummarize.equals("true"));
        }
        if (backgroundMusicFile != null && !backgroundMusicFile.isEmpty()) {
            sharedPreferencesRepository.setBackgroundMusicFile(backgroundMusicFile);
        }
        if (aiModel != null && !aiModel.isEmpty()) {
            sharedPreferencesRepository.setAiModel(aiModel);
        }
        if (translationModel != null && !translationModel.isEmpty()) {
            sharedPreferencesRepository.setTranslationModel(translationModel);
        }
        if (summarizationModel != null && !summarizationModel.isEmpty()) {
            sharedPreferencesRepository.setSummarizationModel(summarizationModel);
        }
        if (chatbotModel != null && !chatbotModel.isEmpty()) {
            sharedPreferencesRepository.setChatbotModel(chatbotModel);
        }
        if (groqApiKey != null && !groqApiKey.isEmpty()) {
            sharedPreferencesRepository.setGroqApiKey(groqApiKey);
        }
        if (abbreviationList != null && !abbreviationList.isEmpty()) {
            sharedPreferencesRepository.setAbbreviationList(abbreviationList);
        }
        if (customTranslationPrompt != null && !customTranslationPrompt.isEmpty()) {
            sharedPreferencesRepository.setCustomTranslationPrompt(customTranslationPrompt);
        }
        if (customSummarizationPrompt != null && !customSummarizationPrompt.isEmpty()) {
            sharedPreferencesRepository.setCustomSummarizationPrompt(customSummarizationPrompt);
        }
        if (aiLimitTpm != null && !aiLimitTpm.isEmpty()) {
            sharedPreferencesRepository.setAiLimitTpm(Integer.parseInt(aiLimitTpm));
        }
        if (aiLimitRpd != null && !aiLimitRpd.isEmpty()) {
            sharedPreferencesRepository.setAiLimitRpd(Integer.parseInt(aiLimitRpd));
        }
        if (aiLimitTpd != null && !aiLimitTpd.isEmpty()) {
            sharedPreferencesRepository.setAiLimitTpd(Integer.parseInt(aiLimitTpd));
        }
    }

    private long importFeed(XmlPullParser parser) {
        String title = parser.getAttributeValue(null, "text");
        String link = parser.getAttributeValue(null, "xmlUrl");
        String imageUrl = parser.getAttributeValue(null, "imageUrl");
        String description = parser.getAttributeValue(null, "description");
        String language = parser.getAttributeValue(null, "language");
        String delayTimeString = parser.getAttributeValue(null, "delayTime");
        int delayTime = 0;
        if (delayTimeString != null) {
            delayTime = Integer.parseInt(delayTimeString);
        }
        String ttsSpeechRateString = parser.getAttributeValue(null, "ttsSpeechRate");
        float ttsSpeechRate = 0;
        if (ttsSpeechRateString != null) {
            ttsSpeechRate = Float.parseFloat(ttsSpeechRateString);
        }

        if (link != null && !link.isEmpty()) {
            Feed feed = new Feed(title, link, description, imageUrl, (language == null || language.isEmpty()) ? null : language, delayTime, ttsSpeechRate);
            if (!feedRepository.checkFeedExist(feed.getLink())) {
                feedRepository.insert(feed);
            }
            return feedRepository.getFeedIdByLink(link);
        }
        return 0;
    }

    private void importEntry(XmlPullParser parser, long feedId, SimpleDateFormat formatter) throws ParseException {
        String entryTitle = parser.getAttributeValue(null, "entryTitle");
        String bookmark = parser.getAttributeValue(null, "bookmark");
        String visitedDate = parser.getAttributeValue(null, "visitedDate");
        String link = parser.getAttributeValue(null, "link");
        String description = parser.getAttributeValue(null, "description");
        String publishedDate = parser.getAttributeValue(null, "publishedDate");
        String entryImageUrl = parser.getAttributeValue(null, "entryImageUrl");
        String entryCategory = parser.getAttributeValue(null, "entryCategory");

        if (entryCategory != null && entryCategory.isEmpty()) entryCategory = null;
        if (entryImageUrl != null && entryImageUrl.isEmpty()) entryImageUrl = null;
        if (description != null && description.isEmpty()) description = null;

        if (link != null && !link.isEmpty()) {
            Entry entry = new Entry(feedId, entryTitle, link, description, entryImageUrl, entryCategory, formatter.parse(publishedDate));
            if (bookmark != null && !bookmark.isEmpty()) {
                entry.setBookmark(bookmark);
            }
            if (visitedDate != null && !visitedDate.isEmpty()) {
                entry.setVisitedDate(formatter.parse(visitedDate));
            }
            entryRepository.insert(feedId, entry);
        }
    }

    public void exportOpml(Uri uri, OnExportCompleteListener listener) {
        try {
            XmlSerializer serializer = Xml.newSerializer();
            OutputStream os = context.getContentResolver().openOutputStream(uri);
            serializer.setOutput(os, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, "opml");
            serializer.startTag(null, "body");

            exportSettings(serializer);

            List<Feed> feeds = feedRepository.getAllStaticFeeds();
            @SuppressLint("SimpleDateFormat") SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (Feed feed : feeds) {
                serializer.startTag(null, "outline");
                serializer.attribute(null, "text", feed.getTitle() != null ? feed.getTitle() : "");
                serializer.attribute(null, "title", feed.getTitle() != null ? feed.getTitle() : "");
                serializer.attribute(null, "imageUrl", feed.getImageUrl() != null ? feed.getImageUrl() : "");
                serializer.attribute(null, "description", feed.getDescription() != null ? feed.getDescription() : "");
                serializer.attribute(null, "language", feed.getLanguage() != null ? feed.getLanguage() : "");
                serializer.attribute(null, "xmlUrl", feed.getLink() != null ? feed.getLink() : "");
                serializer.attribute(null, "delayTime", Integer.toString(feed.getDelayTime()));
                serializer.attribute(null, "ttsSpeechRate", Float.toString(feed.getTtsSpeechRate()));
                serializer.attribute(null, "type", "rss");

                List<Entry> entries = entryRepository.getStaticEntries(feed.getId());
                for (Entry entry : entries) {
                    serializer.startTag(null, "entry");
                    serializer.attribute(null, "entryTitle", entry.getTitle() != null ? entry.getTitle() : "");
                    serializer.attribute(null, "bookmark", entry.getBookmark() != null ? entry.getBookmark() : "");
                    serializer.attribute(null, "visitedDate", entry.getVisitedDate() != null ? formatter.format(entry.getVisitedDate()) : "");
                    serializer.attribute(null, "link", entry.getLink() != null ? entry.getLink() : "");
                    serializer.attribute(null, "description", entry.getDescription() != null ? entry.getDescription() : "");
                    serializer.attribute(null, "publishedDate", entry.getPublishedDate() != null ? formatter.format(entry.getPublishedDate()) : "");
                    serializer.attribute(null, "entryImageUrl", entry.getImageUrl() != null ? entry.getImageUrl() : "");
                    serializer.attribute(null, "entryCategory", entry.getCategory() != null ? entry.getCategory() : "");
                    serializer.endTag(null, "entry");
                }
                serializer.endTag(null, "outline");
            }

            serializer.endTag(null, "body");
            serializer.endTag(null, "opml");
            serializer.endDocument();
            if (os != null) os.close();
            if (listener != null) listener.onExportComplete(true, null);
        } catch (IOException e) {
            Log.e(TAG, "Export failed", e);
            if (listener != null) listener.onExportComplete(false, e.getMessage());
        }
    }

    private void exportSettings(XmlSerializer serializer) throws IOException {
        serializer.startTag(null, "setting");
        serializer.attribute(null, "jobPeriodic", Integer.toString(sharedPreferencesRepository.getJobPeriodic()));
        serializer.attribute(null, "highlightText", sharedPreferencesRepository.getHighlightText() ? "true" : "false");
        serializer.attribute(null, "textZoom", Integer.toString(sharedPreferencesRepository.getTextZoom()));
        serializer.attribute(null, "sortBy", sharedPreferencesRepository.getSortBy());
        serializer.attribute(null, "backgroundMusic", sharedPreferencesRepository.getBackgroundMusic() ? "true" : "false");
        serializer.attribute(null, "backgroundMusicVolume", Integer.toString(sharedPreferencesRepository.getBackgroundMusicVolume()));
        serializer.attribute(null, "entriesLimitPerFeed", Integer.toString(sharedPreferencesRepository.getEntriesLimitPerFeed()));
        serializer.attribute(null, "confidenceThreshold", Integer.toString(sharedPreferencesRepository.getConfidenceThreshold()));
        serializer.attribute(null, "defaultTranslationLanguage", sharedPreferencesRepository.getDefaultTranslationLanguage());
        serializer.attribute(null, "translationMethod", sharedPreferencesRepository.getTranslationMethod());
        serializer.attribute(null, "summaryLength", Integer.toString(sharedPreferencesRepository.getSummaryLength()));
        serializer.attribute(null, "night", sharedPreferencesRepository.getNight() ? "true" : "false");
        serializer.attribute(null, "autoTranslate", sharedPreferencesRepository.getAutoTranslate() ? "true" : "false");
        serializer.attribute(null, "autoSummarize", sharedPreferencesRepository.getAutoSummarize() ? "true" : "false");
        serializer.attribute(null, "backgroundMusicFile", sharedPreferencesRepository.getBackgroundMusicFile());
        serializer.attribute(null, "ai_model", sharedPreferencesRepository.getAiModel());
        serializer.attribute(null, "translation_model", sharedPreferencesRepository.getTranslationModel());
        serializer.attribute(null, "summarization_model", sharedPreferencesRepository.getSummarizationModel());
        serializer.attribute(null, "chatbot_model", sharedPreferencesRepository.getChatbotModel());
        serializer.attribute(null, "groq_api_key", sharedPreferencesRepository.getGroqApiKey());
        serializer.attribute(null, "abbreviation_list", sharedPreferencesRepository.getAbbreviationList());
        serializer.attribute(null, "customTranslationPrompt", sharedPreferencesRepository.getCustomTranslationPrompt());
        serializer.attribute(null, "customSummarizationPrompt", sharedPreferencesRepository.getCustomSummarizationPrompt());
        serializer.attribute(null, "ai_limit_tpm", Integer.toString(sharedPreferencesRepository.getAiLimitTpm()));
        serializer.attribute(null, "ai_limit_rpd", Integer.toString(sharedPreferencesRepository.getAiLimitRpd()));
        serializer.attribute(null, "ai_limit_tpd", Integer.toString(sharedPreferencesRepository.getAiLimitTpd()));
        serializer.endTag(null, "setting");
    }

    public interface OnImportCompleteListener {
        void onImportComplete(boolean success, String error);
    }

    public interface OnExportCompleteListener {
        void onExportComplete(boolean success, String error);
    }
}
