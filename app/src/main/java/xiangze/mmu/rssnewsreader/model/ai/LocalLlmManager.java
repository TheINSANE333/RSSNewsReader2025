package xiangze.mmu.rssnewsreader.model.ai;

import android.content.Context;
import timber.log.Timber;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import java.io.File;

public class LocalLlmManager {
    
    private static LocalLlmManager instance;
    private LlmInference llmInference;
    private String currentModelId;

    public static final String QWEN_MODEL_ID = "local_qwen_2_5_1_5b";

    private static final String QWEN_MODEL_FILENAME = "qwen2.5-1.5b.task";

    public static final String QWEN_DEFAULT_URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task";
    
    // Default URL for the generic download button
    public static final String DEFAULT_MODEL_URL = QWEN_DEFAULT_URL;

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

    public String getModelFilename(String modelId) {
        return QWEN_MODEL_FILENAME;
    }

    public String getDefaultUrl(String modelId) {
        return QWEN_DEFAULT_URL;
    }

    public void downloadModel(String url) {
        downloadModel(url, QWEN_MODEL_ID); // Default to Qwen for backward compatibility if called without ID
    }

    public void downloadModel(String url, String modelId) {
        if (url == null || url.isEmpty()) url = getDefaultUrl(modelId);
        String filename = getModelFilename(modelId);
        
        try {
            File extFile = new File(appContext.getExternalFilesDir(null), filename);
            if (extFile.exists()) extFile.delete();
            File intFile = new File(appContext.getFilesDir(), filename);
            if (intFile.exists()) intFile.delete();

            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
            request.setTitle("Downloading Qwen Model");
            request.setDescription("Downloading " + filename + "...");
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(appContext, null, filename);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            
            // Add User-Agent to mimic a browser, which can help with Hugging Face redirects/CDN issues
            request.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");

            android.app.DownloadManager manager = (android.app.DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
            }
        } catch (Exception e) {
            Timber.e(e, "Failed to start download");
        }
    }

    public synchronized String generateResponse(Context context, String prompt, String modelId) throws Exception {
        if (llmInference == null || !modelId.equals(currentModelId)) {
            if (llmInference != null) {
                close();
            }
            
            String filename = getModelFilename(modelId);
            File extDir = appContext.getExternalFilesDir(null);
            File modelFile = (extDir != null) ? new File(extDir, filename) : null;
            
            if (modelFile == null || !modelFile.exists()) {
                 File internalFile = new File(appContext.getFilesDir(), filename);
                 if (internalFile.exists()) {
                     modelFile = internalFile;
                     Timber.d("Using internal storage model: " + modelFile.getAbsolutePath());
                 }
            } else {
                Timber.d("Using external storage model: " + modelFile.getAbsolutePath());
            }
            
            if (modelFile == null || !modelFile.exists()) {
                downloadModel(getDefaultUrl(modelId), modelId);
                throw new Exception("Model file not found! Download started for " + modelId + ". Please wait for the 'Download complete' notification.");
            }

            Timber.d("Initializing LlmInference with model: " + modelFile.getAbsolutePath());
            try {
                LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()     
                        .setModelPath(modelFile.getAbsolutePath())
                        .setMaxTokens(4096)
                        .setPreferredBackend(LlmInference.Backend.GPU)
                        .setMaxTopK(40)
                        .build();
                llmInference = LlmInference.createFromOptions(appContext, options);
                currentModelId = modelId;
            } catch (Exception e) {
                Timber.e(e, "Failed to create LlmInference from options");
                throw new Exception("Failed to initialize local LLM engine: " + e.getMessage());
            }
        }

        return llmInference.generateResponse(prompt);
    }

    public synchronized void close() {
        if (llmInference != null) {
            llmInference.close();
            llmInference = null;
            currentModelId = null;
        }
    }
}
