package vn.cuong.tftboost;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Phien choi: bat dau khi bam Toi uu, ket thuc khi thoat game.
 * Ket thuc phien = tra Khong lam phien ve dung trang thai truoc do + tu tat Tro nang (neu bat tuy chon).
 */
final class Session {

    static final String PREFS = "tftboost";
    static final String K_AUTO_OFF = "auto_off";
    private static final String K_ACTIVE = "session_active";
    private static final String K_GAME = "session_game";
    private static final String K_PREV_FILTER = "session_prev_filter";
    private static final String K_PENDING = "pending_boost_at";
    private static final long PENDING_TTL = 3 * 60 * 1000L;

    private Session() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean active(Context c) {
        return p(c).getBoolean(K_ACTIVE, false);
    }

    static String game(Context c) {
        return p(c).getString(K_GAME, null);
    }

    static boolean autoOff(Context c) {
        return p(c).getBoolean(K_AUTO_OFF, true);
    }

    /** Ghi lai trang thai Khong lam phien hien tai truoc khi doi, de luc thoat tra ve dung nhu cu. */
    static void begin(Context c, String game) {
        SharedPreferences sp = p(c);
        SharedPreferences.Editor e = sp.edit();
        if (!sp.getBoolean(K_ACTIVE, false)) {
            int prev = -1;
            try {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.isNotificationPolicyAccessGranted()) {
                    prev = nm.getCurrentInterruptionFilter();
                }
            } catch (Throwable ignored) {
            }
            e.putInt(K_PREV_FILTER, prev);
        }
        e.putBoolean(K_ACTIVE, true);
        e.putString(K_GAME, game);
        e.apply();
    }

    /** Ket thuc phien: khoi phuc Khong lam phien, tat Tro nang neu duoc phep. */
    static void end(Context c, String reason) {
        SharedPreferences sp = p(c);
        if (!sp.getBoolean(K_ACTIVE, false)) {
            return;
        }
        BoostLog.add("──────── Khôi phục (" + reason + ") ────────");
        int prev = sp.getInt(K_PREV_FILTER, -1);
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.isNotificationPolicyAccessGranted()) {
                int target = prev > 0 ? prev : NotificationManager.INTERRUPTION_FILTER_ALL;
                nm.setInterruptionFilter(target);
                BoostLog.add(target == NotificationManager.INTERRUPTION_FILTER_ALL
                        ? "✓ Đã tắt Không làm phiền"
                        : "✓ Đã trả Không làm phiền về như trước khi chơi");
            }
        } catch (Throwable t) {
            BoostLog.add("✗ Không khôi phục được Không làm phiền: " + t);
        }
        sp.edit().putBoolean(K_ACTIVE, false).remove(K_GAME).remove(K_PREV_FILTER).apply();

        final BoostService svc = BoostService.instance;
        if (autoOff(c) && svc != null) {
            BoostService.instance = null; // coi nhu da tat ngay, tranh lan toi uu ke tiep dung nham
            BoostLog.add("✓ Đã tự tắt Trợ năng");
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    svc.disableSelf();
                } catch (Throwable ignored) {
                }
            });
        }
        try {
            Toast.makeText(c.getApplicationContext(), "TFT Boost: đã khôi phục cài đặt", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ cho bat Tro nang roi tu toi uu

    static void markPending(Context c) {
        p(c).edit().putLong(K_PENDING, System.currentTimeMillis()).apply();
    }

    static boolean pending(Context c) {
        long at = p(c).getLong(K_PENDING, 0);
        return at > 0 && System.currentTimeMillis() - at < PENDING_TTL;
    }

    static void clearPending(Context c) {
        p(c).edit().remove(K_PENDING).apply();
    }
}
