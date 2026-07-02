package xiangze.mmu.rssnewsreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;

@RunWith(AndroidJUnit4.class)
public class TextUtilTest {

    private TextUtil textUtil;

    @Before
    public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferencesRepository sharedPreferencesRepository = new SharedPreferencesRepository(context);
        textUtil = new TextUtil(sharedPreferencesRepository);
    }

    @Test
    public void testSplitIntoSentences() {
        String input = "This is the first sentence. Here is the second sentence! And the third one?";
        String delimiter = "|";
        String expected = "This is the first sentence.|Here is the second sentence!|And the third one?";
        
        String result = textUtil.splitIntoSentences(input, delimiter);
        assertEquals(expected, result);
    }

    @Test
    public void testEndsWithAbbreviation() {
        // Test short abbreviations handled by regex (1-3 letters + period)
        assertTrue(textUtil.endsWithAbbreviation("Mr."));
        assertTrue(textUtil.endsWithAbbreviation("Dr."));
        assertTrue(textUtil.endsWithAbbreviation("St."));
        
        // Test non-abbreviations
        assertFalse(textUtil.endsWithAbbreviation("Hello."));
        assertFalse(textUtil.endsWithAbbreviation("This is a long sentence."));
    }

    @Test
    public void testParseAiResponseWithMarkers() {
        String rawResponse = "[TITLE] Artificial Intelligence in 2026\n[CONTENT] <p>AI is progressing rapidly.</p>";
        TextUtil.AiResponse response = textUtil.parseAiResponse(rawResponse, "Default Title");
        
        assertEquals("Artificial Intelligence in 2026", response.title);
        assertEquals("<p>AI is progressing rapidly.</p>", response.content);
    }

    @Test
    public void testParseAiResponseWithoutMarkers() {
        String rawResponse = "Just a raw response body from the model without any tags.";
        TextUtil.AiResponse response = textUtil.parseAiResponse(rawResponse, "Fallback Title");
        
        assertEquals("Fallback Title", response.title);
        assertEquals("Just a raw response body from the model without any tags.", response.content);
    }

    @Test
    public void testApplyTtsSubstitutions() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferencesRepository sharedPreferencesRepository = new SharedPreferencesRepository(context);
        
        // Setup substitutions
        java.util.Map<String, String> substitutions = new java.util.HashMap<>();
        substitutions.put("AI", "Artificial Intelligence");
        substitutions.put("TTS", "Text to Speech");
        sharedPreferencesRepository.setTtsSubstitutions(substitutions);
        
        String input = "AI is integrated with TTS.";
        String expected = "Artificial Intelligence is integrated with Text to Speech.";
        
        String result = textUtil.applyTtsSubstitutions(input);
        assertEquals(expected, result);
        
        // Clean up
        sharedPreferencesRepository.setTtsSubstitutions(new java.util.HashMap<>());
    }
}
