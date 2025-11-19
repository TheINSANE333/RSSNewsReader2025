package xiangze.mmu.rssnewsreader.ui.allentries;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import xiangze.mmu.rssnewsreader.R;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class EntryItemBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = EntryItemBottomSheet.class.getSimpleName();

    private EntryItemViewModel entryItemViewModel;
    private MaterialButton markReadButton;
    private MaterialButton shareButton;
    private MaterialButton deleteEntriesAboveButton;
    private MaterialButton deleteEntriesBelowButton;
    private MaterialButton deleteEntryButton;
    private CircularProgressIndicator loading;
    private ArrayList<Long> entryIds;
    private long id;
    private String link;
    private boolean unread;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_bottomentryitem, null);
        entryItemViewModel = new ViewModelProvider(this).get(EntryItemViewModel.class);

        if (getArguments() != null) {
            long[] entryIdsArray = getArguments().getLongArray("ids");
            entryIds = new ArrayList<>();
            for (long n : entryIdsArray) {
                entryIds.add(n);
            }
            id = getArguments().getLong("id");
            link = getArguments().getString("link");
            unread = getArguments().getBoolean("unread");
        }

        markReadButton = view.findViewById(R.id.markReadButton);
        shareButton = view.findViewById(R.id.shareButton);
        deleteEntriesAboveButton = view.findViewById(R.id.deleteEntriesAboveButton);
        deleteEntriesBelowButton = view.findViewById(R.id.deleteEntriesBelowButton);
        deleteEntryButton = view.findViewById(R.id.deleteEntryButton);
        loading = view.findViewById(R.id.loadingEntryItem);

        if (unread) {
            markReadButton.setIcon(ContextCompat.getDrawable(getContext(), R.drawable.ic_check));
            markReadButton.setText(R.string.mark_as_read);
        } else {
            markReadButton.setIcon(ContextCompat.getDrawable(getContext(), R.drawable.ic_cancel));
            markReadButton.setText(R.string.mark_as_unread);
        }

        markReadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                entryItemViewModel.updateVisitedDate(id, unread);
            }
        });

        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, link);
                sendIntent.setType("text/plain");

                Intent shareIntent = Intent.createChooser(sendIntent, null);
                startActivity(shareIntent);
            }
        });

        deleteEntriesAboveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                entryItemViewModel.deleteEntriesByIds(entryIds, id, true);
            }
        });

        deleteEntriesBelowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                entryItemViewModel.deleteEntriesByIds(entryIds, id, false);
            }
        });

        deleteEntryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                entryItemViewModel.deleteEntry(id);
            }
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setView(view);

        entryItemViewModel.getIsLoading().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean != null) {
                    if (aBoolean) {
                        loading.setVisibility(View.VISIBLE);
                    } else {
                        loading.setVisibility(View.INVISIBLE);
                        dismiss();
                    }
                }
            }
        });

        entryItemViewModel.getToastMessage().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s != null && !s.isEmpty()) {
                    Snackbar.make(getParentFragment() != null ? getParentFragment().requireView() : view, s, Snackbar.LENGTH_SHORT).show();
                    entryItemViewModel.resetToastMessage();
                }
            }
        });

        return builder.create();
    }
}
