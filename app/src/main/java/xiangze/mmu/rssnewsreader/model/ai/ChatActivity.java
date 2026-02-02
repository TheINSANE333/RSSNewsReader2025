package xiangze.mmu.rssnewsreader.model.ai;

import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
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
import xiangze.mmu.rssnewsreader.service.ai.ChatAdapter;

import xiangze.mmu.rssnewsreader.R;

public class ChatActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private EditText messageInput;
    private ImageButton sendButton;
    private ProgressBar progressBar;
    private ChatAdapter adapter;
    private List<Message> messages = new ArrayList<>();
    private AiClient aiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("ChatActivity", "RUNNING CREATE");
        super.onCreate(savedInstanceState);

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

        if (getIntent().hasExtra("initial_message")) {
            String summary = getIntent().getStringExtra("initial_message");
            messages.add(new Message("assistant", summary));
        }
        else {
            // Add welcome message
            messages.add(new Message("assistant", "Hello! How can I help you today?"));
        }
        adapter.notifyDataSetChanged();
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

    private void setupRecyclerView() {
        adapter = new ChatAdapter(messages);
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
                    String response = aiClient.getChatResponse(messages);
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

    private void showError(String errorMessage) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        Log.e("ChatBot", errorMessage);
    }
}