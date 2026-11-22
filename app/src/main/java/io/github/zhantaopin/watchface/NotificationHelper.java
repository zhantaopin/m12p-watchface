package io.github.zhantaopin.watchface;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 血糖常驻通知。
 * 静默渠道 IMPORTANCE_LOW：不响、不弹横幅、不点亮屏幕；
 * 提醒渠道 IMPORTANCE_HIGH：亮屏提示。由设置项「血糖通知」切换。
 */
public final class NotificationHelper {

    private static final String CH_SILENT = "bg_silent";
    private static final String CH_ALERT = "bg_alert";
    private static final int NOTIFY_ID = 1001;

    private NotificationHelper() {
    }

    /** 按当前设置刷新或撤销通知；广播到达、切换设置时调用 */
    public static void refresh(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("wf", Context.MODE_PRIVATE);
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        String mode = p.getString("wf_notify", "off");
        if ("off".equals(mode) || !p.contains("bg_mgdl")) {
            nm.cancel(NOTIFY_ID);
            return;
        }
        createChannels(nm);

        double mgdl = p.getFloat("bg_mgdl", 0);
        boolean mmol = "mmol".equals(p.getString("unit", "mmol"));
        boolean fromAaps = "aaps".equals(p.getString("bg_src", "aaps"));
        double effMgdl = fromAaps ? mgdl : mgdl + p.getFloat("offset", 0f) * 18.0;
        double shown = mmol ? effMgdl / 18.0 : effMgdl;

        String arrow = FaceDrawer.arrowText(p.getString("bg_arrow", "NONE"));
        String title = (mmol ? String.format(Locale.US, "%.1f", shown)
                : String.valueOf((int) Math.round(shown)))
                + (mmol ? " mmol/L " : " mg/dL ") + arrow;

        long ageMin = (System.currentTimeMillis() - p.getLong("bg_time", 0)) / 60000L;
        String src = fromAaps ? "AAPS" : "欧态";
        String text = (ageMin < 1 ? "刚刚" : ageMin + " 分钟前") + " · " + src;

        Intent it = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String ch = "alert".equals(mode) ? CH_ALERT : CH_SILENT;
        Notification n = new Notification.Builder(ctx, ch)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        nm.notify(NOTIFY_ID, n);
    }

    private static void createChannels(NotificationManager nm) {
        NotificationChannel silent = new NotificationChannel(CH_SILENT, "血糖（静默）",
                NotificationManager.IMPORTANCE_LOW);
        silent.setShowBadge(false);
        silent.enableVibration(false);
        silent.setSound(null, null);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "血糖（提醒）",
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(silent);
        nm.createNotificationChannel(alert);
    }
}
