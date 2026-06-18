package xiangze.mmu.rssnewsreader.service.util;

import android.annotation.SuppressLint;
import timber.log.Timber;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

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

import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;
import xiangze.mmu.rssnewsreader.data.ai.Message;
import xiangze.mmu.rssnewsreader.data.sharedpreferences.SharedPreferencesRepository;
import xiangze.mmu.rssnewsreader.model.ai.AiClient;

public class TextUtil {
    public static final String TAG = TextUtil.class.getSimpleName();
    private static final Semaphore GLOBAL_TRANSLATION_LOCK = new Semaphore(1, true);
    private static final Semaphore GLOBAL_SUMMARIZATION_LOCK = new Semaphore(1, true);

    private final SharedPreferencesRepository sharedPreferencesRepository;

    @Inject
    public TextUtil(SharedPreferencesRepository sharedPreferencesRepository) {
        this.sharedPreferencesRepository = sharedPreferencesRepository;

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

            Timber.d("Attempting to acquire Translation Lock for ID: " + articleId + " (Priority: " + isPriority + ")");

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

            Timber.d("Lock Acquired. Starting translation for ID: " + articleId);

            // 2. RUN: Your existing translation logic goes here.
            // Ensure this returns a Single<String>.
            return performActualTranslation(sourceLanguage, targetLanguage, html, title, articleId, progressCallback)
                    .doFinally(() -> {
                        // 3. RELEASE: This runs whether the translation Succeeds OR Fails.
                        // It is critical to ensure the next item in line can proceed.
                        GLOBAL_TRANSLATION_LOCK.release();
                        Timber.d("Lock Released for ID: " + articleId);
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

            Timber.d("Attempting to acquire Summarization Lock for ID: " + articleId + " (Priority: " + isPriority + ")");

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

            Timber.d("Lock Acquired. Starting summarization for ID: " + articleId);

            // 2. RUN: Your existing translation logic goes here.
            // Ensure this returns a Single<String>.
            return performActualSummarization(sourceLanguage, targetLanguage, html, length, articleId, title, progressCallback)
                    .doFinally(() -> {
                        // 3. RELEASE: This runs whether the translation Succeeds OR Fails.
                        // It is critical to ensure the next item in line can proceed.
                        GLOBAL_SUMMARIZATION_LOCK.release();
                        Timber.d("Lock Released for ID: " + articleId);
                    });
        });
    }

