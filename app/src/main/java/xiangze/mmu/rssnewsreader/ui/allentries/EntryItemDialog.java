package xiangze.mmu.rssnewsreader.ui.allentries;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.DialogFragment;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.databinding.DialogEntryitemBinding;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;

import java.text.SimpleDateFormat;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class EntryItemDialog extends AppCompatDialogFragment {

    public static final String TAG = EntryItemDialog.class.getSimpleName();

    private EntryItemDialogClickInterface entryItemDialogClickInterface;
    private EntryInfo entryInfo;

    private ImageView feedImage;
    private ImageView entryImage;
    private DialogEntryitemBinding binding;

    private TextView feedTitle;
    private TextView entryTitle;
    private TextView entryDate;
    private TextView entryDesc;

    private MenuItem bookmarkButton;
    private MenuItem shareButton;

    private MaterialButton readingButton;
    private MaterialButton playingButton;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Theme_App_FullScreenDialog);
    }

    public EntryItemDialog(EntryItemDialogClickInterface entryItemDialogClickInterface, EntryInfo entryInfo) {
        this.entryItemDialogClickInterface = entryItemDialogClickInterface;
        this.entryInfo = entryInfo;
    }

    @SuppressLint("SimpleDateFormat")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogEntryitemBinding.inflate(inflater, container, false);

        feedImage = binding.entryDialogFeedImageView;
        entryImage = binding.entryDialogEntryImageView;

        feedTitle = binding.entryDialogFeedTitleTextView;
        entryTitle = binding.entryDialogEntryTitleTextView;
        entryDate = binding.entryDialogDateTextView;
        entryDesc = binding.entryDialogDescTextView;

        bookmarkButton = binding.entryDialogToolbar.getMenu().findItem(R.id.entryDialogBookmark);
        shareButton = binding.entryDialogToolbar.getMenu().findItem(R.id.entryDialogShare);

        readingButton = binding.entryDialogReadingButton;
        playingButton = binding.entryDialogPlayingButton;

        if (entryInfo.getFeedImageUrl() != null && !entryInfo.getFeedImageUrl().isEmpty()) {
            Picasso.get().load(entryInfo.getFeedImageUrl()).into(feedImage);
        } else {
            feedImage.setVisibility(View.GONE);
        }

        if (entryInfo.getEntryImageUrl() != null && !entryInfo.getEntryImageUrl().isEmpty()) {
            Picasso.get().load(entryInfo.getEntryImageUrl()).into(entryImage);
        } else {
            entryImage.setVisibility(View.GONE);
        }

        feedTitle.setText(entryInfo.getFeedTitle());
        entryTitle.setText(entryInfo.getEntryTitle());
        entryDate.setText(new SimpleDateFormat("EEE, d MMM yyyy").format(entryInfo.getEntryPublishedDate()));
        if (entryInfo.getEntryDescription() != null) {
            entryDesc.setText(Html.fromHtml(entryInfo.getEntryDescription(), Html.FROM_HTML_MODE_LEGACY));
        }

        if (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
        } else {
            bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
        }

        binding.entryDialogToolbar.setNavigationOnClickListener(view -> dismiss());

        binding.entryDialogToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();

                if (itemId == R.id.entryDialogBookmark) {
                    if (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) {
                        bookmarkButton.setIcon(R.drawable.ic_bookmark_filled);
                        entryItemDialogClickInterface.onBookmarkButtonClick("Y", entryInfo.getEntryId());
                        entryInfo.setBookmark("Y");
                    } else {
                        bookmarkButton.setIcon(R.drawable.ic_bookmark_outline);
                        entryItemDialogClickInterface.onBookmarkButtonClick("N", entryInfo.getEntryId());
                        entryInfo.setBookmark("N");
                    }
                    return true;
                } else if (itemId == R.id.entryDialogShare) {
                    Intent sendIntent = new Intent();
                    sendIntent.setAction(Intent.ACTION_SEND);
                    sendIntent.putExtra(Intent.EXTRA_TEXT, entryInfo.getEntryLink());
                    sendIntent.setType("text/plain");

                    Intent shareIntent = Intent.createChooser(sendIntent, null);
                    startActivity(shareIntent);
                    return true;
                }

                return false;
            }
        });

        readingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
                entryItemDialogClickInterface.onReadingButtonClick(entryInfo.getEntryId());
            }
        });

        playingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
                entryItemDialogClickInterface.onPlayingButtonClick(entryInfo.getEntryId());
            }
        });

        return binding.getRoot();
    }

    @SuppressLint("SimpleDateFormat")
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        WindowManager.LayoutParams wlp = window.getAttributes();

        wlp.gravity = Gravity.CENTER;
        wlp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        window.setAttributes(wlp);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return dialog;
    }

    public interface EntryItemDialogClickInterface {
        void onBookmarkButtonClick(String bool, long id);
        void onPlayingButtonClick(long entryId);
        void onReadingButtonClick(long entryId);
    }
}
