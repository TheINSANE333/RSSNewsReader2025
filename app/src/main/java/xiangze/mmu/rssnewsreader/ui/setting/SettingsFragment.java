package xiangze.mmu.rssnewsreader.ui.setting;

import static android.app.Activity.RESULT_OK;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.ai.LocalLlmManager;
import xiangze.mmu.rssnewsreader.model.ai.TokenUsageGuard;
import xiangze.mmu.rssnewsreader.service.rss.RssWorkManager;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;
import xiangze.mmu.rssnewsreader.ui.main.MainActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SettingsFragment extends PreferenceFragmentCompat {

    @Inject
    RssWorkManager rssWorkManager;

    @Inject
    TtsPlayer ttsPlayer;

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    private ListPreference backgroundMusicFilePreference;
    private boolean isAdditionalImport;
    private final CharSequence[] defaultMusicEntries = {"Default", "Import music file (ogg format is preferred)"};
    private final CharSequence[] defaultMusicValues = {"default", "userFile"};
    private final CharSequence[] extendedMusicEntries  = {"Default", "Imported music file", "Import another music file (ogg format is preferred)"};
    private final CharSequence[] extendedMusicValues  = {"default", "userFile", "addUserFile"};

    private SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            switch (key) {
                case "jobPeriodic":
                    rssWorkManager.enqueueRssWorker();
                    break;
                case "night":
                    boolean night = sharedPreferencesRepository.getNight();
                    if (night) {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                    } else {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                    }
                    ((MainActivity) getActivity()).updateThemeSwitch();
                    break;
                case "backgroundMusic":
                    if (ttsPlayer.isPlayingMediaPlayer()) {
                        boolean backgroundMusic = sharedPreferencesRepository.getBackgroundMusic();
                        if (backgroundMusic) {
                            ttsPlayer.setupMediaPlayer(false);
                        } else {
                            ttsPlayer.stopMediaPlayer();
                        }
                    }
                    break;
                case "backgroundMusicFile":
                    String musicFile = sharedPreferencesRepository.getBackgroundMusicFile();
                    if (!musicFile.equals("default")) {
                        if (!musicFile.equals("userFile")) {
                            isAdditionalImport = true;
                            sharedPreferencesRepository.setBackgroundMusicFile("userFile");
                            backgroundMusicFilePreference.setValue("userFile");
                        } else {
                            backgroundMusicFilePreference.setEntries(extendedMusicEntries);
                            backgroundMusicFilePreference.setEntryValues(extendedMusicValues);
                            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                            intent.setType("audio/*");
                            saveMusicFileLauncher.launch(intent);
                        }
                    } else {
                        backgroundMusicFilePreference.setEntries(defaultMusicEntries);
                        backgroundMusicFilePreference.setEntryValues(defaultMusicValues);
                        if (ttsPlayer.isPlayingMediaPlayer()) {
                            ttsPlayer.setupMediaPlayer(true);
                        }
                    }
                    break;
                case "backgroundMusicVolume":
                    ttsPlayer.changeMediaPlayerVolume();
                    break;
                case "autoTranslate":
                case "autoSummarize":
                    if (sharedPreferences.getBoolean(key, false)) {
                        rssWorkManager.triggerOneTimeRssWorker();
                    }
                    break;
                case "groq_api_key":
                    String newApiKey = sharedPreferences.getString(key, "");
                    sharedPreferencesRepository.setGroqApiKey(newApiKey);
                    break;
            }
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        backgroundMusicFilePreference = findPreference("backgroundMusicFile");

        if (!sharedPreferencesRepository.getBackgroundMusicFile().equals("default")) {
            backgroundMusicFilePreference.setEntries(extendedMusicEntries);
            backgroundMusicFilePreference.setEntryValues(extendedMusicValues);
        } else {
            backgroundMusicFilePreference.setEntries(defaultMusicEntries);
            backgroundMusicFilePreference.setEntryValues(defaultMusicValues);
        }

        Preference ttsSettingsPreference = findPreference("key_text_to_speech_settings");
        if (ttsSettingsPreference != null) {
            ttsSettingsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(@NonNull Preference preference) {
                    Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                    startActivity(intent);
                    return true;
                }
            });
        }

        Preference ttsSubstitutionsPreference = findPreference("key_tts_substitutions");
        if (ttsSubstitutionsPreference != null) {
            ttsSubstitutionsPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(getActivity(), xiangze.mmu.rssnewsreader.ui.settings.TtsSubstitutionsActivity.class);
                startActivity(intent);
                return true;
            });
        }

        Preference getgroqKeyPreference = findPreference("get_groq_key");
        if (getgroqKeyPreference != null) {
            getgroqKeyPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"));
                startActivity(intent);
                return true;
            });
        }

        Preference downloadModelPreference = findPreference("download_local_model");
        if (downloadModelPreference != null) {
            downloadModelPreference.setOnPreferenceClickListener(preference -> {
                showDownloadModelDialog();
                return true;
            });
        }

        Preference deleteModelPreference = findPreference("delete_local_model");
        if (deleteModelPreference != null) {
            deleteModelPreference.setOnPreferenceClickListener(preference -> {
                showDeleteModelDialog();
                return true;
            });
        }

        Preference aiLimitSettingsPreference = findPreference("ai_limit_settings");
        if (aiLimitSettingsPreference != null) {
            aiLimitSettingsPreference.setOnPreferenceClickListener(preference -> {
                showTokenLimitDialog();
                return true;
            });
        }

        Preference manageApiKeysPreference = findPreference("manage_api_keys");
        if (manageApiKeysPreference != null) {
            manageApiKeysPreference.setOnPreferenceClickListener(preference -> {
                showManageApiKeysDialog();
                return true;
            });
        }
    }

    private void showManageApiKeysDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.manage_api_keys_title);

        android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(requireContext());
        rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        rootLayout.setPadding(padding, padding, padding, padding);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(requireContext());
        android.widget.LinearLayout listLayout = new android.widget.LinearLayout(requireContext());
        listLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        scrollView.addView(listLayout);
        rootLayout.addView(scrollView, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        refreshApiKeysList(listLayout);

        com.google.android.material.button.MaterialButton addButton = new com.google.android.material.button.MaterialButton(requireContext());
        addButton.setText(R.string.add_api_key);
        addButton.setIconResource(R.drawable.ic_plus);
        android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, padding, 0, 0);
        addButton.setLayoutParams(btnParams);
        addButton.setOnClickListener(v -> showAddApiKeyDialog(listLayout));
        rootLayout.addView(addButton);
        
        com.google.android.material.button.MaterialButton getFreeKeyButton = new com.google.android.material.button.MaterialButton(requireContext());
        getFreeKeyButton.setText("Get Free Key");
        android.widget.LinearLayout.LayoutParams btnParams2 = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        getFreeKeyButton.setLayoutParams(btnParams2);
        getFreeKeyButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"));
            startActivity(intent);
        });
        rootLayout.addView(getFreeKeyButton);

        builder.setView(rootLayout);
        builder.setPositiveButton(R.string.yes, null);
        builder.show();
    }

    private void refreshApiKeysList(android.widget.LinearLayout listLayout) {
        listLayout.removeAllViews();
        java.util.List<SharedPreferencesRepository.ApiKey> keys = sharedPreferencesRepository.getSavedApiKeys();
        String activeKey = sharedPreferencesRepository.getGroqApiKey();

        for (SharedPreferencesRepository.ApiKey key : keys) {
            android.view.View itemView = android.view.LayoutInflater.from(requireContext()).inflate(R.layout.item_api_key, listLayout, false);
            android.widget.TextView nameText = itemView.findViewById(R.id.key_name);
            android.widget.TextView valueText = itemView.findViewById(R.id.key_value);
            android.widget.RadioButton radioButton = itemView.findViewById(R.id.radio_button);
            android.widget.ImageButton deleteBtn = itemView.findViewById(R.id.delete_button);

            nameText.setText(key.name);
            // Show only first and last few chars of key
            String maskedKey = key.value.length() > 8 ? 
                key.value.substring(0, 4) + "..." + key.value.substring(key.value.length() - 4) : 
                "********";
            valueText.setText(maskedKey);

            radioButton.setChecked(key.value.equals(activeKey));
            radioButton.setOnClickListener(v -> {
                sharedPreferencesRepository.setGroqApiKey(key.value);
                refreshApiKeysList(listLayout);
                // Update the EditTextPreference too
                androidx.preference.EditTextPreference groqPref = findPreference("groq_api_key");
                if (groqPref != null) {
                    groqPref.setText(key.value);
                }
            });

            deleteBtn.setOnClickListener(v -> {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete)
                    .setMessage(R.string.delete_api_key_confirmation)
                    .setPositiveButton(R.string.yes, (dialog, which) -> {
                        sharedPreferencesRepository.removeSavedApiKey(key.value);
                        refreshApiKeysList(listLayout);
                        androidx.preference.EditTextPreference groqPref = findPreference("groq_api_key");
                        if (groqPref != null) {
                            groqPref.setText(sharedPreferencesRepository.getGroqApiKey());
                        }
                    })
                    .setNegativeButton(R.string.no, null)
                    .show();
            });

            listLayout.addView(itemView);
        }
    }

    private void showAddApiKeyDialog(android.widget.LinearLayout listLayout) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final com.google.android.material.textfield.TextInputEditText nameInput = new com.google.android.material.textfield.TextInputEditText(requireContext());
        nameInput.setHint(R.string.api_key_name_hint);
        layout.addView(nameInput);

        final com.google.android.material.textfield.TextInputEditText valueInput = new com.google.android.material.textfield.TextInputEditText(requireContext());
        valueInput.setHint(R.string.api_key_value_hint);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, padding, 0, 0);
        valueInput.setLayoutParams(params);
        layout.addView(valueInput);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_api_key)
            .setView(layout)
            .setPositiveButton(R.string.yes, (dialog, which) -> {
                String name = nameInput.getText().toString().trim();
                String value = valueInput.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.api_key_name_empty_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (value.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.api_key_empty_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                
                java.util.List<SharedPreferencesRepository.ApiKey> keys = sharedPreferencesRepository.getSavedApiKeys();
                keys.add(new SharedPreferencesRepository.ApiKey(name, value));
                sharedPreferencesRepository.setSavedApiKeys(keys);
                refreshApiKeysList(listLayout);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showTokenLimitDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.ai_limit_settings_title);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        
        // TPM
        com.google.android.material.textview.MaterialTextView tpmLabel = new com.google.android.material.textview.MaterialTextView(requireContext());
        int currentTpm = prefs.getInt(TokenUsageGuard.KEY_LIMIT_TPM, 30000);
        tpmLabel.setText(getString(R.string.limit_tpm_title) + ": " + currentTpm);
        layout.addView(tpmLabel);

        android.widget.SeekBar tpmSeekBar = new android.widget.SeekBar(requireContext());
        tpmSeekBar.setMax(60000);
        tpmSeekBar.setProgress(currentTpm);
        tpmSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 1000) progress = 1000;
                tpmLabel.setText(getString(R.string.limit_tpm_title) + ": " + progress);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        layout.addView(tpmSeekBar);

        // RPD
        com.google.android.material.textview.MaterialTextView rpdLabel = new com.google.android.material.textview.MaterialTextView(requireContext());
        int currentRpd = prefs.getInt(TokenUsageGuard.KEY_LIMIT_RPD, 1000);
        rpdLabel.setText(getString(R.string.limit_rpd_title) + ": " + currentRpd);
        rpdLabel.setPadding(0, padding, 0, 0);
        layout.addView(rpdLabel);

        android.widget.SeekBar rpdSeekBar = new android.widget.SeekBar(requireContext());
        rpdSeekBar.setMax(2000);
        rpdSeekBar.setProgress(currentRpd);
        rpdSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 100) progress = 100;
                rpdLabel.setText(getString(R.string.limit_rpd_title) + ": " + progress);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        layout.addView(rpdSeekBar);

        // TPD
        com.google.android.material.textview.MaterialTextView tpdLabel = new com.google.android.material.textview.MaterialTextView(requireContext());
        int currentTpd = prefs.getInt(TokenUsageGuard.KEY_LIMIT_TPD, 500000);
        tpdLabel.setText(getString(R.string.limit_tpd_title) + ": " + currentTpd);
        tpdLabel.setPadding(0, padding, 0, 0);
        layout.addView(tpdLabel);

        android.widget.SeekBar tpdSeekBar = new android.widget.SeekBar(requireContext());
        tpdSeekBar.setMax(1000000);
        tpdSeekBar.setProgress(currentTpd);
        tpdSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 10000) progress = 10000;
                tpdLabel.setText(getString(R.string.limit_tpd_title) + ": " + progress);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        layout.addView(tpdSeekBar);

        // Reset Button (Themed)
        com.google.android.material.button.MaterialButton resetButton = new com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle);
        resetButton.setText(R.string.reset_token_usage_title);
        android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, padding, 0, 0);
        resetButton.setLayoutParams(btnParams);
        resetButton.setOnClickListener(v -> {
            TokenUsageGuard.getInstance(requireContext()).resetManual();
            Toast.makeText(requireContext(), R.string.token_usage_reset_success, Toast.LENGTH_SHORT).show();
        });
        layout.addView(resetButton);

        builder.setView(layout);
        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            int newTpm = Math.max(1000, tpmSeekBar.getProgress());
            int newRpd = Math.max(100, rpdSeekBar.getProgress());
            int newTpd = Math.max(10000, tpdSeekBar.getProgress());
            
            prefs.edit()
                .putInt(TokenUsageGuard.KEY_LIMIT_TPM, newTpm)
                .putInt(TokenUsageGuard.KEY_LIMIT_RPD, newRpd)
                .putInt(TokenUsageGuard.KEY_LIMIT_TPD, newTpd)
                .apply();
            
            Toast.makeText(requireContext(), "Limits updated successfully", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private String pendingModelId;

    private final ActivityResultLauncher<String[]> importModelLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && pendingModelId != null) {
                    importModel(uri, pendingModelId);
                }
            });

    private void importModel(Uri uri, String modelId) {
        String filename = LocalLlmManager.getInstance(requireContext()).getModelFilename(modelId);
        File destFile = new File(requireContext().getExternalFilesDir(null), filename);
        
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(destFile)) {
            
            if (is == null) throw new IOException("Failed to open input stream");
            
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            
            Toast.makeText(requireContext(), "Model imported successfully: " + filename, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(requireContext(), "Failed to import model: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDeleteModelDialog() {
        File extQwen = new File(requireContext().getExternalFilesDir(null), "qwen2.5-1.5b.task");
        File intQwen = new File(requireContext().getFilesDir(), "qwen2.5-1.5b.task");

        if (!extQwen.exists() && !intQwen.exists()) {
            Toast.makeText(requireContext(), "No model files found to delete.", Toast.LENGTH_SHORT).show();
            return;
        }

        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("Delete Local Models")
                .setMessage("Are you sure you want to delete the Qwen model files?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    boolean deleted = false;
                    if (extQwen.exists() && extQwen.delete()) deleted = true;
                    if (intQwen.exists() && intQwen.delete()) deleted = true;
                    
                    if (deleted) {
                        Toast.makeText(requireContext(), "Model files deleted successfully.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete model files.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDownloadModelDialog() {
        String modelId = LocalLlmManager.QWEN_MODEL_ID;
        String modelName = "Qwen 2.5 1.5B";
        
        String[] options = {"Download from URL", "Import from Device (.task file)"};

        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("Manage Qwen Model")
                .setItems(options, (optionDialog, optionWhich) -> {
                    if (options[optionWhich].contains("Download")) {
                        showUrlInputDialog(modelId, modelName);
                    } else {
                        pendingModelId = modelId;
                        importModelLauncher.launch(new String[]{"*/*"});
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showUrlInputDialog(String modelId, String modelName) {
        String filename = LocalLlmManager.getInstance(requireContext()).getModelFilename(modelId);
        String defaultUrl = LocalLlmManager.getInstance(requireContext()).getDefaultUrl(modelId);
        
        File file = new File(requireContext().getExternalFilesDir(null), filename);
        String message = "Please enter the direct download URL for the " + modelName + " model (.task file).";
        
        if (file.exists()) {
            message = "Model file already exists. Downloading again will overwrite it.\n\n" + message;
        }

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle("Download " + modelName);
        builder.setMessage(message);

        android.widget.LinearLayout container = new android.widget.LinearLayout(requireContext());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, 0, padding, 0);

        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(defaultUrl);
        input.setText(defaultUrl);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(5);
        input.setHorizontallyScrolling(false);
        
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Download", (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                downloadModel(url, modelId);
            } else {
                Toast.makeText(requireContext(), "URL cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void downloadModel(String url, String modelId) {
        try {
            LocalLlmManager.getInstance(requireContext()).downloadModel(url, modelId);
            Toast.makeText(requireContext(), "Download started for " + modelId + "...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Objects.requireNonNull(getPreferenceManager().getSharedPreferences()).registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void onPause() {
        super.onPause();
        Objects.requireNonNull(getPreferenceManager().getSharedPreferences()).unregisterOnSharedPreferenceChangeListener(listener);
    }

    // Define the ActivityResultLauncher for saving the music file
    private final ActivityResultLauncher<Intent> saveMusicFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null) {
                            // Handle the selected audio file
                            Uri fileUri = data.getData();
                            handleSelectedFile(fileUri);
                        }
                    } else {
                        // File selection canceled or failed
                        if (isAdditionalImport) {
                            isAdditionalImport = false;
                        } else {
                            sharedPreferencesRepository.setBackgroundMusicFile("default");
                            backgroundMusicFilePreference.setValue("default");
                        }
                        Toast.makeText(requireContext(), "File selection canceled or failed", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private void handleSelectedFile(Uri fileUri) {
        File internalStorageDir = getActivity().getFilesDir();

        // Create a File object for the destination file in internal storage
        File destinationFile = new File(internalStorageDir, "user_file.mp3");

        // Copy the user-inserted file to the destination in internal storage
        try {
            InputStream inputStream = getActivity().getContentResolver().openInputStream(fileUri);
            OutputStream outputStream = new FileOutputStream(destinationFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.close();
            inputStream.close();
            Toast.makeText(requireContext(), "File imported successfully", Toast.LENGTH_SHORT).show();
            if (ttsPlayer.isPlayingMediaPlayer()) {
                ttsPlayer.setupMediaPlayer(true);
            }
        } catch (IOException e) {
            sharedPreferencesRepository.setBackgroundMusicFile("default");
            e.printStackTrace();
        }
    }
}
