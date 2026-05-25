package xiangze.mmu.rssnewsreader.ui.main;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import androidx.appcompat.widget.LinearLayoutCompat;

public class LayoutBehavior extends AppBarLayout.ScrollingViewBehavior {

    private int marginBottom = 0;

    public LayoutBehavior() {
        super();
    }

    public LayoutBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean layoutDependsOn(CoordinatorLayout parent, View child, View dependency) {
        return super.layoutDependsOn(parent, child, dependency)
                || (dependency instanceof LinearLayoutCompat && dependency.getId() == xiangze.mmu.rssnewsreader.R.id.bottom_container);
    }

    @Override
    public boolean onDependentViewChanged(CoordinatorLayout parent, View child, View dependency) {
        return super.onDependentViewChanged(parent, child, dependency)
                | /* [!] single pipe is required */ updateBottomMarginIfNeeded(child, dependency);
    }

    private boolean updateBottomMarginIfNeeded(View child, View dependency) {
        if (dependency instanceof LinearLayoutCompat && dependency.getId() == xiangze.mmu.rssnewsreader.R.id.bottom_container
                && dependency.getMeasuredHeight() != this.marginBottom) {
            this.marginBottom = dependency.getMeasuredHeight();
            CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) child.getLayoutParams();
            lp.bottomMargin = this.marginBottom;
            child.setLayoutParams(lp);
            return true;
        }
        return false;
    }
}
