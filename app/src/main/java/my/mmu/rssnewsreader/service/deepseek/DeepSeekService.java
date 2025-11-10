package my.mmu.rssnewsreader.service.deepseek;

import my.mmu.rssnewsreader.data.deepseek.ChatRequest;
import my.mmu.rssnewsreader.data.deepseek.ChatResponse;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface DeepSeekService {
    @POST("chat/completions")
    Call<ChatResponse> chatCompletion(@Body ChatRequest request);
}