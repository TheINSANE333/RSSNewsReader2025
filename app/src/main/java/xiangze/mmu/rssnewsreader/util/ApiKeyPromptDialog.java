package xiangze.mmu.rssnewsreader.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;

public class ApiKeyPromptDialog {

    public static void show(Context context, SharedPreferencesRepository sharedPreferencesRepository, Runnable onKeySaved) {
        new AlertDialog.Builder(context)
                .setTitle("AI Features Need a Key")
                .setMessage("To use AI features like Translation and Summarization, you need a free Groq API Key. It takes just 1 minute to get one!")
                .setPositiveButton("Get Free Key", (dialog, which) -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"));
                    context.startActivity(browserIntent);
                })
                .setNeutralButton("Enter Key", (dialog, which) -> {
                    showEnterKeyDialog(context, sharedPreferencesRepository, onKeySaved);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void showEnterKeyDialog(Context context, SharedPreferencesRepository sharedPreferencesRepository, Runnable onKeySaved) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Paste your API key here");

        new AlertDialog.Builder(context)
                .setTitle("Enter API Key")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String key = input.getText().toString().trim();
                    if (!key.isEmpty()) {
                        sharedPreferencesRepository.setGroqApiKey(key);
                        
                        // Ensure it's in the saved list
                        List<SharedPreferencesRepository.ApiKey> savedKeys = sharedPreferencesRepository.getSavedApiKeys();
                        boolean exists = false;
                        for (SharedPreferencesRepository.ApiKey existingKey : savedKeys) {
                            if (existingKey.value.equals(key)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            savedKeys.add(new SharedPreferencesRepository.ApiKey("Key " + (savedKeys.size() + 1), key));
                            sharedPreferencesRepository.setSavedApiKeys(savedKeys);
                        }
                        
                        Toast.makeText(context, "API Key saved!", Toast.LENGTH_SHORT).show();
                        if (onKeySaved != null) {
                            onKeySaved.run();
                        }
                    } else {
                        Toast.makeText(context, "Key cannot be empty", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }
}
