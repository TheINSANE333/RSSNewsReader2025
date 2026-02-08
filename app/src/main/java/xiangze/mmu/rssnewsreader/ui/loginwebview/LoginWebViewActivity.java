package xiangze.mmu.rssnewsreader.ui.loginwebview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.databinding.ActivityLoginwebviewBinding;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LoginWebViewActivity extends AppCompatActivity {

    private ActivityLoginwebviewBinding binding;
    private LinearProgressIndicator loading;
    private WebView webView;
    private String link;

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        link = getIntent().getStringExtra("link");

        binding = ActivityLoginwebviewBinding.inflate(getLayoutInflater());

        loading = binding.loginWebViewLoading;

        webView= binding.loginWebView;
        webView.setWebViewClient(new WebClient());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(LoginWebViewActivity.this);
                    builder.setTitle(R.string.login_complete_confirmation)
                            .setIcon(R.drawable.ic_alert)
                            .setMessage(R.string.login_complete_message)
                            .setNeutralButton(R.string.no, (dialogInterface, i) -> {
                            })
                            .setPositiveButton(R.string.yes, (dialogInterface, i) -> {
                                setResult(Activity.RESULT_OK, new Intent());
                                finish();
                            })
                            .show();
                }
            }
        });

        MaterialToolbar toolbar = binding.loginWebViewToolbar;
        toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());
        toolbar.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.loginWebViewZoomIn) {
                int newTextZoom = webView.getSettings().getTextZoom() + 10;
                webView.getSettings().setTextZoom(newTextZoom);
                sharedPreferencesRepository.setTextZoom(newTextZoom);
                return true;
            } else if (itemId == R.id.loginWebViewZoomOut) {
                int newTextZoom = webView.getSettings().getTextZoom() - 10;
                webView.getSettings().setTextZoom(newTextZoom);
                sharedPreferencesRepository.setTextZoom(newTextZoom);
                return true;
            }
            return false;
        });

        int textZoom = sharedPreferencesRepository.getTextZoom();
        if (textZoom != 0) {
            webView.getSettings().setTextZoom(textZoom);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.getSettings().setAlgorithmicDarkeningAllowed(sharedPreferencesRepository.getNight());
        }

        webView.loadUrl(link);

        Log.d("Test url",link);
        setContentView(binding.getRoot());
    }

    private class WebClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon){
            super.onPageStarted(view,url,favicon);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
            view.loadUrl(request.getUrl().toString());
            return true;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            loading.setVisibility(View.INVISIBLE);
        }
    }
}
