package xiangze.mmu.rssnewsreader.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.databinding.ActivityTtsSubstitutionsBinding;
import xiangze.mmu.rssnewsreader.databinding.ItemTtsSubstitutionBinding;

@AndroidEntryPoint
public class TtsSubstitutionsActivity extends AppCompatActivity {

    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    private ActivityTtsSubstitutionsBinding binding;
    private SubstitutionsAdapter adapter;
    private Map<String, String> substitutionsMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTtsSubstitutionsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        substitutionsMap = sharedPreferencesRepository.getTtsSubstitutions();
        
        adapter = new SubstitutionsAdapter(new ArrayList<>(substitutionsMap.keySet()));
        binding.substitutionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.substitutionsRecyclerView.setAdapter(adapter);

        binding.addButton.setOnClickListener(v -> showAddDialog(null, null));
    }

    private void showAddDialog(String original, String replacement) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_tts_substitution, null);
        EditText originalEdit = view.findViewById(R.id.originalEditText);
        EditText replacementEdit = view.findViewById(R.id.replacementEditText);

        if (original != null) originalEdit.setText(original);
        if (replacement != null) replacementEdit.setText(replacement);

        new AlertDialog.Builder(this)
                .setTitle(original == null ? "Add Substitution" : "Edit Substitution")
                .setView(view)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newOriginal = originalEdit.getText().toString().trim();
                    String newReplacement = replacementEdit.getText().toString().trim();
                    if (!newOriginal.isEmpty()) {
                        if (original != null) substitutionsMap.remove(original);
                        substitutionsMap.put(newOriginal, newReplacement);
                        saveAndRefresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveAndRefresh() {
        sharedPreferencesRepository.setTtsSubstitutions(substitutionsMap);
        adapter.setKeys(new ArrayList<>(substitutionsMap.keySet()));
    }

    class SubstitutionsAdapter extends RecyclerView.Adapter<SubstitutionsAdapter.ViewHolder> {
        private List<String> keys;

        SubstitutionsAdapter(List<String> keys) {
            this.keys = keys;
        }

        void setKeys(List<String> keys) {
            this.keys = keys;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemTtsSubstitutionBinding itemBinding = ItemTtsSubstitutionBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new ViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String key = keys.get(position);
            String value = substitutionsMap.get(key);
            holder.binding.originalTextView.setText(key);
            holder.binding.replacementTextView.setText(value);
            
            holder.itemView.setOnClickListener(v -> showAddDialog(key, value));
            holder.binding.deleteButton.setOnClickListener(v -> {
                substitutionsMap.remove(key);
                saveAndRefresh();
            });
        }

        @Override
        public int getItemCount() {
            return keys.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ItemTtsSubstitutionBinding binding;
            ViewHolder(ItemTtsSubstitutionBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}