package xiangze.mmu.rssnewsreader.service.util;

import android.content.Context;
import timber.log.Timber;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;

public class AdBlocker {
    
    private static final Set<String> AD_DOMAINS = new HashSet<>();

    static {
        // A lightweight list of common ad/tracker domains
        AD_DOMAINS.add("doubleclick.net");
        AD_DOMAINS.add("googleadservices.com");
        AD_DOMAINS.add("googlesyndication.com");
        AD_DOMAINS.add("moatads.com");
        AD_DOMAINS.add("adnxs.com");
        AD_DOMAINS.add("advertising.com");
        AD_DOMAINS.add("adtech.de");
        AD_DOMAINS.add("casalemedia.com");
        AD_DOMAINS.add("rubiconproject.com");
        AD_DOMAINS.add("openx.net");
        AD_DOMAINS.add("pubmatic.com");
        AD_DOMAINS.add("outbrain.com");
        AD_DOMAINS.add("taboola.com");
        AD_DOMAINS.add("criteo.com");
        AD_DOMAINS.add("amazon-adsystem.com");
        AD_DOMAINS.add("quantserve.com");
        AD_DOMAINS.add("scorecardresearch.com");
        AD_DOMAINS.add("facebook.net");
        AD_DOMAINS.add("fontawesome.com"); // Often used for tracking/heavy icons
        AD_DOMAINS.add("google-analytics.com");
    }

    public static boolean isAd(String url) {
        if (url == null || url.isEmpty()) return false;
        
        for (String domain : AD_DOMAINS) {
            if (url.contains(domain)) {
                Timber.d("Blocking ad/tracker: " + url);
                return true;
            }
        }
        return false;
    }

    public static WebResourceResponse createEmptyResource() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
    }
}
