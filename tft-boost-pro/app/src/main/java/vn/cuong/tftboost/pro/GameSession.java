package vn.cuong.tftboost.pro;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phien choi game.
 *
 * Truoc khi doi bat ky cai dat nao, app ghi lai lenh dua no ve gia tri GOC (moi muc chi ghi 1 lan,
 * ke ca khi bam toi uu nhieu lan). Khi thoat game, chay lai danh sach do de tra may ve dung nhu cu.
 * Muc nao khoi phuc loi (vd. Shizuku chua chay) duoc giu lai de lan sau khoi phuc tiep.
 */
final class GameSession {

    static final String PREFS = "tftboost_pro";
    private static final String K_ACTIVE = "session_active";
    private static final String K_GAME = "session_game";
    private static final String K_RESTORE = "session_restore";

    static final String J_DND = "java:dnd";
    static final String J_SYNC = "java:sync";

    private GameSession() {
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

    // ------------------------------------------------------------------ danh sach khoi phuc

    /** key -> lenh khoi phuc, giu dung thu tu. Luu dang "key\tlenh" moi dong. */
    static synchronized Map<String, String> load(Context c) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        String raw = p(c).getString(K_RESTORE, "");
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String line : raw.split("\n")) {
            int t = line.indexOf('\t');
            if (t > 0) {
                m.put(line.substring(0, t), line.substring(t + 1));
            }
        }
        return m;
    }

    private static void save(Context c, Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        p(c).edit().putString(K_RESTORE, sb.toString()).apply();
    }

    static synchronized void begin(Context c, String game, Map<String, String> restore) {
        save(c, restore);
        p(c).edit().putBoolean(K_ACTIVE, true).putString(K_GAME, game).apply();
    }

    /** Lenh khoi phuc 1 khoa settings: tra ve "settings put ..." gia tri cu, hoac "settings delete" neu truoc do chua co. */
    static String settingRestore(String ns, String key) {
        String v = Shell.get("settings get " + ns + " " + key);
        if (v == null) {
            return null;
        }
        if (v.isEmpty() || v.equals("null")) {
            return "settings delete " + ns + " " + key;
        }
        return "settings put " + ns + " " + key + " " + Shell.quote(v);
    }

    static String deviceConfigRestore(String ns, String key) {
        String v = Shell.get("device_config get " + ns + " " + key);
        if (v == null) {
            return null;
        }
        if (v.isEmpty() || v.equals("null")) {
            return "device_config delete " + ns + " " + key;
        }
        return "device_config put " + ns + " " + key + " " + Shell.quote(v);
    }

    static String label(String key) {
        if (key.equals(J_DND)) {
            return "Không làm phiền";
        }
        if (key.equals(J_SYNC)) {
            return "Tự động đồng bộ";
        }
        if (key.contains("refresh_rate")) {
            return "Tần số quét màn hình (" + key.substring(key.lastIndexOf(':') + 1) + ")";
        }
        if (key.startsWith("dc:game_overlay")) {
            return "Giới hạn FPS của game";
        }
        if (key.startsWith("gamemode")) {
            return "Game Mode";
        }
        if (key.equals("power_mode")) {
            return "Tiết kiệm pin";
        }
        if (key.equals("wifi_ll")) {
            return "Wi-Fi độ trễ thấp";
        }
        if (key.contains("scan_always")) {
            return "Quét nền " + (key.contains("ble") ? "Bluetooth" : "Wi-Fi");
        }
        if (key.equals("gos")) {
            return "GOS";
        }
        return key;
    }

    // ------------------------------------------------------------------ khoi phuc

    /**
     * Tra may ve trang thai goc. Goi tren luong nen (co chay lenh shell).
     *
     * @return true neu da khoi phuc het; false neu con muc chua khoi phuc duoc (thieu Shizuku).
     */
    static synchronized boolean restore(Context c, String reason) {
        if (!active(c)) {
            return true;
        }
        BoostLog.add("──────── Khôi phục mặc định (" + reason + ") ────────");
        Map<String, String> m = load(c);
        boolean shell = Shell.granted();
        int ok = 0;
        Iterator<Map.Entry<String, String>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            String key = e.getKey();
            String val = e.getValue();
            boolean done = false;
            try {
                if (key.equals(J_DND)) {
                    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm.isNotificationPolicyAccessGranted()) {
                        int f = Integer.parseInt(val);
                        nm.setInterruptionFilter(f > 0 ? f : NotificationManager.INTERRUPTION_FILTER_ALL);
                        done = true;
                    }
                } else if (key.equals(J_SYNC)) {
                    ContentResolver.setMasterSyncAutomatically("1".equals(val));
                    done = true;
                } else if (shell) {
                    Shell.Result r = Shell.run(val);
                    done = r.ok();
                    if (!done) {
                        BoostLog.add("✗ " + label(key) + " — " + Shell.firstLine(r.out));
                    }
                }
            } catch (Throwable t) {
                BoostLog.add("✗ " + label(key) + " — " + t);
            }
            if (done) {
                ok++;
                it.remove();
            }
        }
        if (ok > 0) {
            BoostLog.add("✓ Đã trả " + ok + " thiết lập về như trước khi chơi");
        }
        if (m.isEmpty()) {
            p(c).edit().putBoolean(K_ACTIVE, false).remove(K_GAME).remove(K_RESTORE).apply();
            BoostLog.add("✓ Máy đã về cài đặt mặc định");
            return true;
        }
        save(c, m);
        BoostLog.add("✗ Còn " + m.size() + " thiết lập chưa khôi phục"
                + (shell ? "" : " – Shizuku chưa chạy. Mở Shizuku bấm Start rồi bấm Khôi phục ngay."));
        return false;
    }
}
