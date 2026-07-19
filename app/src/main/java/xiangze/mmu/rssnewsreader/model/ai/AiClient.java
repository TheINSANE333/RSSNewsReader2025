package xiangze.mmu.rssnewsreader.model.ai;

import android.content.Context;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        return getChatResponseInternal(messages, model, 0);
    }

    private String getChatResponseInternal(List<Message> messages, String model, int retryCount) throws IOException {
        if (model == null || model.isEmpty()) {
            model = PreferenceManager.getDefaultSharedPreferences(context).getString("ai_model", "llama-3.3-70b-versatile");
        }

        boolean enableFallback = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("enable_dynamic_model_fallback", true);
        if (enableFallback) {
            // Force starting with the best model (which has the highest TPM limit of 12k)
            // if a lower-tier model was requested, maximizing chance of success for large prompts.
            if ("llama-3.1-8b-instant".equals(model) || "openai/gpt-oss-20b".equals(model) || "local_qwen_2_5_1_5b".equals(model)) {
                model = "llama-3.3-70b-versatile";
            }
        }

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
                if (choice != null && choice.message != null) {
                    return choice.message.content;
                }
            }
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
                            return getChatResponseInternal(messages, model, retryCount + 1);
                        }
                    }

                    // Fall back to next model if we run out of API keys for the current model
                    String fallbackModel = getNextFallbackModel(model);
                    if (fallbackModel != null) {
                        Timber.w("API error (%d) hit on model %s. Falling back to: %s", errorCode, model, fallbackModel);
                        return getChatResponseInternal(messages, fallbackModel, 0);
                    }
                    break;
            }
            throw new IOException(errorMessage);
        }

        return "Sorry, I didn't get a response.";
    }

    /**
     * Fetches the list of available model IDs from the Groq API.
     * @return sorted list of model IDs, or null on failure.
     */
    public List<String> fetchAvailableModels() {
        if (userKey == null || userKey.isEmpty()) {
            return null;
        }
        try {
            retrofit2.Response<GroqModelsResponse> response = service.listModels().execute();
            if (response.isSuccessful() && response.body() != null && response.body().data != null) {
                List<String> modelIds = new ArrayList<>();
                for (GroqModelsResponse.Model model : response.body().data) {
                    if (model.id != null && !model.id.isEmpty()) {
                        modelIds.add(model.id);
                    }
                }
                Collections.sort(modelIds);
                return modelIds;
            }
        } catch (IOException e) {
            // Network or parsing error — fall back to hardcoded list
        }
        return null;
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

    private String getNextFallbackModel(String currentModel) {
        boolean enableFallback = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("enable_dynamic_model_fallback", true);
        if (!enableFallback) {
            return null;
        }

        if (currentModel == null) return null;

        switch (currentModel) {
            case "llama-3.3-70b-versatile":
            case "meta-llama/llama-3.3-70b-instruct:free":
                return "openai/gpt-oss-120b";
            case "openai/gpt-oss-120b":
                return "openai/gpt-oss-20b";
            case "openai/gpt-oss-20b":
                return "llama-3.1-8b-instant";
            case "llama-3.1-8b-instant":
                return null; // Don't use local model in fallback cascade
            case "local_qwen_2_5_1_5b":
                return null;
            default:
                // Fall back to llama-3.1-8b-instant as general high-limit fallback
                return "llama-3.1-8b-instant";
        }
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
     * Gets the known context limit (or organization TPM limit) of a given model.
     * Capped at the organization's TPM rate limit since single requests exceeding 
     * this are rejected by Groq with a 413 error.
     */
    private int getModelContextLimit(String model) {
        if (model == null) return 6000;

        if (model.contains("llama-3.3")) {
            return 12000; // 12,000 TPM limit
        }
        if (model.contains("gpt-oss")) {
            return 8000; // 8,000 TPM limit
        }
        if (model.contains("llama-3.1") || model.contains("allam")) {
            return 6000; // 6,000 TPM limit
        }
        if (model.contains("qwen") || model.contains("local")) {
            return 4000; // 4k context limits
        }
        if (model.contains("gemma")) {
            return 8192; // 8k context limit
        }
        return 6000; // Safe default for unrecognized models
    }
}
