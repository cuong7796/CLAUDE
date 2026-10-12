package vn.cuong.tftboost.pro;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * TFT Boost Pro - toi uu toi da qua Shizuku cho Dau Truong Chan Ly Mobile tren Galaxy S25+.
 * Moi thiet lap bi doi deu duoc ghi gia tri goc va tu khoi phuc khi thoat game.
 */
public class MainActivity extends Activity {

    private static final String GAME_PREFIX = "com.riotgames.league.teamfighttactics";
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String GOS_PKG = "com.samsung.android.game.gos";
    private static final String GAMING_HUB_PKG = "com.samsung.android.game.gamehome";
    private static final int SHIZUKU_REQ = 7796;

    /** App he thong nang, buoc dung an toan. */
    private static final List<String> HEAVY_SYSTEM = Arrays.asList(
            "com.android.chrome",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.sec.android.app.sbrowser",
            "com.google.android.apps.maps",
            "com.google.android.apps.photos",
            "com.google.android.gm",
            "com.google.android.apps.docs",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.instagram.android",
            "com.samsung.android.app.tips",
            "com.sec.android.app.shealth",
            "com.samsung.android.voc",
            "com.sec.android.app.samsungapps");

    /** Khong bao gio buoc dung. */
    private static final Set<String> NEVER = new HashSet<String>(Arrays.asList(
            SHIZUKU_PKG,
            "vn.cuong.tftboost",
            "com.android.settings",
            "com.android.systemui",
            "com.sec.android.app.launcher",
            "com.sec.android.app.clockpackage",
            "com.samsung.android.dialer",
            "com.samsung.android.messaging",
            "com.samsung.android.incallui",
            "com.samsung.android.honeyboard",
            GAMING_HUB_PKG,
            "com.samsung.android.game.gametools",
            GOS_PKG));

    // Khoa tuy chon
    private static final String K_AUTO = "auto_run";
    private static final String K_AUTO_RESTORE = "auto_restore";
    private static final String K_DND = "dnd";
    private static final String K_FORCE_STOP = "force_stop";
    private static final String K_FPS120 = "fps120";
    private static final String K_GAMEMODE = "game_mode";
    private static final String K_POWERSAVE = "power_save_off";
    private static final String K_WIFI_LL = "wifi_low_latency";
    private static final String K_SYNC = "sync_off";
    private static final String K_SCAN = "scan_off";
    private static final String K_GOS = "gos_off_max";
    private static final String K_THERMAL = "thermal_override";
    private static final String K_KEEP = "keep";

    private static final int C_BG = Color.parseColor("#0E1220");
    private static final int C_CARD = Color.parseColor("#1B2340");
    private static final int C_ACCENT = Color.parseColor("#FF5A36");
    private static final int C_GOLD = Color.parseColor("#F0B232");
    private static final int C_TEXT = Color.parseColor("#E8ECF8");
    private static final int C_DIM = Color.parseColor("#9AA3BF");

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView statsView;
    private TextView logView;
    private Button boostBtn;
    private volatile boolean busy;

