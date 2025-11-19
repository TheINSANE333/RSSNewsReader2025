package xiangze.mmu.rssnewsreader.ui.main;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

public class NavigationFeedItemAdapter extends ListAdapter<Feed, NavigationFeedItemAdapter.FeedItemHolder> {

    private FeedItemClickInterface feedItemClickInterface;

    protected NavigationFeedItemAdapter(FeedItemClickInterface feedItemClickInterface) {
        super(DIFF_CALLBACK);
        this.feedItemClickInterface = feedItemClickInterface;
    }

    private static final DiffUtil.ItemCallback<Feed> DIFF_CALLBACK = new DiffUtil.ItemCallback<Feed>() {
        @Override
        public boolean areItemsTheSame(@NonNull Feed oldItem, @NonNull Feed newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull Feed oldItem, @NonNull Feed newItem) {
            return oldItem.equals(newItem);
        }
    };

    @NonNull
    @Override
    public FeedItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.navigation_feed_item, parent, false);
        return new FeedItemHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull FeedItemHolder holder, int position) {
        Feed currentFeed = getItem(position);
        holder.bind(currentFeed);
    }

    class FeedItemHolder extends RecyclerView.ViewHolder {

        private MaterialButton navigationButton;

        public FeedItemHolder(@NonNull View itemView) {
            super(itemView);
            navigationButton = itemView.findViewById(R.id.navigationFeedButton);
        }

        public void bind(Feed feed) {
            navigationButton.setText(feed.getTitle());
            if (!TextUtils.isEmpty(feed.getImageUrl())) {
                Picasso.get().load(feed.getImageUrl()).into(new Target() {
                    @Override
                    public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                        Drawable drawable = new BitmapDrawable(navigationButton.getContext().getResources(), bitmap);
                        navigationButton.setIcon(drawable);
                    }

                    @Override
                    public void onBitmapFailed(Exception e, Drawable errorDrawable) {

                    }

                    @Override
                    public void onPrepareLoad(Drawable placeHolderDrawable) {

                    }
                });
            }
            navigationButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    feedItemClickInterface.onClick(feed.getId(), feed.getTitle());
                }
            });
        }
    }

    interface FeedItemClickInterface {
        void onClick(long id, String feedTitle);
    }
}
