package my.mmu.rssnewsreader.data.deepseek;

import java.util.List;

public class ChatRequest {
    public String model;
    public List<Message> messages;
    public double temperature;
    public int max_tokens;

    public ChatRequest(String model, List<Message> messages, double temperature, int max_tokens) {
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
        this.max_tokens = max_tokens;
    }
}
