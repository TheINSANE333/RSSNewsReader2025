package xiangze.mmu.rssnewsreader.ui.discovery;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import dagger.hilt.android.AndroidEntryPoint;
import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.databinding.FragmentDiscoveryBinding;

@AndroidEntryPoint
public class DiscoveryFragment extends Fragment {

    private FragmentDiscoveryBinding binding;
    private DiscoveryViewModel discoveryViewModel;
    private DiscoveryAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentDiscoveryBinding.inflate(inflater, container, false);
        discoveryViewModel = new ViewModelProvider(this).get(DiscoveryViewModel.class);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new DiscoveryAdapter(discoveryFeed -> {
            NavController navController = Navigation.findNavController(requireView());
            Bundle bundle = new Bundle();
            bundle.putString("recommended_url", discoveryFeed.getUrl());
            navController.navigate(R.id.feedFragment, bundle);
        });

        binding.searchResultsRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.searchResultsRecycler.setAdapter(adapter);

        binding.searchBtn.setOnClickListener(v -> performSearch());
        binding.searchQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });

        discoveryViewModel.getSearchResults().observe(getViewLifecycleOwner(), results -> {
            adapter.submitList(results);
            binding.emptySearchText.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            if (results.isEmpty()) {
                binding.emptySearchText.setText("No feeds found for this search.");
            }
        });

        discoveryViewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
            binding.searchProgress.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            binding.searchBtn.setEnabled(!isLoading);
        });
    }

    private void performSearch() {
        String query = binding.searchQuery.getText().toString().trim();
        if (!query.isEmpty()) {
            discoveryViewModel.searchFeeds(query);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
