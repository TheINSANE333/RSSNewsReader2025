package xiangze.mmu.rssnewsreader.ui.feed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.databinding.FragmentManageFeedBinding;
import xiangze.mmu.rssnewsreader.ui.feedsetting.FeedSettingDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ManageFeedFragment extends Fragment implements FeedItemAdapter.FeedItemClickInterface, ManageFeedListener {

    private FragmentManageFeedBinding binding;
    private FeedViewModel feedViewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        feedViewModel = new ViewModelProvider(this).get(FeedViewModel.class);
        binding = FragmentManageFeedBinding.inflate(inflater, container, false);

        RecyclerView feedsRecycler = binding.feedsRecycler;
        feedsRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        feedsRecycler.setHasFixedSize(true);
        FeedItemAdapter adapter = new FeedItemAdapter(this);
        feedsRecycler.setAdapter(adapter);

        LinearLayoutCompat emptyFeedContainer = binding.emptyFeedContainer;

        feedViewModel.getToastMessage().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s != null && !s.isEmpty()) {
                    Snackbar.make(requireView(), s, Snackbar.LENGTH_SHORT).show();
                    feedViewModel.resetToastMessage();
                }
            }
        });

        feedViewModel.getAllFeeds().observe(getViewLifecycleOwner(), new Observer<List<Feed>>() {
            @Override
            public void onChanged(List<Feed> feeds) {
                if (feeds.size() == 0) {
                    feedsRecycler.setVisibility(View.GONE);
                    emptyFeedContainer.setVisibility(View.VISIBLE);
                } else {
                    emptyFeedContainer.setVisibility(View.GONE);
                    feedsRecycler.setVisibility(View.VISIBLE);
                }
                adapter.submitList(feeds);
            }
        });

        return binding.getRoot();
    }

    @Override
    public void onReExtract(long feedId) {
        ReloadDialog dialog = new ReloadDialog(this, feedId, R.string.reload_confirmation, R.string.reload_all_message);
        dialog.show(getChildFragmentManager(), ReloadDialog.TAG);
    }

    @Override
    public void onUpdate(Feed feed) {
        Bundle args = new Bundle();
        args.putString("imageUrl", feed.getImageUrl());
        args.putLong("feedId", feed.getId());
        args.putString("title", feed.getTitle());
        args.putString("description", feed.getDescription());
        args.putString("language", feed.getLanguage());
        args.putString("link", feed.getLink());
        args.putFloat("ttsSpeechRate", feed.getTtsSpeechRate());
        args.putInt("delayTime", feed.getDelayTime());
        args.putBoolean("autoSummarize", feed.isAutoSummarize());
        args.putBoolean("autoTranslate", feed.isAutoTranslate());
        FeedSettingDialog dialog = new FeedSettingDialog();
        dialog.setArguments(args);
        dialog.show(getChildFragmentManager(), FeedSettingDialog.TAG);
    }

    @Override
    public void onDelete(Feed feed) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.delete_feed_message)
                .setIcon(R.drawable.ic_alert)
                .setMessage(R.string.delete_feed_confirmation)
                .setNeutralButton(R.string.cancel, (dialogInterface, i) -> {

                })
                .setPositiveButton(R.string.yes, (dialogInterface, i) -> {
                    feedViewModel.deleteFeed(feed);
                })
                .show();
    }

    @Override
    public void reExtract(long feedId) {
        feedViewModel.reExtractFeed(feedId);
    }
}
