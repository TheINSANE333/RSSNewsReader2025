package xiangze.mmu.rssnewsreader.ui.main;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import timber.log.Timber;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.databinding.ActivityMainBinding;
import xiangze.mmu.rssnewsreader.service.tts.TtsService;
import xiangze.mmu.rssnewsreader.ui.webview.MediaBrowserHelper;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewActivity;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetView;
import android.graphics.Typeface;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.service.rss.RssWorkManager;
import xiangze.mmu.rssnewsreader.service.tts.TtsExtractor;
import xiangze.mmu.rssnewsreader.model.EntryInfo;
import com.squareup.picasso.Picasso;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;
    private DrawerLayout drawerLayout;
    private MaterialSwitch themeSwitch;
    private NavigationFeedItemAdapter adapter;
    private MainActivityViewModel mainActivityViewModel;
    private GestureDetector gestureDetector;
    private MediaBrowserHelper mMediaBrowserHelper;
    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;
    @Inject
    RssWorkManager rssWorkManager;
    @Inject
    TtsExtractor ttsExtractor;
    @Inject
    xiangze.mmu.rssnewsreader.data.opml.OpmlRepository opmlRepository;

    // Define the ActivityResultLauncher for importing OPML file
    private final ActivityResultLauncher<String[]> importOpmlLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                if (uris != null && !uris.isEmpty()) {
                    mainActivityViewModel.setIsLoading(true);
                    for (Uri uri : uris) {
                        if (uri != null) {
                            opmlRepository.importOpml(uri, (success, error) -> {
                                mainActivityViewModel.setIsLoading(false);
                                if (success) {
                                    updateThemeSwitch();
                                    Toast.makeText(getApplicationContext(), "Feeds imported successfully", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(getApplicationContext(), "Import failed: " + error, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                }
            });

    // Define the ActivityResultLauncher for exporting OPML file
    private final ActivityResultLauncher<Intent> exportOpmlLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    mainActivityViewModel.setIsLoading(true);
                    opmlRepository.exportOpml(result.getData().getData(), (success, error) -> {
                        mainActivityViewModel.setIsLoading(false);
                        if (success) {
                            Toast.makeText(getApplicationContext(), "Feeds exported successfully", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getApplicationContext(), "Export failed: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(getApplicationContext(), "Export failed", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        if (sharedPreferencesRepository.isFirstLaunch()) {
            startActivity(new Intent(this, xiangze.mmu.rssnewsreader.ui.onboarding.OnboardingActivity.class));
            finish();
            return;
        }

        splashScreen.setOnExitAnimationListener(splashScreenView -> {
            final android.view.View iconView = splashScreenView.getIconView();
            final long duration = 500L;

            iconView.animate()
                    .scaleX(2.0f)
                    .scaleY(2.0f)
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(new android.view.animation.AnticipateInterpolator())
                    .withEndAction(splashScreenView::remove)
                    .start();

            splashScreenView.getView().animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .start();
        });

        binding = ActivityMainBinding.inflate(getLayoutInflater());

        mainActivityViewModel = new ViewModelProvider(this).get(MainActivityViewModel.class);

        mainActivityViewModel.getIsLoading().observe(this, isLoading -> {
            if (isLoading != null) {
                binding.globalLoadingIndicator.setVisibility(isLoading ? android.view.View.VISIBLE : android.view.View.GONE);
                binding.loadingOverlay.setVisibility(isLoading ? android.view.View.VISIBLE : android.view.View.GONE);
                if (isLoading) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    }
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
                } else {
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
                }
            }
        });

        themeSwitch = binding.themeSwitch;

        updateThemeSwitch();
        setContentView(binding.getRoot());

        // Fancy animation for main content
        binding.getRoot().setAlpha(0f);
        binding.getRoot().setTranslationY(100f);
        binding.getRoot().animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(600L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

        drawerLayout = binding.drawerLayout;

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    if (diffX > 0 && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.openDrawer(GravityCompat.START);
                        return true;
                    }
                }
                return false;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mainActivityViewModel.getIsLoading().getValue() != null && mainActivityViewModel.getIsLoading().getValue()) {
                    // Do nothing or maybe show a toast: "Please wait until loading is complete"
                    return;
                }
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        Toolbar toolbar = binding.toolbar;
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                super.onDrawerClosed(drawerView);
            }
        });
        setSupportActionBar(toolbar);

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).setOpenableLayout(drawerLayout).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.bottomNavigation, navController);

        RecyclerView recyclerView = binding.navigationView.findViewById(R.id.navigationFeedsRecycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(getBaseContext()));
        adapter = new NavigationFeedItemAdapter((id, feedTitle) -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            Bundle args = new Bundle();
            args.putLong("id", id);
            args.putString("title", feedTitle);
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.allEntriesFragment, false)
                    .setEnterAnim(R.anim.feed_open_enter)
                    .setExitAnim(R.anim.feed_open_exit)
                    .setPopEnterAnim(R.anim.feed_pop_enter)
                    .setPopExitAnim(R.anim.feed_pop_exit)
                    .build();
            navController.navigate(R.id.allEntriesFragment, args, navOptions);
        });
        recyclerView.setAdapter(adapter);

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            boolean handled = true;

            if (itemId == R.id.allEntriesFragment && navController.getCurrentDestination().getId() != R.id.allEntriesFragment) {
                navController.popBackStack(R.id.allEntriesFragment, false);
            } else {
                handled = NavigationUI.onNavDestinationSelected(item, navController);
            }

            return handled;
        });

        binding.navigationView.findViewById(R.id.allFeedsButton).setOnClickListener(view -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            Bundle args = new Bundle();
            args.putInt("id", 0);
            args.putString("title", "All feeds");
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.allEntriesFragment, false)
                    .setEnterAnim(R.anim.feed_open_enter)
                    .setExitAnim(R.anim.feed_open_exit)
                    .setPopEnterAnim(R.anim.feed_pop_enter)
                    .setPopExitAnim(R.anim.feed_pop_exit)
                    .build();
            navController.navigate(R.id.allEntriesFragment, args, navOptions);
        });

        binding.navigationView.findViewById(R.id.addFeedButton).setOnClickListener(view -> {
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.allEntriesFragment, false)
                    .setEnterAnim(R.anim.fade_in)
                    .setExitAnim(R.anim.fade_out)
                    .setPopEnterAnim(R.anim.fade_in)
                    .setPopExitAnim(R.anim.fade_out)
                    .build();
            navController.navigate(R.id.feedFragment, null, navOptions);
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        binding.navigationView.findViewById(R.id.navigationImportOpmlButton).setOnClickListener(view -> {
            // Create an intent for selecting multiple documents of OPML MIME type
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("text/xml");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            // Launch the activity for selecting the OPML file(s) to import
            importOpmlLauncher.launch(new String[]{"text/xml"});
        });

        binding.navigationView.findViewById(R.id.navigationExportOpmlButton).setOnClickListener(view -> {
            // Create an intent for exporting the OPML file
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/xml");
            intent.putExtra(Intent.EXTRA_TITLE, "rss_news_reader.opml");

            // Launch the activity for exporting the OPML file
            exportOpmlLauncher.launch(intent);
        });

        binding.navigationView.findViewById(R.id.settingsButton).setOnClickListener(view -> {
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.allEntriesFragment, false)
                    .setEnterAnim(R.anim.slide_in_right)
                    .setExitAnim(R.anim.slide_out_left_fade)
                    .setPopEnterAnim(R.anim.slide_in_left_fade)
                    .setPopExitAnim(R.anim.slide_out_right_fade)
                    .build();
            navController.navigate(R.id.settingsFragment, null, navOptions);
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        binding.navigationView.findViewById(R.id.helpButton).setOnClickListener(view -> {
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.allEntriesFragment, false)
                    .setEnterAnim(R.anim.slide_in_right)
                    .setExitAnim(R.anim.slide_out_left_fade)
                    .setPopEnterAnim(R.anim.slide_in_left_fade)
                    .setPopExitAnim(R.anim.slide_out_right_fade)
                    .build();
            navController.navigate(R.id.helpFragment, null, navOptions);
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        themeSwitch = binding.navigationView.findViewById(R.id.themeSwitch);

        binding.navigationView.findViewById(R.id.themeButton).setOnClickListener(view -> {
            themeSwitch.setChecked(!themeSwitch.isChecked());
            switchTheme();
        });

        themeSwitch.setOnClickListener(view -> switchTheme());

        mainActivityViewModel.getAllFeeds().observe(this, feeds -> adapter.submitList(feeds));

        ttsExtractor.extractAllEntries();
        
        setupMediaControlBar();

        // Show interactive walkthrough on first launch
        if (sharedPreferencesRepository.isFirstMainActivityView()) {
            sharedPreferencesRepository.setFirstMainActivityView(false);
            
            // Post delay to ensure toolbar is fully rendered
            binding.getRoot().postDelayed(() -> {
                TapTargetView.showFor(this,
                    TapTarget.forToolbarNavigationIcon(toolbar, "Open Menu", "Access all your feeds, settings, and add new sources from here.")
                        .cancelable(false)
                        .tintTarget(true)
                        .outerCircleColor(R.color.primary)
                        .targetCircleColor(R.color.onPrimary)
                        .titleTextSize(20)
                        .titleTextColor(R.color.onPrimary)
                        .descriptionTextSize(16)
                        .descriptionTextColor(R.color.onPrimary)
                        .textTypeface(Typeface.SANS_SERIF)
                        .drawShadow(true),
                    new TapTargetView.Listener() {
                        @Override
                        public void onTargetClick(TapTargetView view) {
                            super.onTargetClick(view);
                            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
                            drawerLayout.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                                @Override
                                public void onDrawerOpened(android.view.View drawerView) {
                                    drawerLayout.removeDrawerListener(this);
                                    
                                    View helpBtn = binding.navigationView.findViewById(R.id.helpButton);
                                    android.graphics.Rect bounds = new android.graphics.Rect();
                                    helpBtn.getGlobalVisibleRect(bounds);
                                    // Restrict the bounds to just the left side (the icon)
                                    int iconArea = (int) (56 * getResources().getDisplayMetrics().density);
                                    bounds.right = bounds.left + iconArea;
                                    
                                    TapTargetView.showFor(MainActivity.this,
                                        TapTarget.forBounds(bounds, "Need Help?", "If you ever get stuck, tap here to access the comprehensive help guide.")
                                            .cancelable(false)
                                            .transparentTarget(true)
                                            .outerCircleColor(R.color.primary)
                                            .targetCircleColor(R.color.onPrimary)
                                            .titleTextSize(20)
                                            .titleTextColor(R.color.onPrimary)
                                            .descriptionTextSize(16)
                                            .descriptionTextColor(R.color.onPrimary)
                                            .textTypeface(Typeface.SANS_SERIF)
                                            .drawShadow(true),
                                        new TapTargetView.Listener() {
                                            @Override
                                            public void onTargetClick(TapTargetView view) {
                                                super.onTargetClick(view);
                                                helpBtn.performClick();
                                            }
                                        }
                                    );
                                }
                            });
                        }
                    });
            }, 1000); // Wait for entry animations to finish
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mMediaBrowserHelper != null) {
            mMediaBrowserHelper.onStart();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mMediaBrowserHelper != null) {
            mMediaBrowserHelper.onStop();
        }
    }

    private void setupMediaControlBar() {
        mMediaBrowserHelper = new MediaBrowserHelper(this, TtsService.class);
        mMediaBrowserHelper.registerCallback(new MediaControllerCompat.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadataCompat metadata) {
                updateMediaBarMetadata(metadata);
            }

            @Override
            public void onPlaybackStateChanged(PlaybackStateCompat state) {
                updateMediaBarPlaybackState(state);
            }
        });

        binding.mediaControlBarLayout.mediaPlayPause.setOnClickListener(v -> {
            MediaControllerCompat controller = mMediaBrowserHelper.getMediaController();
            if (controller != null) {
                PlaybackStateCompat state = controller.getPlaybackState();
                if (state != null) {
                    if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
                        controller.getTransportControls().pause();
                    } else {
                        controller.getTransportControls().play();
                    }
                }
            }
        });

        binding.mediaControlBarLayout.mediaSkipNext.setOnClickListener(v -> {
            MediaControllerCompat controller = mMediaBrowserHelper.getMediaController();
            if (controller != null) {
                controller.getTransportControls().skipToNext();
            }
        });

        binding.mediaControlBarLayout.mediaSkipPrevious.setOnClickListener(v -> {
            MediaControllerCompat controller = mMediaBrowserHelper.getMediaController();
            if (controller != null) {
                controller.getTransportControls().skipToPrevious();
            }
        });

        binding.mediaControlBarLayout.mediaControlBar.setOnClickListener(v -> {
            MediaControllerCompat controller = mMediaBrowserHelper.getMediaController();
            if (controller != null && controller.getMetadata() != null) {
                String mediaId = controller.getMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                if (mediaId != null) {
                    Intent intent = new Intent(MainActivity.this, WebViewActivity.class);
                    intent.putExtra("id", Long.parseLong(mediaId));
                    startActivity(intent);
                }
            }
        });
    }

    private void updateMediaBarMetadata(MediaMetadataCompat metadata) {
        if (metadata == null) {
            binding.mediaControlBarLayout.mediaTitle.setText("");
            binding.mediaControlBarLayout.mediaSubtitle.setText("");
            binding.mediaControlBarLayout.mediaThumbnail.setImageResource(R.drawable.ic_rss_feed);
            return;
        }
        
        String articleTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
        String feedTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
        
        binding.mediaControlBarLayout.mediaTitle.setText(articleTitle);
        binding.mediaControlBarLayout.mediaSubtitle.setText(feedTitle);
        
        // Ensure marquee works
        binding.mediaControlBarLayout.mediaTitle.setSelected(true);

        String imageUrl = metadata.getString("feedImageUrl");
        if (imageUrl == null || imageUrl.isEmpty()) {
            imageUrl = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI);
        }
        
        android.graphics.Bitmap icon = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
        
        if (imageUrl != null && !imageUrl.isEmpty()) {
            Picasso.get()
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_rss_feed)
                    .error(R.drawable.ic_rss_feed)
                    .into(binding.mediaControlBarLayout.mediaThumbnail);
        } else if (icon != null) {
            binding.mediaControlBarLayout.mediaThumbnail.setImageBitmap(icon);
        } else {
            binding.mediaControlBarLayout.mediaThumbnail.setImageResource(R.drawable.ic_rss_feed);
        }
    }

    private void updateMediaBarPlaybackState(PlaybackStateCompat state) {
        if (state == null || state.getState() == PlaybackStateCompat.STATE_NONE || state.getState() == PlaybackStateCompat.STATE_STOPPED) {
            binding.mediaControlBarLayout.mediaControlBar.setVisibility(View.GONE);
            return;
        }

        binding.mediaControlBarLayout.mediaControlBar.setVisibility(View.VISIBLE);

        if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
            binding.mediaControlBarLayout.mediaPlayPause.setImageResource(R.drawable.ic_baseline_pause_24);
        } else {
            binding.mediaControlBarLayout.mediaPlayPause.setImageResource(R.drawable.ic_baseline_play_arrow_24);
        }

        if (state.getState() == PlaybackStateCompat.STATE_BUFFERING) {
            binding.mediaControlBarLayout.mediaPlayPause.setEnabled(false);
            binding.mediaControlBarLayout.mediaPlayPause.setAlpha(0.5f);
        } else {
            binding.mediaControlBarLayout.mediaPlayPause.setEnabled(true);
            binding.mediaControlBarLayout.mediaPlayPause.setAlpha(1.0f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // Immediate UI update from database to ensure current article is always shown correctly
        long currentId = sharedPreferencesRepository.getCurrentReadingEntryId();
        if (currentId != -1 && currentId != 0) {
            Single.fromCallable(() -> mainActivityViewModel.getEntryInfoById(currentId))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(info -> {
                        if (info != null) {
                            binding.mediaControlBarLayout.mediaTitle.setText(info.getEntryTitle());
                            binding.mediaControlBarLayout.mediaSubtitle.setText(info.getFeedTitle());
                            binding.mediaControlBarLayout.mediaTitle.setSelected(true);
                            
                            String imageUrl = info.getFeedImageUrl();
                            if (imageUrl == null || imageUrl.isEmpty()) {
                                imageUrl = info.getEntryImageUrl();
                            }
                            
                            if (imageUrl != null && !imageUrl.isEmpty()) {
                                Picasso.get()
                                        .load(imageUrl)
                                        .placeholder(R.drawable.ic_rss_feed)
                                        .error(R.drawable.ic_rss_feed)
                                        .into(binding.mediaControlBarLayout.mediaThumbnail);
                            } else {
                                binding.mediaControlBarLayout.mediaThumbnail.setImageResource(R.drawable.ic_rss_feed);
                            }
                            
                            // Also make sure the bar is visible if we have a valid article
                            binding.mediaControlBarLayout.mediaControlBar.setVisibility(View.VISIBLE);
                        }
                    }, throwable -> Timber.e(throwable, "Error updating media bar on resume"));
        }

        if (mMediaBrowserHelper != null) {
            MediaControllerCompat controller = mMediaBrowserHelper.getMediaController();
            if (controller != null) {
                // Force a sync with the service to ensure it matches the article we just loaded
                if (controller.getTransportControls() != null) {
                    controller.getTransportControls().prepare();
                }
                updateMediaBarMetadata(controller.getMetadata());
                updateMediaBarPlaybackState(controller.getPlaybackState());
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        if (navController.getCurrentDestination() != null && navController.getCurrentDestination().getId() == R.id.allEntriesFragment) {
            if (gestureDetector != null && gestureDetector.onTouchEvent(ev)) {
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void switchTheme() {
        boolean isNight = mainActivityViewModel.getNight();
        mainActivityViewModel.setNight(!isNight);
        if (!isNight) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }

    public void updateThemeSwitch() {
        boolean isNight = mainActivityViewModel.getNight();
        themeSwitch.setChecked(isNight);
        int currentMode = AppCompatDelegate.getDefaultNightMode();
        int expectedMode = isNight ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        if (currentMode != expectedMode) {
            AppCompatDelegate.setDefaultNightMode(expectedMode);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }
}
