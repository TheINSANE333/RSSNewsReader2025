package xiangze.mmu.rssnewsreader.service.rss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import xiangze.mmu.rssnewsreader.R;

public class RssNotification {

    public static final String TAG = RssNotification.class.getSimpleName();
    public static final String RSS_CHANNEL_ID = "CHANNEL_RSS";
    public static final int RSS_NOTIFICATION_ID = 77777;

    private final NotificationManager notificationManager;
    private Context context;

    public RssNotification(Context context) {
        this.context = context;
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public void sendNotification(String text) {
        if (notificationManager.getNotificationChannel(RSS_CHANNEL_ID) == null) {
            NotificationChannel rssChannel = new NotificationChannel(
                    RSS_CHANNEL_ID,
                    "Channel RSS",
                    NotificationManager.IMPORTANCE_HIGH
            );
            rssChannel.setDescription("RSS Notification Channel");
            notificationManager.createNotificationChannel(rssChannel);
        }

        Notification notification = new NotificationCompat.Builder(context, RSS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_rss)
                .setColor(Color.WHITE)
                .setContentTitle("RSS feed is refreshed")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build();

        notificationManager.notify(RSS_NOTIFICATION_ID, notification);
    }
}
