package xiangze.mmu.rssnewsreader.model.ai;

import android.content.Context;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import java.io.File;

public class LocalLlmManager {
    private static LocalLlmManager instance;
    private LlmInference llmInference;
    private static final String MODEL_FILENAME = "qwen2.5-1.5b.task";

    private LocalLlmManager(Context context) {
        // Initialization is done in generateResponse or init
    }

    public static synchronized LocalLlmManager getInstance(Context context) {
        if (instance == null) {
            instance = new LocalLlmManager(context.getApplicationContext());
        }
        return instance;
    }

    public synchronized String generateResponse(Context context, String prompt) throws Exception {
        if (llmInference == null) {
            File modelFile = new File(context.getExternalFilesDir(null), MODEL_FILENAME);
            if (!modelFile.exists()) {
                 modelFile = new File(context.getFilesDir(), MODEL_FILENAME);
            }
            
            if (!modelFile.exists()) {
                throw new Exception("Model file not found! Please copy " + MODEL_FILENAME + " to " + context.getExternalFilesDir(null).getAbsolutePath());
            }

            LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.getAbsolutePath())
                    .setMaxTokens(4096)
                    .build();

            llmInference = LlmInference.createFromOptions(context, options);
        }

        return llmInference.generateResponse(prompt);
    }
}
