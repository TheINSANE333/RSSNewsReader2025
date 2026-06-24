package xiangze.mmu.rssnewsreader.data.sharedpreferences;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;

import xiangze.mmu.rssnewsreader.model.ai.TokenUsageGuard;

public class SharedPreferencesRepository {

    private final SharedPreferences sharedPreferences;
    private final Context context;
    private static final String KEY_TOGGLE_STATE_PREFIX = "is_translated_view_";
    private static final String KEY_TOGGLE_STATE_PREFIX2 = "is_summarized_view_";
    private static final String KEY_SCROLL_X_PREFIX = "scroll_x_";
    private static final String KEY_SCROLL_Y_PREFIX = "scroll_y_";
    private static final String KEY_WEB_VIEW_MODE = "web_view_mode_";
    private static final String KEY_CURRENT_READING_ENTRY_ID = "current_reading_entry_id";
    private static final String KEY_SAVED_API_KEYS = "saved_api_keys";
    private static final String KEY_FIRST_LAUNCH = "is_first_launch";
    private static final String KEY_FIRST_ARTICLE_VIEW = "is_first_article_view_v2";
    private static final String KEY_FIRST_MAIN_ACTIVITY_VIEW = "is_first_main_activity_view_v2";
    private static final String KEY_ZOOM_SCALE = "zoom_scale";
    private static final String KEY_BROWSER_ZOOM_SCALE = "browser_zoom_scale";

    public static class ApiKey {
        @com.google.gson.annotations.SerializedName("name")
        public String name;
        @com.google.gson.annotations.SerializedName("value")
        public String value;

        public ApiKey() {}

