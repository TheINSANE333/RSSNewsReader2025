package xiangze.mmu.rssnewsreader.service.ai;

import xiangze.mmu.rssnewsreader.data.ai.ChatRequest;
import xiangze.mmu.rssnewsreader.data.ai.ChatResponse;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface AiService {
    @POST("chat/completions")
    Call<ChatResponse> chatCompletion(@Body ChatRequest request);
}