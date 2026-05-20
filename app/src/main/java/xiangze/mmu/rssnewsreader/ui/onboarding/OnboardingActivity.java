package xiangze.mmu.rssnewsreader.ui.onboarding;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayoutMediator;
import xiangze.mmu.rssnewsreader.databinding.ActivityOnboardingBinding;
import xiangze.mmu.rssnewsreader.ui.main.MainActivity;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import javax.inject.Inject;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class OnboardingActivity extends AppCompatActivity {

    private ActivityOnboardingBinding binding;
    
    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        OnboardingPagerAdapter adapter = new OnboardingPagerAdapter(this);
        binding.viewPager.setAdapter(adapter);

        new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> {
            // No text needed for dots
        }).attach();

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (position == adapter.getItemCount() - 1) {
                    binding.finishButton.setText("Get Started");
                } else {
                    binding.finishButton.setText("Next");
                }
            }
        });

        binding.finishButton.setOnClickListener(v -> {
            int current = binding.viewPager.getCurrentItem();
            if (current < adapter.getItemCount() - 1) {
                binding.viewPager.setCurrentItem(current + 1);
            } else {
                completeOnboarding();
            }
        });

        binding.skipButton.setOnClickListener(v -> completeOnboarding());
    }

    private void completeOnboarding() {
        sharedPreferencesRepository.setFirstLaunch(false);
        startActivity(new Intent(OnboardingActivity.this, MainActivity.class));
        finish();
    }
}
