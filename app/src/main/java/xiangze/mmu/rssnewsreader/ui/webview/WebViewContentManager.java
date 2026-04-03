package xiangze.mmu.rssnewsreader.ui.webview;

import android.util.Log;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.EntryInfo;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class WebViewContentManager {
    private static final String TAG = "WebViewContentManager";
    private final WebView webView;
    private final WebViewViewModel viewModel;
    private final SharedPreferencesRepository sharedPreferencesRepository;
    private final WebViewListener listener;

    public WebViewContentManager(WebView webView, WebViewViewModel viewModel, SharedPreferencesRepository sharedPreferencesRepository, WebViewListener listener) {
        this.webView = webView;
        this.viewModel = viewModel;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        this.listener = listener;
    }

    public void loadHtml(String html, long currentId) {
        if (html == null || html.trim().isEmpty()) return;

        Single.fromCallable(() -> {
            String processedHtml = html;
            if (html.contains("--####--")) {
                processedHtml = html.replace("--####--", "<br><br>");
            }

            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(processedHtml);
            doc.head().append(viewModel.getStyle(sharedPreferencesRepository.getNight()));

            EntryInfo entryInfo = viewModel.getEntryInfoById(currentId);
            if (entryInfo != null && doc.selectFirst(".entry-header") == null) {
                doc.selectFirst("body").prepend(
                        viewModel.getHtml(
                                entryInfo.getEntryTitle(),
                                entryInfo.getFeedTitle(),
                                entryInfo.getEntryPublishedDate(),
                                entryInfo.getFeedImageUrl(),
                                sharedPreferencesRepository.getNight()
                        )
                );
            }
            return doc.html();
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(htmlResult -> {
            webView.loadDataWithBaseURL("file:///android_res/", htmlResult, "text/html", "UTF-8", null);
            webView.setAlpha(1.0f);
            
            if (listener != null) {
                listener.hideFakeLoading();
                listener.finishedSetup();
            }

            webView.postDelayed(() -> {
                int scrollX = sharedPreferencesRepository.getScrollX(currentId);
                int scrollY = sharedPreferencesRepository.getScrollY(currentId);
                webView.scrollTo(scrollX, scrollY);
            }, 300);
        }, throwable -> {
            Log.e(TAG, "Error processing HTML", throwable);
        });
    }

    public void highlightText(String searchText) {
        if (sharedPreferencesRepository.getHighlightText()) {
            String text = searchText.trim();
            if (text.length() < 2) return;

            String escapedText = text.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");

            String js = "(function() {" +
                    "  try {" +
                    "    var text = '" + escapedText + "';" +
                    "    if (!text) return;" +
                    "    window.ttsHighlightId = (window.ttsHighlightId || 0) + 1;" +
                    "    var myId = window.ttsHighlightId;" +
                    "    " +
                    "    function clean(s) { return s.replace(/^[.,!?;:\"'\\s]+/, '').replace(/[.,!?;:\"'\\s]+$/, '').trim(); }" +
                    "    var cleanSearchText = clean(text);" +
                    "    if (!cleanSearchText) return;" +
                    "    " +
                    "    function removeHighlights() {" +
                    "      var highlights = document.querySelectorAll('.tts-highlight');" +
                    "      highlights.forEach(function(el) {" +
                    "        var parent = el.parentNode;" +
                    "        if (parent) {" +
                    "          var textNode = document.createTextNode(el.textContent);" +
                    "          parent.replaceChild(textNode, el);" +
                    "          parent.normalize();" +
                    "        }" +
                    "      });" +
                    "    }" +
                    "    " +
                    "    var attempts = 0;" +
                    "    function tryHighlight() {" +
                    "      if (window.ttsHighlightId !== myId) return;" +
                    "      if (document.readyState !== 'complete' && attempts < 3) {" +
                    "        attempts++;" +
                    "        setTimeout(tryHighlight, 500);" +
                    "        return;" +
                    "      }" +
                    "      removeHighlights();" +
                    "      var nodes = [];" +
                    "      var fullText = '';" +
                    "      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {" +
                    "        acceptNode: function(node) {" +
                    "          var p = node.parentNode;" +
                    "          if (!p) return NodeFilter.FILTER_REJECT;" +
                    "          var tag = p.tagName.toUpperCase();" +
                    "          if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return NodeFilter.FILTER_REJECT;" +
                    "          return NodeFilter.FILTER_ACCEPT;" +
                    "        }" +
                    "      }, false);" +
                    "      var node;" +
                    "      while(node = walker.nextNode()) {" +
                    "        nodes.push({ node: node, start: fullText.length, end: fullText.length + node.nodeValue.length });" +
                    "        fullText += node.nodeValue;" +
                    "      }" +
                    "      var index = fullText.indexOf(text);" +
                    "      var matchLen = text.length;" +
                    "      if (index === -1) {" +
                    "        index = fullText.indexOf(cleanSearchText);" +
                    "        matchLen = cleanSearchText.length;" +
                    "      }" +
                    "      if (index !== -1) {" +
                    "        var matchEnd = index + matchLen;" +
                    "        var firstMark = null;" +
                    "        for (var i = 0; i < nodes.length; i++) {" +
                    "          var m = nodes[i];" +
                    "          if (m.end > index && m.start < matchEnd) {" +
                    "            var offsetStart = Math.max(0, index - m.start);" +
                    "            var offsetEnd = Math.min(m.node.nodeValue.length, matchEnd - m.start);" +
                    "            var range = document.createRange();" +
                    "            range.setStart(m.node, offsetStart);" +
                    "            range.setEnd(m.node, offsetEnd);" +
                    "            var mark = document.createElement('mark');" +
                    "            mark.className = 'tts-highlight';" +
                    "            try {" +
                    "              range.surroundContents(mark);" +
                    "              if (!firstMark) firstMark = mark;" +
                    "            } catch(e) { console.warn('Highlight failed', e); }" +
                    "          }" +
                    "        }" +
                    "        if (firstMark) firstMark.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                    "      } else if (attempts < 2) {" +
                    "        attempts++;" +
                    "        setTimeout(tryHighlight, 800);" +
                    "      }" +
                    "    }" +
                    "    tryHighlight();" +
                    "  } catch(e) {" +
                    "    console.error('Highlight error:', e);" +
                    "  }" +
                    "})();";

            webView.post(() -> webView.evaluateJavascript(js, null));
        }
    }
}
