package xiangze.mmu.rssnewsreader.ui.chat;

import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;

public class SimpleMarkdownParser {
    public static SpannableString parseSimpleMarkdown(String text) {
        if (text == null) return new SpannableString("");

        // Escape HTML characters to prevent rendering issues
        String htmlText = text.replace("&", "&amp;")
                             .replace("<", "&lt;")
                             .replace(">", "&gt;");

        // Handle headings: ### Heading or ## Heading
        htmlText = htmlText.replaceAll("(?m)^###\\s+(.*?)$", "<b><big>$1</big></b>");
        htmlText = htmlText.replaceAll("(?m)^##\\s+(.*?)$", "<b><big><big>$1</big></big></b>");
        htmlText = htmlText.replaceAll("(?m)^#\\s+(.*?)$", "<b><big><big><big>$1</big></big></big></b>");

        // Handle bold: **text**
        htmlText = htmlText.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");

        // Handle italic: *text*
        htmlText = htmlText.replaceAll("\\*(.*?)\\*", "<i>$1</i>");

        // Handle code: `text`
        htmlText = htmlText.replaceAll("`(.*?)`", "<tt>$1</tt>");

        // Handle Markdown links: [text](url)
        htmlText = htmlText.replaceAll("\\[(.*?)\\]\\((.*?)\\)", "<a href=\"$2\">$1</a>");

        // Handle bullet points: - item or * item at start of line
        htmlText = htmlText.replaceAll("(?m)^[-*]\\s+(.*?)$", "• $1");

        // Handle newlines: \n to <br>
        htmlText = htmlText.replace("\n", "<br>");

        // Use Android's fromHtml for basic formatting
        Spanned spanned = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY);
        return new SpannableString(spanned);
    }
}
