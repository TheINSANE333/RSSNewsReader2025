package xiangze.mmu.rssnewsreader.ui.webview;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.nio.charset.StandardCharsets;

import xiangze.mmu.rssnewsreader.R;

public class ChatGPTWebViewActivity extends AppCompatActivity {
    private static final String TAG = "ChatGPTWebViewActivity";
    private WebView webView;
    private LinearProgressIndicator progressBar;
    public static String sPrompt;
    private String prompt;
    private boolean promptInjected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_gpt_webview);

        if (sPrompt != null) {
            prompt = sPrompt;
            sPrompt = null; // Clear it after retrieving
        } else {
            prompt = getIntent().getStringExtra("prompt");
        }

        initViews();
        setupWebView();

        if (prompt != null) {
            webView.loadUrl("https://chatgpt.com/");
        } else {
            finish();
        }
    }

    private void initViews() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("ChatGPT Summary");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // Desktop User-Agent for better compatibility
        webView.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                if (url.contains("chatgpt.com") && !promptInjected && !url.contains("auth")) {
                    injectPrompt();
                }
            }
        });
    }

    private void injectPrompt() {
        if (prompt == null || prompt.isEmpty()) return;

        // Base64 encode the prompt to avoid issues with special characters in JS
        String encodedPrompt = Base64.encodeToString(prompt.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        String js = "(function() {" +
                "  var prompt = atob('" + encodedPrompt + "');" +
                "  var textbox = document.getElementById('prompt-textarea');" +
                "  if (!textbox) {" +
                "    textbox = document.querySelector('textarea, [contenteditable=\"true\"], [id^=\"prompt-textarea\"]');" +
                "  }" +
                "  if (textbox) {" +
                "    textbox.focus();" +
                "    if (textbox.tagName === 'DIV' || textbox.getAttribute('contenteditable') === 'true') {" +
                "       textbox.innerHTML = '<p>' + prompt.replace(/\\n/g, '</p><p>') + '</p>';" +
                "    } else {" +
                "       textbox.value = prompt;" +
                "    }" +
                "    textbox.dispatchEvent(new Event('input', { bubbles: true }));" +
                "    textbox.dispatchEvent(new Event('change', { bubbles: true }));" +
                "    " +
                "    setTimeout(function() {" +
                "      var sendButton = document.querySelector('button[data-testid=\"send-button\"], button[aria-label=\"Send prompt\"], .mb-1\\\\.5.rounded-lg.bg-black');" +
                "      if (sendButton && !sendButton.disabled) {" +
                "        sendButton.click();" +
                "        console.log('ChatGPT Send button clicked');" +
                "      } else {" +
                "         var enterEvent = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: 'Enter', code: 'Enter', keyCode: 13 });" +
                "         textbox.dispatchEvent(enterEvent);" +
                "         console.log('ChatGPT Enter pressed as fallback');" +
                "      }" +
                "    }, 3000);" + // Increased delay to 3 seconds as requested
                "    return true;" +
                "  }" +
                "  return false;" +
                "})();";

        webView.evaluateJavascript(js, result -> {
            if ("true".equals(result)) {
                Log.d(TAG, "Prompt injected successfully into ChatGPT");
                promptInjected = true;
            } else {
                Log.d(TAG, "Failed to find ChatGPT textbox, retrying in 2s...");
                new Handler(Looper.getMainLooper()).postDelayed(this::injectPrompt, 2000);
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}