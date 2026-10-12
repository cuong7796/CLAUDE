package vn.cuong.tftboost;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
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
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TFT Boost 2.0 - toi uu 1 cham cho Dau Truong Chan Ly Mobile, KHONG can Shizuku.
 *
 * Chuoi toi uu: bat Khong lam phien -> don tien trinh nen -> dich vu Tro nang tu bam
 * "Buoc dung" cho cac app vua dung -> "Dong tat ca" app gan day -> mo game.
 */
public class MainActivity extends Activity {

    private static final String GAME_PREFIX = "com.riotgames.league.teamfighttactics";
    private static final String GAMING_HUB_PKG = "com.samsung.android.game.gamehome";
    private static final String DEVICE_CARE_PKG = "com.samsung.android.lool";
    private static final long RECENT_WINDOW_MS = 12L * 60 * 60 * 1000; // app dung trong 12 gio qua
    private static final int MAX_STOP = 30;

    /** App he thong nang, buoc dung an toan (trinh duyet, video, mang xa hoi cai san...). */
    private static final Set<String> HEAVY_SYSTEM = new HashSet<String>(Arrays.asList(
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
            "com.microsoft.office.officehubrow",
            "com.netflix.mediaclient",
            "com.samsung.android.app.tips",
            "com.sec.android.app.shealth",
            "com.samsung.android.voc",
            "com.sec.android.app.samsungapps",
            "com.android.vending"));

    /** Khong bao gio buoc dung (bao thuc, goi dien, tin nhan, bo phan he thong). */
    private static final Set<String> NEVER = new HashSet<String>(Arrays.asList(
            "com.android.settings",
            "com.android.systemui",
            "com.sec.android.app.launcher",
            "com.sec.android.app.clockpackage",
            "com.samsung.android.dialer",
            "com.samsung.android.messaging",
            "com.samsung.android.incallui",
            "com.google.android.gms",
            "com.samsung.android.game.gamehome",
            "com.samsung.android.game.gametools",
            "com.samsung.android.game.gos",
            "com.samsung.android.honeyboard",
            "com.samsung.android.lool"));

    private static final String K_AUTO = "auto_run";
    private static final String K_DND = "dnd";
    private static final String K_FORCE_STOP = "a11y_force_stop";
    private static final String K_CLOSE_RECENTS = "a11y_close_recents";
    private static final String K_KEEP = "keep";

