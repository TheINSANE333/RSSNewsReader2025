package xiangze.mmu.rssnewsreader.data.ai;

import java.util.List;

public class ChatResponse {
    public List<Choice> choices;
    public Usage usage;

    public static class Choice {
        public Message message;
        public String finish_reason;
        public int index;
    }

    public static class Usage {
        public int prompt_tokens;
        public int completion_tokens;
        public int total_tokens;
    }
}