    private final Shizuku.OnRequestPermissionResultListener shizukuListener = (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_REQ) {
            return;
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            log("✓ Đã được cấp quyền Shizuku");
            worker.execute(this::autoGrant);
        } else {
            log("✗ Anh đã từ chối quyền Shizuku");
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            ui.postDelayed(this, 2000);
        }
    };

    // ------------------------------------------------------------------ vong doi

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(GameSession.PREFS, Context.MODE_PRIVATE);
        setContentView(buildUi());
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        if (savedInstanceState == null && prefs.getBoolean(K_AUTO, false)) {
            ui.postDelayed(this::boost, 700);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        logView.setText(BoostLog.text());
        BoostLog.setListener(all -> logView.setText(all));
        ui.removeCallbacks(ticker);
        ui.post(ticker);
        // Quay lai app trong luc dang co phien choi = da roi game -> khoi phuc
        if (!busy && GameSession.active(this) && prefs.getBoolean(K_AUTO_RESTORE, true)) {
            worker.execute(() -> GameSession.restore(this, "đã quay lại TFT Boost Pro"));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        BoostLog.setListener(null);
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        worker.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ giao dien

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams rowParams(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(C_TEXT);
        b.setTextSize(14);
        b.setBackground(round(C_CARD, 12));
        b.setOnClickListener(l);
        return b;
    }

    private Switch toggle(String label, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(C_TEXT);
        s.setTextSize(14);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        return s;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(root);

        root.addView(text("TFT Boost Pro", 26, C_ACCENT, true));
        root.addView(text("Hiệu năng tối đa qua Shizuku · Khóa 120 FPS · Tự khôi phục khi thoát game", 13, C_DIM, false));

        statsView = text("…", 14, C_TEXT, false);
        statsView.setBackground(round(C_CARD, 14));
        statsView.setPadding(dp(14), dp(12), dp(14), dp(12));
        statsView.setLineSpacing(dp(3), 1f);
        root.addView(statsView, rowParams(16));

        boostBtn = new Button(this);
        boostBtn.setText("⚡ TỐI ƯU & VÀO GAME");
        boostBtn.setAllCaps(false);
        boostBtn.setTextSize(19);
        boostBtn.setTypeface(Typeface.DEFAULT_BOLD);
        boostBtn.setTextColor(C_BG);
        boostBtn.setBackground(round(C_ACCENT, 18));
        boostBtn.setOnClickListener(v -> boost());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76));
        bp.topMargin = dp(16);
        root.addView(boostBtn, bp);

        root.addView(text("Tối ưu khi vào game", 15, C_GOLD, true), rowParams(22));
        root.addView(toggle("Khóa 120 FPS (màn hình 120Hz cố định + Game Mode 120 FPS)", K_FPS120, true));
        root.addView(toggle("Buộc dừng app nền + dọn RAM", K_FORCE_STOP, true));
        root.addView(toggle("Game Mode hiệu năng cho Đấu Trường Chân Lý", K_GAMEMODE, true));
        root.addView(toggle("Tắt Tiết kiệm pin trong lúc chơi", K_POWERSAVE, true));
        root.addView(toggle("Wi-Fi độ trễ thấp (giảm ping)", K_WIFI_LL, true));
        root.addView(toggle("Tạm dừng tự động đồng bộ (Gmail, Drive, Ảnh…)", K_SYNC, true));
        root.addView(toggle("Tắt quét Wi-Fi/Bluetooth chạy nền", K_SCAN, true));
        root.addView(toggle("Bật Không làm phiền", K_DND, true));
        root.addView(toggle("Tắt GOS – bỏ giới hạn hiệu năng Samsung (máy nóng hơn)", K_GOS, true));
        root.addView(toggle("Bỏ giới hạn nhiệt cho game (rất nóng – chỉ bật khi cần)", K_THERMAL, false));

        root.addView(text("Sau khi chơi", 15, C_GOLD, true), rowParams(18));
        root.addView(toggle("Thoát game → tự khôi phục mặc định", K_AUTO_RESTORE, true));
        root.addView(toggle("Tự tối ưu ngay khi mở app (đúng 1 chạm)", K_AUTO, false));
        root.addView(text("Mọi thiết lập bị đổi đều được ghi lại giá trị gốc. Về màn hình chính quá 8 giây, "
                + "sang app khác quá 45 giây, mở lại app này hoặc tắt màn hình quá 10 phút là máy tự trả về như cũ.",
                12, C_DIM, false));

        root.addView(text("Thiết lập & công cụ", 15, C_GOLD, true), rowParams(18));
        root.addView(smallButton("Cấp quyền Shizuku (tự cấp luôn các quyền còn lại)", v -> requestShizuku()), rowParams(8));
        root.addView(smallButton("Chọn app KHÔNG bị dừng (Zalo, Messenger…)", v -> pickKeepApps()), rowParams(8));
        root.addView(smallButton("Biên dịch tối ưu game (1 lần sau mỗi bản cập nhật)", v -> compileGame()), rowParams(8));
        root.addView(smallButton("Mở Gaming Hub / Game Booster", v ->
                launchPackage(GAMING_HUB_PKG, "Không tìm thấy Gaming Hub trên máy.")), rowParams(8));
        root.addView(smallButton("↺ Khôi phục mặc định ngay", v -> restoreNow()), rowParams(8));

        root.addView(text("Nhật ký", 15, C_GOLD, true), rowParams(18));
        logView = text(BoostLog.text(), 12, C_DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(round(C_CARD, 14));
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView.setTextIsSelectable(true);
        root.addView(logView, rowParams(8));
        return scroll;
    }

    private void log(String s) {
        BoostLog.add(s);
    }

    private void toast(final String s) {
        ui.post(() -> Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show());
    }

    private void setBusy(final boolean b) {
        busy = b;
        ui.post(() -> {
            boostBtn.setEnabled(!b);
            boostBtn.setAlpha(b ? 0.5f : 1f);
        });
    }

    // ------------------------------------------------------------------ trang thai

    private long availMb() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    private long totalMb() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem / (1024 * 1024);
    }

    private boolean usageGranted() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean dndGranted() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.isNotificationPolicyAccessGranted();
    }

    private void refreshStats() {
        if (statsView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "RAM trống: %.1f / %.1f GB", availMb() / 1024f, totalMb() / 1024f));
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int t = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            sb.append(String.format(Locale.US, "\nPin: %d%%  ·  Nhiệt độ pin: %.1f°C", level, t / 10f));
        }
        String game = findGame();
        sb.append("\nGame: ").append(game == null ? "CHƯA CÀI Đấu Trường Chân Lý" : game);
        String sh;
        if (!Shell.alive()) {
            sh = "CHƯA CHẠY – mở Shizuku bấm Start";
        } else if (!Shell.granted()) {
            sh = "đang chạy, chưa cấp quyền";
        } else {
            sh = "sẵn sàng ✓";
        }
        sb.append("\nShizuku: ").append(sh);
        sb.append("\nQuyền theo dõi thoát game: ").append(usageGranted() ? "✓" : "chưa có");
        sb.append("  ·  Không làm phiền: ").append(dndGranted() ? "✓" : "chưa có");
        if (GameSession.active(this)) {
            sb.append("\nPhiên chơi: ĐANG BẬT (").append(GameSession.load(this).size())
                    .append(" thiết lập chờ khôi phục)");
        } else {
            sb.append("\nPhiên chơi: không có – máy đang ở cài đặt mặc định");
        }
        statsView.setText(sb.toString());
    }

    // ------------------------------------------------------------------ tien ich

    private String findGame() {
        try {
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                if (ai.packageName.startsWith(GAME_PREFIX)) {
                    return ai.packageName;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean launchPackage(String pkg, String failMsg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            }
        } catch (Throwable ignored) {
        }
        toast(failMsg);
        return false;
    }

    private Set<String> protectedSet(String game) {
        Set<String> keep = new HashSet<String>(prefs.getStringSet(K_KEEP, new HashSet<String>()));
        keep.addAll(NEVER);
        keep.add(getPackageName());
        if (game != null) {
            keep.add(game);
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null) {
                keep.add(ri.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
        }
        try {
            String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) {
                keep.add(ime.substring(0, ime.indexOf('/')));
            }
        } catch (Throwable ignored) {
        }
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(this);
            if (sms != null) {
                keep.add(sms);
            }
        } catch (Throwable ignored) {
        }
        try {
            TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && tm.getDefaultDialerPackage() != null) {
                keep.add(tm.getDefaultDialerPackage());
            }
        } catch (Throwable ignored) {
        }
        return keep;
    }

    // ------------------------------------------------------------------ Shizuku

    private void requestShizuku() {
        if (!Shell.alive()) {
            if (!launchPackage(SHIZUKU_PKG, "Chưa cài Shizuku. Cài Shizuku từ CH Play rồi bấm Start.")) {
                log("✗ Shizuku chưa chạy. Mở Shizuku → Ghép nối qua Gỡ lỗi không dây → Start.");
            }
            return;
        }
        if (Shell.granted()) {
            worker.execute(this::autoGrant);
            return;
        }
        try {
            Shizuku.requestPermission(SHIZUKU_REQ);
        } catch (Throwable t) {
            log("✗ Không xin được quyền Shizuku: " + t);
        }
    }

    /** Dung Shizuku tu cap: xem nhat ky su dung, Khong lam phien, thong bao. */
    private void autoGrant() {
        if (!Shell.granted()) {
            return;
        }
        String me = getPackageName();
        boolean ok = true;
        if (!usageGranted()) {
            ok &= Shell.run("appops set " + me + " GET_USAGE_STATS allow").ok();
        }
        if (!dndGranted()) {
            ok &= Shell.run("cmd notification allow_dnd " + me).ok();
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Shell.run("pm grant " + me + " android.permission.POST_NOTIFICATIONS");
        }
        log(ok ? "✓ Đã tự cấp quyền theo dõi thoát game, Không làm phiền, thông báo"
                : "✗ Chưa cấp được một số quyền – xem trạng thái phía trên");
    }

    /** Chay 1 lenh, ghi nhat ky, tra ve true neu thanh cong. */
    private boolean step(String label, String cmd) {
        Shell.Result r = Shell.run(cmd);
        boolean ok = r.ok();
        log((ok ? "✓ " : "✗ ") + label + (ok ? "" : " — " + Shell.firstLine(r.out)));
        return ok;
    }

    // ------------------------------------------------------------------ TOI UU

    private void boost() {
        if (busy) {
            return;
        }
        setBusy(true);
        worker.execute(() -> {
            try {
                doBoost();
            } catch (Throwable t) {
                log("✗ Lỗi: " + t);
            } finally {
                setBusy(false);
            }
        });
    }

    private void doBoost() {
        final String game = findGame();
        log("──────── Bắt đầu tối ưu ────────");
        long before = availMb();
        boolean shell = Shell.granted();
        if (!shell) {
            log("✗ Shizuku chưa sẵn sàng → chỉ chạy được Không làm phiền, đồng bộ và dọn nền cơ bản");
        } else {
            autoGrant();
        }

        // Danh sach khoi phuc: giu nguyen gia tri goc tu lan dau, chi bo sung muc moi
        final Map<String, String> restore = GameSession.load(this);

        // 1) Khong lam phien
        if (prefs.getBoolean(K_DND, true)) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.isNotificationPolicyAccessGranted()) {
                if (!restore.containsKey(GameSession.J_DND)) {
                    restore.put(GameSession.J_DND, String.valueOf(nm.getCurrentInterruptionFilter()));
                }
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                log("✓ Bật Không làm phiền");
            } else {
                log("• Bỏ qua Không làm phiền (chưa có quyền)");
            }
        }

        // 2) Tam dung dong bo
        if (prefs.getBoolean(K_SYNC, true)) {
            try {
                boolean was = ContentResolver.getMasterSyncAutomatically();
                if (!restore.containsKey(GameSession.J_SYNC)) {
                    restore.put(GameSession.J_SYNC, was ? "1" : "0");
                }
                ContentResolver.setMasterSyncAutomatically(false);
                log("✓ Tạm dừng tự động đồng bộ");
            } catch (Throwable t) {
                log("✗ Tạm dừng đồng bộ — " + t);
            }
        }

        if (shell) {
            // 3) Khoa 120 FPS: man hinh co dinh 120Hz + Game Mode cho phep 120 FPS
            if (prefs.getBoolean(K_FPS120, true)) {
                capture(restore, "s:system:peak_refresh_rate", "system", "peak_refresh_rate");
                capture(restore, "s:system:min_refresh_rate", "system", "min_refresh_rate");
                String rrm = Shell.get("settings get secure refresh_rate_mode");
                if ("0".equals(rrm)) {
                    log("✗ Màn hình đang để Tiêu chuẩn (60Hz). Vào Cài đặt → Màn hình → Độ mượt chuyển động → "
                            + "chọn Thích ứng thì mới lên được 120Hz.");
                }
                boolean hz = step("Màn hình cố định 120Hz (không tự hạ xuống 60Hz/1Hz)",
                        "settings put system peak_refresh_rate 120.0 && settings put system min_refresh_rate 120.0");
                if (game != null) {
                    String dcKey = "dc:game_overlay:" + game;
                    if (!restore.containsKey(dcKey)) {
                        String r = GameSession.deviceConfigRestore("game_overlay", game);
                        if (r != null) {
                            restore.put(dcKey, r);
                        }
                    }
                    step("Game Mode cho phép 120 FPS",
                            "device_config put game_overlay " + game + " " + Shell.quote("mode=2,fps=120:mode=3,fps=60"));
                }
                if (hz) {
                    log("  → Nhớ chọn mức khung hình cao nhất trong cài đặt đồ họa của game");
                }
            }

            // 4) Game Mode hieu nang
            if (game != null && (prefs.getBoolean(K_GAMEMODE, true) || prefs.getBoolean(K_FPS120, true))) {
                if (step("Game Mode = hiệu năng", "cmd game mode performance " + game)) {
                    restore.put("gamemode:" + game, "cmd game mode standard " + game);
                }
            }

            // 5) Tat Tiet kiem pin
            if (prefs.getBoolean(K_POWERSAVE, true)) {
                String lp = Shell.get("settings get global low_power");
                if ("1".equals(lp)) {
                    if (step("Tắt Tiết kiệm pin", "cmd power set-mode 0") && !restore.containsKey("power_mode")) {
                        restore.put("power_mode", "cmd power set-mode 1");
                    }
                } else {
                    log("• Tiết kiệm pin đang tắt sẵn");
                }
            }

            // 6) Wi-Fi do tre thap
            if (prefs.getBoolean(K_WIFI_LL, true)) {
                if (step("Wi-Fi độ trễ thấp", "cmd wifi force-low-latency-mode enabled")) {
                    restore.put("wifi_ll", "cmd wifi force-low-latency-mode disabled");
                }
            }

            // 7) Tat quet nen
            if (prefs.getBoolean(K_SCAN, true)) {
                capture(restore, "s:global:wifi_scan_always_enabled", "global", "wifi_scan_always_enabled");
                capture(restore, "s:global:ble_scan_always_enabled", "global", "ble_scan_always_enabled");
                step("Tắt quét Wi-Fi/Bluetooth nền",
                        "settings put global wifi_scan_always_enabled 0 && settings put global ble_scan_always_enabled 0");
            }

            // 8) GOS
            if (prefs.getBoolean(K_GOS, true)) {
                if (step("Tắt GOS (bỏ giới hạn hiệu năng Samsung)", "pm disable-user --user 0 " + GOS_PKG)) {
                    restore.put("gos", "pm enable " + GOS_PKG);
                }
            }

            // 8b) Bo gioi han nhiet: bao "khong nong" cho game de game khong tu ha FPS/do hoa.
            // Chip van tu bao ve o muc phan cung.
            if (prefs.getBoolean(K_THERMAL, false)) {
                if (step("Bỏ giới hạn nhiệt cho game", "cmd thermalservice override-status 0")) {
                    restore.put("thermal", "cmd thermalservice reset");
                }
            }

            // Ghi phien TRUOC khi buoc dung app, de neu co su co van khoi phuc duoc
            GameSession.begin(this, game, restore);

            // 9) Buoc dung app nen + don RAM
            if (prefs.getBoolean(K_FORCE_STOP, true)) {
                forceStopAll(game);
            }
        } else {
            GameSession.begin(this, game, restore);
        }

        // Don nen bang API thuong (luon chay)
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> keep = protectedSet(game);
        try {
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                if (!keep.contains(ai.packageName)) {
                    am.killBackgroundProcesses(ai.packageName);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
        }
        long after = availMb();
        log(String.format(Locale.US, "RAM trống: %d MB → %d MB (%+d MB)", before, after, after - before));

        // 10) Vao game + bat theo doi thoat game
        if (game == null) {
            log("✗ Chưa cài Đấu Trường Chân Lý – khôi phục lại cài đặt");
            GameSession.restore(this, "không có game");
            toast("Chưa tìm thấy Đấu Trường Chân Lý trên máy.");
            return;
        }
        if (prefs.getBoolean(K_AUTO_RESTORE, true)) {
            if (usageGranted()) {
                ui.post(() -> {
                    try {
                        WatchService.start(this, game);
                        log("✓ Bật theo dõi: thoát game sẽ tự khôi phục");
                    } catch (Throwable t) {
                        log("✗ Không bật được theo dõi thoát game: " + t);
                    }
                });
            } else {
                log("• Chưa có quyền theo dõi – khôi phục khi anh mở lại app này");
            }
        }
        log("▶ Mở game…");
        ui.postDelayed(() -> launchPackage(game, "Không mở được game."), 300);
    }

    private void capture(Map<String, String> restore, String key, String ns, String name) {
        if (restore.containsKey(key)) {
            return;
        }
        String r = GameSession.settingRestore(ns, name);
        if (r != null) {
            restore.put(key, r);
        }
    }

    private void forceStopAll(String game) {
        Set<String> keep = protectedSet(game);
        Set<String> targets = new LinkedHashSet<String>();
        Shell.Result list = Shell.run("pm list packages -3");
        if (list.ok()) {
            for (String line : list.out.split("\n")) {
                targets.add(line.replace("package:", "").trim());
            }
        }
        targets.addAll(HEAVY_SYSTEM);
        StringBuilder script = new StringBuilder();
        int n = 0;
        for (String pkg : targets) {
            if (pkg.isEmpty() || keep.contains(pkg) || !pkg.matches("[A-Za-z0-9._]+")) {
                continue;
            }
            script.append("am force-stop ").append(pkg).append("; ");
            n++;
        }
        script.append("am kill-all");
        step("Buộc dừng " + n + " app + dọn toàn bộ tiến trình nền", script.toString());
    }

    // ------------------------------------------------------------------ KHOI PHUC / BIEN DICH

    private void restoreNow() {
        if (busy) {
            return;
        }
        if (!GameSession.active(this)) {
            toast("Máy đang ở cài đặt mặc định, không có gì để khôi phục.");
            return;
        }
        setBusy(true);
        worker.execute(() -> {
            try {
                GameSession.restore(this, "bấm Khôi phục");
                stopService(new Intent(this, WatchService.class));
            } finally {
                setBusy(false);
            }
        });
    }

    private void compileGame() {
        final String game = findGame();
        if (game == null) {
            toast("Chưa tìm thấy Đấu Trường Chân Lý trên máy.");
            return;
        }
        if (!Shell.granted()) {
            toast("Tính năng này cần Shizuku.");
            return;
        }
        if (busy) {
            return;
        }
        setBusy(true);
        log("Đang biên dịch tối ưu game, mất 1–3 phút, anh đừng thoát app…");
        worker.execute(() -> {
            try {
                step("Biên dịch tối ưu (speed) cho game", "cmd package compile -m speed -f " + game);
            } finally {
                setBusy(false);
            }
        });
    }

    // ------------------------------------------------------------------ DANH SACH GIU LAI

    private void pickKeepApps() {
        final PackageManager pm = getPackageManager();
        final List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean eligible = !system || HEAVY_SYSTEM.contains(ai.packageName);
                if (eligible && !NEVER.contains(ai.packageName)
                        && !ai.packageName.equals(getPackageName())
                        && !ai.packageName.startsWith(GAME_PREFIX)) {
                    apps.add(ai);
                }
            }
        } catch (Throwable ignored) {
        }
        final List<String> names = new ArrayList<String>();
        for (ApplicationInfo ai : apps) {
            names.add(String.valueOf(pm.getApplicationLabel(ai)));
        }
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < apps.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> names.get(a).compareToIgnoreCase(names.get(b)));
        final String[] labels = new String[apps.size()];
        final String[] pkgs = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        final Set<String> keep = new HashSet<String>(prefs.getStringSet(K_KEEP, new HashSet<String>()));
        for (int i = 0; i < order.size(); i++) {
            int src = order.get(i);
            labels[i] = names.get(src);
            pkgs[i] = apps.get(src).packageName;
            checked[i] = keep.contains(pkgs[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("App được giữ chạy nền")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Lưu", (d, which) -> {
                    Set<String> out = new HashSet<String>();
                    for (int i = 0; i < pkgs.length; i++) {
                        if (checked[i]) {
                            out.add(pkgs[i]);
                        }
                    }
                    prefs.edit().putStringSet(K_KEEP, out).apply();
                    log("✓ Đã lưu " + out.size() + " app được giữ lại");
                })
                .setNegativeButton("Hủy", null)
                .show();
    }
}