    private static final int C_BG = Color.parseColor("#0E1220");
    private static final int C_CARD = Color.parseColor("#1B2340");
    private static final int C_GOLD = Color.parseColor("#F0B232");
    private static final int C_TEXT = Color.parseColor("#E8ECF8");
    private static final int C_DIM = Color.parseColor("#9AA3BF");

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statsView;
    private TextView logView;
    private Button boostBtn;

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
        prefs = getSharedPreferences("tftboost", Context.MODE_PRIVATE);
        setContentView(buildUi());
        if (savedInstanceState == null && prefs.getBoolean(K_AUTO, false)) {
            ui.postDelayed(this::boost, 500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleReturn();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleReturn();
        logView.setText(BoostLog.text());
        BoostLog.setListener(all -> logView.setText(all));
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        BoostLog.setListener(null);
        ui.removeCallbacks(ticker);
    }

    /**
     * Goi moi khi quay lai man hinh nay:
     * - vua bat Tro nang tu nut Toi uu -> chay tiep toi uu;
     * - dang trong phien choi ma anh quay lai day -> nghia la da thoat game -> khoi phuc.
     */
    private void handleReturn() {
        if (Session.pending(this)) {
            if (a11yOn()) {
                Session.clearPending(this);
                log("✓ Trợ năng đã bật – tiếp tục tối ưu");
                ui.postDelayed(this::boost, 300);
            }
            return;
        }
        if (Session.active(this)) {
            Session.end(this, "đã quay lại TFT Boost");
            refreshStats();
        }
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
        s.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(key, isChecked).apply());
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

        root.addView(text("TFT Boost 2.2", 26, C_GOLD, true));
        root.addView(text("Tối ưu 1 chạm · Không cần Shizuku · Galaxy S25+", 13, C_DIM, false));

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
        boostBtn.setBackground(round(C_GOLD, 18));
        boostBtn.setOnClickListener(v -> boost());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76));
        bp.topMargin = dp(16);
        root.addView(boostBtn, bp);

        root.addView(text("Tùy chọn khi tối ưu", 15, C_GOLD, true), rowParams(22));
        root.addView(toggle("Tự tối ưu ngay khi mở app (đúng 1 chạm)", K_AUTO, false));
        root.addView(toggle("Bật Không làm phiền khi vào game", K_DND, true));
        root.addView(toggle("Tạm dừng tự động đồng bộ trong lúc chơi", Session.K_SYNC, true));
        root.addView(toggle("Buộc dừng các app vừa dùng (Trợ năng)", K_FORCE_STOP, true));
        root.addView(toggle("Đóng tất cả app gần đây (Trợ năng)", K_CLOSE_RECENTS, true));
        root.addView(toggle("Hiện nhiệt độ · RAM · pin khi chơi (lớp phủ nhỏ góc trái)", BoostService.K_HUD, false));
        root.addView(toggle("Cảnh báo khi máy quá nóng (sắp bị hạ FPS)", BoostService.K_HEAT, true));
        root.addView(toggle("Tự bật chế độ chơi khi mở game trực tiếp", BoostService.K_AUTO_DETECT, true));
        root.addView(toggle("Thoát game → tự khôi phục & tự tắt Trợ năng", Session.K_AUTO_OFF, true));
        root.addView(text("Khi bật: thoát game là Không làm phiền về như cũ và Trợ năng tự tắt. "
                + "Lần tối ưu sau app mở trang Trợ năng, anh gạt bật là app tự chạy tiếp.", 12, C_DIM, false));

        root.addView(text("Thiết lập lần đầu", 15, C_GOLD, true), rowParams(18));
        root.addView(text("Làm lần lượt 1 → 4. Dòng trạng thái phía trên hiện ✓ là xong.", 12, C_DIM, false));
        root.addView(smallButton("1. Cho phép cài đặt bị hạn chế (⋮ góc phải)", v -> openAppInfo()), rowParams(8));
        root.addView(smallButton("2. Bật Trợ năng → Ứng dụng đã cài → TFT Boost", v ->
                openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)), rowParams(8));
        root.addView(smallButton("3. Cấp quyền Truy cập dữ liệu sử dụng", v ->
                openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)), rowParams(8));
        root.addView(smallButton("4. Cấp quyền Không làm phiền", v ->
                openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)), rowParams(8));

        root.addView(text("Kiểm tra & tối ưu cài đặt máy", 15, C_GOLD, true), rowParams(18));
        root.addView(smallButton("🔍 Kiểm tra máy (tìm thứ đang làm chậm game)", v -> checkup()), rowParams(8));
        root.addView(smallButton("Màn hình → Độ mượt chuyển động (chọn Thích ứng)", v ->
                openSettings(Settings.ACTION_DISPLAY_SETTINGS)), rowParams(8));
        root.addView(smallButton("Tắt Tiết kiệm pin", v ->
                openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)), rowParams(8));

        root.addView(text("Công cụ", 15, C_GOLD, true), rowParams(18));
        root.addView(smallButton("Chọn app KHÔNG bị dừng (Zalo, Messenger…)", v -> pickKeepApps()), rowParams(8));
        root.addView(smallButton("Xem trước danh sách sẽ bị buộc dừng", v -> previewTargets()), rowParams(8));
        root.addView(smallButton("Mở Gaming Hub / Game Booster", v ->
                launchPackage(GAMING_HUB_PKG, "Không tìm thấy Gaming Hub trên máy.")), rowParams(8));
        root.addView(smallButton("Mở Chăm sóc thiết bị (Device care)", v ->
                launchPackage(DEVICE_CARE_PKG, "Không mở được Chăm sóc thiết bị.")), rowParams(8));
        root.addView(smallButton("↺ Khôi phục ngay (Không làm phiền, Trợ năng)", v -> restore()), rowParams(8));

        root.addView(text("Nhật ký", 15, C_GOLD, true), rowParams(18));
        logView = text(BoostLog.text(), 12, C_DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(round(C_CARD, 14));
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView.setTextIsSelectable(true);
        root.addView(logView, rowParams(8));

        return scroll;
    }

    private void log(String line) {
        BoostLog.add(line);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
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

    private boolean a11yOn() {
        return BoostService.instance != null;
    }

    private boolean usageGranted() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            }
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
        sb.append("\nTrợ năng TFT Boost: ").append(a11yOn() ? "đang bật ✓" : "CHƯA BẬT");
        sb.append("\nDữ liệu sử dụng: ").append(usageGranted() ? "đã cấp ✓" : "chưa cấp");
        sb.append("\nKhông làm phiền: ").append(dndGranted() ? "đã cấp ✓" : "chưa cấp");
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

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Throwable t) {
            toast("Không mở được màn hình cài đặt này.");
        }
    }

    private void openAppInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast("Bấm ⋮ góc trên bên phải → Cho phép cài đặt bị hạn chế");
        } catch (Throwable t) {
            toast("Không mở được Thông tin ứng dụng.");
        }
    }

    /** Mo thang trang bat Tro nang cua TFT Boost (Android 12+), khong duoc thi mo danh sach Tro nang. */
    private void openA11yForBoost() {
        Session.markPending(this);
        toast("Gạt BẬT TFT Boost → Cho phép. App sẽ tự quay lại và tối ưu.");
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                i.putExtra(Intent.EXTRA_COMPONENT_NAME,
                        new ComponentName(this, BoostService.class).flattenToString());
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
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

    /** Cac goi tuyet doi khong dung: app nay, game, danh sach giu lai, launcher, ban phim, SMS, dien thoai. */
    private Set<String> protectedSet(String game) {
        Set<String> keep = new HashSet<String>(prefs.getStringSet(K_KEEP, new HashSet<String>()));
        keep.addAll(NEVER);
        keep.add(getPackageName());
        if (game != null) {
            keep.add(game);
        }
        PackageManager pm = getPackageManager();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(home, 0);
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

    /** App can buoc dung: co mo tu man hinh chinh, da dung trong 12 gio qua, khong thuoc danh sach bao ve. */
    private List<String> collectTargets(String game) {
        final List<String> out = new ArrayList<String>();
        if (!usageGranted()) {
            return out;
        }
        Set<String> keep = protectedSet(game);
        PackageManager pm = getPackageManager();
        long now = System.currentTimeMillis();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - RECENT_WINDOW_MS, now);
        final List<UsageStats> recent = new ArrayList<UsageStats>();
        for (UsageStats us : stats.values()) {
            if (us.getLastTimeUsed() >= now - RECENT_WINDOW_MS) {
                recent.add(us);
            }
        }
        Collections.sort(recent, (a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        for (UsageStats us : recent) {
            String pkg = us.getPackageName();
            if (keep.contains(pkg) || out.contains(pkg)) {
                continue;
            }
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !HEAVY_SYSTEM.contains(pkg)) {
                    continue;
                }
                if (pm.getLaunchIntentForPackage(pkg) == null) {
                    continue;
                }
                out.add(pkg);
            } catch (Throwable ignored) {
            }
            if (out.size() >= MAX_STOP) {
                break;
            }
        }
        return out;
    }

    private List<String> labelsOf(List<String> pkgs) {
        PackageManager pm = getPackageManager();
        List<String> names = new ArrayList<String>();
        for (String p : pkgs) {
            try {
                names.add(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))));
            } catch (Throwable t) {
                names.add(p);
            }
        }
        return names;
    }

    private void previewTargets() {
        if (!usageGranted()) {
            toast("Cần cấp quyền Truy cập dữ liệu sử dụng trước (bước 3).");
            return;
        }
        List<String> names = labelsOf(collectTargets(findGame()));
        String msg = names.isEmpty()
                ? "Không có app nào cần buộc dừng lúc này."
                : "Sẽ buộc dừng " + names.size() + " app:\n\n• " + android.text.TextUtils.join("\n• ", names);
        new AlertDialog.Builder(this)
                .setTitle("Danh sách buộc dừng")
                .setMessage(msg)
                .setPositiveButton("Đóng", null)
                .show();
    }

    // ------------------------------------------------------------------ TOI UU

    private void boost() {
        BoostService svc = BoostService.instance;
        if (svc != null && svc.isRunning()) {
            return;
        }
        final String game = findGame();
        boolean wantStop = prefs.getBoolean(K_FORCE_STOP, true);
        boolean wantClose = prefs.getBoolean(K_CLOSE_RECENTS, true);

        // Tro nang dang tat (do lan truoc tu tat) -> mo trang bat, bat xong app tu chay tiep
        if (svc == null && (wantStop || wantClose)) {
            log("• Trợ năng đang tắt → mở trang bật Trợ năng");
            openA11yForBoost();
            return;
        }

        log("──────── Bắt đầu tối ưu ────────");
        long before = availMb();

        // 1) Khong lam phien + tam dung dong bo (ghi trang thai goc de khoi phuc)
        Session.applyQuick(this, game);

        // Canh bao nhung thu lam cham game ma app khong tu tat duoc
        try {
            PowerManager pwr = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pwr.isPowerSaveMode()) {
                log("✗ Tiết kiệm pin đang BẬT – game bị giới hạn hiệu năng. Bấm nút \"Tắt Tiết kiệm pin\".");
                toast("Tiết kiệm pin đang bật – nên tắt để game chạy mạnh nhất");
            }
        } catch (Throwable ignored) {
        }

        // 2) Don tien trinh nen bang API thuong
        Set<String> keep = protectedSet(game);
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        int killed = 0;
        try {
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                if (!keep.contains(ai.packageName)) {
                    am.killBackgroundProcesses(ai.packageName);
                    killed++;
                }
            }
        } catch (Throwable ignored) {
        }
        log("✓ Đã dọn tiến trình nền của " + killed + " gói");
        long after = availMb();
        log(String.format(Locale.US, "RAM trống: %d MB → %d MB (%+d MB)", before, after, after - before));

        // 3) Buoc dung + dong app gan day qua dich vu Tro nang
        if (svc != null && (wantStop || wantClose)) {
            List<String> targets = new ArrayList<String>();
            if (wantStop) {
                if (usageGranted()) {
                    targets = collectTargets(game);
                    log("• Sẽ buộc dừng " + targets.size() + " app vừa dùng");
                } else {
                    log("• Bỏ qua buộc dừng (chưa cấp quyền Dữ liệu sử dụng)");
                }
            }
            if (game == null) {
                log("✗ Chưa cài Đấu Trường Chân Lý – tối ưu xong sẽ không mở game.");
            }
            svc.start(targets, labelsOf(targets), game, wantClose);
            return;
        }

        if (game == null) {
            Session.end(this, "không có game để mở");
            log("✗ Chưa cài Đấu Trường Chân Lý nên không mở game được.");
            toast("Chưa tìm thấy Đấu Trường Chân Lý trên máy.");
            return;
        }
        log("▶ Mở game…");
        launchPackage(game, "Không mở được game.");
    }

    // ------------------------------------------------------------------ KIEM TRA MAY

    /**
     * Do man hinh co len 120Hz duoc khong: xin che do tan so cao nhat cho cua so cua app,
     * cho 700ms roi doc tan so thuc. Neu van 60Hz -> may dang de Do muot chuyen dong = Tieu chuan.
     */
    private void checkup() {
        float max = 60f;
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Display.Mode best = null;
            for (Display.Mode m : d.getSupportedModes()) {
                if (best == null || m.getRefreshRate() > best.getRefreshRate()) {
                    best = m;
                }
            }
            if (best != null) {
                max = best.getRefreshRate();
                // Chi xin tan so (khong doi do phan giai)
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredRefreshRate = max;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) {
        }
        final float maxHz = max;
        ui.postDelayed(() -> {
            float now = 0f;
            try {
                now = getWindowManager().getDefaultDisplay().getRefreshRate();
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredRefreshRate = 0f;
                getWindow().setAttributes(lp);
            } catch (Throwable ignored) {
            }
            showCheckup(maxHz, now);
        }, 700);
    }

    private void showCheckup(float maxHz, float hzNow) {
        StringBuilder ok = new StringBuilder();
        StringBuilder bad = new StringBuilder();

        // 1) Tan so quet
        if (hzNow >= 100f) {
            ok.append("✓ Màn hình lên được ").append(Math.round(hzNow)).append("Hz\n");
        } else {
            bad.append("✗ Màn hình đang khóa ").append(Math.round(hzNow)).append("Hz (tối đa ")
                    .append(Math.round(maxHz)).append("Hz). Cài đặt → Màn hình → Độ mượt chuyển động → Thích ứng.\n");
        }

        // 2) Tiet kiem pin
        PowerManager pwr = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pwr.isPowerSaveMode()) {
            bad.append("✗ Tiết kiệm pin đang bật – CPU/GPU bị giới hạn. Tắt trước khi chơi.\n");
        } else {
            ok.append("✓ Tiết kiệm pin đang tắt\n");
        }

        // 3) Nhiet do + sac
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            float temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            boolean charging = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (temp >= 40f) {
                bad.append(String.format(Locale.US, "✗ Pin đang %.1f°C – máy nóng sẵn sẽ bị hạ FPS sớm. Để nguội rồi chơi.\n", temp));
            } else {
                ok.append(String.format(Locale.US, "✓ Nhiệt độ pin %.1f°C\n", temp));
            }
            if (charging) {
                bad.append("✗ Đang cắm sạc – vừa sạc vừa chơi rất nóng. Bật Game Booster → Tạm dừng sạc USB khi chơi.\n");
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            int ts = pwr.getCurrentThermalStatus();
            if (ts >= PowerManager.THERMAL_STATUS_MODERATE) {
                bad.append("✗ Hệ thống báo máy đang nóng (mức ").append(ts).append("/6) – chip đang tự hạ xung.\n");
            }
        }

        // 4) Bo nho trong
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            double freeGb = st.getAvailableBytes() / 1e9;
            double pct = 100.0 * st.getAvailableBytes() / st.getTotalBytes();
            if (pct < 10) {
                bad.append(String.format(Locale.US, "✗ Bộ nhớ trong chỉ còn %.1f GB (%.0f%%) – máy chậm, game giật. Nên dọn trên 15%%.\n", freeGb, pct));
            } else {
                ok.append(String.format(Locale.US, "✓ Bộ nhớ trong trống %.0f GB (%.0f%%)\n", freeGb, pct));
            }
        } catch (Throwable ignored) {
        }

        // 5) RAM
        long avail = availMb();
        if (avail < 2500) {
            bad.append("✗ RAM trống chỉ ").append(avail).append(" MB – bấm Tối ưu để dọn app nền.\n");
        } else {
            ok.append("✓ RAM trống ").append(avail).append(" MB\n");
        }

        // 6) Mang
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) {
                bad.append("✗ Không có kết nối mạng\n");
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                int rssi = wi.getRssi();
                int freq = wi.getFrequency();
                String band = freq >= 5900 ? "6GHz" : freq >= 4900 ? "5GHz" : "2.4GHz";
                String line = "Wi-Fi " + band + ", sóng " + rssi + " dBm";
                if (freq < 4900) {
                    bad.append("✗ ").append(line).append(" – băng 2.4GHz dễ nhiễu, ping cao. Nên chọn Wi-Fi 5GHz.\n");
                } else if (rssi < -70) {
                    bad.append("✗ ").append(line).append(" – sóng yếu, dễ lag. Lại gần router.\n");
                } else {
                    ok.append("✓ ").append(line).append("\n");
                }
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                ok.append("• Đang dùng 4G/5G – ổn nếu sóng mạnh; Wi-Fi 5GHz thường ping ổn định hơn\n");
            }
        } catch (Throwable ignored) {
        }

        // 7) Quyen cua app
        if (!a11yOn()) {
            bad.append("• Trợ năng đang tắt – sẽ được bật khi bấm Tối ưu\n");
        }
        if (!usageGranted()) {
            bad.append("✗ Chưa cấp Dữ liệu sử dụng – không buộc dừng được app nền (bước 3)\n");
        }
        if (!dndGranted()) {
            bad.append("✗ Chưa cấp Không làm phiền (bước 4)\n");
        }

        String samsung = "\nNên chỉnh 1 lần trong Gaming Hub → ⋮ → Game Booster:\n"
                + "• Tối ưu hóa game → Hiệu năng\n"
                + "• Tùy chỉnh cho từng game → Đấu Trường Chân Lý → FPS cao nhất\n"
                + "• Tạm dừng sạc USB khi chơi (nếu hay cắm sạc)\n"
                + "Trong game: Cài đặt → Đồ họa → chọn khung hình cao nhất.";

        String msg = (bad.length() == 0 ? "Không phát hiện vấn đề nào 👍\n\n" : bad + "\n") + ok + samsung;
        new AlertDialog.Builder(this)
                .setTitle(bad.length() == 0 ? "Máy đã sẵn sàng" : "Những thứ đang làm chậm game")
                .setMessage(msg)
                .setPositiveButton("Đóng", null)
                .setNeutralButton("Mở Game Booster", (d, w) ->
                        launchPackage(GAMING_HUB_PKG, "Không tìm thấy Gaming Hub trên máy."))
                .show();
        log("Kiểm tra máy: " + (bad.length() == 0 ? "không có vấn đề" : "có mục cần chỉnh – xem hộp thoại"));
    }

    private void restore() {
        if (Session.active(this)) {
            Session.end(this, "bấm Khôi phục");
            refreshStats();
            return;
        }
        BoostService svc = BoostService.instance;
        if (svc != null && Session.autoOff(this)) {
            svc.disableSelf();
            BoostService.instance = null;
            log("✓ Đã tắt Trợ năng");
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            log("✓ Đã tắt Không làm phiền");
        } else {
            log("• Chưa cấp quyền Không làm phiền nên không có gì để khôi phục");
        }
    }

    // ------------------------------------------------------------------ DANH SACH GIU LAI

    private void pickKeepApps() {
        final PackageManager pm = getPackageManager();
        final List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean eligible = !system || HEAVY_SYSTEM.contains(ai.packageName);
                if (eligible && !ai.packageName.equals(getPackageName())
                        && !ai.packageName.startsWith(GAME_PREFIX)
                        && pm.getLaunchIntentForPackage(ai.packageName) != null) {
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
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Lưu", (dialog, which) -> {
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
