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
    private final SharedPreferences.Editor editor;
    private final Context context;
    private static final String KEY_TOGGLE_STATE_PREFIX = "is_translated_view_";
    private static final String KEY_TOGGLE_STATE_PREFIX2 = "is_summarized_view_";
    private static final String KEY_SCROLL_X_PREFIX = "scroll_x_";
    private static final String KEY_SCROLL_Y_PREFIX = "scroll_y_";
    private static final String KEY_WEB_VIEW_MODE = "web_view_mode_";
    private static final String KEY_CURRENT_READING_ENTRY_ID = "current_reading_entry_id";
    private static final String KEY_SAVED_API_KEYS = "saved_api_keys";

    public static class ApiKey {
        public String name;
        public String value;
        public ApiKey(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    @Inject
    public SharedPreferencesRepository(@ApplicationContext Context context) {
        this.context = context;
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        editor = sharedPreferences.edit();
    }

    public int getJobPeriodic() {
        return Integer.parseInt(sharedPreferences.getString("jobPeriodic", "0"));
    }

    public void setInitialJobPeriodic() {
        editor.putString("jobPeriodic", "360");
        editor.apply();
    }

    public void setJobPeriodic(String jobPeriodic) {
        editor.putString("jobPeriodic", jobPeriodic);
        editor.apply();
    }

    public boolean getNight() {
        return sharedPreferences.getBoolean("night", false);
    }

    public void setNight(boolean isNight) {
        editor.putBoolean("night", isNight);
        editor.apply();
    }

    public boolean getHighlightText() {
        return sharedPreferences.getBoolean("highlightText", true);
    }

    public void setHighlightText(boolean highlightText) {
        editor.putBoolean("highlightText", highlightText);
        editor.apply();
    }

    public void setTextZoom(int textZoom) {
        editor.putInt("textZoom", textZoom);
        editor.apply();
    }

    public int getTextZoom() {
        return sharedPreferences.getInt("textZoom", 0);
    }

    public void setSortBy(String sortBy) {
        editor.putString("sortBy", sortBy);
        editor.apply();
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
        editor.putInt("confidenceThreshold", confidenceThreshold);
        editor.apply();
    }

    public boolean getBackgroundMusic() {
        return sharedPreferences.getBoolean("backgroundMusic", false);
    }

    public void setBackgroundMusic(boolean backgroundMusic) {
        editor.putBoolean("backgroundMusic", backgroundMusic);
        editor.apply();
    }

    public String getBackgroundMusicFile() {
        return sharedPreferences.getString("backgroundMusicFile", "default");
    }

    public void setBackgroundMusicFile(String file) {
        editor.putString("backgroundMusicFile", file);
        editor.apply();
    }

    public int getBackgroundMusicVolume() {
        return sharedPreferences.getInt("backgroundMusicVolume", 50);
    }

    public void setBackgroundMusicVolume(int volume) {
        editor.putInt("backgroundMusicVolume", volume);
        editor.apply();
    }

    public int getEntriesLimitPerFeed() {
        return sharedPreferences.getInt("entriesLimitPerFeed", 1000);
    }

    public void setEntriesLimitPerFeed(int limit) {
        editor.putInt("entriesLimitPerFeed", limit);
        editor.apply();
    }

    public boolean getIsPausedManually() {
        return sharedPreferences.getBoolean("isPausedManually", false);
    }

    public void setIsPausedManually(boolean isPaused) {
        editor.putBoolean("isPausedManually", isPaused);
        editor.apply();
    }

    public String getDefaultTranslationLanguage() {
        return sharedPreferences.getString("defaultTranslationLanguage", "zh");
    }

    public void setDefaultTranslationLanguage(String language) {
        editor.putString("defaultTranslationLanguage", language).apply();
    }

    public void setSummaryLength(int summaryLength) {
        editor.putInt("summaryLength", summaryLength);
        editor.apply();
    }

    public void setGroqApiKey(String apiKey) {
        String oldKey = getGroqApiKey();
        editor.putString("groq_api_key", apiKey);
        editor.apply();

        // Auto-reset token usage if key changed
        if (apiKey != null && !apiKey.equals(oldKey)) {
            TokenUsageGuard.getInstance(context).resetManual();
        }

        // Also ensure it's in the saved list if not already
        if (apiKey != null && !apiKey.isEmpty()) {
            List<ApiKey> savedKeys = getSavedApiKeys();
            boolean exists = false;
            for (ApiKey key : savedKeys) {
                if (key.value.equals(apiKey)) {
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
        if (json.isEmpty()) {
            return new ArrayList<>();
        }
        Gson gson = new Gson();
        Type type = new TypeToken<List<ApiKey>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public void setSavedApiKeys(List<ApiKey> keys) {
        Gson gson = new Gson();
        String json = gson.toJson(keys);
        editor.putString(KEY_SAVED_API_KEYS, json).apply();
    }

    public void removeSavedApiKey(String value) {
        List<ApiKey> keys = getSavedApiKeys();
        keys.removeIf(k -> k.value.equals(value));
        setSavedApiKeys(keys);

        // If we removed the active key, clear it
        if (getGroqApiKey().equals(value)) {
            setGroqApiKey("");
        }
    }

    public void setAiModel(String aiModel) {
        editor.putString("ai_model", aiModel);
        editor.apply();
    }

    public void setTranslationModel(String aiModel) {
        editor.putString("translation_model", aiModel);
        editor.apply();
    }

    public void setSummarizationModel(String aiModel) {
        editor.putString("summarization_model", aiModel);
        editor.apply();
    }

    public void setChatbotModel(String aiModel) {
        editor.putString("chatbot_model", aiModel);
        editor.apply();
    }

    public String getTranslationMethod() {
        return sharedPreferences.getString("translationMethod", "allAtOnce");
    }

    public void setTranslationMethod(String method) {
        editor.putString("translationMethod", method);
        editor.apply();
    }

    public boolean getAutoTranslate() {
        return sharedPreferences.getBoolean("autoTranslate", false);
    }

    public void setAutoTranslate(boolean autoTranslate) {
        editor.putBoolean("autoTranslate", autoTranslate);
        editor.apply();
    }

    public boolean getAutoSummarize() {
        return sharedPreferences.getBoolean("autoSummarize", false);
    }

    public void setAutoSummarize(boolean autoSummarize) {
        editor.putBoolean("autoSummarize", autoSummarize);
        editor.apply();
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
        editor.putString(KEY_TTS_SUBSTITUTIONS, json).apply();
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
        editor.putString("customTranslationPrompt", prompt).apply();
    }

    public String getCustomSummarizationPrompt() {
        return sharedPreferences.getString("customSummarizationPrompt", "");
    }

    public void setCustomSummarizationPrompt(String prompt) {
        editor.putString("customSummarizationPrompt", prompt).apply();
    }

    public String getAbbreviationList() {
        return sharedPreferences.getString("abbreviation_list", "Mr., Mrs., Ms., Dr., Prof., Sr., Jr., St., vs., etc., e.g., i.e., Fig., No., Rev.");
    }

    public void setAbbreviationList(String list) {
        editor.putString("abbreviation_list", list).apply();
    }

    public int getAiLimitTpm() {
        return sharedPreferences.getInt(TokenUsageGuard.KEY_LIMIT_TPM, 30000);
    }

    public void setAiLimitTpm(int tpm) {
        editor.putInt(TokenUsageGuard.KEY_LIMIT_TPM, tpm).apply();
    }

    public int getAiLimitRpd() {
        return sharedPreferences.getInt(TokenUsageGuard.KEY_LIMIT_RPD, 1000);
    }

    public void setAiLimitRpd(int rpd) {
        editor.putInt(TokenUsageGuard.KEY_LIMIT_RPD, rpd).apply();
    }

    public int getAiLimitTpd() {
        return sharedPreferences.getInt(TokenUsageGuard.KEY_LIMIT_TPD, 500000);
    }

    public void setAiLimitTpd(int tpd) {
        editor.putInt(TokenUsageGuard.KEY_LIMIT_TPD, tpd).apply();
    }

    public boolean getEnableChunkLimit() {
        return sharedPreferences.getBoolean("enable_chunk_limit", true);
    }

    public int getChunkLimit() {
        if (!getEnableChunkLimit()) {
            return Integer.MAX_VALUE;
        }
        return sharedPreferences.getInt("chunk_limit", 7500);
    }

    public void setChunkLimit(int limit) {
        editor.putInt("chunk_limit", limit).apply();
    }

    public int getMaxFiles() {
        return sharedPreferences.getInt("max_files", 10);
    }

    public void setMaxFiles(int maxFiles) {
        editor.putInt("max_files", maxFiles).apply();
    }

    public String getDailySummaryPrompt() {
        return sharedPreferences.getString("daily_summary_prompt", context.getString(xiangze.mmu.rssnewsreader.R.string.daily_summary_prompt_default));
    }

    public void setDailySummaryPrompt(String prompt) {
        editor.putString("daily_summary_prompt", prompt).apply();
    }

    public Context getContext() {
        return context;
    }
}
