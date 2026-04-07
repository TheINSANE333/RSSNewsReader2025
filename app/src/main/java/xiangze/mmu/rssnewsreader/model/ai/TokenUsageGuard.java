package xiangze.mmu.rssnewsreader.model.ai;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import java.util.Calendar;

public class TokenUsageGuard {
    private static final String PREF_MINUTE_TOKENS = "ai_minute_tokens";
    private static final String PREF_MINUTE_TIMESTAMP = "ai_minute_timestamp";
    private static final String PREF_DAY_TOKENS = "ai_day_tokens";
    private static final String PREF_DAY_REQUESTS = "ai_day_requests";
    private static final String PREF_DAY_TIMESTAMP = "ai_day_timestamp";

    public static final String KEY_LIMIT_TPM = "ai_limit_tpm";
    public static final String KEY_LIMIT_RPD = "ai_limit_rpd";
    public static final String KEY_LIMIT_TPD = "ai_limit_tpd";

    private static final int DEFAULT_LIMIT_TPM = 30000;
    private static final int DEFAULT_LIMIT_RPD = 1000;
    private static final int DEFAULT_LIMIT_TPD = 500000;

    private static TokenUsageGuard instance;
    private final SharedPreferences prefs;

    private TokenUsageGuard(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static synchronized TokenUsageGuard getInstance(Context context) {
        if (instance == null) {
            instance = new TokenUsageGuard(context);
        }
        return instance;
    }

    public synchronized void checkLimits() throws IllegalStateException {
        long now = System.currentTimeMillis();
        resetIfNewPeriod(now);

        int minuteTokens = prefs.getInt(PREF_MINUTE_TOKENS, 0);
        int dayTokens = prefs.getInt(PREF_DAY_TOKENS, 0);
        int dayRequests = prefs.getInt(PREF_DAY_REQUESTS, 0);

        int limitTpm = prefs.getInt(KEY_LIMIT_TPM, DEFAULT_LIMIT_TPM);
        int limitRpd = prefs.getInt(KEY_LIMIT_RPD, DEFAULT_LIMIT_RPD);
        int limitTpd = prefs.getInt(KEY_LIMIT_TPD, DEFAULT_LIMIT_TPD);

        if (minuteTokens >= limitTpm) {
            throw new IllegalStateException("Minute token limit reached (" + limitTpm + " TPM). Please wait a moment.");
        }
        if (dayRequests >= limitRpd) {
            throw new IllegalStateException("Daily request limit reached (" + limitRpd + " RPD).");
        }
        if (dayTokens >= limitTpd) {
            throw new IllegalStateException("Daily token limit reached (" + limitTpd + " TPD).");
        }
    }

    public synchronized void recordUsage(int tokens) {
        long now = System.currentTimeMillis();
        resetIfNewPeriod(now);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(PREF_MINUTE_TOKENS, prefs.getInt(PREF_MINUTE_TOKENS, 0) + tokens);
        editor.putInt(PREF_DAY_TOKENS, prefs.getInt(PREF_DAY_TOKENS, 0) + tokens);
        editor.putInt(PREF_DAY_REQUESTS, prefs.getInt(PREF_DAY_REQUESTS, 0) + 1);
        editor.apply();
    }

    public synchronized void resetManual() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(PREF_MINUTE_TOKENS, 0);
        editor.putLong(PREF_MINUTE_TIMESTAMP, 0);
        editor.putInt(PREF_DAY_TOKENS, 0);
        editor.putInt(PREF_DAY_REQUESTS, 0);
        editor.putLong(PREF_DAY_TIMESTAMP, 0);
        editor.apply();
    }

    private void resetIfNewPeriod(long now) {
        long minuteTimestamp = prefs.getLong(PREF_MINUTE_TIMESTAMP, 0);
        long dayTimestamp = prefs.getLong(PREF_DAY_TIMESTAMP, 0);

        SharedPreferences.Editor editor = prefs.edit();

        // Reset minute if more than 60 seconds passed
        if (now - minuteTimestamp > 60 * 1000) {
            editor.putInt(PREF_MINUTE_TOKENS, 0);
            editor.putLong(PREF_MINUTE_TIMESTAMP, now);
        }

        // Reset day if it's a different day
        if (!isSameDay(now, dayTimestamp)) {
            editor.putInt(PREF_DAY_TOKENS, 0);
            editor.putInt(PREF_DAY_REQUESTS, 0);
            editor.putLong(PREF_DAY_TIMESTAMP, now);
        }

        editor.apply();
    }

    private boolean isSameDay(long t1, long t2) {
        Calendar cal1 = Calendar.getInstance();
        cal1.setTimeInMillis(t1);
        Calendar cal2 = Calendar.getInstance();
        cal2.setTimeInMillis(t2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }
}
