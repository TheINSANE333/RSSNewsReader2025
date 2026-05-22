package xiangze.mmu.rssnewsreader.ui.feedsetting;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import timber.log.Timber;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.lifecycle.ViewModelProvider;

import xiangze.mmu.rssnewsreader.R;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Locale;
import java.util.Objects;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class FeedSettingDialog extends AppCompatDialogFragment implements FeedSettingDialogListener {

    public static String TAG = "SettingFeedDialog";

    private FeedSettingViewModel feedSettingViewModel;

    private long feedId;
    private String title;
    private String description;
    private String link;
    private String language;
    private String languageDisplayName;
    private float speechRateDisplay;
    private boolean autoSummarize;
    private boolean autoTranslate;
    private TextInputEditText titleEditText;
    private TextInputEditText descriptionEditText;
    private MaterialAutoCompleteTextView languageTextView;
    private MaterialAutoCompleteTextView speechRateTextView;
    private com.google.android.material.materialswitch.MaterialSwitch autoSummarizeSwitch;
    private com.google.android.material.materialswitch.MaterialSwitch autoTranslateSwitch;
    private LanguageSelectionDialog dialog;
    private CircularProgressIndicator loading;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_feedsetting, null);
        feedSettingViewModel = new ViewModelProvider(this).get(FeedSettingViewModel.class);

        if (getArguments() != null) {
            feedId = getArguments().getLong("feedId");
            title = getArguments().getString("title");
            link = getArguments().getString("link");
            description = getArguments().getString("description");
            language = getArguments().getString("language");
            speechRateDisplay = getArguments().getFloat("ttsSpeechRate");
            autoSummarize = getArguments().getBoolean("autoSummarize");
            autoTranslate = getArguments().getBoolean("autoTranslate");
            if (language != null) {
                try {
                    Locale locale = Locale.forLanguageTag(language);
                    languageDisplayName = locale.getDisplayName();
                } catch (Exception e) {
                    Timber.d("Failed to create Locale " + e.getMessage());
                    language = null;
                    languageDisplayName = LanguageSelectionDialog.languageIdentifierTAG;
                }
            } else {
                languageDisplayName = LanguageSelectionDialog.languageIdentifierTAG;
            }
        }

        loading = view.findViewById(R.id.loadingTts);
        titleEditText = view.findViewById(R.id.feedTitleEditText);
        descriptionEditText = view.findViewById(R.id.feedDescriptionEditText);
        TextInputEditText urlEditText = view.findViewById(R.id.feedUrlEditText);
        languageTextView = view.findViewById(R.id.languageTextView);
        speechRateTextView = view.findViewById(R.id.speechRateTextView);
        autoSummarizeSwitch = view.findViewById(R.id.autoSummarizeSwitch);
        autoTranslateSwitch = view.findViewById(R.id.autoTranslateSwitch);

        urlEditText.setText(link);
        titleEditText.setText(title);
        descriptionEditText.setText(description);
        autoSummarizeSwitch.setChecked(autoSummarize);
        autoTranslateSwitch.setChecked(autoTranslate);

        String speechRateDisplayName;
        if (speechRateDisplay == 0) {
            speechRateDisplayName = "Use system's settings";
        } else{
            speechRateDisplayName = Float.toString(speechRateDisplay) + "x";
        }
        speechRateTextView.setText(speechRateDisplayName);
        speechRateTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SpeechRateSelectionDialog speechRateSelectionDialog = new SpeechRateSelectionDialog(FeedSettingDialog.this);
                Bundle args = new Bundle();
                args.putLong("feedId", feedId);
                args.putFloat("ttsSpeechRate", speechRateDisplay);
                speechRateSelectionDialog.setArguments(args);
                speechRateSelectionDialog.show(getChildFragmentManager(), SpeechRateSelectionDialog.TAG);
            }
        });

        languageTextView.setText(languageDisplayName);
        languageTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loading.setVisibility(View.VISIBLE);
                dialog = new LanguageSelectionDialog(FeedSettingDialog.this, requireContext());
                Bundle args = new Bundle();
                args.putString("language", language);
                dialog.setArguments(args);
            }
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setView(view)
                .setIcon(R.drawable.ic_setting)
                .setTitle("Feed settings")
                .setNeutralButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {

                    }
                })
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        String updatedTitle = Objects.requireNonNull(titleEditText.getText()).toString();
                        String updatedDesc = Objects.requireNonNull(descriptionEditText.getText()).toString();
                        boolean updatedAutoSummarize = autoSummarizeSwitch.isChecked();
                        boolean updatedAutoTranslate = autoTranslateSwitch.isChecked();
                        feedSettingViewModel.updateFeedSettings(updatedTitle, updatedDesc, language, updatedAutoSummarize, updatedAutoTranslate, link);
                        Toast.makeText(requireContext(), "Update on speech rate will be reflected in the next article", Toast.LENGTH_SHORT).show();
                    }
                });

        return builder.create();
    }

    @Override
    public void modifyLanguage(String newLanguage) {
        if (newLanguage.equals(LanguageSelectionDialog.languageIdentifierTAG)) {
            language = null;
        } else {
            language = newLanguage;
        }
        if (language == null) {
            languageTextView.setText(newLanguage);
        } else {
            Locale locale = Locale.forLanguageTag(newLanguage);
            languageTextView.setText(locale.getDisplayName());
        }
    }

    @Override
    public void modifySpeechRate(float ttsSpeechRate) {
        String speechRate;
        if (ttsSpeechRate == 0) {
            speechRate = "Use system's setting";
        } else {
            speechRate = ttsSpeechRate + "x";
        }
        speechRateTextView.setText(speechRate);
        speechRateDisplay = ttsSpeechRate;
    }

    @Override
    public void showDialog() {
        loading.setVisibility(View.INVISIBLE);
        dialog.show(getChildFragmentManager(), LanguageSelectionDialog.TAG);
    }
}
