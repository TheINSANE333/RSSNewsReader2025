package xiangze.mmu.rssnewsreader.ui.feed;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class FeedPagerAdapter extends FragmentStateAdapter {

    public FeedPagerAdapter(@NonNull Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 1) {
            return new ManageFeedFragment();
        }
        return new AddFeedFragment();
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}
