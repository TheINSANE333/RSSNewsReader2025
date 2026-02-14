package xiangze.mmu.rssnewsreader.model.ai;

import android.content.Context;
import android.util.Log;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import java.io.File;

public class LocalLlmManager {
    private static final String TAG = "LocalLlmManager";
    private static LocalLlmManager instance;
    private LlmInference llmInference;
    private static final String MODEL_FILENAME = "qwen2.5-1.5b.task";
    public static final String DEFAULT_MODEL_URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task";
    private final Context appContext;

    private LocalLlmManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized LocalLlmManager getInstance(Context context) {
        if (instance == null) {
            instance = new LocalLlmManager(context.getApplicationContext());
        }
        return instance;
    }

    public void downloadModel(String url) {
        if (url == null || url.isEmpty()) url = DEFAULT_MODEL_URL;
        
        try {
            File extFile = new File(appContext.getExternalFilesDir(null), MODEL_FILENAME);
            if (extFile.exists()) extFile.delete();
            File intFile = new File(appContext.getFilesDir(), MODEL_FILENAME);
            if (intFile.exists()) intFile.delete();

            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
            request.setTitle("Downloading Qwen2.5 Model");
            request.setDescription("Downloading " + MODEL_FILENAME + "...");
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(appContext, null, MODEL_FILENAME);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            android.app.DownloadManager manager = (android.app.DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download", e);
        }
    }

    public synchronized String generateResponse(Context context, String prompt) throws Exception {
        if (llmInference == null) {
            File extDir = appContext.getExternalFilesDir(null);
            File modelFile = (extDir != null) ? new File(extDir, MODEL_FILENAME) : null;
            
            if (modelFile == null || !modelFile.exists()) {
                 File internalFile = new File(appContext.getFilesDir(), MODEL_FILENAME);
                 if (internalFile.exists()) {
                     modelFile = internalFile;
                     Log.d(TAG, "Using internal storage model: " + modelFile.getAbsolutePath());
                 }
            } else {
                Log.d(TAG, "Using external storage model: " + modelFile.getAbsolutePath());
            }
            
            if (modelFile == null || !modelFile.exists()) {
                downloadModel(DEFAULT_MODEL_URL);
                throw new Exception("Model file not found! Download started. Please wait for the 'Download complete' notification.");
            }

            Log.d(TAG, "Initializing LlmInference with model: " + modelFile.getAbsolutePath());
            try {
                LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.getAbsolutePath())
                        .setMaxTokens(4096)
                        .build();

                llmInference = LlmInference.createFromOptions(appContext, options);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create LlmInference from options", e);
                throw new Exception("Failed to initialize local LLM engine: " + e.getMessage());
            }
        }

        return llmInference.generateResponse(prompt);
    }

    public synchronized void close() {
        if (llmInference != null) {
            llmInference.close();
            llmInference = null;
        }
    }
}
