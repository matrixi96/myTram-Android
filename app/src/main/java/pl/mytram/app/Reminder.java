package pl.mytram.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Powiadomienie: migawka niedługo się kończy. */
public class Reminder extends BroadcastReceiver {
    static final String CHANNEL = "migawka";

    static void createChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Migawka", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        createChannel(ctx);
        String tekst = intent.getStringExtra("tekst");
        if (tekst == null) tekst = "Twoja migawka niedługo się kończy.";
        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("myTram – migawka")
                .setContentText(tekst)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (nm != null) nm.notify(1, n);
        } catch (SecurityException ignored) {
            // brak zgody na powiadomienia
        }
    }
}
