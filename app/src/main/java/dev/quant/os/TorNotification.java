package dev.quant.os;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/**
 * The ongoing shade notification: a one-tap internet switch. It says whether
 * the app's internet (Tor) is on and its button starts or stops Tor. The
 * button opens the app because tor runs in this process, and the OS is where
 * the change is visible.
 */
final class TorNotification {
    static final int ID = 1;
    private static final String CHANNEL = "internet";

    private TorNotification() {
    }

    static void update(Context ctx, String statusJson) {
        String state = "off";
        int progress = 0;
        try {
            JSONObject o = new JSONObject(statusJson);
            state = o.optString("state", "off");
            progress = o.optInt("builtinProgress", 0);
        } catch (Exception ignored) {
        }
        boolean on = "on".equals(state);
        boolean starting = "starting".equals(state);
        String text = on ? "Internet is on through Tor"
                : starting ? "Tor is starting (" + progress + "%) — internet opens when it finishes"
                : "error".equals(state) ? "Tor failed — internet is off"
                : "Internet is off (Tor is off)";
        String action = on || starting ? "Turn internet off" : "Turn internet on";

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Internet",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Whether Quant OS internet is on, with one button to switch it");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getActivity(ctx, 1,
                new Intent(ctx, MainActivity.class).putExtra("torToggle", true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat_internet)
                .setContentTitle("Quant OS internet")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(R.drawable.ic_stat_internet, action, toggle);
        try {
            nm.notify(ID, b.build());
        } catch (RuntimeException ignored) {
            /* SecurityException when POST_NOTIFICATIONS was denied: no shortcut. */
        }
    }

    static void cancel(Context ctx) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID);
    }
}
