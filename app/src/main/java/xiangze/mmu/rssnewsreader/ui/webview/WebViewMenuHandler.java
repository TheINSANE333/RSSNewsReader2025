package xiangze.mmu.rssnewsreader.ui.webview;

import android.content.Context;
import android.content.Intent;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.ui.feed.ReloadDialog;

public class WebViewMenuHandler {

    private final AppCompatActivity activity;
    private final WebViewViewModel viewModel;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final MaterialToolbar toolbar;

    public interface MenuActionListener {
        void onTranslate();
        void onSummarize();
        void onChat();
        void onToggleBookmark();
        void onShare();
        void onToggleBackgroundMusic();
        void onSwitchPlayMode();
        void onSwitchReadMode();
        void onAdjustTextZoom(boolean zoomIn);
        void onToggleHighlight();
        void onExitBrowser();
        void onOpenInBrowser();
        void onReload();
        void onReExtract();
    }

    private final MenuActionListener listener;

    public WebViewMenuHandler(AppCompatActivity activity, 
                              WebViewViewModel viewModel, 
                              SharedPreferencesRepository sharedPreferencesRepository,
                              MaterialToolbar toolbar,
                              MenuActionListener listener) {
        this.activity = activity;
        this.viewModel = viewModel;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.toolbar = toolbar;
        this.listener = listener;
    }

    public void setupMenu() {
        toolbar.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.translate) {
                listener.onTranslate();
                return true;
            } else if (itemId == R.id.summarize) {
                listener.onSummarize();
                return true;
            } else if (itemId == R.id.chatbot) {
                listener.onChat();
                return true;
            } else if (itemId == R.id.bookmark) {
                listener.onToggleBookmark();
                return true;
            } else if (itemId == R.id.share) {
                listener.onShare();
                return true;
            } else if (itemId == R.id.toggleBackgroundMusic) {
                listener.onToggleBackgroundMusic();
                return true;
            } else if (itemId == R.id.switchPlayMode) {
                listener.onSwitchPlayMode();
                return true;
            } else if (itemId == R.id.switchReadMode) {
                listener.onSwitchReadMode();
                return true;
            } else if (itemId == R.id.zoomIn) {
                listener.onAdjustTextZoom(true);
                return true;
            } else if (itemId == R.id.zoomOut) {
                listener.onAdjustTextZoom(false);
                return true;
            } else if (itemId == R.id.highlightText) {
                listener.onToggleHighlight();
                return true;
            } else if (itemId == R.id.openInBrowser) {
                listener.onOpenInBrowser();
                return true;
            } else if (itemId == R.id.exitBrowser) {
                listener.onExitBrowser();
                return true;
            } else if (itemId == R.id.reload) {
                showReloadOptionsDialog();
                return true;
            } else if (itemId == R.id.openTtsSetting) {
                activity.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                return true;
            } else if (itemId == R.id.toggleTranslation) {
                Boolean current = viewModel.getIsTranslatedViewLiveData().getValue();
                boolean newVal = (current == null) || !current;
                viewModel.setIsTranslatedView(newVal);
                if (newVal) viewModel.setIsSummarizedView(false);
                return true;
            } else if (itemId == R.id.toggleSummarization) {
                Boolean current = viewModel.getIsSummarizedViewLiveData().getValue();
                boolean newVal = (current == null) || !current;
                viewModel.setIsSummarizedView(newVal);
                if (newVal) viewModel.setIsTranslatedView(false);
                return true;
            }
            return false;
        });

        // Setup long click for translation
        View translateView = toolbar.findViewById(R.id.translate);
        if (translateView != null) {
            translateView.setOnLongClickListener(v -> {
                showTranslationLanguageDialog();
                return true;
            });
        }
    }

    private void showReloadOptionsDialog() {
        String[] options = {"Reload Feed", "Re-extract Article"};
        new AlertDialog.Builder(activity)
                .setTitle("Reload Options")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        listener.onReload();
                    } else {
                        listener.onReExtract();
                    }
                })
                .show();
    }

    private void showTranslationLanguageDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Default Translation Language");

        CharSequence[] entries = activity.getResources().getStringArray(R.array.defaultTranslationLanguage);
        CharSequence[] entryValues = activity.getResources().getStringArray(R.array.defaultTranslationLanguage_values);

        builder.setItems(entries, (dialog, which) -> {
            viewModel.makeSnackbar("Translating to " + entries[which]);
            String selectedValue = entryValues[which].toString();
            sharedPreferencesRepository.setDefaultTranslationLanguage(selectedValue);
            listener.onTranslate();
            dialog.dismiss();
        });

        builder.show();
    }
}
