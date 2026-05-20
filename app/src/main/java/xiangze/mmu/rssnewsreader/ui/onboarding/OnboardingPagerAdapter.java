package xiangze.mmu.rssnewsreader.ui.onboarding;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import xiangze.mmu.rssnewsreader.R;

public class OnboardingPagerAdapter extends FragmentStateAdapter {

    public OnboardingPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return OnboardingPageFragment.newInstance(
                        "Welcome to RSS Reader",
                        "Stay updated with your favorite news sources in one place.",
                        R.drawable.ic_rss_feed);
            case 1:
                return OnboardingPageFragment.newInstance(
                        "AI Powered Insights",
                        "Summarize long articles and translate content into your preferred language instantly.",
                        R.drawable.ic_summary);
            case 2:
                return OnboardingPageFragment.newInstance(
                        "Discover & Add Feeds",
                        "Easily add news sources by pasting their RSS URL or searching for them on the web.",
                        R.drawable.ic_web_search);
            case 3:
                return OnboardingPageFragment.newInstance(
                        "Listen on the Go",
                        "Convert articles to speech and listen while you work or travel.",
                        R.drawable.ic_speech);
            case 4:
                return OnboardingPageFragment.newInstance(
                        "Smart Organization",
                        "Group your feeds, bookmark important stories, and enjoy a clutter-free reading experience.",
                        R.drawable.ic_folder);
            default:
                return null;
        }
    }

    @Override
    public int getItemCount() {
        return 5;
    }
}
