package xiangze.mmu.rssnewsreader.ui.help;

import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.databinding.FragmentHelpBinding;
import com.google.android.material.button.MaterialButton;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class HelpFragment extends Fragment {
    private FragmentHelpBinding binding;
    private boolean[] isExpanded = new boolean[15];

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHelpBinding.inflate(inflater, container, false);

        CardView[] cardViews = {
                binding.cardView1, binding.cardView2, binding.cardView3, binding.cardView4,
                binding.cardView5, binding.cardView6, binding.cardView7, binding.cardView8,
                binding.cardView9, binding.cardView10, binding.cardView11, binding.cardView12,
                binding.cardView13, binding.cardView14, binding.cardView15
        };
        ImageView[] arrowImageViews = {
                binding.arrowImageView1, binding.arrowImageView2, binding.arrowImageView3, binding.arrowImageView4,
                binding.arrowImageView5, binding.arrowImageView6, binding.arrowImageView7, binding.arrowImageView8,
                binding.arrowImageView9, binding.arrowImageView10, binding.arrowImageView11, binding.arrowImageView12,
                binding.arrowImageView13, binding.arrowImageView14, binding.arrowImageView15
        };
        LinearLayoutCompat[] descTextViews = {
                binding.description1, binding.description2, binding.description3, binding.description4,
                binding.description5, binding.description6, binding.description7, binding.description8,
                binding.description9, binding.description10, binding.description11, binding.description12,
                binding.description13, binding.description14, binding.description15
        };

        for (int i = 0; i < cardViews.length; i++) {
            final int index = i;
            cardViews[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    TransitionManager.beginDelayedTransition((ViewGroup) cardViews[index].getParent(), new AutoTransition());
                    if (isExpanded[index]) {
                        descTextViews[index].setVisibility(View.GONE);
                        arrowImageViews[index].setRotation(0);
                        isExpanded[index] = false;
                    } else {
                        descTextViews[index].setVisibility(View.VISIBLE);
                        arrowImageViews[index].setRotation(180);
                        isExpanded[index] = true;
                    }
                }
            });
        }

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NavController navController = Navigation.findNavController(view);

        MaterialButton[] toFeedButtons = {
                binding.toFeedButton1, binding.toFeedButton2, binding.toFeedButton3, binding.toFeedButton4
        };

        for (MaterialButton toFeedButton : toFeedButtons) {
            toFeedButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    NavOptions navOptions = new NavOptions.Builder()
                            .setPopUpTo(R.id.allEntriesFragment, false)
                            .build();
                    navController.navigate(R.id.feedFragment, null, navOptions);
                }
            });
        }

        MaterialButton[] toSettingsButtons = {
                binding.toSettingsButton, binding.toSettingsButton2, binding.toSettingsButton3, binding.toSettingsButton4, binding.toSettingsButton5
        };

        for (MaterialButton toSettingsButton : toSettingsButtons) {
            toSettingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    NavOptions navOptions = new NavOptions.Builder()
                            .setPopUpTo(R.id.allEntriesFragment, false)
                            .build();
                    navController.navigate(R.id.settingsFragment, null, navOptions);
                }
            });
        }
    }
}
