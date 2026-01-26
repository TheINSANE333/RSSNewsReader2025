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

        Log.d("TextUtil", "HTML: "+ html);

        Document doc = Jsoup.parse(html);
        StringBuilder content = new StringBuilder();

        // Initialize the sentence iterator
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);

        // 1. Extract structured elements
        Elements elements = doc.select(
                "h2, h3, h4, h5, h6, p, td, th, li, figcaption, blockquote"
        );

        for (Element element : elements) {
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
        if (content.length() >= delimiter.length()) {
            content.setLength(content.length() - delimiter.length());
        }

        return content.toString();
    }

    private void appendSentences(StringBuilder sb, String text, String delimiter, BreakIterator iterator) {
        iterator.setText(text);
        int start = iterator.first();

        // Iterate through the boundaries
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).trim();

            // Only append if the sentence actually contains text
            if (!sentence.isEmpty()) {
                sb.append(sentence).append(delimiter);
                // Optional: Log each sentence to verify
                // Log.d("TextUtil", "Sentence: " + sentence);
            }
        }
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
                            // List of tags to extract text from
                            List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
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
                                        int progress = (int) (100.0 * (translatedElements.incrementAndGet()) / elements.size());
                                        progressCallback.accept(progress);
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

    @SuppressLint("CheckResult")
    public Single<String> translateHtmlLineByLine(String sourceLanguage, String targetLanguage, String html, String title, long articleId) {
        Log.d(TAG, "translateHtmlLineByLine: from " + sourceLanguage + " to " + targetLanguage);
        return Single.create(emitter -> {
            try {
                // First, translate the title
                translateText(sourceLanguage, targetLanguage, title)
                        .flatMap(translatedTitle -> {
                            // Parse the HTML
                            Document document = Jsoup.parse(html);
                            // List of tags to extract text from
                            List<String> tags = Arrays.asList("h2", "h3", "h4", "h5", "h6", "p", "td", "pre", "th", "li", "figcaption", "blockquote", "section");
                            // Get all elements with the specified tags
                            Elements elements = document.select(String.join(",", tags));

                            Element existingTitleElement = document.select("p.translated-title").first();
                            if (existingTitleElement == null) {
                                Element titleParagraph = new Element(Tag.valueOf("p"), "");
                                titleParagraph.text(translatedTitle);
                                titleParagraph.addClass("translated-title");
                                titleParagraph.attr("data-article-id", String.valueOf(articleId));
                                document.body().prependChild(titleParagraph);
                            }

                            // Create a Flowable from the elements
                            return Flowable.fromIterable(elements)
                                    .flatMapMaybe(element -> {
                                        if (element.hasText()) {
                                            return translateText(sourceLanguage, targetLanguage, element.text()).map(translateText -> {
                                                element.text(translateText);
                                                return translateText;
                                            }).toMaybe();
                                        }
                                        return Maybe.empty();
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

    private String translateChunkWithRetry(AiClient aiClient, List<Message> messages) {

        int maxRetries = 5;
        int delayMs = 30000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return aiClient.getChatResponse(messages);

            } catch (Exception e) {

                boolean isRateLimit =
                        e.getMessage() != null &&
                                (e.getMessage().contains("429") ||
                                        e.getMessage().toLowerCase().contains("rate"));

                if (isRateLimit && attempt < maxRetries) {

                    Log.e(TAG, "Rate limit hit, retry " + attempt);

                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}

                } else {
                    throw new RuntimeException(e);
                }
            }
        }

        return null;
    }

    private String summarizeChunkWithRetry(AiClient aiClient, List<Message> messages) {

        int maxRetries = 5;
        int delayMs = 30000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return aiClient.getChatResponse(messages);

            } catch (Exception e) {

                boolean isRateLimit =
                        e.getMessage() != null &&
                                (e.getMessage().contains("429") ||
                                        e.getMessage().toLowerCase().contains("rate"));

                if (isRateLimit && attempt < maxRetries) {

                    Log.e(TAG, "Rate limit hit, retry " + attempt);

                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}

                } else {
                    throw new RuntimeException(e);
                }
            }
        }

        return null;
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
    public Single<String> translateHtmlAllAtOnce(String sourceLanguage, String targetLanguage, String html, String title, long articleId, Consumer<Integer> progressCallback) {
        return Single.defer(() -> {

            Log.d(TAG, "Attempting to acquire Translation Lock for ID: " + articleId);

            // 1. BLOCK: This pauses the flow until the lock is free.
            // If WebClient is translating, AutoTranslator waits here (and vice versa).
            try {
                GLOBAL_TRANSLATION_LOCK.acquire();
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

    public Single<String> summarizeHtmlAllAtOnce(String sourceLanguage, String targetLanguage, String html, int length, long articleId, Consumer<Integer> progressCallback) {
        return Single.defer(() -> {

            Log.d(TAG, "Attempting to acquire Summarization Lock for ID: " + articleId);

            // 1. BLOCK: This pauses the flow until the lock is free.
            // If WebClient is translating, AutoTranslator waits here (and vice versa).
            try {
                GLOBAL_SUMMARIZATION_LOCK.acquire();
            } catch (InterruptedException e) {
                return Single.error(e);
            }

            Log.d(TAG, "Lock Acquired. Starting summarization for ID: " + articleId);

            // 2. RUN: Your existing translation logic goes here.
            // Ensure this returns a Single<String>.
            return performActualSummarization(sourceLanguage, targetLanguage, html, length, articleId, progressCallback)
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
                AiClient aiClient = new AiClient();
                List<Message> messages = new ArrayList<>();

                messages.add(new Message(
                    "system",
                    "Translate title and html to the target language. " +
                            "Preserve all HTML exactly." +
                            "Format your response exactly like this: [TITLE] <translated_title> [CONTENT] <translated_html_content>. "
                ));

                // Build the prompt
                messages.add(new Message(
                    "user",
                    String.format(
                            "Target Language: %s\nTitle: %s\nContent: %s",
                            targetLanguage,
                            title,
                            html
                    )
                ));

                // 3. Execute Blocking Request (Safe inside Single.create)
                // Note: Ensure translateChunkWithRetry is accessible here
                String translatedHtml = translateChunkWithRetry(aiClient, messages);

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

    private Single<String> performActualSummarization(String sourceLanguage, String targetLanguage, String html, int length, long articleId, Consumer<Integer> progressCallback) {
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
                AiClient aiClient = new AiClient();
                List<Message> messages = new ArrayList<>();

                messages.add(new Message(
                        "system",
                        "You are a helpful assistant designed to summarize web articles. " +
                                "Provide a concise summary of the content provided in targeted language. " +
                                "Respond in just plain text of the summary. " +
                                "If it's an opinion piece, tell the name and background of the writer. " +
                                "If the content is short, do not make it longer. "
                ));

                // Build the prompt
                messages.add(new Message(
                        "user",
                        String.format(
                                "Please summarize the following article \n" +
                                        "Target Language: %s\n" +
                                        "Length: %s\n" +
                                        "Content:\n%s",
                                targetLanguage,
                                length,
                                html
                        )
                ));

                // 3. Execute Blocking Request (Safe inside Single.create)
                // Note: Ensure summarizeChunkWithRetry is accessible here
                String summarizedHtml = summarizeChunkWithRetry(aiClient, messages);

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

    @SuppressLint("CheckResult")
    public Single<String> translateHtmlByParagraph(String sourceLanguage, String targetLanguage, String html, String title, long articleId, Consumer<Integer> progressCallback) {
        Log.d(TAG, "translateHtmlByParagraph: from " + sourceLanguage + " to " + targetLanguage);
        Log.d(TAG, "translateHtmlByParagraph CALLED");
        return Single.create(emitter -> {
            try {
                translateText(sourceLanguage, targetLanguage, title)
                        .flatMap(translatedTitle -> {
                            Document document = Jsoup.parse(html);
                            List<String> tags = Arrays.asList("p", "section", "blockquote");
                            Elements paragraphs = document.select(String.join(",", tags));
                            Log.d(TAG, "Found " + paragraphs.size() + " paragraphs for translation");

                            Element existingTitleElement = document.select("p.translated-title").first();
                            if (existingTitleElement == null) {
                                Element titleParagraph = new Element(Tag.valueOf("p"), "");
                                titleParagraph.text(translatedTitle);
                                titleParagraph.addClass("translated-title");
                                titleParagraph.attr("data-article-id", String.valueOf(articleId));
                                document.body().prependChild(titleParagraph);
                            }

                            AtomicInteger translatedCount = new AtomicInteger(0);
                            int total = paragraphs.size();

                            return Flowable.fromIterable(paragraphs)
                                    .flatMapMaybe(paragraph -> {
                                        if (paragraph.hasText()) {
                                            return translateText(sourceLanguage, targetLanguage, paragraph.text())
                                                    .map(translatedText -> {
                                                        paragraph.text(translatedText);
                                                        int progress = (int) ((translatedCount.incrementAndGet() / (float) total) * 100);
                                                        try {
                                                            progressCallback.accept(progress);
                                                        } catch (Exception e) {
                                                            Log.e(TAG, "Progress callback failed", e);
                                                        }
                                                        return translatedText;
                                                    }).toMaybe();
                                        }
                                        return Maybe.empty();
                                    })
                                    .toList()
                                    .map(ignored -> document.outerHtml());
                        })
                        .subscribe(
                                emitter::onSuccess,
                                error -> {
                                    Log.e(TAG, "Error during paragraph translation", error);
                                    emitter.onError(error);
                                }
                        );
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error in translateHtmlByParagraph", e);
                emitter.onError(e);
            }
        });
    }

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

    public void onDestroy() {
        compositeDisposable.dispose();
    }
}