    private Single<String> performActualTranslation(String sourceLanguage, String targetLanguage, String html, String title, long articleId, Consumer<Integer> progressCallback) {
        Timber.d("translateHtmlAllAtOnce: AI Mode - from " + sourceLanguage + " to " + targetLanguage);

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
                            Timber.e(callbackException, "Progress callback failed");
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

                // Validate content before sending to AI - prevent translating error pages
                if (isErrorHtml(html)) {
                    progressThread.interrupt();
                    throw new Exception("Article content appears to be an error page or failed to load properly. Please retry.");
                }

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
                Timber.d("AI Translation Response: " + translatedHtml);

                // 4. Stop Progress & Validate
                progressThread.interrupt();

                if (translatedHtml == null || translatedHtml.trim().isEmpty()) {
                    throw new Exception("AI returned empty response.");
                }

                // 5. Finalize Success
                try {
                    progressCallback.accept(100);
                } catch (Throwable e) {
                    Timber.e(e, "Progress callback failed on completion");
                }

                Timber.d("Translation complete, size = " + translatedHtml.length());
                emitter.onSuccess(translatedHtml);

            } catch (Exception e) {
                // 6. Handle Errors
                progressThread.interrupt();
                try {
                    progressCallback.accept(0);
                } catch (Throwable callbackException) {
                    Timber.e(callbackException, "Progress callback failed on error reset");
                }
                Timber.e(e, "Translation error: " + e.getMessage());
                emitter.onError(e);
            }
        });
    }

    private Single<String> performActualSummarization(String sourceLanguage, String targetLanguage, String html, int length, long articleId, String title, Consumer<Integer> progressCallback) {
        Timber.d("summarizeHtmlAllAtOnce: AI Mode - in" + length);

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
                            Timber.e(callbackException, "Progress callback failed");
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
                    Timber.d("Structured extraction too short (" + cleanContent.length() + "). Falling back to Jsoup text.");
                    String basicText = Jsoup.parse(html).text();
                    if (basicText.length() > cleanContent.length()) {
                        cleanContent = basicText;
                    }
                }

                // Validate content before sending to AI - prevent summarizing error pages
                if (isErrorContent(cleanContent) || isErrorHtml(html)) {
                    progressThread.interrupt();
                    throw new Exception("Article content appears to be an error page or failed to load properly. Please retry.");
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
                Timber.d("AI Summary Response: " + summarizedHtml);

                // 4. Stop Progress & Validate
                progressThread.interrupt();

                if (summarizedHtml == null || summarizedHtml.trim().isEmpty()) {
                    throw new Exception("AI returned empty response.");
                }

                // 5. Finalize Success
                try {
                    progressCallback.accept(100);
                } catch (Throwable e) {
                    Timber.e(e, "Progress callback failed on completion");
                }

                Timber.d("Summarization complete, size = " + summarizedHtml.length());
                emitter.onSuccess(summarizedHtml);

            } catch (Exception e) {
                // 6. Handle Errors
                progressThread.interrupt();
                try {
                    progressCallback.accept(0);
                } catch (Throwable callbackException) {
                    Timber.e(callbackException, "Progress callback failed on error reset");
                }
                Timber.e(e, "Summarization error: " + e.getMessage());
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





    public Single<String> identifyLanguageRx(String sentence) {
        float confidenceThreshold = (float) sharedPreferencesRepository.getConfidenceThreshold() / 100;
        return identifyLanguageRx(sentence, confidenceThreshold);
    }

    public Single<String> identifyLanguageRx(String sentence, float confidenceThreshold) {
        if (sentence == null || sentence.trim().isEmpty()) {
            return Single.just("und");
        }

        // Strip HTML if it looks like HTML to improve language detection accuracy
        String cleanText = sentence;
        if (sentence.contains("<") && sentence.contains(">")) {
            try {
                // Parse and remove non-content elements that might contain English boilerplate
                Document doc = Jsoup.parse(sentence);
                doc.select("script, style, head, header, footer, nav").remove();
                cleanText = doc.text();
            } catch (Exception e) {
                Timber.w("Failed to parse HTML for language identification, using raw text");
            }
        }

        // If cleaning resulted in empty string, fallback to original
        final String textToIdentify = (cleanText == null || cleanText.trim().isEmpty()) ? sentence : cleanText;

        LanguageIdentificationOptions options = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(confidenceThreshold)
                .build();

        LanguageIdentifier languageIdentifier = LanguageIdentification.getClient(options);

        return Single.fromCallable(() -> languageIdentifier.identifyLanguage(textToIdentify))
                .subscribeOn(Schedulers.io())
                .map(languageCodeTask -> {
                    try {
                        String languageCode = Tasks.await(languageCodeTask);
                        if ("und".equals(languageCode)) {
                            Timber.i("Unable to identify language.");
                            return "und";
                        } else {
                            Timber.i("Identified language: " + languageCode);
                            return languageCode;
                        }
                    } catch (Exception e) {
                        Timber.e(e, "Error identifying language");
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
        
        if (isAiRefusalResponse(aiRes.content)) {
            throw new IllegalArgumentException("AI response indicates a refusal or error: " + aiRes.content);
        }

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
        String cleanContent = Jsoup.parse(aiRes.content).text();
        String cleanTitle = Jsoup.parse(aiRes.title).text();
        String splitRes = splitIntoSentences(cleanContent, xiangze.mmu.rssnewsreader.service.tts.TtsExtractor.DELIMITER);
        String contentToRead = cleanTitle + xiangze.mmu.rssnewsreader.service.tts.TtsExtractor.DELIMITER + splitRes;

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
        String textColor = isNightMode ? "#E2E2E6" : "#000000";
        String classAttr = (titleClass != null && !titleClass.isEmpty()) ? " class=\"" + titleClass + "\"" : "";
        
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head>");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("</head><body>");
        
        sb.append("<div class=\"entry-header\">");
        sb.append("  <div style=\"display: flex; align-items: center;\">");
        sb.append("    <img style=\"margin-right: 10px; width: 20px; height: 20px\" src=\"").append(feedImageUrl).append("\">");
        sb.append("    <p class=\"feed-title\" style=\"font-size: 0.75em\">").append(feedTitle).append("</p>");
        sb.append("  </div>");
        sb.append("  <p").append(classAttr).append(" style=\"margin:0; font-size: 1.25em; font-weight:bold\">").append(title).append("</p>");
        String dateStr = "";
        if (publishDate != null) {
            try {
                dateStr = new java.text.SimpleDateFormat("EEE, d MMM yyyy 'at' hh:mm aaa").format(publishDate);
            } catch (Exception e) {
                Timber.e(e, "Error formatting date");
            }
        }
        sb.append("  <p class=\"entry-date\" style=\"font-size: 0.75em;\">").append(dateStr).append("</p>");
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

    public boolean isPaywallOrLogin(String html, String text) {
        if (html == null || html.trim().isEmpty()) return false;
        String lowerHtml = html.toLowerCase();
        String lowerText = text != null ? text.toLowerCase() : "";

        // Common paywall/login/credential check keywords
        boolean hasPaywallKeywords =
                lowerHtml.contains("sign in") || lowerHtml.contains("log in") || lowerHtml.contains("login") ||
                lowerHtml.contains("subscribe") || lowerHtml.contains("subscribers only") || lowerHtml.contains("credential") ||
                lowerHtml.contains("paywall") || lowerHtml.contains("register to read") || lowerHtml.contains("create an account") ||
                lowerText.contains("sign in") || lowerText.contains("log in") || lowerText.contains("login") ||
                lowerText.contains("subscribe") || lowerText.contains("subscribers only") || lowerText.contains("credential") ||
                lowerText.contains("paywall") || lowerText.contains("register to read") || lowerText.contains("create an account");

        // Paywall pages are usually shorter/promotional compared to full articles.
        // If it matches keywords and the content/html is short, it's a paywall.
        return hasPaywallKeywords && (html.length() < 25000 || lowerText.length() < 1200);
    }

    public boolean isErrorContent(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase();
        
        if (isAiRefusalResponse(trimmed)) {
            return true;
        }
        
        // Check for error indicators regardless of length
        // These patterns indicate the AI received an error page instead of article content
        if (lower.contains("webpage could not be loaded") ||
            lower.contains("page could not be loaded") ||
            lower.contains("unable to load") ||
            lower.contains("failed to load") ||
            lower.contains("err_name_not_resolved") ||
            lower.contains("err_connection_refused") ||
            lower.contains("err_connection_timed_out") ||
            lower.contains("err_internet_disconnected") ||
            lower.contains("err_network_changed") ||
            lower.contains("err_connection_reset") ||
            lower.contains("err_ssl_protocol_error") ||
            lower.contains("net::err_") ||
            lower.contains("dns_probe_finished") ||
            lower.contains("this site can\u2019t be reached") ||
            lower.contains("this site can't be reached") ||
            lower.contains("the webpage is not available") ||
            lower.contains("web page is not available") ||
            lower.contains("could not be loaded due to") ||
            lower.contains("check your internet connection") ||
            lower.contains("no internet connection") ||
            lower.contains("network error") ||
            lower.contains("connection was reset") ||
            lower.contains("connection timed out") ||
            lower.contains("the connection was reset") ||
            lower.contains("took too long to respond") ||
            lower.contains("server not found")) {
            return true;
        }

        // Too short to be a real article
        if (trimmed.length() < 100) {
            // Check for common error indicators in short text
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
            return trimmed.length() < 50; 
        }
        
        return false;
    }

    public boolean isAiRefusalResponse(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        String lower = text.trim().toLowerCase();
        
        return lower.contains("no content to summarize") ||
               lower.contains("nothing to summarize") ||
               lower.contains("cannot summarize") ||
               lower.contains("unable to summarize") ||
               lower.contains("no text to summarize") ||
               lower.contains("no content to translate") ||
               lower.contains("nothing to translate") ||
               lower.contains("cannot translate") ||
               lower.contains("unable to translate") ||
               lower.contains("no text to translate") ||
               lower.contains("there is no content") ||
               lower.contains("there is no text") ||
               lower.contains("does not contain any content") ||
               lower.contains("does not contain any text") ||
               lower.contains("cannot access") ||
               lower.contains("cannot view") ||
               lower.contains("unable to access") ||
               lower.contains("unable to view") ||
               lower.contains("as an ai") ||
               lower.contains("as a language model") ||
               lower.contains("i am unable to") ||
               lower.contains("i cannot fulfill");
    }

    /**
     * Checks raw HTML for indicators that the page is an error page, not actual article content.
     * This catches cases where the WebView loaded a browser error page or server error page.
     */
    public boolean isErrorHtml(String html) {
        if (html == null || html.trim().isEmpty()) return true;
        String lower = html.toLowerCase();

        // Check for Chrome/WebView error page indicators
        if (lower.contains("neterror") ||
            lower.contains("err_name_not_resolved") ||
            lower.contains("err_connection_refused") ||
            lower.contains("err_connection_timed_out") ||
            lower.contains("err_internet_disconnected") ||
            lower.contains("err_connection_reset") ||
            lower.contains("net::err_") ||
            lower.contains("dns_probe_finished_nxdomain") ||
            lower.contains("dns-error-page") ||
            lower.contains("interstitial-wrapper")) {
            return true;
        }

        // Check for common server error pages
        if ((lower.contains("<title>") && (
            lower.contains("<title>404") ||
            lower.contains("<title>403") ||
            lower.contains("<title>500") ||
            lower.contains("<title>502") ||
            lower.contains("<title>503") ||
            lower.contains("<title>error") ||
            lower.contains("<title>page not found") ||
            lower.contains("<title>access denied") ||
            lower.contains("<title>service unavailable") ||
            lower.contains("<title>server error")))) {
            return true;
        }

        // Extract just the text content and check if it's too thin
        try {
            String textContent = Jsoup.parse(html).text().trim();
            // An HTML page with less than 50 chars of actual text is likely an error page
            if (textContent.length() < 50) {
                return true;
            }
        } catch (Exception e) {
            // If we can't even parse it, it's probably bad
            return true;
        }

        return false;
    }


}