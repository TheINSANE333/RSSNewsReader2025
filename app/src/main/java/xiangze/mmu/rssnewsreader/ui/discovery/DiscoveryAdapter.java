package xiangze.mmu.rssnewsreader.ui.discovery;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;
import xiangze.mmu.rssnewsreader.R;

public class DiscoveryAdapter extends ListAdapter<DiscoveryFeed, DiscoveryAdapter.ViewHolder> {

    private final OnAddClickListener listener;

    public interface OnAddClickListener {
        void onAddClick(DiscoveryFeed feed);
    }

    public DiscoveryAdapter(OnAddClickListener listener) {
        super(new DiffUtil.ItemCallback<DiscoveryFeed>() {
            @Override
            public boolean areItemsTheSame(@NonNull DiscoveryFeed oldItem, @NonNull DiscoveryFeed newItem) {
                return oldItem.getUrl().equals(newItem.getUrl());
            }

            @Override
            public boolean areContentsTheSame(@NonNull DiscoveryFeed oldItem, @NonNull DiscoveryFeed newItem) {
                return oldItem.getTitle().equals(newItem.getTitle()) && oldItem.getUrl().equals(newItem.getUrl());
            }
        });
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_discovery_feed, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DiscoveryFeed feed = getItem(position);
        holder.title.setText(feed.getTitle());
        holder.description.setText(feed.getUrl()); // Show URL as description
        
        String favicon = "https://www.google.com/s2/favicons?sz=64&domain_url=" + feed.getUrl();
        Picasso.get()
                .load(favicon)
                .placeholder(R.drawable.ic_folder)
                .into(holder.icon);
                
        holder.addButton.setOnClickListener(v -> listener.onAddClick(feed));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView title, description;
        MaterialButton addButton;

        ViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.feedIcon);
            title = view.findViewById(R.id.feedTitle);
            description = view.findViewById(R.id.feedDescription);
            addButton = view.findViewById(R.id.addButton);
        }
    }
}
