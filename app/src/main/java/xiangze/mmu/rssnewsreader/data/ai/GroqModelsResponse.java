package xiangze.mmu.rssnewsreader.data.ai;

import java.util.List;

/**
 * Response POJO for Groq's GET /openai/v1/models endpoint.
 * Used by Gson to deserialize the JSON response.
 */
public class GroqModelsResponse {
    public String object;
    public List<Model> data;

    public static class Model {
        public String id;
        public String object;
        public String owned_by;
    }
}
