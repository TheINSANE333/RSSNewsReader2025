package xiangze.mmu.rssnewsreader.service.util;

import android.annotation.SuppressLint;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;
import xiangze.mmu.rssnewsreader.data.ai.Message;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.ai.AiClient;

public class TextUtil {
    public static final String TAG = TextUtil.class.getSimpleName();
    private static final Semaphore GLOBAL_TRANSLATION_LOCK = new Semaphore(1, true);
    private static final Semaphore GLOBAL_SUMMARIZATION_LOCK = new Semaphore(1, true);
    private final CompositeDisposable compositeDisposable;
    private final SharedPreferencesRepository sharedPreferencesRepository;

    @Inject
    public TextUtil(SharedPreferencesRepository sharedPreferencesRepository) {
        this.sharedPreferencesRepository = sharedPreferencesRepository;
        compositeDisposable = new CompositeDisposable();
    }

    public String extractHtmlContent(String html, String delimiter) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        Document doc = Jsoup.parse(html);
        StringBuilder content = new StringBuilder();

        // Initialize the sentence iterator
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);

        // 1. Extract structured elements - Added h1 and div
        Elements elements = doc.select(
                "h1, h2, h3, h4, h5, h6, p, td, th, li, figcaption, blockquote, section, pre, div"
        );

        for (Element element : elements) {
            // Check if any ancestor is also in the selected elements to avoid double counting
            boolean hasSelectedAncestor = false;
            Element parent = element.parent();
            while (parent != null) {
                if (elements.contains(parent)) {
                    hasSelectedAncestor = true;
                    break;
                }
                parent = parent.parent();
            }

            if (hasSelectedAncestor) continue;

            String text = element.text().trim();
            if (!text.isEmpty()) {
                appendSentences(content, text, delimiter, iterator);
            }
        }

        // 2. Extract orphan text nodes under <body>
        for (TextNode node : doc.body().textNodes()) {
            String text = node.text().trim();
            if (!text.isEmpty()) {
                appendSentences(content, text, delimiter, iterator);
            }
        }

        // Remove trailing delimiter
        String result = content.toString();
        if (result.endsWith(delimiter)) {
            result = result.substring(0, result.length() - delimiter.length());
        }

        return result;
    }

    private void appendSentences(StringBuilder sb, String text, String delimiter, BreakIterator iterator) {
        iterator.setText(text);
        int start = iterator.first();
        int end = iterator.next();

        while (end != BreakIterator.DONE) {
            String candidate = text.substring(start, end);
            String trimmed = candidate.trim();

            // Check if the sentence ends with a common abbreviation
            if (endsWithAbbreviation(trimmed)) {
                int nextEnd = iterator.next();
                if (nextEnd != BreakIterator.DONE) {
                    end = nextEnd;
                    continue;
                }
            }

            if (!trimmed.isEmpty()) {
                sb.append(trimmed).append(delimiter);
            }
            start = end;
            end = iterator.next();
        }
    }

    public String splitIntoSentences(String text, String delimiter) {
        if (text == null || text.isEmpty()) return "";
        
        StringBuilder sb = new StringBuilder();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(text);
        
        int start = iterator.first();
        int end = iterator.next();

        while (end != BreakIterator.DONE) {
            String candidate = text.substring(start, end);
            String trimmed = candidate.trim();

            if (endsWithAbbreviation(trimmed)) {
                int nextEnd = iterator.next();
                if (nextEnd != BreakIterator.DONE) {
                    end = nextEnd;
                    continue;
                }
            }

            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) sb.append(delimiter);
                sb.append(trimmed);
            }
            start = end;
            end = iterator.next();
        }
        return sb.toString();
    }

    public boolean endsWithAbbreviation(String text) {
        if (text == null || text.isEmpty() || !text.endsWith(".")) {
            return false;
        }

        // New rule: 1, 2, or 3 letters followed by a period at the end of the segment
        // are treated as abbreviations (e.g., "Mr.", "St.", "Jan.", "A.") to prevent breaking.
        if (text.matches(".*\\b[a-zA-Z]{1,3}\\.$")) {
            return true;
        }

        String abbreviationList = sharedPreferencesRepository.getAbbreviationList();
        String[] abbreviations = abbreviationList.split(",");
        for (String abbr : abbreviations) {
            String trimmedAbbr = abbr.trim();
            if (!trimmedAbbr.isEmpty() && text.endsWith(trimmedAbbr)) {
                return true;
            }
        }
        return false;
    }

    // Translate text element by element
    // Pro: Preserves the HTML structure of the text (e.g. <h1> remains <h1>, <h2> remains <h2>, <p> remains <p>)
    // Con: Slower performance (e.g. translating a very long content (198 elements) can take up to 5 minutes.
    //        In contrast, using the translateAllAtOnce method reduces this time to 2 minutes).
    // Note: Specifying maxConcurrency in x.flatMap (tried with 10 and 100) showed no noticeable difference in performance
    //        compared to leaving it unspecified.
    @SuppressLint("CheckResult")
    public Single<String> translateHtmlLineByLine(String sourceLanguage, String targetLanguage, String html, String title, long articleId, Consumer<Integer> progressCallback) {
        Log.d(TAG, "translateHtmlLineByLine: from " + sourceLanguage + " to " + targetLanguage);
        return Single.create(emitter -> {
            try {
                // First, translate the title
                translateText(sourceLanguage, targetLanguage, title)
                        .flatMap(translatedTitle -> {
                            // Parse the HTML
                            Document document = Jsoup.parse(html);
                            // List of tags to extract text from - Added h1 and div
                            List<String> tags = Arrays.asList("h1", "h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section", "div");
                            // Get all elements with the specified tags
                            Elements elements = document.select(String.join(",", tags));

                            // Check if the translated title has already been prepended
                            Element existingTitleElement = document.select("p.translated-title").first();
                            if (existingTitleElement == null) {
                                Element titleParagraph = new Element(Tag.valueOf("p"), "");
                                titleParagraph.text(translatedTitle);
                                titleParagraph.addClass("translated-title");
                                titleParagraph.attr("data-article-id", String.valueOf(articleId));
                                document.body().prependChild(titleParagraph);
                            }

                            AtomicInteger translatedElements = new AtomicInteger(0);
                            // Create a Flowable from the elements
                            return Flowable.fromIterable(elements)
                                    .flatMapMaybe(element -> {
                                        if (element.hasText()) {
                                            return translateText(sourceLanguage, targetLanguage, element.text())
                                                    .map(translatedText -> {
                                                        element.text(translatedText);
                                                        return translatedText;
                                                    })
                                                    .toMaybe();
                                        }
                                        return Maybe.empty();
                                    })
                                    .doOnNext(translatedText -> {
                                        // Emit progress update
                                        if (elements.size() > 0) {
                                            int progress = (int) (100.0 * (translatedElements.incrementAndGet()) / elements.size());
                                            try {
                                                progressCallback.accept(progress);
                                            } catch (Throwable e) {
                                                Log.e(TAG, "Progress callback failed", e);
                                            }
                                        }
                                    })
                                    .toList()
                                    .map(ignored -> document.outerHtml());
                        })
                        .subscribe(
                                emitter::onSuccess,
                                emitter::onError
                        );
            } catch (Exception e) {
                emitter.onError(e);
            }
        });
    }

