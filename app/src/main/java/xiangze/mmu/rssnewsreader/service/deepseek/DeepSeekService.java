package xiangze.mmu.rssnewsreader.service.deepseek;

import xiangze.mmu.rssnewsreader.data.deepseek.ChatRequest;
import xiangze.mmu.rssnewsreader.data.deepseek.ChatResponse;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface DeepSeekService {
    @POST("chat/completions")
    Call<ChatResponse> chatCompletion(@Body ChatRequest request);
}