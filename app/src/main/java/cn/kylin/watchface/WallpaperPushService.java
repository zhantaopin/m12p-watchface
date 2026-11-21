package cn.kylin.watchface;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 静态壁纸推送服务（v4 主路径）：
 * Mimu UI 无动态壁纸选择器，改为把表盘渲染成 Bitmap 直接
 * WallpaperManager.setBitmap() 写入系统壁纸，每分钟重设实现走时。
 * 仅需 SET_WALLPAPER 普通权限，无需任何系统设置页面。
 * 亮屏时工作，灭屏自动停止（省电，灭屏重设无意义）。
 */
public class WallpaperPushService extends Service {

    private static final String CHANNEL = "wf_push";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bitmap bitmap;   // 复用，避免每分钟分配
    private Canvas canvas;
    private boolean screenOn = true;

    private final Runnable pushRunnable = new Runnable() {
        @Override
        public void run() {
            if (screenOn) {
                push();
            }
            scheduleNext();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenOn = true;
                push();
                scheduleNext();
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOn = false;
                handler.removeCallbacks(pushRunnable);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "壁纸推送",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("表盘运行中")
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
        startForeground(1, n);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);

        // 血糖广播到达 → 立即重设壁纸（BgReceiver 回调，服务存活期间有效）
        BgReceiver.wallpaperPush = () -> {
            if (screenOn) {
                push();
            }
        };

        push();
        scheduleNext();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (screenOn) {
            push();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        BgReceiver.wallpaperPush = null;
        unregisterReceiver(screenReceiver);
        handler.removeCallbacks(pushRunnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleNext() {
        handler.removeCallbacks(pushRunnable);
        long delay = 60_000 - (System.currentTimeMillis() % 60_000) + 100;
        handler.postDelayed(pushRunnable, delay);
    }

    private void push() {
        try {
            if (bitmap == null || bitmap.isRecycled()
                    || bitmap.getWidth() != getResources().getDisplayMetrics().widthPixels) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                bitmap = Bitmap.createBitmap(
                        getResources().getDisplayMetrics().widthPixels,
                        getResources().getDisplayMetrics().heightPixels,
                        Bitmap.Config.ARGB_8888);
                canvas = new Canvas(bitmap);
            }
            FaceDrawer.draw(canvas, this);
            WallpaperManager.getInstance(this).setBitmap(bitmap);
        } catch (Throwable ignored) {
            // 设置失败不崩溃，下一分钟重试
        }
    }

    /** 供「设为壁纸」按钮立即推一次（服务未启动也可用） */
    static void pushOnce(Context ctx) {
        Bitmap b = null;
        try {
            b = Bitmap.createBitmap(
                    ctx.getResources().getDisplayMetrics().widthPixels,
                    ctx.getResources().getDisplayMetrics().heightPixels,
                    Bitmap.Config.ARGB_8888);
            FaceDrawer.draw(new Canvas(b), ctx);
            WallpaperManager.getInstance(ctx).setBitmap(b);
        } catch (Throwable ignored) {
        } finally {
            if (b != null) {
                b.recycle();
            }
        }
    }
}
