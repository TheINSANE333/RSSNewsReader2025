package xiangze.mmu.rssnewsreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import xiangze.mmu.rssnewsreader.data.ai.Message;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.ai.AiClient;

@RunWith(AndroidJUnit4.class)
public class AiClientTest {

    private SharedPreferencesRepository sharedPreferencesRepository;
    private Context context;

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();
        sharedPreferencesRepository = new SharedPreferencesRepository(context);
    }

    @Test
    public void testGetChatResponseWithNoKeyThrowsError() {
        // Backup old key
        String originalKey = sharedPreferencesRepository.getGroqApiKey();
        sharedPreferencesRepository.setGroqApiKey(""); // Clear the key
        
        try {
            AiClient aiClient = new AiClient(context);
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("user", "Hello"));
            
            aiClient.getChatResponse(messages, "llama-3.3-70b-versatile");
            fail("Expected IOException due to missing API key");
        } catch (IOException e) {
            // Check that the exception message notifies user about configuration
            assertTrue(e.getMessage().contains("API key not configured"));
        } finally {
            // Restore original key
            sharedPreferencesRepository.setGroqApiKey(originalKey);
        }
    }

    @Test
    public void testGetChatResponseWithInvalidKeyThrows401() {
        // Backup old key
        String originalKey = sharedPreferencesRepository.getGroqApiKey();
        
        // Put a fake key that reaches the server but triggers authorization failure
        sharedPreferencesRepository.setGroqApiKey("gsk_fakekey1234567890abcdefghijklmnopqrstuvwxyz");
        
        try {
            AiClient aiClient = new AiClient(context);
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("user", "Ping"));
            
            // We use a known standard Groq model
            aiClient.getChatResponse(messages, "gemma2-9b-it");
            fail("Expected IOException (401 Unauthorized) from Groq API");
        } catch (IOException e) {
            // Assert that the client successfully catches the 401 response and maps it to the custom error message
            assertEquals("Invalid groq API Key", e.getMessage());
        } finally {
            // Restore original key
            sharedPreferencesRepository.setGroqApiKey(originalKey);
        }
    }

    private void assertTrue(boolean condition) {
        org.junit.Assert.assertTrue(condition);
    }
}
