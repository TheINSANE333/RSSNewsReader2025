package xiangze.mmu.rssnewsreader.ui.feed;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class FeedPagerAdapter extends FragmentStateAdapter {

    private final android.os.Bundle fragmentArguments;

    public FeedPagerAdapter(@NonNull Fragment fragment) {
        super(fragment);
        this.fragmentArguments = fragment.getArguments();
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 1) {
            return new ManageFeedFragment();
        }
        AddFeedFragment addFeedFragment = new AddFeedFragment();
        if (fragmentArguments != null) {
            addFeedFragment.setArguments(fragmentArguments);
        }
        return addFeedFragment;
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}