        public ApiKey(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    @Inject
    public SharedPreferencesRepository(@ApplicationContext Context context) {
        this.context = context;
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public int getJobPeriodic() {
        return Integer.parseInt(sharedPreferences.getString("jobPeriodic", "0"));
    }

    public void setInitialJobPeriodic() {
        sharedPreferences.edit().putString("jobPeriodic", "360").apply();
    }

    public void setJobPeriodic(String jobPeriodic) {
        sharedPreferences.edit().putString("jobPeriodic", jobPeriodic).apply();
    }

    public boolean getNight() {
        return sharedPreferences.getBoolean("night", false);
    }

    public void setNight(boolean isNight) {
        sharedPreferences.edit().putBoolean("night", isNight).apply();
    }

    public boolean getHighlightText() {
        return sharedPreferences.getBoolean("highlightText", true);
    }

    public void setHighlightText(boolean highlightText) {
        sharedPreferences.edit().putBoolean("highlightText", highlightText).apply();
    }

    public void setTextZoom(int textZoom) {
        sharedPreferences.edit().putInt("textZoom", textZoom).apply();
    }

    public int getTextZoom() {
        return sharedPreferences.getInt("textZoom", 0);
    }

    public void setBrowserTextZoom(int textZoom) {
        sharedPreferences.edit().putInt("browserTextZoom", textZoom).apply();
    }

    public int getBrowserTextZoom() {
        return sharedPreferences.getInt("browserTextZoom", 0);
    }

    public void setZoomScale(int scale) {
        sharedPreferences.edit().putInt(KEY_ZOOM_SCALE, scale).apply();
    }

    public int getZoomScale() {
        return sharedPreferences.getInt(KEY_ZOOM_SCALE, 0);
    }

    public void setBrowserZoomScale(int scale) {
        sharedPreferences.edit().putInt(KEY_BROWSER_ZOOM_SCALE, scale).apply();
    }

    public int getBrowserZoomScale() {
        return sharedPreferences.getInt(KEY_BROWSER_ZOOM_SCALE, 0);
    }

    public void setSortBy(String sortBy) {
        sharedPreferences.edit().putString("sortBy", sortBy).apply();
    }

    public String getSortBy() {
        return sharedPreferences.getString("sortBy", "oldest");
    }

    public int getConfidenceThreshold() {
        return sharedPreferences.getInt("confidenceThreshold", 50);
    }

    public int getSummaryLength() {
        return sharedPreferences.getInt("summaryLength", 100);
    }

    public void setConfidenceThreshold(int confidenceThreshold) {
        sharedPreferences.edit().putInt("confidenceThreshold", confidenceThreshold).apply();
    }

    public boolean getBackgroundMusic() {
        return sharedPreferences.getBoolean("backgroundMusic", false);
    }

    public void setBackgroundMusic(boolean backgroundMusic) {
        sharedPreferences.edit().putBoolean("backgroundMusic", backgroundMusic).apply();
    }

    public String getBackgroundMusicFile() {
        return sharedPreferences.getString("backgroundMusicFile", "default");
    }

    public void setBackgroundMusicFile(String file) {
        sharedPreferences.edit().putString("backgroundMusicFile", file).apply();
    }

    public int getBackgroundMusicVolume() {
        return sharedPreferences.getInt("backgroundMusicVolume", 50);
    }

    public void setBackgroundMusicVolume(int volume) {
        sharedPreferences.edit().putInt("backgroundMusicVolume", volume).apply();
    }

    public int getEntriesLimitPerFeed() {
        return sharedPreferences.getInt("entriesLimitPerFeed", 1000);
    }

    public void setEntriesLimitPerFeed(int limit) {
        sharedPreferences.edit().putInt("entriesLimitPerFeed", limit).apply();
    }

    public boolean getIsPausedManually() {
        return sharedPreferences.getBoolean("isPausedManually", false);
    }

    public void setIsPausedManually(boolean isPaused) {
        sharedPreferences.edit().putBoolean("isPausedManually", isPaused).apply();
    }

    public String getSavedViewMode(long entryId) {
        return sharedPreferences.getString("savedViewMode_" + entryId, "original");
    }

    public void setSavedViewMode(long entryId, String viewMode) {
        sharedPreferences.edit().putString("savedViewMode_" + entryId, viewMode).apply();
    }

    public String getDefaultTranslationLanguage() {
        return sharedPreferences.getString("defaultTranslationLanguage", java.util.Locale.getDefault().getLanguage());
    }

    public void setDefaultTranslationLanguage(String language) {
        sharedPreferences.edit().putString("defaultTranslationLanguage", language).apply();
    }

    public void setSummaryLength(int summaryLength) {
        sharedPreferences.edit().putInt("summaryLength", summaryLength).apply();
    }

    public void setGroqApiKey(String apiKey) {
        String oldKey = getGroqApiKey();
        sharedPreferences.edit().putString("groq_api_key", apiKey).apply();

        // Auto-reset token usage if key changed
        if (apiKey != null && !apiKey.equals(oldKey)) {
            TokenUsageGuard.getInstance(context).resetManual();
        }

        // Also ensure it's in the saved list if not already
        if (apiKey != null && !apiKey.isEmpty()) {
            List<ApiKey> savedKeys = getSavedApiKeys();
            boolean exists = false;
            for (ApiKey key : savedKeys) {
                if (key != null && key.value != null && key.value.equals(apiKey)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                savedKeys.add(new ApiKey("Key " + (savedKeys.size() + 1), apiKey));
                setSavedApiKeys(savedKeys);
            }
        }
    }

    public List<ApiKey> getSavedApiKeys() {
        String json = sharedPreferences.getString(KEY_SAVED_API_KEYS, "");
        if (json == null || json.isEmpty() || json.equals("null")) {
            return new ArrayList<>();
        }
        try {
            Gson gson = new Gson();
            Type type = new TypeToken<List<ApiKey>>() {}.getType();
            List<ApiKey> keys = gson.fromJson(json, type);
            return keys != null ? keys : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void setSavedApiKeys(List<ApiKey> keys) {
        Gson gson = new Gson();
        String json = gson.toJson(keys);
        sharedPreferences.edit().putString(KEY_SAVED_API_KEYS, json).apply();
    }

    public void removeSavedApiKey(String value) {
        List<ApiKey> keys = getSavedApiKeys();
        keys.removeIf(k -> k != null && k.value != null && k.value.equals(value));
        setSavedApiKeys(keys);

        // If we removed the active key, clear it
        if (getGroqApiKey().equals(value)) {
            setGroqApiKey("");
        }
    }

    public void setAiModel(String aiModel) {
        sharedPreferences.edit().putString("ai_model", aiModel).apply();
    }

    public void setTranslationModel(String aiModel) {
        sharedPreferences.edit().putString("translation_model", aiModel).apply();
    }

    public void setSummarizationModel(String aiModel) {
        sharedPreferences.edit().putString("summarization_model", aiModel).apply();
    }

    public void setChatbotModel(String aiModel) {
        sharedPreferences.edit().putString("chatbot_model", aiModel).apply();
    }

    public String getTranslationMethod() {
        return sharedPreferences.getString("translationMethod", "allAtOnce");
    }

    public void setTranslationMethod(String method) {
        sharedPreferences.edit().putString("translationMethod", method).apply();
    }

    public boolean getAutoTranslate() {
        return sharedPreferences.getBoolean("autoTranslate", false);
    }

    public void setAutoTranslate(boolean autoTranslate) {
        sharedPreferences.edit().putBoolean("autoTranslate", autoTranslate).apply();
    }

    public boolean getAutoSummarize() {
        return sharedPreferences.getBoolean("autoSummarize", false);
    }

    public void setAutoSummarize(boolean autoSummarize) {
        sharedPreferences.edit().putBoolean("autoSummarize", autoSummarize).apply();
    }

    public void setIsTranslatedView(long entryId, boolean isTranslatedView) {
        sharedPreferences.edit()
                .putBoolean(KEY_TOGGLE_STATE_PREFIX + entryId, isTranslatedView)
                .apply();
    }

    public void setIsSummarizedView(long entryId, boolean isSummarizedView) {
        sharedPreferences.edit()
                .putBoolean(KEY_TOGGLE_STATE_PREFIX2 + entryId, isSummarizedView)
                .apply();
    }

    public void removeTranslatedViewToggle(long entryId) {
        sharedPreferences.edit()
                .remove(KEY_TOGGLE_STATE_PREFIX + entryId)
                .apply();
    }

    public void removeSummarizedViewToggle(long entryId) {
        sharedPreferences.edit()
                .remove(KEY_TOGGLE_STATE_PREFIX2 + entryId)
                .apply();
    }

    public boolean getIsTranslatedView(long entryId) {
        return sharedPreferences.getBoolean(KEY_TOGGLE_STATE_PREFIX + entryId,false);
    }

    public boolean getIsSummarizedView(long entryId) {
        return sharedPreferences.getBoolean(KEY_TOGGLE_STATE_PREFIX2 + entryId,false);
    }

    public boolean hasTranslationToggle(long entryId) {
        return sharedPreferences.contains(KEY_TOGGLE_STATE_PREFIX + entryId);
    }

    public boolean hasSummarizationToggle(long entryId) {
        return sharedPreferences.contains(KEY_TOGGLE_STATE_PREFIX2 + entryId);
    }

    public void setScrollX(long entryId, int value) {
        sharedPreferences.edit().putInt(KEY_SCROLL_X_PREFIX + entryId, value).apply();
    }

    public void setScrollY(long entryId, int value) {
        sharedPreferences.edit().putInt(KEY_SCROLL_Y_PREFIX + entryId, value).apply();
    }

    public int getScrollX(long entryId) {
        return sharedPreferences.getInt(KEY_SCROLL_X_PREFIX + entryId, 0);
    }

    public int getScrollY(long entryId) {
        return sharedPreferences.getInt(KEY_SCROLL_Y_PREFIX + entryId, 0);
    }

    public void setWebViewMode(long entryId, boolean isWebViewMode) {
        sharedPreferences.edit().putBoolean(KEY_WEB_VIEW_MODE + entryId, isWebViewMode).apply();
    }

    public boolean getWebViewMode(long entryId) {
        return sharedPreferences.getBoolean(KEY_WEB_VIEW_MODE + entryId, false); // default to offline mode
    }

    private static final String KEY_TTS_SUBSTITUTIONS = "tts_substitutions";

    public java.util.Map<String, String> getTtsSubstitutions() {
        String json = sharedPreferences.getString(KEY_TTS_SUBSTITUTIONS, "");
        if (json.isEmpty()) {
            java.util.Map<String, String> defaults = new java.util.HashMap<>();
            defaults.put("Dr.", "Doctor");
            defaults.put("St.", "Saint");
            return defaults;
        }
        Gson gson = new Gson();
        Type type = new TypeToken<java.util.Map<String, String>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public void setTtsSubstitutions(java.util.Map<String, String> substitutions) {
        Gson gson = new Gson();
        String json = gson.toJson(substitutions);
        sharedPreferences.edit().putString(KEY_TTS_SUBSTITUTIONS, json).apply();
    }

    public void setCurrentReadingEntryId(long entryId) {
        sharedPreferences.edit().putLong(KEY_CURRENT_READING_ENTRY_ID, entryId).apply();
    }

    public long getCurrentReadingEntryId() {
        return sharedPreferences.getLong(KEY_CURRENT_READING_ENTRY_ID, -1);
    }

    public String getGroqApiKey() {
        return sharedPreferences.getString("groq_api_key", "");
    }

    public boolean switchToNextKey() {
        List<ApiKey> keys = getSavedApiKeys();
        // Remove any null or invalid keys first
        keys.removeIf(k -> k == null || k.value == null || k.value.isEmpty());
        
        if (keys.size() <= 1) {
            return false;
        }
        String currentKey = getGroqApiKey();
        int currentIndex = -1;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).value.equals(currentKey)) {
                currentIndex = i;
                break;
            }
        }

        int nextIndex = (currentIndex + 1) % keys.size();
        setGroqApiKey(keys.get(nextIndex).value);
        return true;
    }

    public String getAiModel() {
        return sharedPreferences.getString("ai_model", "llama-3.3-70b-versatile");
    }

    public String getTranslationModel() {
        return sharedPreferences.getString("translation_model", "llama-3.3-70b-versatile");
    }

    public String getSummarizationModel() {
        return sharedPreferences.getString("summarization_model", "llama-3.3-70b-versatile");
    }

    public String getChatbotModel() {
        return sharedPreferences.getString("chatbot_model", "llama-3.3-70b-versatile");
    }

    public String getCustomTranslationPrompt() {
        return sharedPreferences.getString("customTranslationPrompt", "");
    }

    public void setCustomTranslationPrompt(String prompt) {
        sharedPreferences.edit().putString("customTranslationPrompt", prompt).apply();
    }

    public String getCustomSummarizationPrompt() {
        return sharedPreferences.getString("customSummarizationPrompt", "");
    }

    public void setCustomSummarizationPrompt(String prompt) {
        sharedPreferences.edit().putString("customSummarizationPrompt", prompt).apply();
    }

    public String getAbbreviationList() {
        return sharedPreferences.getString("abbreviation_list", "Mr., Mrs., Ms., Dr., Prof., Sr., Jr., St., vs., etc., e.g., i.e., Fig., No., Rev.");
    }

    public void setAbbreviationList(String list) {
        sharedPreferences.edit().putString("abbreviation_list", list).apply();
    }

    public int getAiLimitTpm() {
        return sharedPreferences.getInt(TokenUsageGuard.KEY_LIMIT_TPM, 30000);
    }

    public void setAiLimitTpm(int tpm) {
        sharedPreferences.edit().putInt(TokenUsageGuard.KEY_LIMIT_TPM, tpm).apply();
    }

    public int getAiLimitRpd() {
        return sharedPreferences.getInt(TokenUsageGuard.KEY_LIMIT_RPD, 1000);
    }

    public void setAiLimitRpd(int rpd) {
        sharedPreferences.edit().putInt(TokenUsageGuard.KEY_LIMIT_RPD, rpd).apply();
    }

    public int getAiLimitTpd() {
        return sharedPreferences.getInt(TokenUsageGuard.KEY_LIMIT_TPD, 500000);
    }

    public void setAiLimitTpd(int tpd) {
        sharedPreferences.edit().putInt(TokenUsageGuard.KEY_LIMIT_TPD, tpd).apply();
    }

    public boolean getEnableChunkLimit() {
        return sharedPreferences.getBoolean("enable_chunk_limit", true);
    }

    public void setEnableChunkLimit(boolean enabled) {
        sharedPreferences.edit().putBoolean("enable_chunk_limit", enabled).apply();
    }

    public int getChunkLimit() {
        if (!getEnableChunkLimit()) {
            return Integer.MAX_VALUE;
        }
        return sharedPreferences.getInt("chunk_limit", 7500);
    }

    public int getRawChunkLimit() {
        return sharedPreferences.getInt("chunk_limit", 7500);
    }

    public void setChunkLimit(int limit) {
        sharedPreferences.edit().putInt("chunk_limit", limit).apply();
    }

    public int getMaxFiles() {
        return sharedPreferences.getInt("max_files", 10);
    }

    public void setMaxFiles(int maxFiles) {
        sharedPreferences.edit().putInt("max_files", maxFiles).apply();
    }

    public String getDailySummaryPrompt() {
        return sharedPreferences.getString("daily_summary_prompt", context.getString(xiangze.mmu.rssnewsreader.R.string.daily_summary_prompt_default));
    }

    public void setDailySummaryPrompt(String prompt) {
        sharedPreferences.edit().putString("daily_summary_prompt", prompt).apply();
    }

    public boolean isFirstLaunch() {
        return sharedPreferences.getBoolean(KEY_FIRST_LAUNCH, true);
    }

    public void setFirstLaunch(boolean isFirstLaunch) {
        sharedPreferences.edit().putBoolean(KEY_FIRST_LAUNCH, isFirstLaunch).apply();
    }

    public boolean isFirstArticleView() {
        return sharedPreferences.getBoolean(KEY_FIRST_ARTICLE_VIEW, true);
    }

    public void setFirstArticleView(boolean isFirstArticleView) {
        sharedPreferences.edit().putBoolean(KEY_FIRST_ARTICLE_VIEW, isFirstArticleView).apply();
    }

    public boolean isFirstMainActivityView() {
        return sharedPreferences.getBoolean(KEY_FIRST_MAIN_ACTIVITY_VIEW, true);
    }

    public void setFirstMainActivityView(boolean isFirst) {
        sharedPreferences.edit().putBoolean(KEY_FIRST_MAIN_ACTIVITY_VIEW, isFirst).apply();
    }

    public Context getContext() {
        return context;
    }
}
