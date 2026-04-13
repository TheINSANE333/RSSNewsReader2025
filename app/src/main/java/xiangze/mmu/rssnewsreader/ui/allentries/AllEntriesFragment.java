package xiangze.mmu.rssnewsreader.ui.allentries;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.entry.EntryRepository;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlayer;
import xiangze.mmu.rssnewsreader.service.tts.TtsPlaylist;
import xiangze.mmu.rssnewsreader.databinding.FragmentAllEntriesBinding;

import xiangze.mmu.rssnewsreader.model.EntryInfo;
import xiangze.mmu.rssnewsreader.service.util.AutoSummarizer;
import xiangze.mmu.rssnewsreader.service.util.AutoTranslator;
import xiangze.mmu.rssnewsreader.service.util.TextUtil;
import xiangze.mmu.rssnewsreader.ui.chat.ChatActivity;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewActivity;

import com.google.android.material.snackbar.Snackbar;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewViewModel;

@AndroidEntryPoint
public class AllEntriesFragment extends Fragment implements EntryItemAdapter.EntryItemClickInterface, FilterBottomSheet.FilterClickInterface, EntryItemDialog.EntryItemDialogClickInterface {

    private static final String TAG = AllEntriesFragment.class.getSimpleName();
    private FragmentAllEntriesBinding binding;
    private SwipeRefreshLayout swipeRefreshLayout;
    private AllEntriesViewModel allEntriesViewModel;
    private TextView unreadTextView;
    private EntryItemAdapter adapter;
    private List<EntryInfo> entries = new ArrayList<>();
    private String sortBy;
    private String filterBy = "all";
    private String title;
    private long feedId;
    private List<EntryInfo> selectedEntries = new ArrayList<>();
    private TextView selectedCountTextView;
    private ActionBar actionBar;
    @Inject
    AutoTranslator autoTranslator;
    @Inject
    AutoSummarizer autoSummarizer;
    private final AtomicBoolean autoTranslationStarted = new AtomicBoolean(false);
    private final AtomicBoolean autoSummarizationStarted = new AtomicBoolean(false);

    @Inject
    TtsPlaylist ttsPlaylist;
    @Inject
    TtsPlayer ttsPlayer;
    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;
    @Inject
    EntryRepository entryRepository;
    @Inject
    TextUtil textUtil;

    private boolean isSelectionMode = false;
    private WebViewViewModel webViewViewModel;
    private CompositeDisposable compositeDisposable;


    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentAllEntriesBinding.inflate(inflater, container, false);

        allEntriesViewModel = new ViewModelProvider(this).get(AllEntriesViewModel.class);
        unreadTextView = binding.unread;

        ConstraintLayout emptyContainer = binding.emptyContainer;
        RecyclerView entriesRecycler = binding.entriesRecycler;
        entriesRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        entriesRecycler.setHasFixedSize(true);
        boolean autoTranslate = sharedPreferencesRepository.getAutoTranslate();
        boolean autoSummarize = sharedPreferencesRepository.getAutoSummarize();
        adapter = new EntryItemAdapter(this, autoTranslate, autoSummarize);
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeChanged(int positionStart, int itemCount) {
            }

