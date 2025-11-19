package xiangze.mmu.rssnewsreader.model.deepseek;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import xiangze.mmu.rssnewsreader.BuildConfig;
import xiangze.mmu.rssnewsreader.data.deepseek.ChatRequest;
import xiangze.mmu.rssnewsreader.data.deepseek.ChatResponse;
import xiangze.mmu.rssnewsreader.data.deepseek.Message;
import xiangze.mmu.rssnewsreader.service.deepseek.DeepSeekService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class DeepSeekClient {
    private static final String BASE_URL = "https://openrouter.ai/api/v1/";
    private static final String API_KEY = BuildConfig.DEEPSEEK_API_KEY;
    private static final String SITE_URL = "https://github.com/TheINSANE333/RSSNewsReader2025"; // Replace with your app/site URL
    private static final String SITE_NAME = "RSS News Reader 2025"; // Replace with your app name

    private DeepSeekService service;

    public DeepSeekClient() {
        // Validate API key
        if (API_KEY.equals("your_openrouter_api_key_here") || API_KEY.isEmpty()) {
            throw new IllegalStateException("OpenRouter API key not configured!");
        }

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("Authorization", "Bearer " + API_KEY)
                            .header("HTTP-Referer", SITE_URL)
                            .header("X-Title", SITE_NAME)
                            .header("Content-Type", "application/json")
                            .method(original.method(), original.body())
                            .build();
                    return chain.proceed(request);
                })
                .addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        service = retrofit.create(DeepSeekService.class);
    }

    public String getChatResponse(List<Message> messages) throws IOException {
        // Use the free DeepSeek model
        ChatRequest request = new ChatRequest("openai/gpt-oss-20b:free", messages, 0.0, 100000);

        retrofit2.Response<ChatResponse> response = service.chatCompletion(request).execute();

        if (response.isSuccessful() && response.body() != null) {
            if (!response.body().choices.isEmpty()) {
                return response.body().choices.get(0).message.content;
            }
        } else {
            int errorCode = response.code();
            String errorMessage = "API request failed: " + errorCode;

            switch (errorCode) {
                case 401:
                    errorMessage = "Invalid OpenRouter API Key";
                    break;
                case 402:
                    errorMessage = "Payment Required - Check your OpenRouter credits";
                    break;
                case 429:
                    errorMessage = "Rate Limit Exceeded - Too many requests";
                    break;
                default:
                    // Try to get error message from response body
                    try {
                        if (response.errorBody() != null) {
                            String errorBody = response.errorBody().string();
                            errorMessage = "OpenRouter Error " + errorCode + ": " + errorBody;
                        }
                    } catch (IOException e) {
                        errorMessage = "OpenRouter Error " + errorCode + ": " + response.message();
                    }
            }

            throw new IOException(errorMessage);
        }

        return "Sorry, I didn't get a response.";
    }
}