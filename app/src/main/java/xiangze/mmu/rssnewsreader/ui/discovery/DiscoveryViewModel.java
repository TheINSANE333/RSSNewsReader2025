package xiangze.mmu.rssnewsreader.ui.discovery;

import timber.log.Timber;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

@HiltViewModel
public class DiscoveryViewModel extends ViewModel {

    private final MutableLiveData<List<DiscoveryFeed>> searchResults = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    @Inject
    public DiscoveryViewModel() {}

    public LiveData<List<DiscoveryFeed>> getSearchResults() { return searchResults; }
    public LiveData<Boolean> getIsLoading() { return isLoading; }

    public void searchFeeds(String query) {
        isLoading.postValue(true);
        
        compositeDisposable.add(
            Single.fromCallable(() -> fetchFeedsFromDuckDuckGo(query))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(results -> {
                    searchResults.postValue(results);
                    isLoading.postValue(false);
                }, throwable -> {
                    Timber.e(throwable, "Search failed");
                    searchResults.postValue(new ArrayList<>());
                    isLoading.postValue(false);
                })
        );
    }

    private List<DiscoveryFeed> fetchFeedsFromDuckDuckGo(String query) throws Exception {
        List<DiscoveryFeed> results = new ArrayList<>();
        String searchUrl = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8");
        
        String userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.7778.216 Mobile Safari/537.36";
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .referrer("https://duckduckgo.com/")
                .timeout(15000)
                .get();
        
        org.jsoup.select.Elements elements = doc.select(".result");
        for (org.jsoup.nodes.Element element : elements) {
            org.jsoup.nodes.Element linkElement = element.selectFirst(".result__a");
            if (linkElement == null) continue;
            
            String title = linkElement.text().trim();
            String rawUrl = linkElement.attr("href");
            
            // Extract the actual URL from DuckDuckGo's redirect/uddg URL format
            String targetUrl = rawUrl;
            if (rawUrl.contains("uddg=")) {
                try {
                    int index = rawUrl.indexOf("uddg=");
                    String encoded = rawUrl.substring(index + 5);
                    int ampIndex = encoded.indexOf("&");
                    if (ampIndex != -1) {
                        encoded = encoded.substring(0, ampIndex);
                    }
                    targetUrl = java.net.URLDecoder.decode(encoded, "UTF-8");
                } catch (Exception e) {
                    Timber.w("Failed to decode URL: " + rawUrl, e);
                }
            } else if (rawUrl.startsWith("//")) {
                targetUrl = "https:" + rawUrl;
            }
            
            org.jsoup.nodes.Element snippetElement = element.selectFirst(".result__snippet");
            String desc = (snippetElement != null) ? snippetElement.text().trim() : "";
            
            if (!targetUrl.isEmpty()) {
                results.add(new DiscoveryFeed(title, targetUrl, desc, ""));
            }
        }
        
        return results;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.clear();
    }
}
