package xiangze.mmu.rssnewsreader.service.tts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.ui.webview.WebViewActivity;

public class TtsNotification extends Notification {

    public static final String TAG = TtsNotification.class.getSimpleName();
    public static final String TTS_CHANNEL_ID = "CHANNEL_TTS";
    public static final int TTS_NOTIFICATION_ID = 666666;
    public static final int REQUEST_CODE = 1;

    private final TtsService ttsService;

    private final NotificationManager notificationManager;
    private final NotificationCompat.Action playAction;
    private final NotificationCompat.Action pauseAction;
    private final NotificationCompat.Action fastForwardAction;
    private final NotificationCompat.Action rewindAction;
    private final NotificationCompat.Action nextAction;
    private final NotificationCompat.Action previousAction;

    public TtsNotification(TtsService ttsService) {
        this.ttsService = ttsService;

        this.playAction = new NotificationCompat.Action(
                R.drawable.ic_baseline_play_arrow_24,
                "Play",
                TtsMediaButtonReceiver.buildMediaButtonPendingIntent(ttsService, PlaybackStateCompat.ACTION_PLAY));

        this.pauseAction = new NotificationCompat.Action(
                R.drawable.ic_baseline_pause_24,
                "Pause",
                TtsMediaButtonReceiver.buildMediaButtonPendingIntent(ttsService, PlaybackStateCompat.ACTION_PAUSE));

        this.fastForwardAction = new NotificationCompat.Action(
                R.drawable.ic_baseline_fast_forward_24,
                "Fast Forward",
                TtsMediaButtonReceiver.buildMediaButtonPendingIntent(ttsService, PlaybackStateCompat.ACTION_FAST_FORWARD));

        this.rewindAction = new NotificationCompat.Action(
                R.drawable.ic_baseline_fast_rewind_24,
                "Rewind",
                TtsMediaButtonReceiver.buildMediaButtonPendingIntent(ttsService, PlaybackStateCompat.ACTION_REWIND));

        this.nextAction = new NotificationCompat.Action(
                R.drawable.ic_baseline_skip_next_24,
                "Next",
                TtsMediaButtonReceiver.buildMediaButtonPendingIntent(ttsService, PlaybackStateCompat.ACTION_SKIP_TO_NEXT));

        this.previousAction = new NotificationCompat.Action(
                R.drawable.ic_baseline_skip_previous_24,
                "Previous",
                TtsMediaButtonReceiver.buildMediaButtonPendingIntent(ttsService, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));

        notificationManager = (NotificationManager) ttsService.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    public NotificationManager getNotificationManager() {
        return notificationManager;
    }

    public Notification getNotification(MediaMetadataCompat metaData, @NonNull PlaybackStateCompat state, MediaSessionCompat.Token token) {
        MediaDescriptionCompat description = metaData.getDescription();
        return buildNotification(state, token, description);
    }

    private Notification buildNotification(@NonNull PlaybackStateCompat state, MediaSessionCompat.Token token, MediaDescriptionCompat description) {
        if (notificationManager.getNotificationChannel(TTS_CHANNEL_ID) == null) {
            NotificationChannel ttsChannel = new NotificationChannel(
                    TTS_CHANNEL_ID,
                    "Channel TTS",
                    NotificationManager.IMPORTANCE_LOW
            );
            ttsChannel.setDescription("Text To Speech Notification Channel");
            ttsChannel.enableVibration(true);
            ttsChannel.setVibrationPattern(
                    new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
            notificationManager.createNotificationChannel(ttsChannel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ttsService, TTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_rss)
                .setColor(Color.WHITE)
                .setContentIntent(createContentIntent())
                .setDeleteIntent(TtsMediaButtonReceiver.buildMediaButtonPendingIntent(ttsService, PlaybackStateCompat.ACTION_STOP))
                .setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle())
                .setLargeIcon(description.getIconBitmap())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0,2,4)
                        .setMediaSession(token)
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(TtsMediaButtonReceiver.buildMediaButtonPendingIntent(ttsService, PlaybackStateCompat.ACTION_STOP)));

        // 0
        builder.addAction(previousAction);

        // 1
        builder.addAction(rewindAction);

        builder.addAction(state.getState() == PlaybackStateCompat.STATE_PLAYING? pauseAction : playAction);

        // 3
        builder.addAction(fastForwardAction);

        // 4
        builder.addAction(nextAction);

        return builder.build();
    }

    private PendingIntent createContentIntent() {
        Intent openUI = new Intent(ttsService, WebViewActivity.class);
        openUI.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ttsService,
                REQUEST_CODE, openUI, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

}