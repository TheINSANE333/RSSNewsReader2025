package xiangze.mmu.rssnewsreader.ui.allentries;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import timber.log.Timber;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.model.EntryInfo;

import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class EntryItemAdapter extends ListAdapter<EntryInfo, EntryItemAdapter.EntryItemHolder> {

    EntryItemClickInterface entryItemClickInterface;
    private Context context;
    private boolean isSelectionMode = false;
    
    private final boolean autoTranslateEnabled;
    private final boolean autoSummarizeEnabled;

    public EntryItemAdapter(EntryItemClickInterface entryItemClickInterface, boolean autoTranslateEnabled, boolean autoSummarizeEnabled) {
        super(DIFF_CALLBACK);
        this.entryItemClickInterface = entryItemClickInterface;
        this.autoTranslateEnabled = autoTranslateEnabled;
        this.autoSummarizeEnabled = autoSummarizeEnabled;
    }

    private static boolean hasTranslation(EntryInfo e) {
        return e.isHasTranslated();
    }

    private static boolean hasSummarization(EntryInfo e) {
        return e.isHasSummarized();
    }

    private static final DiffUtil.ItemCallback<EntryInfo> DIFF_CALLBACK = new DiffUtil.ItemCallback<EntryInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull EntryInfo oldItem, @NonNull EntryInfo newItem) {
            return oldItem.getEntryId() == newItem.getEntryId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull EntryInfo oldE, @NonNull EntryInfo newE) {
            boolean oldTranslated = oldE.isHasTranslated();
            boolean newTranslated = newE.isHasTranslated();

            boolean oldSummarized = oldE.isHasSummarized();
            boolean newSummarized = newE.isHasSummarized();

            boolean oldExtracted = oldE.isHasContent();
            boolean newExtracted = newE.isHasContent();

            boolean sameBookmark = Objects.equals(oldE.getBookmark(), newE.getBookmark());
            boolean sameVisited  = Objects.equals(oldE.getVisitedDate(), newE.getVisitedDate());

            return sameBookmark && sameVisited &&
                    (oldTranslated == newTranslated) &&
                    (oldSummarized == newSummarized) &&
                    (oldExtracted  == newExtracted);
        }
    };

    @NonNull
    @Override
    public EntryItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.entry_item, parent, false);
        context = parent.getContext();
        return new EntryItemHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryItemAdapter.EntryItemHolder holder, int position) {
        EntryInfo currentEntry = getItem(position);

        boolean isLoading = currentEntry.isLoading();
        Timber.d("onBindViewHolder: Position " + position + " - isLoading: " + isLoading);

        holder.bind(currentEntry);
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        entryItemClickInterface.onSelectionModeChanged(isSelectionMode);
        notifyDataSetChanged();
    }

    class EntryItemHolder extends RecyclerView.ViewHolder {

        private TextView textViewEntryTitle, textViewFeedTitle, textViewEntryPubDate, textViewEntrySummary;
        private ImageView imageViewEntryImage, imageViewFeedImage;
        private MaterialButton bookmarkButton;
        private MaterialButton moreButton;
        private CheckBox selectedCheckbox;
        private View view;

        public EntryItemHolder(@NonNull View itemView) {
            super(itemView);
            textViewEntryTitle = itemView.findViewById(R.id.entryTitle);
            textViewFeedTitle = itemView.findViewById(R.id.feedTitle);
            textViewEntryPubDate = itemView.findViewById(R.id.entryPubDate);
            textViewEntrySummary = itemView.findViewById(R.id.entrySummary);
            imageViewFeedImage = itemView.findViewById(R.id.feedImage);
            imageViewEntryImage = itemView.findViewById(R.id.entryImage);
            bookmarkButton = itemView.findViewById(R.id.bookmark_button);
            moreButton = itemView.findViewById(R.id.more_button);
            selectedCheckbox = itemView.findViewById(R.id.selectedCheckbox);
            view = itemView;
        }

        public void bind(EntryInfo entryInfo) {
            TextView statusView = view.findViewById(R.id.extractionStatus);

            textViewEntryTitle.setTextColor(textViewEntryPubDate.getTextColors());
            textViewEntryTitle.setText(entryInfo.getEntryTitle());
            textViewFeedTitle.setText(entryInfo.getFeedTitle());

            String pubDate = covertTimeToText(entryInfo.getEntryPublishedDate());
            textViewEntryPubDate.setText(pubDate);

            // Bind Summary Snippet
            String summary = entryInfo.getSummarizedSnippet();
            if (!TextUtils.isEmpty(summary)) {
                textViewEntrySummary.setText(summary.trim());
                textViewEntrySummary.setVisibility(View.VISIBLE);
            } else {
                textViewEntrySummary.setVisibility(View.GONE);
            }

            if (entryInfo.getVisitedDate() != null) {
                textViewEntryTitle.setTextColor(ContextCompat.getColor(context, R.color.unreadText));
                view.setAlpha(0.9f);
            } else {
                textViewEntryTitle.setTextColor(ContextCompat.getColor(context, R.color.text));
                view.setAlpha(1.0f);
            }

            if (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) {
                bookmarkButton.setIcon(ContextCompat.getDrawable(context, R.drawable.ic_bookmark_outline));
                bookmarkButton.setIconTint(ContextCompat.getColorStateList(context, R.color.text));
            } else {
                bookmarkButton.setIcon(ContextCompat.getDrawable(context, R.drawable.ic_bookmark_filled));
                bookmarkButton.setIconTint(ContextCompat.getColorStateList(context, R.color.primary));
            }
            bookmarkButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) {
                        entryItemClickInterface.onBookmarkButtonClick("Y", entryInfo.getEntryId());
                    } else {
                        entryItemClickInterface.onBookmarkButtonClick("N", entryInfo.getEntryId());
                    }
                }
            });

            moreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    entryItemClickInterface.onMoreButtonClick(entryInfo.getEntryId(), entryInfo.getEntryLink(), entryInfo.getVisitedDate() == null);
                }
            });

            if (TextUtils.isEmpty(entryInfo.getEntryImageUrl())) {
                imageViewEntryImage.setVisibility(View.GONE);
            } else {
                imageViewEntryImage.setVisibility(View.VISIBLE);
                Picasso.get().load(entryInfo.getEntryImageUrl()).into(imageViewEntryImage);
            }
            if (TextUtils.isEmpty(entryInfo.getFeedImageUrl())) {
                imageViewFeedImage.setVisibility(View.GONE);
            } else {
                imageViewFeedImage.setVisibility(View.VISIBLE);
                Picasso.get().load(entryInfo.getFeedImageUrl()).into(imageViewFeedImage);
            }

            selectedCheckbox.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
            selectedCheckbox.setChecked(entryInfo.isSelected());
            selectedCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                    if (isSelectionMode) {
                        entryInfo.setSelected(isChecked);
                        entryItemClickInterface.onItemSelected(entryInfo);
                    }
                }
            });

            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    entryItemClickInterface.onEntryClick(entryInfo);
                }
            });

            view.setOnLongClickListener(new View.OnLongClickListener() {
                @SuppressLint("NotifyDataSetChanged")
                @Override
                public boolean onLongClick(View view) {
                    isSelectionMode = true;
                    entryItemClickInterface.onSelectionModeChanged(true);
                    notifyDataSetChanged();
                    return true;
                }
            });

            boolean hasContent      = entryInfo.isHasContent();
            int priority            = entryInfo.getPriority();
            boolean hasOriginalHtml = entryInfo.isHasOriginalHtml();
            boolean hasTranslatedHtml = entryInfo.isHasTranslated();
            boolean hasSummarizedHtml = entryInfo.isHasSummarized();

            statusView.setText("");

            if (autoTranslateEnabled && autoSummarizeEnabled) {
                if (hasOriginalHtml && hasTranslatedHtml && hasSummarizedHtml) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                    statusView.setVisibility(View.VISIBLE);
                } else if (hasContent) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                    statusView.setVisibility(View.VISIBLE);
                }else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                    statusView.setVisibility(View.VISIBLE);
                }
            } else if (autoSummarizeEnabled) {
                if (hasOriginalHtml && hasSummarizedHtml) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                    statusView.setVisibility(View.VISIBLE);
                } else if (hasContent) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                    statusView.setVisibility(View.VISIBLE);
                }else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                    statusView.setVisibility(View.VISIBLE);
                }
            } else if (autoTranslateEnabled) {
                if (hasOriginalHtml && hasTranslatedHtml) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                    statusView.setVisibility(View.VISIBLE);
                } else if (hasContent) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                    statusView.setVisibility(View.VISIBLE);
                }else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                    statusView.setVisibility(View.VISIBLE);
                }
            } else {
                if (hasContent) {
                    statusView.setBackgroundResource(R.drawable.status_dot_green);
                    statusView.setVisibility(View.VISIBLE);
                } else if (priority > 0) {
                    statusView.setBackgroundResource(R.drawable.status_dot_yellow);
                    statusView.setVisibility(View.VISIBLE);
                } else {
                    statusView.setBackgroundResource(R.drawable.status_dot_red);
                    statusView.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    public interface EntryItemClickInterface {
        void onEntryClick(EntryInfo entryInfo);

        void onMoreButtonClick(long entryId, String link, boolean unread);

        void onBookmarkButtonClick(String bool, long id);

        void onSelectionModeChanged(boolean isSelectionMode);

        void onItemSelected(EntryInfo entryInfo);
    }

    public String covertTimeToText(Date date) {

        String convTime = null;
        String suffix = "ago";
        Date nowTime = new Date();

        long dateDiff = nowTime.getTime() - date.getTime();

        long second = TimeUnit.MILLISECONDS.toSeconds(dateDiff);
        long minute = TimeUnit.MILLISECONDS.toMinutes(dateDiff);
        long hour = TimeUnit.MILLISECONDS.toHours(dateDiff);
        long day = TimeUnit.MILLISECONDS.toDays(dateDiff);

        if (second < 60) {
            convTime = second + " seconds " + suffix;
        } else if (minute < 60) {
            convTime = minute + " minutes " + suffix;
        } else if (hour < 24) {
            convTime = hour + " hours " + suffix;
        } else if (day >= 7) {
            if (day > 360) {
                convTime = (day / 360) + " years " + suffix;
            } else if (day > 30) {
                convTime = (day / 30) + " months " + suffix;
            } else {
                convTime = (day / 7) + " week " + suffix;
            }
        } else {
            convTime = day + " days " + suffix;
        }

        return convTime;
    }
}
