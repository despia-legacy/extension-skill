package despia.ext.applock;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import org.json.*;
import java.time.Instant;
import java.util.*;

public final class LockAccessibilityService extends AccessibilityService {
    private static volatile LockAccessibilityService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> protectedPackages = new HashSet<>();
    private WindowManager windows;
    private View overlay;
    private String foregroundPackage, shieldGroup;
    private final Runnable ticker = new Runnable() { @Override public void run() { refresh(); main.postDelayed(this, 60000 - System.currentTimeMillis() % 60000); } };
    @Override protected void onServiceConnected() {
        super.onServiceConnected(); active = this; windows = getSystemService(WindowManager.class);
        protectedPackages.addAll(Arrays.asList(getPackageName(), "android", "com.android.systemui", "com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller"));
        refreshProtectedPackages();
        main.post(ticker);
    }
    private void refreshProtectedPackages() {
        addResolved(new Intent(Settings.ACTION_SETTINGS));
        addResolved(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        addResolved(new Intent(Intent.ACTION_DIAL));
        TelecomManager telecom = getSystemService(TelecomManager.class);
        if (telecom != null) {
            String dialer = telecom.getDefaultDialerPackage(); if (dialer != null) protectedPackages.add(dialer);
            if (Build.VERSION.SDK_INT >= 29) { String systemDialer = telecom.getSystemDialerPackage(); if (systemDialer != null) protectedPackages.add(systemDialer); }
        }
    }
    private void addResolved(Intent intent) {
        ResolveInfo resolved = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved != null && resolved.activityInfo != null) protectedPackages.add(resolved.activityInfo.packageName);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        // Overlay window events use this package too; the host lifecycle clears it on a real resume.
        if (packageName.equals(getPackageName())) return;
        refreshProtectedPackages(); foregroundPackage = packageName; refresh();
    }
    static void hostResumed() { LockAccessibilityService service = active; if (service != null) service.main.post(() -> { service.foregroundPackage = service.getPackageName(); service.removeOverlay(); }); }
    static void refreshCurrent() { LockAccessibilityService service = active; if (service != null) service.main.post(service::refresh); }
    private void refresh() {
        if (foregroundPackage == null || !mayShield(foregroundPackage)) { removeOverlay(); return; }
        try {
            JSONObject match = null;
            for (JSONObject group : LockState.activeGroups(new LockStore(this).read(), Instant.now())) {
                JSONObject android = group.optJSONObject("android"); if (android == null) continue;
                JSONArray packages = android.optJSONArray("packages"), categories = android.optJSONArray("categories");
                boolean selected = false;
                if (packages != null) for (int index = 0; index < packages.length(); index++) if (foregroundPackage.equals(packages.getString(index))) selected = true;
                if (!selected && Build.VERSION.SDK_INT >= 26 && categories != null) {
                    try {
                        ApplicationInfo application = getPackageManager().getApplicationInfo(foregroundPackage, 0);
                        for (int index = 0; index < categories.length(); index++) if (application.category == categories.getInt(index)) selected = true;
                    } catch (PackageManager.NameNotFoundException invisible) { /* Apps outside package visibility cannot be categorized. */ }
                }
                if (selected) { match = group; break; }
            }
            if (match == null) removeOverlay(); else if (!match.toString().equals(shieldGroup)) showOverlay(match);
        } catch (Exception failure) { removeOverlay(); android.util.Log.w("DespiaAppLock", "Foreground shield rules could not be read"); }
    }
    private boolean mayShield(String packageName) {
        if (protectedPackages.contains(packageName)) return false;
        PackageManager packages = getPackageManager();
        try { packages.getApplicationInfo(packageName, 0); }
        catch (PackageManager.NameNotFoundException invisible) { return false; }
        return packages.checkPermission("android.permission.GRANT_RUNTIME_PERMISSIONS", packageName) != PackageManager.PERMISSION_GRANTED
            && packages.checkPermission("android.permission.MANAGE_ROLE_HOLDERS", packageName) != PackageManager.PERMISSION_GRANTED;
    }
    private void showOverlay(JSONObject group) throws JSONException {
        removeOverlay(); JSONObject style = group.optJSONObject("shield"); if (style == null) style = new JSONObject();
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER); int padding = (int)(24 * getResources().getDisplayMetrics().density); panel.setPadding(padding, padding, padding, padding);
        panel.setBackgroundColor(color(style.optString("background_color"), Color.rgb(250, 250, 250)));
        TextView title = new TextView(this); title.setText(style.optString("title", group.getString("name"))); title.setTextSize(26); title.setGravity(Gravity.CENTER); title.setTextColor(color(style.optString("text_color"), Color.BLACK)); panel.addView(title);
        TextView message = new TextView(this); message.setText(style.optString("message", "This app is in an active focus group.")); message.setTextSize(17); message.setGravity(Gravity.CENTER); message.setTextColor(color(style.optString("text_color"), Color.BLACK)); panel.addView(message);
        Button home = new Button(this); home.setText(style.optString("button", "Go home")); home.setOnClickListener(view -> { removeOverlay(); performGlobalAction(GLOBAL_ACTION_HOME); }); panel.addView(home);
        Button open = new Button(this); open.setText(style.optString("secondary_button", "Open focus app")); open.setOnClickListener(view -> {
            removeOverlay(); foregroundPackage = getPackageName(); Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName()); if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }); panel.addView(open);
        Button escape = new Button(this); escape.setText("Stop all blocking"); escape.setOnClickListener(view -> {
            try { AppLockExtension.releaseAll(this); removeOverlay(); }
            catch (Exception failure) { Toast.makeText(this, "Could not save the change. You can disable App Lock in Accessibility settings.", Toast.LENGTH_LONG).show(); }
        }); panel.addView(escape);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.OPAQUE);
        windows.addView(panel, params); overlay = panel; shieldGroup = group.toString();
    }
    private static int color(String text, int fallback) { try { return Color.parseColor(text); } catch (IllegalArgumentException invalid) { return fallback; } }
    private void removeOverlay() { if (overlay != null) { windows.removeView(overlay); overlay = null; shieldGroup = null; } }
    @Override public void onInterrupt() { removeOverlay(); }
    @Override public void onDestroy() { main.removeCallbacks(ticker); removeOverlay(); if (active == this) active = null; super.onDestroy(); }
}