//    private String createTranslationPrompt(String source, String target, String content, String title) {
//        return "Translate the following HTML content from " + source + " to " + target + ".\n" +
//                "Title context: " + title + "\n\n" +
//                "HTML Content:\n" + content;
//    }

    private String translateChunkWithRetry(AiClient aiClient, List<Message> messages, String model) {
        try {
            return aiClient.getChatResponse(messages, model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String summarizeChunkWithRetry(AiClient aiClient, List<Message> messages, String model) {
        try {
            return aiClient.getChatResponse(messages, model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Translation Method: Concat texts from all the elements then translate the concatenated text
    // Pro: Faster performance. (e.g. translating very long content (198 elements) takes only 2 minutes,
    //      compared to 5 minutes with the translateLineByLine method.
    //      (However, it's worth noting that despite being faster, this method's speed is still limited due to the MLKit Model's lack of optimization for long text. The speed of translation also heavily depends on the device specifications, with devices having more memory typically performing faster due to the use of TensorFlow as the backbone.)
    // Con:
    // 1. Special tags are replaced with <p>. Attempting to retain original tags such as <h1> or <h2> often results in errors due to discrepancies in
    //    element count after splitting the translated string using a delimiter (e.g. totalTextToTranslate: 198, totalTranslatedTexts: 210).
    //    Therefore, all tags are replaced with <p>.
    // 2. Due to limitations in the MLKit API translation model, it may not accurately separate lines, resulting in some text remaining untranslated.
    // Note:
    // 1. The delimiter used for splitting can also be translated (e.g., original delimiter ===@@@=== might become ==@== or @@ after translation). Therefore, a regex is used to split the translated text.
    // 2. The choice of delimiter can affect the translation. After testing various options like <br>, &nbsp;, and other character combinations, "++++++@@@@@@++++++" gave the best results.
    // 3. The accuracy of translation can sometimes be compromised, resulting in unusual or unexpected translations.
    // 4. MLKit uses English as an intermediate language for translation. For example, when translating from Chinese to Malay, the process is actually Chinese -> English -> Malay. This indirect translation process may affect the quality of the final translation.
    public Single<String> translateHtmlAllAtOnce(String sourceLanguage, String targetLanguage, String html, String title, long articleId, Consumer<Integer> progressCallback, boolean isPriority) {
        return Single.defer(() -> {

            Log.d(TAG, "Attempting to acquire Translation Lock for ID: " + articleId + " (Priority: " + isPriority + ")");

            try {
                if (isPriority) {
                    // To jump a fair semaphore's queue, we can't use acquire().
                    // We need to use tryAcquire() repeatedly or use a different sync primitive.
                    // For now, let's use a loop with tryAcquire to "cut" the line.
                    while (!GLOBAL_TRANSLATION_LOCK.tryAcquire()) {
                        Thread.sleep(50); // Small wait to avoid spinning too hard
                    }
                } else {
                    GLOBAL_TRANSLATION_LOCK.acquire();
                }
            } catch (InterruptedException e) {
                return Single.error(e);
            }

            Log.d(TAG, "Lock Acquired. Starting translation for ID: " + articleId);

            // 2. RUN: Your existing translation logic goes here.
            // Ensure this returns a Single<String>.
            return performActualTranslation(sourceLanguage, targetLanguage, html, title, articleId, progressCallback)
                    .doFinally(() -> {
                        // 3. RELEASE: This runs whether the translation Succeeds OR Fails.
                        // It is critical to ensure the next item in line can proceed.
                        GLOBAL_TRANSLATION_LOCK.release();
                        Log.d(TAG, "Lock Released for ID: " + articleId);
                    });
        });
    }

    public Single<String> summarizeHtmlRx(String html, String title, int length) {
        return summarizeHtmlRx(html, title, length, false);
    }

    public Single<String> summarizeHtmlRx(String html, String title, int length, boolean isPriority) {
        String targetLang = sharedPreferencesRepository.getDefaultTranslationLanguage();
        return identifyLanguageRx(html)
                .flatMap(sourceLang -> summarizeHtmlAllAtOnce(sourceLang, targetLang, html, length, 0, title, progress -> {}, isPriority));
    }

    public Single<String> summarizeHtmlAllAtOnce(String sourceLanguage, String targetLanguage, String html, int length, long articleId, String title, Consumer<Integer> progressCallback, boolean isPriority) {
        return Single.defer(() -> {

            Log.d(TAG, "Attempting to acquire Summarization Lock for ID: " + articleId + " (Priority: " + isPriority + ")");

            try {
                if (isPriority) {
                    while (!GLOBAL_SUMMARIZATION_LOCK.tryAcquire()) {
                        Thread.sleep(50);
                    }
                } else {
                    GLOBAL_SUMMARIZATION_LOCK.acquire();
                }
            } catch (InterruptedException e) {
                return Single.error(e);
            }

            Log.d(TAG, "Lock Acquired. Starting summarization for ID: " + articleId);

            // 2. RUN: Your existing translation logic goes here.
            // Ensure this returns a Single<String>.
            return performActualSummarization(sourceLanguage, targetLanguage, html, length, articleId, title, progressCallback)
                    .doFinally(() -> {
                        // 3. RELEASE: This runs whether the translation Succeeds OR Fails.
                        // It is critical to ensure the next item in line can proceed.
                        GLOBAL_SUMMARIZATION_LOCK.release();
                        Log.d(TAG, "Lock Released for ID: " + articleId);
                    });
        });
    }

    private Single<String> performActualTranslation(String sourceLanguage, String targetLanguage, String html, String title, long articleId, Consumer<Integer> progressCallback) {
        Log.d(TAG, "translateHtmlAllAtOnce: AI Mode - from " + sourceLanguage + " to " + targetLanguage);

        return Single.create(emitter -> {

            // 1. Keep the Simulated Progress Bar (Preserving original flow)
            AtomicInteger progress = new AtomicInteger(0);
            Thread progressThread = new Thread(() -> {
                try {
                    while (progress.get() < 90) {
                        Thread.sleep(300); // Simulate progress while waiting for AI
                        try {
                            progressCallback.accept(progress.incrementAndGet());
                        } catch (Throwable callbackException) {
                            Log.e(TAG, "Progress callback failed", callbackException);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            progressThread.start();

            try {
                // 2. Prepare the AI Client & Messages
                AiClient aiClient = new AiClient(sharedPreferencesRepository.getContext());
                List<Message> messages = new ArrayList<>();

                String baseSystemPrompt = "Translate the provided text to the target language. " +
                        "Preserve any HTML tags if present, but focus on translating the content. " +
                        "Please also translate the article title. " +
                        "Return the translated title and content in the following format:\n" +
                        "[TITLE] Translated Title Here\n" +
                        "[CONTENT] Translated HTML/Content Here\n" +
                        "Return ONLY the translated content in this format, without any other markers, headers, or additional text.";
                String customPrompt = sharedPreferencesRepository.getCustomTranslationPrompt();
                if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                    baseSystemPrompt += "\n\nAdditional Instructions:\n" + customPrompt;
                }

                messages.add(new Message(
                    "system",
                    baseSystemPrompt
                ));

                // Build the prompt
                messages.add(new Message(
                    "user",
                    String.format(
                            "Target Language: %s\nOriginal Title: %s\nText to translate:\n%s",
                            targetLanguage,
                            title,
                            html
                    )
                ));

                // 3. Execute Blocking Request (Safe inside Single.create)
                // Note: Ensure translateChunkWithRetry is accessible here
                String translationModel = sharedPreferencesRepository.getTranslationModel();
                String translatedHtml = translateChunkWithRetry(aiClient, messages, translationModel);
                Log.d(TAG, "AI Translation Response: " + translatedHtml);

                // 4. Stop Progress & Validate
                progressThread.interrupt();

                if (translatedHtml == null || translatedHtml.trim().isEmpty()) {
                    throw new Exception("AI returned empty response.");
                }

                // 5. Finalize Success
                try {
                    progressCallback.accept(100);
                } catch (Throwable e) {
                    Log.e(TAG, "Progress callback failed on completion", e);
                }

                Log.d(TAG, "Translation complete, size = " + translatedHtml.length());
                emitter.onSuccess(translatedHtml);

            } catch (Exception e) {
                // 6. Handle Errors
                progressThread.interrupt();
                try {
                    progressCallback.accept(0);
                } catch (Throwable callbackException) {
                    Log.e(TAG, "Progress callback failed on error reset", callbackException);
                }
                Log.e(TAG, "Translation error: " + e.getMessage(), e);
                emitter.onError(e);
            }
        });
    }

    private Single<String> performActualSummarization(String sourceLanguage, String targetLanguage, String html, int length, long articleId, String title, Consumer<Integer> progressCallback) {
        Log.d(TAG, "summarizeHtmlAllAtOnce: AI Mode - in" + length);

        return Single.create(emitter -> {

            // 1. Keep the Simulated Progress Bar (Preserving original flow)
            AtomicInteger progress = new AtomicInteger(0);
            Thread progressThread = new Thread(() -> {
                try {
                    while (progress.get() < 90) {
                        Thread.sleep(300); // Simulate progress while waiting for AI
                        try {
                            progressCallback.accept(progress.incrementAndGet());
                        } catch (Throwable callbackException) {
                            Log.e(TAG, "Progress callback failed", callbackException);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            progressThread.start();

            try {
                // 2. Prepare the AI Client & Messages
                AiClient aiClient = new AiClient(sharedPreferencesRepository.getContext());
                List<Message> messages = new ArrayList<>();

                boolean isSameLanguage = sourceLanguage != null && sourceLanguage.equalsIgnoreCase(targetLanguage);
                String baseSystemPrompt = "You are a helpful assistant designed to summarize web articles. " +
                        "Provide a concise summary of the content in the target language (" + targetLanguage + "). " +
                        "Strictly adhere to the requested summary length. " +
                        "Please also translate the article title to the target language. " +
                        "Return the translated title and summary in the following format:\n" +
                        "[TITLE] Translated Title Here\n" +
                        "[CONTENT] Summarized Content Here\n" +
                        "Return ONLY the summarized content as plain text (or HTML if appropriate), without any other markers, headers, or additional metadata.";
                
                if (isSameLanguage) {
                    baseSystemPrompt = "You are a helpful assistant designed to summarize web articles. " +
                            "Provide a concise summary of the content. " +
                            "Strictly adhere to the requested summary length. " +
                            "Return the summarized content in the following format:\n" +
                            "[CONTENT] Summarized Content Here\n" +
                            "Return ONLY the summarized content as plain text (or HTML if appropriate), without any other markers, headers, or additional metadata.";
                }

                String customPrompt = sharedPreferencesRepository.getCustomSummarizationPrompt();
                if (customPrompt != null && !customPrompt.trim().isEmpty()) {
                    baseSystemPrompt += "\n\nAdditional Instructions:\n" + customPrompt;
                }

                messages.add(new Message(
                        "system",
                        baseSystemPrompt
                ));

                // Extract clean content to save tokens and improve focus (matching manual mode)
                String cleanContent = extractHtmlContent(html, "--####--");

                // Fallback to basic text extraction if structured extraction yielded nothing
                // This prevents "There is no content to summarize" AI responses for poorly structured HTML
                if (cleanContent.trim().length() < 50) {
                    Log.d(TAG, "Structured extraction too short (" + cleanContent.length() + "). Falling back to Jsoup text.");
                    String basicText = Jsoup.parse(html).text();
                    if (basicText.length() > cleanContent.length()) {
                        cleanContent = basicText;
                    }
                }

                // Build the prompt (matching manual mode format)
                String prompt = String.format(
                        "Target Language: %s\nOriginal Title: %s\nRequested Summary Length: approximately %d words.\nContent to summarize:\n%s",
                        targetLanguage, title, length, cleanContent
                );

                messages.add(new Message("user", prompt));

                // 3. Execute Blocking Request (Safe inside Single.create)
                // Note: Ensure summarizeChunkWithRetry is accessible here
                String summarizationModel = sharedPreferencesRepository.getSummarizationModel();
                String summarizedHtml = summarizeChunkWithRetry(aiClient, messages, summarizationModel);
                Log.d(TAG, "AI Summary Response: " + summarizedHtml);

                // 4. Stop Progress & Validate
                progressThread.interrupt();

                if (summarizedHtml == null || summarizedHtml.trim().isEmpty()) {
                    throw new Exception("AI returned empty response.");
                }

                // 5. Finalize Success
                try {
                    progressCallback.accept(100);
                } catch (Throwable e) {
                    Log.e(TAG, "Progress callback failed on completion", e);
                }

                Log.d(TAG, "Summarization complete, size = " + summarizedHtml.length());
                emitter.onSuccess(summarizedHtml);

            } catch (Exception e) {
                // 6. Handle Errors
                progressThread.interrupt();
                try {
                    progressCallback.accept(0);
                } catch (Throwable callbackException) {
                    Log.e(TAG, "Progress callback failed on error reset", callbackException);
                }
                Log.e(TAG, "Summarization error: " + e.getMessage(), e);
                emitter.onError(e);
            }
        });
    }

    public List<String> getDailySummaryPrompts(List<xiangze.mmu.rssnewsreader.data.entry.Entry> entries, String targetLanguage) {
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<>();
        }

        final long MAX_WORDS_PER_CHUNK = sharedPreferencesRepository.getChunkLimit();
        final int MAX_FILES = sharedPreferencesRepository.getMaxFiles();
        List<String> prompts = new ArrayList<>();
        
        StringBuilder currentPrompt = new StringBuilder();
        long currentWords = 0;
        
        for (int i = 0; i < entries.size(); i++) {
            StringBuilder entryPart = new StringBuilder();
            xiangze.mmu.rssnewsreader.data.entry.Entry entry = entries.get(i);
            entryPart.append("--- Article ").append(i + 1).append(" ---\n");
            entryPart.append("Title: ").append(entry.getTitle()).append("\n");

            // Prefer full HTML content, then content, then description
            String rawContent = entry.getHtml();
            if (rawContent == null || rawContent.isEmpty()) {
                rawContent = entry.getContent();
            }
            if (rawContent == null || rawContent.isEmpty()) {
                rawContent = entry.getDescription();
            }
            
            if (rawContent != null && !rawContent.isEmpty()) {
                String cleanContent = Jsoup.parse(rawContent).text();
                entryPart.append("Content: ").append(cleanContent).append("\n\n");
            } else {
                entryPart.append("Content: [No content available]\n\n");
            }

            String partString = entryPart.toString();
            int partWords = countWords(partString);

            // If this article alone is huge, it might need to occupy its own chunk(s)
            if (partWords > MAX_WORDS_PER_CHUNK) {
                // If we already have content in currentPrompt, finish it
                if (currentWords > 0) {
                    prompts.add(currentPrompt.toString());
                    if (prompts.size() >= MAX_FILES) break;
                    currentPrompt = new StringBuilder();
                    currentWords = 0;
                }
                
                // For huge articles, we'll split the string into word-based chunks
                String[] words = partString.split("\\s+");
                StringBuilder hugePart = new StringBuilder();
                long hugeWords = 0;
                for (String word : words) {
                    if (hugeWords + 1 > MAX_WORDS_PER_CHUNK) {
                        prompts.add(hugePart.toString());
                        if (prompts.size() >= MAX_FILES) break;
                        hugePart = new StringBuilder();
                        hugeWords = 0;
                    }
                    hugePart.append(word).append(" ");
                    hugeWords++;
                }
                if (hugeWords > 0) {
                    currentPrompt.append(hugePart);
                    currentWords += hugeWords;
                }
            } else if (currentWords + partWords > MAX_WORDS_PER_CHUNK) {
                // Current chunk is full, start a new one
                prompts.add(currentPrompt.toString());
                if (prompts.size() >= MAX_FILES) break;
                
                currentPrompt = new StringBuilder();
                currentPrompt.append(partString);
                currentWords = partWords;
            } else {
                currentPrompt.append(partString);
                currentWords += partWords;
            }

            if (prompts.size() >= MAX_FILES) break;
        }

        if (currentWords > 0 && prompts.size() < MAX_FILES) {
            prompts.add(currentPrompt.toString());
        }

        // Add the system instruction at the END
        String customPromptTemplate = sharedPreferencesRepository.getDailySummaryPrompt();
        String systemInstruction;
        try {
            systemInstruction = String.format(customPromptTemplate, targetLanguage);
        } catch (Exception e) {
            systemInstruction = customPromptTemplate + " (Language: " + targetLanguage + ")\n\nArticles List:\n\n";
        }
        prompts.add(systemInstruction);

        return prompts;
    }

    private int countWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String[] words = text.trim().split("\\s+");
        return words.length;
    }

    public String getDailySummaryPrompt(List<xiangze.mmu.rssnewsreader.data.entry.Entry> entries, String targetLanguage) {
        List<String> prompts = getDailySummaryPrompts(entries, targetLanguage);
        if (prompts.isEmpty()) return null;
        return prompts.get(0); // For backward compatibility if needed, though we should update callers
    }

//    public Single<String> summarizeDailyNews(List<xiangze.mmu.rssnewsreader.data.entry.Entry> entries, String targetLanguage) {
//        return Single.create(emitter -> {
//            try {
//                if (entries == null || entries.isEmpty()) {
//                    emitter.onError(new Exception("No news articles to summarize."));
//                    return;
//                }
//
//                AiClient aiClient = new AiClient(sharedPreferencesRepository.getContext());
//                List<Message> messages = new ArrayList<>();
//
//                String systemPrompt = "You are a professional news anchor. Provide a concise daily news briefing based on the following headlines and summaries from today's unread articles. " +
//                        "Group related stories together and highlight the most important events. " +
//                        "Use clear headings and bullet points. " +
//                        "The response should be in " + targetLanguage + ". " +
//                        "Start your response with a catchy headline like 'Daily News Briefing - [Date]'.";
//
//                messages.add(new Message("system", systemPrompt));
//
//                StringBuilder userPrompt = new StringBuilder("Here are the news articles for today:\n\n");
//                for (int i = 0; i < entries.size(); i++) {
//                    xiangze.mmu.rssnewsreader.data.entry.Entry entry = entries.get(i);
//                    userPrompt.append(i + 1).append(". ").append(entry.getTitle()).append("\n");
//
//                    String content = entry.getSummarized();
//                    if (content == null || content.isEmpty()) {
//                        content = entry.getDescription();
//                    }
//                    if (content != null && !content.isEmpty()) {
//                        // Limit content per article to avoid context window issues
//                        if (content.length() > 500) {
//                            content = content.substring(0, 500) + "...";
//                        }
//                        userPrompt.append("Summary: ").append(content).append("\n");
//                    }
//                    userPrompt.append("\n");
//
//                    // Limit total input size if needed (e.g., first 20 articles)
//                    if (i >= 20) {
//                        userPrompt.append("... and more articles.");
//                        break;
//                    }
//                }
//
//                messages.add(new Message("user", userPrompt.toString()));
//
//                String summarizationModel = sharedPreferencesRepository.getSummarizationModel();
//                String summary = aiClient.getChatResponse(messages, summarizationModel);
//
//                if (summary == null || summary.trim().isEmpty()) {
//                    emitter.onError(new Exception("AI returned empty response."));
//                } else {
//                    emitter.onSuccess(summary);
//                }
//            } catch (Exception e) {
//                emitter.onError(e);
//            }
//        });
//    }

//    @SuppressLint("CheckResult")
//    public Single<String> translateHtmlByParagraph(String sourceLanguage, String targetLanguage, String html, String title, long articleId, Consumer<Integer> progressCallback) {
//        Log.d(TAG, "translateHtmlByParagraph: from " + sourceLanguage + " to " + targetLanguage);
//        Log.d(TAG, "translateHtmlByParagraph CALLED");
//        return Single.create(emitter -> {
//            try {
//                translateText(sourceLanguage, targetLanguage, title)
//                        .flatMap(translatedTitle -> {
//                            Document document = Jsoup.parse(html);
//                            List<String> tags = Arrays.asList("p", "section", "blockquote");
//                            Elements paragraphs = document.select(String.join(",", tags));
//                            Log.d(TAG, "Found " + paragraphs.size() + " paragraphs for translation");
//
//                            Element existingTitleElement = document.select("p.translated-title").first();
//                            if (existingTitleElement == null) {
//                                Element titleParagraph = new Element(Tag.valueOf("p"), "");
//                                titleParagraph.text(translatedTitle);
//                                titleParagraph.addClass("translated-title");
//                                titleParagraph.attr("data-article-id", String.valueOf(articleId));
//                                document.body().prependChild(titleParagraph);
//                            }
//
//                            AtomicInteger translatedCount = new AtomicInteger(0);
//                            int total = paragraphs.size();
//
//                            return Flowable.fromIterable(paragraphs)
//                                    .flatMapMaybe(paragraph -> {
//                                        if (paragraph.hasText()) {
//                                            return translateText(sourceLanguage, targetLanguage, paragraph.text())
//                                                    .map(translatedText -> {
//                                                        paragraph.text(translatedText);
//                                                        int progress = (int) ((translatedCount.incrementAndGet() / (float) total) * 100);
//                                                        try {
//                                                            progressCallback.accept(progress);
//                                                        } catch (Exception e) {
//                                                            Log.e(TAG, "Progress callback failed", e);
//                                                        }
//                                                        return translatedText;
//                                                    }).toMaybe();
//                                        }
//                                        return Maybe.empty();
//                                    })
//                                    .toList()
//                                    .map(ignored -> document.outerHtml());
//                        })
//                        .subscribe(
//                                emitter::onSuccess,
//                                error -> {
//                                    Log.e(TAG, "Error during paragraph translation", error);
//                                    emitter.onError(error);
//                                }
//                        );
//            } catch (Exception e) {
//                Log.e(TAG, "Unexpected error in translateHtmlByParagraph", e);
//                emitter.onError(e);
//            }
//        });
//    }

    public Single<String> translateText(String sourceLanguage, String targetLanguage, String text) {
        return Single.create(emitter -> {
            if (text == null || text.isEmpty()) {
                emitter.onError(new IllegalArgumentException("Invalid content for translation"));
                return;
            }

            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build();

            Translator translator = Translation.getClient(options);
            DownloadConditions conditions = new DownloadConditions.Builder().build();

            translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener(v -> translator.translate(text)
                            .addOnSuccessListener(emitter::onSuccess)
                            .addOnFailureListener(error -> {
                                Log.e(TAG, "Translation failed", error);
                                emitter.onError(error);
                            }))
                    .addOnFailureListener(error -> {
                        Log.e(TAG, "Model download failed", error);
                        emitter.onError(error);
                    });
        });
    }

    public Single<String> identifyLanguageRx(String sentence) {
        float confidenceThreshold = (float) sharedPreferencesRepository.getConfidenceThreshold() / 100;
        return identifyLanguageRx(sentence, confidenceThreshold);
    }

    public Single<String> identifyLanguageRx(String sentence, float confidenceThreshold) {
        LanguageIdentificationOptions options = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(confidenceThreshold)
                .build();

        LanguageIdentifier languageIdentifier = LanguageIdentification.getClient(options);

        return Single.fromCallable(() -> languageIdentifier.identifyLanguage(sentence))
                .subscribeOn(Schedulers.io())
                .map(languageCodeTask -> {
                    try {
                        String languageCode = Tasks.await(languageCodeTask);
                        if ("und".equals(languageCode)) {
                            Log.i(TAG, "Unable to identify language.");
                            return "und";
                        } else {
                            Log.i(TAG, "Identified language: " + languageCode);
                            return languageCode;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error identifying language", e);
                        return "und";
                    }
                })
                .onErrorReturnItem("und");
    }

    public static class ProcessedAiResponse {
        public final String html;
        public final String contentToRead;
        public final String title;
        public final String content;

        public ProcessedAiResponse(String html, String contentToRead, String title, String content) {
            this.html = html;
            this.contentToRead = contentToRead;
            this.title = title;
            this.content = content;
        }
    }

    public ProcessedAiResponse processAiResponse(String rawAiResponse, String defaultTitle, String feedTitle, java.util.Date publishDate, String feedImageUrl, boolean isNightMode, String titleClass) {
        AiResponse aiRes = parseAiResponse(rawAiResponse, defaultTitle);
        String finalHtml = formatAiResponseToHtml(
                aiRes.title,
                aiRes.content,
                feedTitle,
                publishDate,
                feedImageUrl,
                isNightMode,
                titleClass
        );

        // Standardized TTS content generation: Title + DELIMITER + split content sentences
        // This avoids including feed title and publish date in the TTS content
        String splitRes = splitIntoSentences(aiRes.content, xiangze.mmu.rssnewsreader.service.tts.TtsExtractor.DELIMITER);
        String contentToRead = aiRes.title + xiangze.mmu.rssnewsreader.service.tts.TtsExtractor.DELIMITER + splitRes;

        return new ProcessedAiResponse(finalHtml, contentToRead, aiRes.title, aiRes.content);
    }

    public static class AiResponse {
        public final String title;
        public final String content;

        public AiResponse(String title, String content) {
            this.title = title;
            this.content = content;
        }
    }

    public AiResponse parseAiResponse(String rawResponse, String defaultTitle) {
        if (rawResponse == null) return new AiResponse(defaultTitle, "");

        String cleaned = rawResponse
                .replaceAll("(?s)^\\s*```[a-zA-Z]*\\n?", "")
                .replaceAll("(?s)\\n?```\\s*$", "")
                .trim();

        String title = defaultTitle;
        String content = cleaned;

        java.util.regex.Pattern titlePattern = java.util.regex.Pattern.compile("(?i)(?:\\*\\*)?\\[(?:translated\\s+|summarized\\s+)?title\\](?:\\*\\*)?[:\\-—\\s]*");
        java.util.regex.Pattern contentPattern = java.util.regex.Pattern.compile("(?i)(?:\\*\\*)?\\[(?:translated\\s+|summarized\\s+)?content\\](?:\\*\\*)?[:\\-—\\s]*");

        java.util.regex.Matcher titleMatcher = titlePattern.matcher(cleaned);
        java.util.regex.Matcher contentMatcher = contentPattern.matcher(cleaned);

        if (titleMatcher.find() && contentMatcher.find()) {
            int titleEnd = titleMatcher.end();
            int contentStart = contentMatcher.start();

            if (titleMatcher.start() < contentStart) {
                title = cleaned.substring(titleEnd, contentStart).trim();
                content = cleaned.substring(contentMatcher.end()).trim();
            } else {
                // In case markers are swapped: [CONTENT] ... [TITLE] ...
                content = cleaned.substring(contentMatcher.end(), titleMatcher.start()).trim();
                title = cleaned.substring(titleEnd).trim();
            }
        } else if (contentMatcher.find()) {
            content = cleaned.substring(contentMatcher.end()).trim();
        }

        // Final cleanup of the content to remove any leftover redundant markers
        String markerCleanupRegex = "(?i)(?:\\*\\*)?\\[(?:translated\\s+|summarized\\s+)?(?:title|content|summary|article|text|translated|summarized)\\](?:\\*\\*)?[:\\-—\\s]*";
        title = title.replaceAll(markerCleanupRegex, "").trim();
        content = content.replaceAll(markerCleanupRegex, "").trim();

        // Handle common AI prefixes without brackets (e.g., "Summary: ", "Translated Title: ")
        String prefixCleanupRegex = "(?i)^(?:translated\\s+|summarized\\s+)?(?:title|content|summary|article|text|translated|summarized)[:\\-—\\s]+";
        title = title.replaceAll(prefixCleanupRegex, "").trim();
        content = content.replaceAll(prefixCleanupRegex, "").trim();

        // Also remove empty brackets "[]" or "[ ]" which might be leftover from AI confusion
        String emptyBracketsRegex = "\\[\\s*\\]\\s*";
        title = title.replaceAll(emptyBracketsRegex, "").trim();
        content = content.replaceAll(emptyBracketsRegex, "").trim();

        // Strip wrapping brackets if the entire content is enclosed in them
        if (title.startsWith("[") && title.endsWith("]")) {
            title = title.substring(1, title.length() - 1).trim();
        }
        if (content.startsWith("[") && content.endsWith("]")) {
            content = content.substring(1, content.length() - 1).trim();
        }

        // Remove title if it somehow leaked into the start of the content
        if (content.toLowerCase().startsWith(title.toLowerCase())) {
            String potentialContent = content.substring(title.length()).trim();
            if (potentialContent.startsWith(":") || potentialContent.startsWith("-") || potentialContent.startsWith("—")) {
                potentialContent = potentialContent.substring(1).trim();
            }
            if (!potentialContent.isEmpty()) {
                content = potentialContent;
            }
        }

        return new AiResponse(title, content);
    }

    @SuppressLint("SimpleDateFormat")
    public String formatAiResponseToHtml(String title, String content, String feedTitle, java.util.Date publishDate, String feedImageUrl, boolean isNightMode, String titleClass) {
        String textColor = isNightMode ? "#E2E2E6" : "#1B1B1F";
        String classAttr = (titleClass != null && !titleClass.isEmpty()) ? " class=\"" + titleClass + "\"" : "";
        
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head>");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("</head><body>");
        
        sb.append("<div class=\"entry-header\" style=\"color: ").append(textColor).append("\">");
        sb.append("  <div style=\"display: flex; align-items: center;\">");
        sb.append("    <img style=\"margin-right: 10px; width: 20px; height: 20px\" src=\"").append(feedImageUrl).append("\">");
        sb.append("    <p style=\"font-size: 0.75em\">").append(feedTitle).append("</p>");
        sb.append("  </div>");
        sb.append("  <p").append(classAttr).append(" style=\"margin:0; font-size: 1.25em; font-weight:bold\">").append(title).append("</p>");
        sb.append("  <p style=\"font-size: 0.75em;\">").append(new java.text.SimpleDateFormat("EEE, d MMM yyyy 'at' hh:mm aaa").format(publishDate)).append("</p>");
        sb.append("</div>");
        
        // Wrap content in paragraphs if it doesn't look like HTML
        if (!content.trim().startsWith("<")) {
            String[] paragraphs = content.split("\\n\\n+");
            for (String p : paragraphs) {
                if (!p.trim().isEmpty()) {
                    sb.append("<p>").append(p.trim().replace("\n", "<br>")).append("</p>");
                }
            }
        } else {
            sb.append(content);
        }
        
        sb.append("</body></html>");
        return sb.toString();
    }

    public String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        try {
            // Force https and lowercase for consistency
            String normalized = url.replace("http://", "https://");
            
            // Strip tracking parameters
            normalized = normalized.split("\\?")[0];
            
            if (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            
            // Strip common feedproxy/redirector noise if possible
            if (normalized.contains("feedproxy.google.com")) {
                return normalized;
            }
            return normalized.toLowerCase();
        } catch (Exception e) {
            return url;
        }
    }

    public String applyTtsSubstitutions(String text) {
        if (text == null || text.isEmpty()) return text;
        java.util.Map<String, String> substitutions = sharedPreferencesRepository.getTtsSubstitutions();
        if (substitutions.isEmpty()) return text;

        String result = text;
        for (java.util.Map.Entry<String, String> entry : substitutions.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && !key.isEmpty() && value != null) {
                result = result.replace(key, value);
            }
        }
        return result;
    }

    public boolean isErrorContent(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        String trimmed = text.trim();
        
        // Too short to be a real article
        if (trimmed.length() < 100) {
            // Check for common error indicators in short text
            String lower = trimmed.toLowerCase();
            if (lower.contains("extraction failed") || 
                lower.contains("not found") || 
                lower.contains("404") || 
                lower.contains("error") || 
                lower.contains("denied") ||
                lower.contains("forbidden") ||
                lower.contains("timeout") ||
                lower.contains("no content")) {
                return true;
            }
            
            // If it's very short and doesn't look like a title/snippet, it's likely an error
            return trimmed.length() < 30; 
        }
        
        return false;
    }

    public void onDestroy() {
        compositeDisposable.dispose();
    }
}