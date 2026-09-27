package xiangze.mmu.rssnewsreader.model.ai;

import android.content.Context;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xiangze.mmu.rssnewsreader.data.ai.ChatRequest;
import xiangze.mmu.rssnewsreader.data.ai.ChatResponse;
import xiangze.mmu.rssnewsreader.data.ai.GroqModelsResponse;
import xiangze.mmu.rssnewsreader.data.ai.Message;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.ai.AiService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import timber.log.Timber;

public class AiClient {
    private static final String BASE_URL = "https://api.groq.com/openai/v1/";
    private String userKey;
    private static final String SITE_URL = "https://github.com/TheINSANE333/RSSNewsReader2025"; // Replace with your app/site URL
    private static final String SITE_NAME = "Text to Speech News Reader 2025"; // Replace with your app name

    private AiService service;
    private final Context context;
    private final SharedPreferencesRepository sharedPreferencesRepository;

    /**
     * Cached list of active model metadata fetched from the Groq API.
     * Populated lazily on the first call to {@link #getAvailableModelsCached()}.
     * Static so it survives across AiClient instances within the same process.
     */
    private static List<GroqModelsResponse.Model> cachedModels = null;

    public AiClient(Context context) {
        this.context = context.getApplicationContext();
        this.sharedPreferencesRepository = new SharedPreferencesRepository(context);
        this.userKey = sharedPreferencesRepository.getGroqApiKey();
        initializeService();
    }