            @Override
            public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) {
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                entriesRecycler.scrollToPosition(0);

            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                entriesRecycler.scrollToPosition(0);

            }

            @Override
            public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                entriesRecycler.scrollToPosition(0);

            }
        });
        entriesRecycler.setAdapter(adapter);

        sortBy = allEntriesViewModel.getSortBy();

        swipeRefreshLayout = binding.swipeRefreshLayout;

        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                allEntriesViewModel.refreshEntries(swipeRefreshLayout);
            }
        });

        binding.deleteAllVisitedEntriesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                allEntriesViewModel.deleteAllVisitedEntries();
            }
        });

        binding.dailySummaryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showFeedSelectionDialog();
            }
        });

        final Snackbar[] progressSnackbar = {null};
        allEntriesViewModel.getIsSummarizing().observe(getViewLifecycleOwner(), isSummarizing -> {
            if (isSummarizing) {
                progressSnackbar[0] = Snackbar.make(binding.getRoot(), "Generating daily summary...", Snackbar.LENGTH_INDEFINITE);
                progressSnackbar[0].show();
            } else {
                if (progressSnackbar[0] != null) {
                    progressSnackbar[0].dismiss();
                    progressSnackbar[0] = null;
                }
            }
        });

        allEntriesViewModel.getDailySummaryResult().observe(getViewLifecycleOwner(), summary -> {
            if (summary != null && !summary.isEmpty()) {
                // Clear the "Generating..." snackbar
                Snackbar.make(binding.getRoot(), "Summary generated!", Snackbar.LENGTH_SHORT).show();
                
                // Launch ChatActivity with the summary
                Intent intent = new Intent(requireContext(), ChatActivity.class);
                intent.putExtra("initial_message", summary);
                startActivity(intent);
                
                // Reset summary result to avoid re-triggering on rotation/back
                allEntriesViewModel.resetDailySummary();
            }
        });

        allEntriesViewModel.getToastMessage().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s != null && !s.isEmpty()) {
                    Snackbar.make(requireView(), s, Snackbar.LENGTH_SHORT).show();
                    allEntriesViewModel.resetToastMessage();
                }
            }
        });

        allEntriesViewModel.getUnreadCount().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                String unread;
                if (integer != null) unread = integer + " unread";
                else unread = "0 unread";
                unreadTextView.setText(unread);
            }
        });

        allEntriesViewModel.getAllEntries().observe(getViewLifecycleOwner(), new Observer<List<EntryInfo>>() {
            @Override
            public void onChanged(List<EntryInfo> entryInfos) {
                entries = entryInfos;

                for (EntryInfo entry : entries) {
                    Log.d("ENTRY_CHECK", "Entry: " + entry.getEntryTitle() + ", FeedTitle: " + entry.getFeedTitle() + ", FeedID: " + entry.getFeedId());
                }

                if (entries.size() == 0) {
                    entriesRecycler.setVisibility(View.GONE);
                    emptyContainer.setVisibility(View.VISIBLE);
                } else {
                    emptyContainer.setVisibility(View.GONE);
                    entriesRecycler.setVisibility(View.VISIBLE);
                    if (sortBy.equals("oldest")) {
                        Collections.sort(entries, new EntryInfo.OldestComparator());
                    } else {
                        Collections.sort(entries, new EntryInfo.LatestComparator());
                    }
                }
                adapter.submitList(new ArrayList<>(entries));
            }
        });

        webViewViewModel = new ViewModelProvider(requireActivity()).get(WebViewViewModel.class);
        compositeDisposable = new CompositeDisposable();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 2. Read arguments
        if (getArguments() != null) {
            String newTitle = getArguments().getString("title");
            feedId = getArguments().getLong("id");

            if (newTitle != null) {
                title = newTitle;
                binding.filterTitle.setText(title);
                allEntriesViewModel.getEntriesByFeed(feedId, filterBy);
            }
        } else {
            title = "All feeds";
        }

        // 3. Attach LiveData observer ONCE
        observeEntries();

        // 4. Navigation setup
        setupNavigation(view);

        // 5. Menu setup
        setupMenu();

        // 6. Loading state observer
        observeLoadingState();

        // 7. Swipe-to-action setup
        setupSwipeToAction();
    }

    private void setupSwipeToAction() {
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAbsoluteAdapterPosition();
                EntryInfo entryInfo = adapter.getCurrentList().get(position);

                if (direction == ItemTouchHelper.RIGHT) {
                    // Toggle bookmark
                    String newBookmark = (entryInfo.getBookmark() == null || entryInfo.getBookmark().equals("N")) ? "Y" : "N";
                    allEntriesViewModel.updateBookmark(newBookmark, entryInfo.getEntryId());
                    adapter.notifyItemChanged(position);
                    String message = newBookmark.equals("Y") ? "Bookmarked" : "Removed from bookmarks";
                    Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
                } else if (direction == ItemTouchHelper.LEFT) {
                    // Delete entry
                    allEntriesViewModel.deleteEntry(entryInfo.getEntryId());
                    Snackbar.make(binding.getRoot(), "Entry deleted", Snackbar.LENGTH_LONG)
                            .setAction("Undo", v -> {
                                allEntriesViewModel.insertEntry(entryInfo);
                            }).show();
                }
            }

            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                android.graphics.Paint paint = new android.graphics.Paint();
                View itemView = viewHolder.itemView;

                if (dX > 0) { // Swiping Right (Bookmark)
                    paint.setColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary));
                    c.drawRect((float) itemView.getLeft(), (float) itemView.getTop(), dX, (float) itemView.getBottom(), paint);
                    
                    android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_bookmark_filled);
                    if (icon != null) {
                        int iconMargin = (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                        icon.setBounds(itemView.getLeft() + iconMargin, itemView.getTop() + iconMargin,
                                itemView.getLeft() + iconMargin + icon.getIntrinsicWidth(), itemView.getBottom() - iconMargin);
                        icon.draw(c);
                    }
                } else if (dX < 0) { // Swiping Left (Delete)
                    paint.setColor(android.graphics.Color.RED);
                    c.drawRect((float) itemView.getRight() + dX, (float) itemView.getTop(), (float) itemView.getRight(), (float) itemView.getBottom(), paint);

                    android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_cancel);
                    if (icon != null) {
                        int iconMargin = (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                        icon.setBounds(itemView.getRight() - iconMargin - icon.getIntrinsicWidth(), itemView.getTop() + iconMargin,
                                itemView.getRight() - iconMargin, itemView.getBottom() - iconMargin);
                        icon.draw(c);
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        };

        new ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.entriesRecycler);
    }

    private void observeEntries() {
        allEntriesViewModel.getAllEntries().observe(getViewLifecycleOwner(), entries -> {

            this.entries = entries;
            adapter.submitList(entries);

            // Auto-translation (single run)
            if (autoTranslator != null && autoTranslationStarted.compareAndSet(false, true)) {

                autoTranslator.runAutoTranslation(() -> {
                    adapter.submitList(new ArrayList<>(entries));
                    Log.d("AutoTranslator", "Auto translation finished");
                });
            }

            // Auto-summarization
            if (autoSummarizer != null && autoSummarizationStarted.compareAndSet(false, true)) {

                autoSummarizer.runAutoSummarization(() -> {
                    adapter.submitList(new ArrayList<>(entries));
                    Log.d("AutoSummarizer", "Auto summarization finished");
                });
            }
        });
    }

    private void setupNavigation(View view) {
        NavController navController = Navigation.findNavController(view);

        binding.goToAddFeedButton.setOnClickListener(v -> {
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.feedFragment, false)
                    .build();
            navController.navigate(R.id.feedFragment, null, navOptions);
        });
    }

    private void setupMenu() {
        requireActivity().addMenuProvider(new MenuProvider() {

            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                if (isSelectionMode) {
                    menu.clear();
                    enterSelectionMode();
                } else {
                    menuInflater.inflate(R.menu.top_app_bar_main, menu);

                    MenuItem menuItem = menu.findItem(R.id.search);
                    SearchView searchView = (SearchView) menuItem.getActionView();
                    searchView.setQueryHint("Type here to search");

                    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                        @Override
                        public boolean onQueryTextSubmit(String query) {
                            return false;
                        }

                        @Override
                        public boolean onQueryTextChange(String newText) {
                            final String query = newText.toLowerCase(Locale.ROOT);
                            final List<EntryInfo> filteredEntries = new ArrayList<>();

                            if (entries != null) {
                                for (EntryInfo entryInfo : entries) {
                                    if (entryInfo.getEntryTitle().toLowerCase(Locale.ROOT).contains(query)) {
                                        filteredEntries.add(entryInfo);
                                    }
                                }
                            }

                            adapter.submitList(filteredEntries);
                            return true;
                        }
                    });
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.filter) {
                    FilterBottomSheet filterBottomSheet =
                            new FilterBottomSheet(AllEntriesFragment.this, sortBy, filterBy);
                    filterBottomSheet.show(requireActivity().getSupportFragmentManager(), "FilterBottomSheet");
                    return true;
                }
                return false;
            }

        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void observeLoadingState() {
        webViewViewModel.getLoadingState().observe(getViewLifecycleOwner(), isLoading -> {

            Log.d(TAG, "Loading state observed in AllEntriesFragment: " + isLoading);

            if (entries != null) {
                for (EntryInfo entry : entries) {
                    entry.setLoading(isLoading);
                }
                adapter.notifyDataSetChanged();
            } else {
                Log.d(TAG, "Entries list is null in AllEntriesFragment.");
            }
        });
    }

    private void doWhenTranslationFinish(EntryInfo entryInfo, String translationRaw, String targetLanguage) {
        TextUtil.AiResponse aiRes = textUtil.parseAiResponse(translationRaw, entryInfo.getEntryTitle());

        // Use unified formatter to include markers and header
        String finalHtml = textUtil.formatAiResponseToHtml(
                aiRes.title,
                aiRes.content,
                entryInfo.getFeedTitle(),
                entryInfo.getEntryPublishedDate(),
                entryInfo.getFeedImageUrl(),
                sharedPreferencesRepository.getNight(),
                "translated-title"
        );

        // Store result in Translated fields, NOT original html fields
        webViewViewModel.updateTranslatedHtml(finalHtml, entryInfo.getEntryId());
        entryRepository.updateTranslatedHtml(finalHtml, entryInfo.getEntryId());

        final String translatedContent = textUtil.extractHtmlContent(finalHtml, "--####--");

        webViewViewModel.updateTranslated(translatedContent, entryInfo.getEntryId());
        entryRepository.updateTranslatedText(translatedContent, entryInfo.getEntryId());

        sharedPreferencesRepository.setIsTranslatedView(entryInfo.getEntryId(), true);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Entry updatedEntry = entryRepository.getEntryById(entryInfo.getEntryId());
            String contentToSpeak = (updatedEntry != null) ? updatedEntry.getTranslated() : null;

            if (contentToSpeak != null && !contentToSpeak.trim().isEmpty()) {
                Log.d("AllEntriesFragment", "Triggering TTS with translated content");

                boolean isInWebView = sharedPreferencesRepository.getCurrentReadingEntryId() == entryInfo.getEntryId();
                boolean isTranslatedView = sharedPreferencesRepository.getIsTranslatedView(entryInfo.getEntryId());

                if (isInWebView && isTranslatedView) {
                    ttsPlayer.extract(entryInfo.getEntryId(), entryInfo.getFeedId(), contentToSpeak, targetLanguage);
                } else {
                    Log.d("AllEntriesFragment", "TTS extract skipped (not current or not translated view)");
                }
            } else {
                Log.w("AllEntriesFragment", "Translated content is empty or missing");
            }
        }, 500);
    }

    private void translate(EntryInfo entryInfo) {
        long entryId = entryInfo.getEntryId();

        // 1. Check if already translated or processing
        if (AutoTranslator.isProcessing(entryId)) {
            Toast.makeText(requireContext(), "Translation is already in progress...", Toast.LENGTH_SHORT).show();
            return;
        }

        String translatedHtml = webViewViewModel.getTranslatedHtmlById(entryId);
        if (translatedHtml != null && !translatedHtml.trim().isEmpty() && translatedHtml.contains("translated-title")) {
            Toast.makeText(requireContext(), "Article is already translated.", Toast.LENGTH_SHORT).show();
            return;
        }

        String html = webViewViewModel.getOriginalHtmlById(entryId);
        if (html == null || html.trim().isEmpty()) {
            html = webViewViewModel.getHtmlById(entryInfo.getEntryId());
        }
        
        if (html == null || html.trim().isEmpty()) return;
        final String sourceHtml = html; // Make it effectively final for lambdas
        Log.d(TAG, "translating title: " + entryInfo.getEntryTitle());
        
        String targetLanguage = sharedPreferencesRepository.getDefaultTranslationLanguage();

        AutoTranslator.processingIds.add(entryId);

        Disposable disposable = textUtil.identifyLanguageRx(sourceHtml)
                .flatMap(sourceLang -> {
                    if (sourceLang != null && sourceLang.equalsIgnoreCase(targetLanguage)) {
                        return Single.error(new Exception("Article is already in " + Locale.forLanguageTag(targetLanguage).getDisplayLanguage()));
                    }
                    return textUtil.translateHtmlAllAtOnce(sourceLang, targetLanguage, sourceHtml, entryInfo.getEntryTitle(), entryId, progress -> {
                        // Optional progress update
                    }, true);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> AutoTranslator.processingIds.remove(entryId))
                .subscribe(translatedResult -> {
                    doWhenTranslationFinish(entryInfo, translatedResult, targetLanguage);
                    Toast.makeText(requireContext(), "Translation completed successfully", Toast.LENGTH_SHORT).show();
                }, error -> {
                    Log.e(TAG, "Translation failed", error);
                    Toast.makeText(requireContext(), "Translation failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                });
        
        compositeDisposable.add(disposable);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (swipeRefreshLayout.isRefreshing()) swipeRefreshLayout.setRefreshing(false);
        if (isSelectionMode) {
            exitSelectionMode();
        }
    }

    @Override
    public void onEntryClick(EntryInfo entryInfo) {
        onPlayingButtonClick(entryInfo.getEntryId());
    }

    @Override
    public void onMoreButtonClick(long entryId, String link, boolean unread) {
        long[] allLinks = new long[entries.size()];
        int index = 0;
        for (EntryInfo entryInfo : entries) {
            allLinks[index] = entryInfo.getEntryId();
            index++;
        }

        Bundle args = new Bundle();
        args.putLongArray("ids", allLinks);
        args.putLong("id", entryId);
        args.putString("link", link);
        args.putBoolean("unread", unread);
        EntryItemBottomSheet bottomSheet = new EntryItemBottomSheet();
        bottomSheet.setArguments(args);
        bottomSheet.show(getChildFragmentManager(), EntryItemBottomSheet.TAG);
    }

    @Override
    public void onBookmarkButtonClick(String bool, long id) {
        allEntriesViewModel.updateBookmark(bool, id);
    }

    @Override
    public void onFilterChange(String filter) {
        this.filterBy = filter;
        allEntriesViewModel.getEntriesByFeed(feedId, filter);
        String text = " (" + title + ")";

        switch (filter) {
            case "read":
                text = "Read only" + text;
                binding.filterTitle.setText(text);
                break;
            case "unread":
                text = "Unread only" + text;
                binding.filterTitle.setText(text);
                break;
            case "bookmark":
                text = "Bookmarks" + text;
                binding.filterTitle.setText(text);
                break;
            default:
                binding.filterTitle.setText(title);
        }
    }

    @Override
    public void onSortChange(String sort) {
        allEntriesViewModel.setSortBy(sort);
        sortBy = sort;
        List<EntryInfo> sortedEntries = new ArrayList<>(entries);
        if (sortBy.equals("oldest")) {
            Collections.sort(sortedEntries, new EntryInfo.OldestComparator());
        } else {
            Collections.sort(sortedEntries, new EntryInfo.LatestComparator());
        }
        adapter.submitList(sortedEntries);
        entries = sortedEntries;
    }

    @Override
    public void onPlayingButtonClick(long entryId) {
        Context context = getContext();
        Intent intent = new Intent(context, WebViewActivity.class);
        intent.putExtra("read", false);
        intent.putExtra("entry_id", entryId);
        intent.putExtra("force_id", true);

        List<Long> allLinks = new ArrayList<>();
        for (EntryInfo entryInfo : entries) {
            allLinks.add(entryInfo.getEntryId());
        }
        allEntriesViewModel.insertPlaylist(allLinks, entryId);
        allEntriesViewModel.updateVisitedDate(entryId);

        if (context != null) {
            androidx.core.app.ActivityOptionsCompat options = androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(
                    context, R.anim.article_open_enter, R.anim.article_open_exit);
            context.startActivity(intent, options.toBundle());
        }
    }

    @Override
    public void onReadingButtonClick(long entryId) {
        Context context = getContext();
        Intent intent = new Intent(context, WebViewActivity.class);
        intent.putExtra("read", true);
        intent.putExtra("entry_id", entryId);
        intent.putExtra("force_id", true);

        List<Long> allLinks = new ArrayList<>();
        for (EntryInfo entryInfo : entries) {
            allLinks.add(entryInfo.getEntryId());
        }
        allEntriesViewModel.insertPlaylist(allLinks, entryId);
        allEntriesViewModel.updateVisitedDate(entryId);

        if (context != null) {
            androidx.core.app.ActivityOptionsCompat options = androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(
                    context, R.anim.article_open_enter, R.anim.article_open_exit);
            context.startActivity(intent, options.toBundle());
        }
    }

    @Override
    public void onSelectionModeChanged(boolean isSelectionMode) {
        this.isSelectionMode = isSelectionMode;
        requireActivity().invalidateOptionsMenu();
    }

    @Override
    public void onItemSelected(EntryInfo entryInfo) {
        if (entryInfo.isSelected()) {
            selectedEntries.add(entryInfo);
        } else {
            selectedEntries.remove(entryInfo);
        }
        selectedCountTextView.setText(selectedEntries.size() + " selected");
    }

    private void showFeedSelectionDialog() {
        allEntriesViewModel.getFeedsWithUnreadArticles()
                .firstOrError()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(feeds -> {
                    if (feeds.isEmpty()) {
                        Snackbar.make(binding.getRoot(), "No unread articles found.", Snackbar.LENGTH_SHORT).show();
                        return;
                    }

                    String[] feedTitles = new String[feeds.size()];
                    long[] feedIds = new long[feeds.size()];
                    boolean[] checkedItems = new boolean[feeds.size()];
                    List<Long> selectedFeedIds = new ArrayList<>();

                    for (int i = 0; i < feeds.size(); i++) {
                        feedTitles[i] = feeds.get(i).getTitle();
                        feedIds[i] = feeds.get(i).getId();
                        checkedItems[i] = true; // Default to all checked
                        selectedFeedIds.add(feedIds[i]);
                    }

                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Select Feeds for Summary")
                            .setMultiChoiceItems(feedTitles, checkedItems, (dialog, which, isChecked) -> {
                                if (isChecked) {
                                    selectedFeedIds.add(feedIds[which]);
                                } else {
                                    selectedFeedIds.remove(feedIds[which]);
                                }
                            })
                            .setPositiveButton("Summarize", (dialog, which) -> {
                                if (selectedFeedIds.isEmpty()) {
                                    Snackbar.make(binding.getRoot(), "Please select at least one feed.", Snackbar.LENGTH_SHORT).show();
                                } else {
                                    allEntriesViewModel.generateDailySummary(selectedFeedIds);
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }, throwable -> {
                    Log.e(TAG, "Error fetching feeds with unread articles", throwable);
                    Snackbar.make(binding.getRoot(), "Error loading feeds.", Snackbar.LENGTH_SHORT).show();
                });
    }

    public void enterSelectionMode() {
        isSelectionMode = true;

        // Inflate custom view for action bar
        actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setCustomView(R.layout.actionbar_multipleselection);

            // Find the TextView in the custom view and update it
            selectedCountTextView = actionBar.getCustomView().findViewById(R.id.selected_count);
            selectedCountTextView.setText(selectedEntries.size() + " selected");

            // Set up listeners for the action buttons in the custom view
            actionBar.getCustomView().findViewById(R.id.menu_delete).setOnClickListener(v -> {
                for (EntryInfo item : selectedEntries) {
                    allEntriesViewModel.deleteEntry(item.getEntryId());
                }
                exitSelectionMode();
            });
            actionBar.getCustomView().findViewById(R.id.menu_mark_as_read).setOnClickListener(v -> {
                for (EntryInfo item : selectedEntries) {
                    allEntriesViewModel.updateVisitedDate(item.getEntryId());
                }
                exitSelectionMode();
            });
            actionBar.getCustomView().findViewById(R.id.menu_translate).setOnClickListener(v -> {
                String translationMethod = sharedPreferencesRepository.getTranslationMethod();
                if (!translationMethod.equals("lineByLine") && !translationMethod.equals("paragraphByParagraph")) {
                    String translationModel = sharedPreferencesRepository.getTranslationModel();
                    if (!new xiangze.mmu.rssnewsreader.model.ai.AiClient(requireContext()).hasKey(translationModel)) {
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("API Key Missing")
                                .setMessage("Bulk AI Translation requires groq API Key. Please configure it in Settings.")
                                .setPositiveButton("OK", null)
                                .show();
                        return;
                    }
                }
                for (EntryInfo item : selectedEntries) {
                    translate(item);
                }
                Toast.makeText(requireContext(), "Translating " + selectedEntries.size() + " entries", Toast.LENGTH_SHORT).show();
                exitSelectionMode();
            });
            actionBar.getCustomView().findViewById(R.id.menu_cancel).setOnClickListener(v -> {
                exitSelectionMode();
            });
        }
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        for (EntryInfo entryInfo : selectedEntries) {
            entryInfo.setSelected(false);
        }
        selectedEntries.clear();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowCustomEnabled(false);
        actionBar.setCustomView(null);
        requireActivity().invalidateOptionsMenu();
        adapter.exitSelectionMode();
    }
}
