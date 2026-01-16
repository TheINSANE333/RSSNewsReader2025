package xiangze.mmu.rssnewsreader.data.ai;

import java.util.Collections;
import java.util.List;

public class ChatRequest {
    public String model;
    public List<Message> messages;
    public double temperature;
    public int max_tokens;
    public ProviderData provider;

    public ChatRequest(String model, List<Message> messages, double temperature, int max_tokens) {
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
        this.max_tokens = max_tokens;
        this.provider = new ProviderData();
        this.provider.order = Collections.singletonList("google");
    }

    public static class ProviderData {
        public List<String> order;
    }
}
