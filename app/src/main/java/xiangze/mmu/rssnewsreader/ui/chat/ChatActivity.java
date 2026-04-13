package xiangze.mmu.rssnewsreader.ui.chat;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import com.google.android.material.button.MaterialButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import xiangze.mmu.rssnewsreader.data.ai.Message;
import xiangze.mmu.rssnewsreader.model.ai.AiClient;
import xiangze.mmu.rssnewsreader.model.ai.LocalLlmManager;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

import xiangze.mmu.rssnewsreader.R;

import javax.inject.Inject;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ChatActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private EditText messageInput;
    private MaterialButton sendButton;
    private ProgressBar progressBar;
    private ChatAdapter adapter;
    private List<Message> messages = new ArrayList<>();
    private AiClient aiClient;
    
    @Inject
    TtsPlayer ttsPlayer;

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("ChatActivity", "RUNNING CREATE");
        super.onCreate(savedInstanceState);

        // Ensure TtsService is started so TtsPlayer is initialized
        Intent serviceIntent = new Intent(this, xiangze.mmu.rssnewsreader.service.tts.TtsService.class);
        startService(serviceIntent);

        // Set up global exception handler
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                e.printStackTrace();
                Log.e("ChatBot_Crash", "Uncaught exception: " + e.getMessage(), e);

                // Show error dialog (optional)
                runOnUiThread(() -> {
                    new AlertDialog.Builder(ChatActivity.this)
                            .setTitle("App Crash")
                            .setMessage("Error: " + e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });

        setContentView(R.layout.activity_chat);

        initViews();
        setupRecyclerView();
        setupClickListeners();

        aiClient = new AiClient(this);

        String chatModel = sharedPreferencesRepository.getChatbotModel();

        if (!aiClient.hasKey(chatModel)) {
            new AlertDialog.Builder(this)
                    .setTitle("API Key Missing")
                    .setMessage("Please configure the groq API Key in Settings to use the Chatbot.")
                    .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                    .show();
        }

        if (getIntent().hasExtra("initial_message")) {
            String summary = getIntent().getStringExtra("initial_message");
            messages.add(new Message("assistant", summary));
        }
        else {
            // Add welcome message
            messages.add(new Message("assistant", "Hello! How can I help you today?"));
        }
        adapter.notifyDataSetChanged();

        ttsPlayer.setPlaybackUiListener(new TtsPlayer.PlaybackUiListener() {
            @Override
            public void onPlaybackStarted() {
                // Adapter already updated on click
            }

            @Override
            public void onPlaybackPaused() {
                runOnUiThread(() -> {
                    adapter.setPlayingText(null);
                });
            }
        });
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        progressBar = findViewById(R.id.progressBar);

        // Setup toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("AI Chatbot");
    }

    private String lastTtsText = null;

    private void setupRecyclerView() {
        adapter = new ChatAdapter(messages);
        adapter.setTtsClickListener(text -> {
            if (ttsPlayer.isSpeaking() && text.equals(lastTtsText)) {
                ttsPlayer.pauseTts();
                adapter.setPlayingText(null);
            } else {
                lastTtsText = text;
                String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();
                ttsPlayer.setPausedManually(false);
                // Use a specific negative ID for chat messages
                ttsPlayer.extract(-999, 0, text, targetLanguage);
                if (!ttsPlayer.isSpeaking()) {
                    ttsPlayer.play();
                }
                adapter.setPlayingText(text);
            }
        });
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void setupClickListeners() {
        sendButton.setOnClickListener(v -> {
            String message = messageInput.getText().toString().trim();
            if (!message.isEmpty()) {
                sendMessage(message);
                messageInput.setText("");
            }
        });

        // Send on enter key
        messageInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                String message = messageInput.getText().toString().trim();
                if (!message.isEmpty()) {
                    sendMessage(message);
                    messageInput.setText("");
                }
                return true;
            }
            return false;
        });
    }

    private void sendMessage(String userMessage) {
        try {
            // Add user message to chat
            messages.add(new Message("user", userMessage));
            adapter.notifyItemInserted(messages.size() - 1);
            recyclerView.scrollToPosition(messages.size() - 1);

            // Show loading
            progressBar.setVisibility(View.VISIBLE);
            sendButton.setEnabled(false);

            // Call API
            new Thread(() -> {
                try {
                    Log.d("ChatBot", "Starting API call...");
                    String chatModel = sharedPreferencesRepository.getChatbotModel();
                    String response = aiClient.getChatResponse(messages, chatModel);
                    Log.d("ChatBot", "API response received: " + response);

                    runOnUiThread(() -> {
                        try {
                            messages.add(new Message("assistant", response));
                            adapter.notifyItemInserted(messages.size() - 1);
                            recyclerView.scrollToPosition(messages.size() - 1);
                            progressBar.setVisibility(View.GONE);
                            sendButton.setEnabled(true);
                        } catch (Exception e) {
                            Log.e("ChatBot", "UI Update Error: " + e.getMessage(), e);
                            showError("UI Error: " + e.getMessage());
                        }
                    });

                } catch (Exception e) {
                    Log.e("ChatBot", "API Call Error: " + e.getMessage(), e);
                    runOnUiThread(() -> {
                        showError("API Error: " + e.getMessage());
                        progressBar.setVisibility(View.GONE);
                        sendButton.setEnabled(true);
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e("ChatBot", "Send Message Error: " + e.getMessage(), e);
            showError("Send Message Error: " + e.getMessage());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ttsPlayer != null) {
            ttsPlayer.stopTtsPlayback();
        }
        LocalLlmManager.getInstance(this).close();
    }

    private void showError(String errorMessage) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        Log.e("ChatBot", errorMessage);
    }
}
