package xiangze.mmu.rssnewsreader.ui.main;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Xml;
import android.view.View;
import android.widget.Toast;

import xiangze.mmu.rssnewsreader.R;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
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

import xiangze.mmu.rssnewsreader.data.entry.Entry;
import xiangze.mmu.rssnewsreader.data.feed.Feed;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.databinding.ActivityMainBinding;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;
    private DrawerLayout drawerLayout;
    private MaterialSwitch themeSwitch;
    private NavigationFeedItemAdapter adapter;
    private MainActivityViewModel mainActivityViewModel;
    @Inject
    SharedPreferencesRepository sharedPreferencesRepository;

    // Define the ActivityResultLauncher for importing OPML file
    private final ActivityResultLauncher<String[]> importOpmlLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            new ActivityResultCallback<List<Uri>>() {
                @Override
                public void onActivityResult(List<Uri> uris) {
                    // Handle the result of importing OPML file(s)
                    if (uris != null && !uris.isEmpty()) {
                        for (Uri uri : uris) {
                            // Do something with the URI(s) of the imported OPML file(s)
                            if (uri != null) {
                                try {
                                    InputStream inputStream = getContentResolver().openInputStream(uri);
                                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                                    XmlPullParser parser = factory.newPullParser();
                                    parser.setInput(inputStream, null);
                                    int eventType = parser.getEventType();
                                    @SuppressLint("SimpleDateFormat") SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                                    long feedId = 0;
                                    while (eventType != XmlPullParser.END_DOCUMENT) {
                                        if (eventType == XmlPullParser.START_TAG && parser.getName().equals("setting")) {
                                            String jobPeriodic = parser.getAttributeValue(null, "jobPeriodic");
                                            String displaySummary = parser.getAttributeValue(null, "displaySummary");
                                            String highlightText = parser.getAttributeValue(null, "highlightText");
                                            String confidenceThreshold = parser.getAttributeValue(null, "confidenceThreshold");
                                            String textZoom = parser.getAttributeValue(null, "textZoom");
                                            String sortBy = parser.getAttributeValue(null, "sortBy");
                                            String backgroundMusic = parser.getAttributeValue(null, "backgroundMusic");
                                            String backgroundMusicVolume = parser.getAttributeValue(null, "backgroundMusicVolume");
                                            String entriesLimitPerFeed = parser.getAttributeValue(null, "entriesLimitPerFeed");
                                            String defaultTranslationLanguage = parser.getAttributeValue(null, "defaultTranslationLanguage");
                                            String translationMethod = parser.getAttributeValue(null, "translationMethod");
                                            Log.d(TAG, "onActivityResult: set defaultTranslationLanguage" + defaultTranslationLanguage);
                                            if (jobPeriodic != null && !jobPeriodic.isEmpty()) {
                                                sharedPreferencesRepository.setJobPeriodic(jobPeriodic);
                                            }
                                            if (displaySummary != null && !displaySummary.isEmpty()) {
                                                sharedPreferencesRepository.setDisplaySummary(displaySummary.equals("true"));
                                            }
                                            if (highlightText != null && !highlightText.isEmpty()) {
                                                sharedPreferencesRepository.setHighlightText(highlightText.equals("true"));
                                            }
                                            if (sortBy != null && !textZoom.isEmpty()) {
                                                sharedPreferencesRepository.setTextZoom(Integer.parseInt(textZoom));
                                            }
                                            if (sortBy != null && !sortBy.isEmpty()) {
                                                sharedPreferencesRepository.setSortBy(sortBy);
                                            }
                                            if (backgroundMusic != null && !backgroundMusic.isEmpty()) {
                                                sharedPreferencesRepository.setBackgroundMusic(backgroundMusic.equals("true"));
                                            }
                                            if (backgroundMusicVolume != null && !backgroundMusicVolume.isEmpty()) {
                                                sharedPreferencesRepository.setBackgroundMusicVolume(Integer.parseInt(backgroundMusicVolume));
                                            }
                                            if (entriesLimitPerFeed != null && !entriesLimitPerFeed.isEmpty()) {
                                                sharedPreferencesRepository.setEntriesLimitPerFeed(Integer.parseInt(entriesLimitPerFeed));
                                            }
                                            if (confidenceThreshold != null && !confidenceThreshold.isEmpty()) {
                                                sharedPreferencesRepository.setConfidenceThreshold(Integer.parseInt(confidenceThreshold));
                                            }
                                            if (defaultTranslationLanguage != null && !defaultTranslationLanguage.isEmpty()) {
                                                sharedPreferencesRepository.setDefaultTranslationLanguage(defaultTranslationLanguage);
                                            }
                                            if (translationMethod != null && !translationMethod.isEmpty()) {
                                                sharedPreferencesRepository.setTranslationMethod(translationMethod);
                                            }
                                        } else if (eventType == XmlPullParser.START_TAG && parser.getName().equals("outline")) {
                                            String title = parser.getAttributeValue(null, "text");
                                            String link = parser.getAttributeValue(null, "xmlUrl");
                                            Log.d("Test url", link);
                                            String imageUrl = parser.getAttributeValue(null, "imageUrl");
                                            String description = parser.getAttributeValue(null, "description");
                                            String language = parser.getAttributeValue(null, "language");
                                            String delayTimeString = parser.getAttributeValue(null, "delayTime");
                                            int delayTime = 0;
                                            if (delayTimeString != null) {
                                                delayTime = Integer.parseInt(delayTimeString);
                                            }
                                            String ttsSpeechRateString = parser.getAttributeValue(null, "ttsSpeechRate");
                                            float ttsSpeechRate = 0;
                                            if (ttsSpeechRateString != null) {
                                                ttsSpeechRate = Float.parseFloat(ttsSpeechRateString);
                                            }

                                            if (link != null && !link.isEmpty()) {
                                                Feed feed = new Feed(title, link, description, imageUrl, language.isEmpty() ? null : language, delayTime, ttsSpeechRate);
                                                mainActivityViewModel.addFeedUsingOPML(feed);
                                                feedId = mainActivityViewModel.getFeedIdByLink(link);
                                            }
                                        } else if (eventType == XmlPullParser.START_TAG && parser.getName().equals("entry")) {
                                            String entryTitle = parser.getAttributeValue(null, "entryTitle");
                                            String bookmark = parser.getAttributeValue(null, "bookmark");
                                            String visitedDate = parser.getAttributeValue(null, "visitedDate");
                                            String link = parser.getAttributeValue(null, "link");
                                            String description = parser.getAttributeValue(null, "description");
                                            String publishedDate = parser.getAttributeValue(null, "publishedDate");
                                            String entryImageUrl = parser.getAttributeValue(null, "entryImageUrl");
                                            String entryCategory = parser.getAttributeValue(null, "entryCategory");

                                            if (entryCategory != null && entryCategory.isEmpty()) {
                                                entryCategory = null;
                                            }
                                            if (entryImageUrl != null && entryImageUrl.isEmpty()) {
                                                entryImageUrl = null;
                                            }
                                            if (description != null && description.isEmpty()) {
                                                description = null;
                                            }

                                            try {
                                                Entry entry = new Entry(feedId, entryTitle, link, description, entryImageUrl, entryCategory, formatter.parse(publishedDate));
                                                if (!bookmark.isEmpty()) {
                                                    entry.setBookmark(bookmark);
                                                }
                                                if (!visitedDate.isEmpty()) {
                                                    entry.setVisitedDate(formatter.parse(visitedDate));
                                                }
                                                mainActivityViewModel.addEntry(feedId, entry);
                                            } catch (ParseException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                        eventType = parser.next();
                                    }
                                    Toast.makeText(getApplicationContext(), "Feeds imported successfully", Toast.LENGTH_SHORT).show();
                                } catch (IOException | XmlPullParserException e) {
                                    e.printStackTrace();
                                    Toast.makeText(getApplicationContext(), "Import failed", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                    }
                }
            });

    // Define the ActivityResultLauncher for exporting OPML file
    private final ActivityResultLauncher<Intent> exportOpmlLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    // Handle the result of exporting OPML file
                    if (result.getResultCode() == RESULT_OK) {
                        try {
                            XmlSerializer serializer = Xml.newSerializer();
                            OutputStream os = getContentResolver().openOutputStream(result.getData().getData());
                            serializer.setOutput(os, StandardCharsets.UTF_8.name());
                            serializer.startDocument(null, true);
                            serializer.startTag(null, "opml");
                            serializer.startTag(null, "body");
                            serializer.startTag(null, "setting");
                            serializer.attribute(null, "jobPeriodic", Integer.toString(sharedPreferencesRepository.getJobPeriodic()));
                            serializer.attribute(null, "displaySummary", sharedPreferencesRepository.getDisplaySummary() ? "true" : "false");
                            serializer.attribute(null, "highlightText", sharedPreferencesRepository.getHighlightText() ? "true" : "false");
                            serializer.attribute(null, "textZoom", Integer.toString(sharedPreferencesRepository.getTextZoom()));
                            serializer.attribute(null, "sortBy", sharedPreferencesRepository.getSortBy());
                            serializer.attribute(null, "backgroundMusic", sharedPreferencesRepository.getBackgroundMusic() ? "true" : "false");
                            serializer.attribute(null, "backgroundMusicVolume", Integer.toString(sharedPreferencesRepository.getBackgroundMusicVolume()));
                            serializer.attribute(null, "entriesLimitPerFeed", Integer.toString(sharedPreferencesRepository.getEntriesLimitPerFeed()));
                            serializer.attribute(null, "confidenceThreshold", Integer.toString(sharedPreferencesRepository.getConfidenceThreshold()));
                            serializer.attribute(null, "defaultTranslationLanguage", sharedPreferencesRepository.getDefaultTranslationLanguage());
                            serializer.attribute(null, "translationMethod", sharedPreferencesRepository.getTranslationMethod());
                            serializer.attribute(null, "summaryLength", Integer.toString(sharedPreferencesRepository.getSummaryLength()));
                            serializer.endTag(null, "setting");
                            List<Feed> feeds = mainActivityViewModel.getAllStaticFeeds();
                            @SuppressLint("SimpleDateFormat") SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                            for (Feed feed : feeds) {
                                serializer.startTag(null, "outline");
                                serializer.attribute(null, "text", feed.getTitle() != null ? feed.getTitle() : "");
                                serializer.attribute(null, "title", feed.getTitle() != null ? feed.getTitle() : "");
                                serializer.attribute(null, "imageUrl", feed.getImageUrl() != null ? feed.getImageUrl() : "");
                                serializer.attribute(null, "description", feed.getDescription() != null ? feed.getDescription() : "");
                                serializer.attribute(null, "language", feed.getLanguage() != null ? feed.getLanguage() : "");
                                serializer.attribute(null, "xmlUrl", feed.getLink() != null ? feed.getLink() : "");
                                serializer.attribute(null, "delayTime", Integer.toString(feed.getDelayTime()));
                                serializer.attribute(null, "ttsSpeechRate", Float.toString(feed.getTtsSpeechRate()));
                                serializer.attribute(null, "type", "rss");
                                List<Entry> entries = mainActivityViewModel.getAllStaticEntries(feed.getId());
                                for (Entry entry : entries) {
                                    serializer.startTag(null, "entry");
                                    serializer.attribute(null, "entryTitle", entry.getTitle() != null ? entry.getTitle() : "");
                                    serializer.attribute(null, "bookmark", entry.getBookmark() != null ? entry.getBookmark() : "");
                                    serializer.attribute(null, "visitedDate", entry.getVisitedDate() != null ? formatter.format(entry.getVisitedDate()) : "");
                                    serializer.attribute(null, "link", entry.getLink() != null ? entry.getLink() : "");
                                    serializer.attribute(null, "description", entry.getDescription() != null ? entry.getDescription() : "");
                                    serializer.attribute(null, "publishedDate", entry.getPublishedDate() != null ? formatter.format(entry.getPublishedDate()) : "");
                                    serializer.attribute(null, "entryImageUrl", entry.getImageUrl() != null ? entry.getImageUrl() : "");
                                    serializer.attribute(null, "entryCategory", entry.getCategory() != null ? entry.getCategory() : "");
                                    serializer.endTag(null, "entry");
                                }
                                serializer.endTag(null, "outline");
                            }
                            serializer.endTag(null, "body");
                            serializer.endTag(null, "opml");
                            serializer.endDocument();
                            os.close();
                            Toast.makeText(getApplicationContext(), "Feeds exported successfully", Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            e.printStackTrace();
                            Toast.makeText(getApplicationContext(), "Export failed", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        // Handle the case where the OPML file export is cancelled or failed
                        Toast.makeText(getApplicationContext(), "Export failed", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    public void onBackPressed() {
        if (this.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            this.drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());

        mainActivityViewModel = new ViewModelProvider(this).get(MainActivityViewModel.class);

        themeSwitch = binding.themeSwitch;

        updateThemeSwitch();
        setContentView(binding.getRoot());

        Toolbar toolbar = binding.toolbar;
        drawerLayout = binding.drawerLayout;
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

        RecyclerView recyclerView = binding.navigationFeedsRecycler;
        recyclerView.setLayoutManager(new LinearLayoutManager(getBaseContext()));
        adapter = new NavigationFeedItemAdapter(new NavigationFeedItemAdapter.FeedItemClickInterface() {
            @Override
            public void onClick(long id, String feedTitle) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Bundle args = new Bundle();
                args.putLong("id", id);
                args.putString("title", feedTitle);
                NavOptions navOptions = new NavOptions.Builder()
                        .setPopUpTo(R.id.allEntriesFragment, false)
                        .build();
                navController.navigate(R.id.allEntriesFragment, args, navOptions);
            }
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

        binding.allFeedsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Bundle args = new Bundle();
                args.putInt("id", 0);
                args.putString("title", "All feeds");
                NavOptions navOptions = new NavOptions.Builder()
                        .setPopUpTo(R.id.allEntriesFragment, false)
                        .build();
                navController.navigate(R.id.allEntriesFragment, args, navOptions);
            }
        });

        binding.addFeedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                NavOptions navOptions = new NavOptions.Builder()
                        .setPopUpTo(R.id.allEntriesFragment, false)
                        .build();
                navController.navigate(R.id.feedFragment, null, navOptions);
                drawerLayout.closeDrawer(GravityCompat.START);
            }
        });

        binding.navigationImportOpmlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Create an intent for selecting multiple documents of OPML MIME type
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("text/xml");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                // Launch the activity for selecting the OPML file(s) to import
                importOpmlLauncher.launch(new String[]{"text/xml"});
            }
        });

        binding.navigationExportOpmlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Create an intent for exporting the OPML file
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/xml");
                intent.putExtra(Intent.EXTRA_TITLE, "rss_news_reader.opml");

                // Launch the activity for exporting the OPML file
                exportOpmlLauncher.launch(intent);
            }
        });

        binding.settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                NavOptions navOptions = new NavOptions.Builder()
                        .setPopUpTo(R.id.allEntriesFragment, false)
                        .build();
                navController.navigate(R.id.settingsFragment, null, navOptions);
                drawerLayout.closeDrawer(GravityCompat.START);
            }
        });

        binding.helpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                NavOptions navOptions = new NavOptions.Builder()
                        .setPopUpTo(R.id.allEntriesFragment, false)
                        .build();
                navController.navigate(R.id.helpFragment, null, navOptions);
                drawerLayout.closeDrawer(GravityCompat.START);
            }
        });

        binding.themeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                themeSwitch.setChecked(!themeSwitch.isChecked());
                switchTheme();
            }
        });

        themeSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                switchTheme();
            }
        });

        mainActivityViewModel.getAllFeeds().observe(this, new Observer<List<Feed>>() {
            @Override
            public void onChanged(List<Feed> feeds) {
                adapter.submitList(feeds);
            }
        });
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
        if (isNight) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }
}