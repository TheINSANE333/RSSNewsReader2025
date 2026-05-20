package xiangze.mmu.rssnewsreader.ui.main;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.databinding.ActivityMainBinding;

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
                    for (Uri uri : uris) {
                        if (uri != null) {
                            opmlRepository.importOpml(uri, (success, error) -> {
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
                    opmlRepository.exportOpml(result.getData().getData(), (success, error) -> {
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
    }

    @Override
    protected void onResume() {
        super.onResume();
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
