package xiangze.mmu.rssnewsreader.ui.feedsetting;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;

import xiangze.mmu.rssnewsreader.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LanguageSelectionDialog extends AppCompatDialogFragment {

    public static final String TAG = "LanguageSelectionDialog";
    public static final String languageIdentifierTAG = "Use Language Identifier";

    private RadioGroup radioGroup;
    private FeedSettingDialogListener listener;
    private Map<String, Integer> languagesMap;
    private TextToSpeech tts;

    public LanguageSelectionDialog(FeedSettingDialogListener listener, Context context) {
        this.listener = listener;

        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                Log.d(TAG, "tts is initialized");
                if (status == TextToSpeech.SUCCESS) {
                    listener.showDialog();
                }
            }
        });
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_languageselection, null);
        radioGroup = view.findViewById(R.id.languageRadioGroup);

        Set<Locale> availableLanguages = tts.getAvailableLanguages();
        tts.shutdown();
        languagesMap = new TreeMap<>();
        int checkId = 0;

        int i = 0;
        for (Locale locale : availableLanguages) {
            i++;
            languagesMap.put(locale.getLanguage(), i);
        }

        if (getArguments() != null) {
            String language = getArguments().getString("language");
            if (language != null) {
                Integer id = languagesMap.get(language);
                if (id != null) {
                    checkId = id;
                } else {
                    i++;
                    languagesMap.put(language, i);
                    checkId = i;
                }
                i++;
            }
            else {
                i++;
                checkId = i;
            }
        }

        // for Language Identifier
        RadioButton identifierRadioButton = new RadioButton(requireContext());
        identifierRadioButton.setText(R.string.languageIdentifier);
        identifierRadioButton.setId(i);
        identifierRadioButton.setLayoutParams(new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.MATCH_PARENT));
        radioGroup.addView(identifierRadioButton);

        for (Map.Entry<String, Integer> entry : languagesMap.entrySet()) {
            Locale locale = Locale.forLanguageTag(entry.getKey());
            RadioButton radioButton = new RadioButton(requireContext());
            radioButton.setText(locale.getDisplayName());
            radioButton.setId(entry.getValue());
            radioButton.setLayoutParams(new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.MATCH_PARENT));
            radioGroup.addView(radioButton);
        }

        // Insert language identifier into map
        languagesMap.put(languageIdentifierTAG, i);

        if (checkId != 0) {
            radioGroup.check(checkId);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setView(view)
                .setIcon(R.drawable.ic_setting)
                .setTitle("Select language")
                .setNeutralButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {

                    }
                })
                .setPositiveButton("Select", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        int radioId = radioGroup.getCheckedRadioButtonId();
                        String language = getKey(radioId);
                        listener.modifyLanguage((String) language);
                    }
                });

        return builder.create();
    }

    private String getKey(int id) {
        for (Map.Entry<String, Integer> entry : languagesMap.entrySet()) {
            if (Objects.equals(id, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }
}
