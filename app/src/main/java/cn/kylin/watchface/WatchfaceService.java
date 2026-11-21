package cn.kylin.watchface;

import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/**
 * 表盘壁纸服务：每分钟对齐整分重绘一次，收到血糖广播立即重绘，
 * 不可见时停止刷新以省电。
 */
public class WatchfaceService extends WallpaperService {

    private static FaceEngine sActive;

    /** BgReceiver 收到广播后调用 */
    static void requestRedraw() {
        FaceEngine e = sActive;
        if (e != null) {
            e.handler.post(e.drawRunnable);
        }
    }

    @Override
    public Engine onCreateEngine() {
        return new FaceEngine();
    }

    private class FaceEngine extends Engine {

        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable drawRunnable = new Runnable() {
            @Override
            public void run() {
                drawFrame();
                scheduleNextMinute();
            }
        };

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (visible) {
                sActive = this;
                drawFrame();
                scheduleNextMinute();
            } else {
                handler.removeCallbacks(drawRunnable);
                if (sActive == this) {
                    sActive = null;
                }
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame();
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacks(drawRunnable);
            if (sActive == this) {
                sActive = null;
            }
            super.onDestroy();
        }

        void scheduleNextMinute() {
            handler.removeCallbacks(drawRunnable);
            long now = System.currentTimeMillis();
            long delay = 60_000 - (now % 60_000) + 100; // 对齐整分 + 100ms 余量
            handler.postDelayed(drawRunnable, delay);
        }

        void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            android.graphics.Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    FaceDrawer.draw(c, getApplicationContext());
                }
            } catch (Throwable ignored) {
                // 绘制失败不崩溃，下一分钟重试
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }
}
