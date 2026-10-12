package vn.cuong.tftboost.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dich vu chay nen trong luc choi: cu 2 giay xem app nao dang o man hinh chinh.
 * Khi anh roi game (ve man hinh chinh > 8s, sang app khac > 45s, mo lai TFT Boost Pro,
 * hoac tat man hinh > 10 phut) thi tu khoi phuc moi cai dat ve nhu truoc khi choi.
 */
public class WatchService extends Service {

    static final String ACTION_RESTORE = "vn.cuong.tftboost.pro.RESTORE";
    static final String EXTRA_GAME = "game";

    private static final String CHANNEL = "session";
    private static final int NOTI_ID = 7796;
    private static final long TICK_MS = 2000;
    private static final long GRACE_HOME_MS = 8000;
    private static final long GRACE_OTHER_MS = 45000;
    private static final long SCREEN_OFF_MS = 10 * 60 * 1000L;
    private static final long GAME_START_TIMEOUT_MS = 3 * 60 * 1000L;

    /** Cua so tam thoi khi dang choi (cuoc goi, xin quyen, thanh toan...) - khong tinh la thoat game. */
    private static final Set<String> TRANSIENT = new HashSet<String>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.samsung.android.incallui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.gms",
            "com.android.vending",
            "com.samsung.android.game.gametools",
            "com.samsung.android.game.gamehome",
            "moe.shizuku.privileged.api"));

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    private String game;
    private String homePkg;
    private String fg;
    private boolean gameSeen;
    private boolean finishing;
    private long startAt;
    private long leftAt;
    private long offSince;
    private long lastQuery;

    static void start(Context c, String game) {
        Intent i = new Intent(c, WatchService.class).putExtra(EXTRA_GAME, game);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground("Đang chơi – thoát game sẽ tự khôi phục cài đặt");
        if (intent != null && ACTION_RESTORE.equals(intent.getAction())) {
            finishSession("bấm Khôi phục trên thông báo");
            return START_NOT_STICKY;
        }
        String g = intent != null ? intent.getStringExtra(EXTRA_GAME) : null;
        game = g != null ? g : GameSession.game(this);
        if (game == null || !GameSession.active(this)) {
            stopNow();
            return START_NOT_STICKY;
        }
        homePkg = resolveHome();
        fg = null;
        gameSeen = false;
        finishing = false;
        startAt = System.currentTimeMillis();
        lastQuery = startAt - 5000;
        leftAt = 0;
        offSince = 0;
        h.removeCallbacks(tick);
        h.postDelayed(tick, TICK_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        ex.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ theo doi

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (finishing) {
                return;
            }
            if (!GameSession.active(WatchService.this)) {
                stopNow();
                return;
            }
            long now = System.currentTimeMillis();
            readForeground(now);

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                if (offSince == 0) {
                    offSince = now;
                }
                if (now - offSince > SCREEN_OFF_MS) {
                    finishSession("màn hình tắt quá 10 phút");
                    return;
                }
            } else {
                offSince = 0;
            }

            if (game.equals(fg)) {
                gameSeen = true;
                leftAt = 0;
            } else if (!gameSeen) {
                if (now - startAt > GAME_START_TIMEOUT_MS) {
                    finishSession("game không mở");
                    return;
                }
            } else if (fg != null && !TRANSIENT.contains(fg)) {
                if (leftAt == 0) {
                    leftAt = now;
                }
                long grace = fg.equals(getPackageName()) ? 0
                        : fg.equals(homePkg) ? GRACE_HOME_MS : GRACE_OTHER_MS;
                if (now - leftAt >= grace) {
                    finishSession("đã thoát game");
                    return;
                }
            }
            h.postDelayed(this, TICK_MS);
        }
    };

    /** Lay app vua len man hinh gan nhat tu nhat ky su dung cua he thong. */
    private void readForeground(long now) {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(lastQuery, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                // 1 = MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                if (e.getEventType() == 1) {
                    fg = e.getPackageName();
                }
            }
            lastQuery = now;
        } catch (Throwable ignored) {
        }
    }

    private String resolveHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null) {
                return ri.activityInfo.packageName;
            }
        } catch (Throwable ignored) {
        }
        return "com.sec.android.app.launcher";
    }

    // ------------------------------------------------------------------ ket thuc phien

    private void finishSession(final String reason) {
        if (finishing) {
            return;
        }
        finishing = true;
        h.removeCallbacks(tick);
        goForeground("Đang khôi phục cài đặt…");
        ex.execute(() -> {
            final boolean ok = GameSession.restore(WatchService.this, reason);
            h.post(() -> {
                stopNow();
                notifyResult(ok);
            });
        });
    }

    private void stopNow() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    // ------------------------------------------------------------------ thong bao

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Phiên chơi game",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification build(String text, boolean withRestore) {
        ensureChannel();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("TFT Boost Pro")
                .setContentText(text)
                .setOngoing(withRestore)
                .setOnlyAlertOnce(true);
        Intent open = new Intent(this, MainActivity.class);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (withRestore) {
            Intent r = new Intent(this, WatchService.class).setAction(ACTION_RESTORE);
            PendingIntent pi = PendingIntent.getService(this, 1, r,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Khôi phục ngay", pi).build());
        }
        return b.build();
    }

    private void goForeground(String text) {
        Notification n = build(text, true);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable t) {
            BoostLog.add("✗ Không chạy được dịch vụ theo dõi: " + t);
        }
    }

    private void notifyResult(boolean ok) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTI_ID + 1, build(ok
                    ? "Đã thoát game – máy đã về cài đặt như trước khi chơi"
                    : "Chưa khôi phục hết – mở Shizuku bấm Start rồi mở TFT Boost Pro", false));
        } catch (Throwable ignored) {
        }
    }
}
