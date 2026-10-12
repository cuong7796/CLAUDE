package vn.cuong.tftboost;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dich vu Tro nang: tu dong bam "Buoc dung" trong Thong tin ung dung cua tung app,
 * sau do mo man hinh App gan day va bam "Dong tat ca", roi mo game.
 * Thay the cho Shizuku - khong can ADB, khong can root.
 */
public class BoostService extends AccessibilityService {

    static volatile BoostService instance;

    private static final String SETTINGS_PKG = "com.android.settings";
    static final String GAME_PREFIX = "com.riotgames.league.teamfighttactics";
    static final String K_AUTO_DETECT = "auto_detect";
    static final String K_HUD = "hud";
    static final String K_HEAT = "heat_alert";
    private static final float HOT_BATTERY_C = 45f;
    private static final String[] STOP_TEXTS = {"Buộc dừng", "Force stop", "Dừng bắt buộc"};
    private static final String[] OK_TEXTS = {"OK", "Đồng ý"};
    /** Cua so xuat hien tam thoi khi dang choi - khong tinh la thoat game. */
    private static final Set<String> TRANSIENT = new HashSet<String>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.samsung.android.game.gametools",
            "com.samsung.android.game.gos",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.incallui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.gms",
            "com.android.vending",
            "vn.cuong.tftboost"));
    private static final long GRACE_HOME_MS = 8000;   // ve man hinh chinh
    private static final long GRACE_OTHER_MS = 45000; // chuyen sang app khac (Zalo, trinh duyet...)

    private static final String[] CLOSE_ALL_TEXTS = {"Đóng tất cả", "Close all", "Clear all", "Xóa tất cả", "Đóng hết"};

    private static final int PH_IDLE = 0;
    private static final int PH_FIND_STOP = 1;
    private static final int PH_CONFIRM = 2;
    private static final int PH_RECENTS = 3;

    private final Handler h = new Handler(Looper.getMainLooper());

    private List<String> queue = new ArrayList<String>();
    private List<String> labels = new ArrayList<String>();
    private int idx;
    private String game;
    private boolean closeRecents;
    private boolean running;
    private int phase = PH_IDLE;
    private long phaseStart;
    private int stopped;
    private int skipped;
    private long startedAt;

    private TextView overlay;
    private boolean gameSeen;
    private String homePkg;
    private TextView hud;
    private boolean heatWarned;

    // ------------------------------------------------------------------ vong doi

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        homePkg = resolveHome();
        // Vua duoc bat lai tu nut Toi uu -> tu quay ve TFT Boost de chay tiep
        if (Session.pending(this)) {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    }

    private String resolveHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null) {
                return ri.activityInfo.packageName;
            }
        } catch (Throwable ignored) {
        }
        return "com.sec.android.app.launcher";
    }

    @Override
    public boolean onUnbind(Intent intent) {
        abort();
        hideHud();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        abort();
        hideHud();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Trong luc toi uu, dich vu tu doc man hinh theo chu ky nen bo qua su kien.
        // Ngoai luc do, chi theo doi doi cua so de biet khi nao anh thoat game.
        if (running || event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence cs = event.getPackageName();
        if (cs == null) {
            return;
        }
        String pkg = cs.toString();
        if (!Session.active(this)) {
            // Anh mo game truc tiep (khong qua nut Toi uu) -> tu bat che do choi
            if (pkg.startsWith(GAME_PREFIX) && prefs().getBoolean(K_AUTO_DETECT, true)) {
                BoostLog.add("──────── Phát hiện mở game – bật chế độ chơi ────────");
                Session.applyQuick(this, pkg);
                gameSeen = true;
                onGameVisible();
            }
            return;
        }
        String game = Session.game(this);
        if (game == null) {
            return;
        }
        if (pkg.equals(game)) {
            gameSeen = true;
            h.removeCallbacks(exitCheck);
            onGameVisible();
            return;
        }
        if (!gameSeen || TRANSIENT.contains(pkg)) {
            return;
        }
        // Roi khoi game: an lop phu thong so, cho het thoi gian roi khoi phuc
        h.removeCallbacks(hudTick);
        hideHud();
        long grace = pkg.equals(homePkg) ? GRACE_HOME_MS : GRACE_OTHER_MS;
        h.removeCallbacks(exitCheck);
        h.postDelayed(exitCheck, grace);
    }

    /** Het thoi gian cho ma game chua quay lai man hinh -> coi nhu da thoat game. */
    private final Runnable exitCheck = () -> {
        if (running || !Session.active(this)) {
            return;
        }
        String game = Session.game(this);
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null && game != null
                    && game.contentEquals(root.getPackageName())) {
                return;
            }
        } catch (Throwable ignored) {
        }
        gameSeen = false;
        Session.end(this, "đã thoát game");
    };

    @Override
    public void onInterrupt() {
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Session.PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ lop phu thong so + canh bao nong

    private void onGameVisible() {
        boolean wantHud = prefs().getBoolean(K_HUD, false);
        boolean wantHeat = prefs().getBoolean(K_HEAT, true);
        if (wantHud) {
            showHud();
        }
        if (wantHud || wantHeat) {
            h.removeCallbacks(hudTick);
            h.post(hudTick);
        }
    }

    /** Goi tu Session.end: dung moi thu cua phien choi. */
    void onSessionEnded() {
        h.removeCallbacks(hudTick);
        h.removeCallbacks(exitCheck);
        hideHud();
        heatWarned = false;
        gameSeen = false;
    }

    private final Runnable hudTick = new Runnable() {
        @Override
        public void run() {
            if (!Session.active(BoostService.this)) {
                hideHud();
                return;
            }
            updateHud();
            h.postDelayed(this, 3000);
        }
    };

    private static String thermalName(int s) {
        switch (s) {
            case 0: return "Mát";
            case 1: return "Hơi ấm";
            case 2: return "Ấm";
            case 3: return "Nóng";
            case 4: return "Rất nóng";
            case 5: return "Quá nóng";
            case 6: return "Sắp tắt máy";
            default: return "?";
        }
    }

    private void updateHud() {
        int thermal = -1;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                thermal = pm.getCurrentThermalStatus();
            }
        } catch (Throwable ignored) {
        }
        float temp = 0f;
        int level = -1;
        boolean charging = false;
        try {
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
                level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                charging = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            }
        } catch (Throwable ignored) {
        }
        float ramGb = 0f;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ramGb = mi.availMem / (1024f * 1024f * 1024f);
        } catch (Throwable ignored) {
        }
        if (hud != null) {
            hud.setText(String.format(java.util.Locale.US, "%.1f°C · %s\nPin %d%%%s · RAM trống %.1f GB",
                    temp, thermalName(thermal), level, charging ? " (sạc)" : "", ramGb));
        }
        boolean hot = thermal >= 3 || temp >= HOT_BATTERY_C;
        if (hot && !heatWarned && prefs().getBoolean(K_HEAT, true)) {
            heatWarned = true;
            String msg = String.format(java.util.Locale.US,
                    "⚠ Máy đang nóng (%.1f°C · %s): game sẽ bị hạ FPS. Nên giảm đồ họa, tháo ốp%s.",
                    temp, thermalName(thermal), charging ? ", rút sạc hoặc bật Tạm dừng sạc USB trong Game Booster" : "");
            BoostLog.add(msg);
            try {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }

    private void showHud() {
        if (hud != null) {
            return;
        }
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            TextView v = new TextView(this);
            v.setTextColor(Color.parseColor("#F0B232"));
            v.setTextSize(11);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setBackgroundColor(Color.parseColor("#990E1220"));
            v.setPadding(14, 8, 14, 8);
            v.setText("TFT Boost…");
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 24;
            lp.y = 24;
            wm.addView(v, lp);
            hud = v;
        } catch (Throwable ignored) {
            hud = null;
        }
    }

    private void hideHud() {
        if (hud != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                wm.removeView(hud);
            } catch (Throwable ignored) {
            }
            hud = null;
        }
    }

    boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ dieu khien

    void start(List<String> pkgs, List<String> names, String gamePkg, boolean closeAll) {
        if (running) {
            return;
        }
        queue = new ArrayList<String>(pkgs);
        labels = new ArrayList<String>(names);
        idx = 0;
        game = gamePkg;
        closeRecents = closeAll;
        stopped = 0;
        skipped = 0;
        gameSeen = false;
        h.removeCallbacks(exitCheck);
        running = true;
        startedAt = SystemClock.uptimeMillis();
        showOverlay("Đang tối ưu…");
        h.postDelayed(this::nextApp, 150);
    }

    private void abort() {
        running = false;
        phase = PH_IDLE;
        h.removeCallbacksAndMessages(null);
        hideOverlay();
    }

    private final Runnable poll = this::poll;

    private void schedulePoll(long delay) {
        h.removeCallbacks(poll);
        h.postDelayed(poll, delay);
    }

    private void setPhase(int p) {
        phase = p;
        phaseStart = SystemClock.uptimeMillis();
    }

    private void nextApp() {
        if (!running) {
            return;
        }
        if (idx >= queue.size()) {
            afterStops();
            return;
        }
        String pkg = queue.get(idx);
        String label = idx < labels.size() ? labels.get(idx) : pkg;
        idx++;
        updateOverlay("Đang buộc dừng " + idx + "/" + queue.size() + "\n" + label);
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(i);
        } catch (Throwable t) {
            skipped++;
            h.postDelayed(this::nextApp, 50);
            return;
        }
        setPhase(PH_FIND_STOP);
        schedulePoll(350);
    }

    private void poll() {
        if (!running) {
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - phaseStart;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        boolean inSettings = root != null && root.getPackageName() != null
                && SETTINGS_PKG.contentEquals(root.getPackageName());

        switch (phase) {
            case PH_FIND_STOP: {
                if (inSettings) {
                    AccessibilityNodeInfo btn = findExact(root, STOP_TEXTS);
                    AccessibilityNodeInfo target = clickable(btn);
                    if (target != null) {
                        if (target.isEnabled() && btn.isEnabled()) {
                            target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            setPhase(PH_CONFIRM);
                            schedulePoll(200);
                            return;
                        }
                        // Nut bi mo = app khong chay. Cho them chut vi Samsung cap nhat trang thai cham.
                        if (elapsed > 1100) {
                            skipped++;
                            nextApp();
                            return;
                        }
                    }
                }
                if (elapsed > 4000) {
                    skipped++;
                    nextApp();
                    return;
                }
                schedulePoll(150);
                return;
            }
            case PH_CONFIRM: {
                if (root != null) {
                    AccessibilityNodeInfo ok = byId(root, "android:id/button1");
                    if (ok == null) {
                        ok = findExact(root, OK_TEXTS);
                    }
                    AccessibilityNodeInfo target = clickable(ok);
                    if (target != null && target.isEnabled()) {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        stopped++;
                        h.postDelayed(this::nextApp, 250);
                        return;
                    }
                }
                if (elapsed > 2500) {
                    skipped++;
                    nextApp();
                    return;
                }
                schedulePoll(150);
                return;
            }
            case PH_RECENTS: {
                if (root != null) {
                    AccessibilityNodeInfo target = clickable(findExact(root, CLOSE_ALL_TEXTS));
                    if (target != null && target.isEnabled()) {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        BoostLog.add("✓ Đã đóng tất cả app gần đây");
                        h.postDelayed(this::finish, 700);
                        return;
                    }
                }
                if (elapsed > 2200) {
                    BoostLog.add("• Không thấy nút Đóng tất cả (có thể danh sách đang trống)");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    h.postDelayed(this::finish, 400);
                    return;
                }
                schedulePoll(150);
                return;
            }
            default:
        }
    }

    private void afterStops() {
        if (!queue.isEmpty()) {
            BoostLog.add("✓ Buộc dừng " + stopped + " app · bỏ qua " + skipped + " app (không chạy)");
        }
        performGlobalAction(GLOBAL_ACTION_HOME);
        if (!closeRecents) {
            h.postDelayed(this::finish, 400);
            return;
        }
        updateOverlay("Đang đóng app gần đây…");
        h.postDelayed(() -> {
            if (!running) {
                return;
            }
            performGlobalAction(GLOBAL_ACTION_RECENTS);
            setPhase(PH_RECENTS);
            schedulePoll(450);
        }, 450);
    }

    private void finish() {
        float secs = (SystemClock.uptimeMillis() - startedAt) / 1000f;
        abort();
        BoostLog.add(String.format(java.util.Locale.US, "Hoàn tất trong %.1f giây", secs));
        if (game == null) {
            Session.end(this, "không có game để mở");
            return;
        }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(game);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                BoostLog.add("▶ Đã mở game");
            }
        } catch (Throwable t) {
            BoostLog.add("✗ Không mở được game: " + t);
        }
    }

    // ------------------------------------------------------------------ tim nut

    private static AccessibilityNodeInfo findExact(AccessibilityNodeInfo root, String[] texts) {
        if (root == null) {
            return null;
        }
        for (String t : texts) {
            List<AccessibilityNodeInfo> list;
            try {
                list = root.findAccessibilityNodeInfosByText(t);
            } catch (Throwable e) {
                continue;
            }
            if (list == null) {
                continue;
            }
            for (AccessibilityNodeInfo n : list) {
                CharSequence s = n.getText();
                if (s == null) {
                    s = n.getContentDescription();
                }
                if (s != null && s.toString().trim().equalsIgnoreCase(t)) {
                    return n;
                }
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo byId(AccessibilityNodeInfo root, String id) {
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
            if (list != null && !list.isEmpty()) {
                return list.get(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Leo len cay giao dien toi node bam duoc gan nhat. */
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        int depth = 0;
        while (n != null && depth < 6) {
            if (n.isClickable()) {
                return n;
            }
            n = n.getParent();
            depth++;
        }
        return null;
    }

    // ------------------------------------------------------------------ lop phu tien do

    private void showOverlay(String msg) {
        hideOverlay();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            TextView v = new TextView(this);
            v.setText(msg);
            v.setTextColor(Color.parseColor("#F0B232"));
            v.setTextSize(20);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setGravity(Gravity.CENTER);
            v.setBackgroundColor(Color.parseColor("#E60E1220"));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            wm.addView(v, lp);
            overlay = v;
        } catch (Throwable ignored) {
            overlay = null;
        }
    }

    private void updateOverlay(String msg) {
        if (overlay != null) {
            overlay.setText("⚡ TFT Boost\n\n" + msg);
        }
    }

    private void hideOverlay() {
        if (overlay != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                wm.removeView(overlay);
            } catch (Throwable ignored) {
            }
            overlay = null;
        }
    }
}
