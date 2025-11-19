package xiangze.mmu.rssnewsreader.ui.feed;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.lifecycle.ViewModelProvider;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewListener;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ReloadDialog extends AppCompatDialogFragment implements AdapterView.OnItemSelectedListener{

    public static final String TAG = ReloadDialog.class.getSimpleName();

    private FeedViewModel feedViewModel;
    private WebViewListener webViewListener;
    private ManageFeedListener manageFeedListener;
    private Spinner spinner;
    private int delayTime;
    private int title;
    private int message;
    private long id;
    private String[] options = {"0", "3", "5", "10", "20", "30", "40", "50", "60"};

    public ReloadDialog(WebViewListener webViewListener, long id, int title, int message) {
        this.webViewListener = webViewListener;
        this.id = id;
        this.title = title;
        this.message = message;
    }

    public ReloadDialog(ManageFeedListener manageFeedListener, long id, int title, int message) {
        this.manageFeedListener = manageFeedListener;
        this.id = id;
        this.title = title;
        this.message = message;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_reloadfeed, null);
        feedViewModel = new ViewModelProvider(this).get(FeedViewModel.class);
        delayTime = feedViewModel.getDelayTimeById(id);

        TextView reloadMessage = view.findViewById(R.id.reloadDialogTextView);
        reloadMessage.setText(message);

        spinner = view.findViewById(R.id.reloadDialogSpinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, options);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);
        int position = -1;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(Integer.toString(delayTime))) {
                position = i;
                break;
            }
        }
        spinner.setSelection(position);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setView(view)
                .setIcon(R.drawable.ic_alert)
                .setTitle(title)
                .setNeutralButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        webViewListener = null;
                        manageFeedListener = null;
                    }
                })
                .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        feedViewModel.updateDelayTimeById(id, delayTime);

                        if (webViewListener != null) {
                            webViewListener.reload();
                            webViewListener = null;
                        }

                        if (manageFeedListener != null) {
                            manageFeedListener.reExtract(id);
                            manageFeedListener = null;
                        }
                    }
                });

        return builder.create();
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        delayTime = Integer.parseInt((String) adapterView.getItemAtPosition(i));
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }
}
