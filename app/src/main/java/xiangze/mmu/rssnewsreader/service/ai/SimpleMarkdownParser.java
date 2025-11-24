package xiangze.mmu.rssnewsreader.service.ai;

import android.text.Html;
import android.text.SpannableString;

public class SimpleMarkdownParser {
    public static SpannableString parseSimpleMarkdown(String text) {
        SpannableString spannable = new SpannableString(text);

        // Handle bold: **text**
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");

        // Handle italic: *text*
        text = text.replaceAll("\\*(.*?)\\*", "<i>$1</i>");

        // Handle code: `text`
        text = text.replaceAll("`(.*?)`", "<tt>$1</tt>");

        // Use Android's fromHtml for basic formatting
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            return new SpannableString(Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY));
        } else {
            return new SpannableString(Html.fromHtml(text));
        }
    }
}