    private void initializeService() {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder requestBuilder = original.newBuilder()
                            .header("HTTP-Referer", SITE_URL)
                            .header("X-Title", SITE_NAME)
                            .header("Content-Type", "application/json")
                            .method(original.method(), original.body());
                    
                    if (this.userKey != null && !this.userKey.isEmpty()) {
                        requestBuilder.header("Authorization", "Bearer " + this.userKey);
                    }
                    
                    return chain.proceed(requestBuilder.build());
                })
                .addInterceptor(new HttpLoggingInterceptor().setLevel(xiangze.mmu.rssnewsreader.BuildConfig.DEBUG ? HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.NONE))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        service = retrofit.create(AiService.class);
    }

    public boolean hasKey(String model) {
        if ("local_qwen_2_5_1_5b".equals(model)) {
            return true;
        }
        return userKey != null && !userKey.isEmpty();
    }

    public String getChatResponse(List<Message> messages, String model) throws IOException {
        return getChatResponseInternal(messages, model, 0, new HashSet<>());
    }

    private String getChatResponseInternal(List<Message> messages, String model, int retryCount, Set<String> triedModels) throws IOException {
        if (model == null || model.isEmpty()) {
            model = PreferenceManager.getDefaultSharedPreferences(context).getString("ai_model", "llama-3.3-70b-versatile");
        }

        boolean enableFallback = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("enable_dynamic_model_fallback", true);
        if (enableFallback) {
            // Only override local models to the first available cloud model;
            // respect the user's cloud model choice so we don't burn through
            // the rate limit of a single model before fallback models get a chance.
            if ("local_qwen_2_5_1_5b".equals(model)) {
                String firstCloud = getFirstAvailableCloudModel();
                if (firstCloud != null) {
                    model = firstCloud;
                }
            }
        }

        // Track that we've tried this model to prevent infinite loops
        triedModels.add(model);

        if ("local_qwen_2_5_1_5b".equals(model)) {
            try {
                StringBuilder promptBuilder = new StringBuilder();
                // ChatML format for Qwen
                for (Message msg : messages) {
                    promptBuilder.append("<|im_start|>").append(msg.role).append("\n");
                    promptBuilder.append(msg.content).append("<|im_end|>\n");
                }
                promptBuilder.append("<|im_start|>assistant\n");

                return LocalLlmManager.getInstance(context).generateResponse(context, promptBuilder.toString(), model);
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
        }

        if (!hasKey(model)) {
            throw new IOException("groq API key not configured! Please set it in Settings.");
        }

        // Check token limits before making the request
        try {
            TokenUsageGuard.getInstance(context).checkLimits();
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage());
        }

        // Estimate prompt token count
        int estimatedPromptTokens = estimateTokenCount(messages);
        int contextLimit = getModelContextLimit(model);

        // Dynamically adjust max_tokens so that prompt_tokens + max_tokens fits in the model context limit
        int maxTokens = 6000;
        if (estimatedPromptTokens + maxTokens > contextLimit) {
            maxTokens = contextLimit - estimatedPromptTokens - 100;
        }
        if (maxTokens < 128) {
            maxTokens = 128; // safe minimum to avoid empty responses
        }

        ChatRequest request = new ChatRequest(model, messages, 0.0, maxTokens);

        retrofit2.Response<ChatResponse> response = service.chatCompletion(request).execute();

        if (response.isSuccessful() && response.body() != null) {
            ChatResponse chatResponse = response.body();
            
            // Record usage if available
            if (chatResponse.usage != null) {
                TokenUsageGuard.getInstance(context).recordUsage(chatResponse.usage.total_tokens);
            } else {
                // Fallback: estimate or just count as 1 request if usage not provided
                TokenUsageGuard.getInstance(context).recordUsage(0); 
            }

            if (chatResponse.choices != null && !chatResponse.choices.isEmpty()) {
                ChatResponse.Choice choice = chatResponse.choices.get(0);
                if (choice != null && choice.message != null
                        && choice.message.content != null
                        && !choice.message.content.trim().isEmpty()) {
                    return choice.message.content;
                }
            }

            // API returned 200 but no usable content — treat as a soft error
            // and attempt model fallback before giving up.
            Timber.w("API returned 200 but empty/null content for model %s", model);
            String emptyFallback = getNextFallbackModel(model, triedModels);
            if (emptyFallback != null) {
                Timber.w("Falling back from %s to %s after empty response", model, emptyFallback);
                return getChatResponseInternal(messages, emptyFallback, 0, triedModels);
            }
            throw new IOException("API returned an empty response for model: " + model);
        } else {
            int errorCode = response.code();
            String errorMessage = "API request failed: " + errorCode;

            switch (errorCode) {
                case 401:
                    errorMessage = "Invalid groq API Key";
                    break;
                default:
                    // Try to get error message from response body
                    try {
                        if (response.errorBody() != null) {
                            String errorBody = response.errorBody().string();
                            errorMessage = "groq Error " + errorCode + ": " + errorBody;
                        }
                    } catch (IOException e) {
                        errorMessage = "groq Error " + errorCode + ": " + response.message();
                    }

                    // Trigger key rotation first for any error other than 401
                    if (retryCount < sharedPreferencesRepository.getSavedApiKeys().size()) {
                        if (sharedPreferencesRepository.switchToNextKey()) {
                            this.userKey = sharedPreferencesRepository.getGroqApiKey();
                            initializeService();
                            return getChatResponseInternal(messages, model, retryCount + 1, triedModels);
                        }
                    }

                    // Fall back to next model if we run out of API keys for the current model
                    String fallbackModel = getNextFallbackModel(model, triedModels);
                    if (fallbackModel != null) {
                        Timber.w("API error (%d) hit on model %s. Falling back to: %s", errorCode, model, fallbackModel);
                        return getChatResponseInternal(messages, fallbackModel, 0, triedModels);
                    }
                    break;
            }
            throw new IOException(errorMessage);
        }
    }

    /**
     * Returns the first available cloud model from the cached model list,
     * or a sensible default if the cache is not yet populated.
     */
    private String getFirstAvailableCloudModel() {
        List<GroqModelsResponse.Model> models = getAvailableModelsCached();
        if (models != null && !models.isEmpty()) {
            // Models are sorted by context_window descending, so the first is the biggest
            return models.get(0).id;
        }
        return null;
    }

    /**
     * Fetches and caches the list of available active models from the Groq API,
     * sorted by context_window descending (largest first) for fallback priority.
     * Returns the cached list on subsequent calls.
     */
    private List<GroqModelsResponse.Model> getAvailableModelsCached() {
        if (cachedModels != null) {
            return cachedModels;
        }

        if (userKey == null || userKey.isEmpty()) {
            return null;
        }

        try {
            retrofit2.Response<GroqModelsResponse> response = service.listModels().execute();
            if (response.isSuccessful() && response.body() != null && response.body().data != null) {
                List<GroqModelsResponse.Model> activeModels = new ArrayList<>();
                for (GroqModelsResponse.Model model : response.body().data) {
                    if (model.id != null && !model.id.isEmpty() && model.active && model.context_window > 0) {
                        activeModels.add(model);
                    }
                }
                // Sort by context_window descending — largest context first for fallback priority
                Collections.sort(activeModels, (a, b) -> Integer.compare(b.context_window, a.context_window));
                cachedModels = activeModels;
                Timber.d("Cached %d active models from Groq API", activeModels.size());
                return cachedModels;
            }
        } catch (IOException e) {
            Timber.w(e, "Failed to fetch models from Groq API");
        }
        return null;
    }

    /**
     * Forces a refresh of the cached model list on next access.
     * Call this when API keys change or when the user opens settings.
     */
    public static void invalidateModelCache() {
        cachedModels = null;
    }

    /**
     * Fetches the list of available model IDs from the Groq API.
     * @return sorted list of model IDs, or null on failure.
     */
    public List<String> fetchAvailableModels() {
        // Invalidate cache so we get fresh data
        invalidateModelCache();
        List<GroqModelsResponse.Model> models = getAvailableModelsCached();
        if (models == null || models.isEmpty()) {
            return null;
        }

        List<String> modelIds = new ArrayList<>();
        for (GroqModelsResponse.Model model : models) {
            modelIds.add(model.id);
        }
        Collections.sort(modelIds);
        return modelIds;
    }

    /**
     * Converts a raw model ID into a user-friendly display name.
     * e.g. "meta-llama/llama-4-scout-17b-16e-instruct" → "Llama 4 Scout 17B 16e Instruct"
     *      "llama-3.3-70b-versatile" → "Llama 3.3 70B Versatile"
     */
    public static String formatModelName(String modelId) {
        if (modelId == null || modelId.isEmpty()) return modelId;

        // Strip owner prefix (everything before and including '/')
        int slashIndex = modelId.lastIndexOf('/');
        String name = (slashIndex >= 0) ? modelId.substring(slashIndex + 1) : modelId;

        // Replace hyphens with spaces, but keep decimal version numbers intact (e.g. 3.3)
        name = name.replace('-', ' ');

        // Title-case each word, with special handling for size suffixes
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        // Pattern for size tokens like "70b", "8b", "1.5b" — keep the letter uppercase
        Pattern sizePattern = Pattern.compile("^(\\d+\\.?\\d*)(b)$", Pattern.CASE_INSENSITIVE);

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');

            Matcher sizeMatcher = sizePattern.matcher(word);
            if (sizeMatcher.matches()) {
                // e.g. "70b" → "70B"
                sb.append(sizeMatcher.group(1)).append(sizeMatcher.group(2).toUpperCase());
            } else if (word.matches("\\d+.*")) {
                // Starts with digit (version numbers like "3.3", "16e") — keep as-is
                sb.append(word);
            } else {
                // Normal word — capitalize first letter
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    sb.append(word.substring(1));
                }
            }
        }
        return sb.toString();
    }

    /**
     * Dynamically determines the next fallback model by walking the cached
     * list of available models (sorted by context_window descending).
     * Skips models that have already been tried to prevent infinite loops.
     * Returns null if fallback is disabled, or no untried models remain.
     */
    private String getNextFallbackModel(String currentModel, Set<String> triedModels) {
        boolean enableFallback = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("enable_dynamic_model_fallback", true);
        if (!enableFallback) {
            return null;
        }

        if (currentModel == null) return null;

        // Local model has no cloud fallback
        if ("local_qwen_2_5_1_5b".equals(currentModel)) {
            return null;
        }

        List<GroqModelsResponse.Model> models = getAvailableModelsCached();
        if (models == null || models.isEmpty()) {
            return null;
        }

        // Walk the sorted list (largest context first) and return the first
        // model that hasn't been tried yet
        for (GroqModelsResponse.Model model : models) {
            if (!triedModels.contains(model.id)) {
                return model.id;
            }
        }

        // All cloud models exhausted
        return null;
    }

    /**
     * Estimates the token count of a message list based on character counts.
     */
    private int estimateTokenCount(List<Message> messages) {
        int charCount = 0;
        for (Message msg : messages) {
            if (msg.content != null) {
                charCount += msg.content.length();
            }
        }
        // Rough rule of thumb: 1 token ≈ 4 characters
        return (int) Math.ceil(charCount / 4.0);
    }

    /**
     * Gets the context limit of a given model from the cached API data.
     * Falls back to a conservative default if the model is not found in the cache.
     */
    private int getModelContextLimit(String model) {
        if (model == null) return 6000;

        // Local model
        if (model.contains("local") || model.contains("qwen")) {
            return 4000;
        }

        // Look up context_window from the cached API data
        List<GroqModelsResponse.Model> models = getAvailableModelsCached();
        if (models != null) {
            for (GroqModelsResponse.Model m : models) {
                if (model.equals(m.id) && m.context_window > 0) {
                    return m.context_window;
                }
            }
        }

        return 6000; // Conservative default for models not found in the cache
    }
}
