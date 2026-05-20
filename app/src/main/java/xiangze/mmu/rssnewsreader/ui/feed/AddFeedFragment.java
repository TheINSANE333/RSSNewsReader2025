package xiangze.mmu.rssnewsreader.ui.feed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.databinding.FragmentAddFeedBinding;
import xiangze.mmu.rssnewsreader.service.rss.RssFeed;
import xiangze.mmu.rssnewsreader.service.rss.RssItem;
import xiangze.mmu.rssnewsreader.ui.loginwebview.LoginWebViewActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class AddFeedFragment extends Fragment implements FeedViewModel.AddFeedCallback {

    private FragmentAddFeedBinding binding;
    private FeedViewModel feedViewModel;
    private LinearProgressIndicator loading;
    private RssFeed feed;

    private final ActivityResultLauncher<Intent> activityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    feedViewModel.addNewFeed(feed);
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        feedViewModel = new ViewModelProvider(this).get(FeedViewModel.class);
        binding = FragmentAddFeedBinding.inflate(inflater, container, false);

        loading = binding.progressBar;

        feedViewModel.getIsLoading().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean != null) {
                    if (aBoolean) {
                        loading.setVisibility(View.VISIBLE);
                    } else {
                        loading.setVisibility(View.INVISIBLE);
                    }
                }
            }
        });

        feedViewModel.getToastMessage().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s != null && !s.isEmpty()) {
                    Snackbar.make(requireView(), s, Snackbar.LENGTH_SHORT).show();
                    feedViewModel.resetToastMessage();
                }
            }
        });

        binding.addFeedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    InputMethodManager imm = (InputMethodManager)getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(getActivity().getCurrentFocus().getWindowToken(), 0);
                } catch (Exception e) {
                    Log.d("test", e.getMessage());
                }
                String link = binding.rssLink.getEditText().getText().toString();
                feedViewModel.checkNewFeed(link, AddFeedFragment.this);
            }
        });

        binding.discoverButton.setOnClickListener(v -> {
            androidx.navigation.Navigation.findNavController(requireView())
                    .navigate(R.id.discoveryFragment);
        });

        return binding.getRoot();
    }

    @Override
    public void openLoginDialog(RssFeed feed) {
        this.feed = feed;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.login_request_confirmation)
                .setIcon(R.drawable.ic_alert)
                .setMessage(R.string.login_message)
                .setNeutralButton(R.string.no, (dialogInterface, i) -> {
                    feedViewModel.addNewFeed(feed);
                })
                .setPositiveButton(R.string.yes, (dialogInterface, i) -> {
                    if (feed.getRssItems().size() > 0) {
                        RssItem rssItem = feed.getRssItems().get(0);
                        Context context = getContext();
                        if (context != null && rssItem.getLink() != null && !rssItem.getLink().isEmpty()) {
                            Intent intent = new Intent(context, LoginWebViewActivity.class);
                            intent.putExtra("link", rssItem.getLink());
                            activityResultLauncher.launch(intent);
                        } else {
                            feedViewModel.addNewFeed(feed);
                        }
                    } else {
                        feedViewModel.addNewFeed(feed);
                    }
                })
                .show();
    }
}
