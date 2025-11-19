package xiangze.mmu.rssnewsreader.ui.allentries;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import xiangze.mmu.rssnewsreader.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.ChipGroup;

import java.util.List;

public class FilterBottomSheet extends BottomSheetDialogFragment {

    private FilterClickInterface filterClickInterface;
    private String sort;
    private String filter;

    public FilterBottomSheet(FilterClickInterface filterClickInterface, String sort, String filter) {
        this.filterClickInterface = filterClickInterface;
        this.sort = sort;
        this.filter = filter;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.dialog_bottomfilter, container, false);

        ChipGroup sortChipGroup = view.findViewById(R.id.sortChipGroup);
        ChipGroup filterChipGroup = view.findViewById(R.id.filterChipGroup);

        if (sort.equals("oldest")) {
            sortChipGroup.check(R.id.sort_oldest);
        } else {
            sortChipGroup.check(R.id.sort_latest);
        }

        switch (filter) {
            case "bookmark":
                filterChipGroup.check(R.id.filter_bookmark);
                break;
            case "read":
                filterChipGroup.check(R.id.filter_read);
                break;
            case "unread":
                filterChipGroup.check(R.id.unread);
                break;
            default:
                filterChipGroup.check(R.id.filter_all);
                break;
        }

        sortChipGroup.setOnCheckedStateChangeListener(new ChipGroup.OnCheckedStateChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull ChipGroup group, @NonNull List<Integer> checkedIds) {
                if (checkedIds.contains(R.id.sort_oldest)) {
                    filterClickInterface.onSortChange("oldest");
                } else {
                    filterClickInterface.onSortChange("latest");
                }
            }
        });

        filterChipGroup.setOnCheckedStateChangeListener(new ChipGroup.OnCheckedStateChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull ChipGroup group, @NonNull List<Integer> checkedIds) {
                if (checkedIds.contains(R.id.filter_bookmark)) {
                    filterClickInterface.onFilterChange("bookmark");
                }
                else if (checkedIds.contains(R.id.filter_read)) {
                    filterClickInterface.onFilterChange("read");
                } else if (checkedIds.contains(R.id.filter_unread)) {
                    filterClickInterface.onFilterChange("unread");
                } else {
                    filterClickInterface.onFilterChange("all");
                }
            }
        });

        return view;
    }

    public interface FilterClickInterface {
        void onFilterChange(String filter);
        void onSortChange(String sort);
    }
}
