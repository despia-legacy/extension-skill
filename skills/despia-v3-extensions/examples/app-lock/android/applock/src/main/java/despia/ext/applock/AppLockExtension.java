package despia.ext.applock;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.despia.nativeext.*;
import org.json.*;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.time.*;
import java.util.*;

public final class AppLockExtension implements NativeExtension {
    private static WeakReference<AppLockExtension> current = new WeakReference<>(null);
    private ExtContext context;
    private ComponentActivity activity;
    private ActivityResultLauncher<Intent> vpnConsent;
    private boolean selectionPending;
    private final VpnConsent vpnRequests = new VpnConsent();
    private AlertDialog vpnDisclosure, selectionDialog;
    @Override public void onCreate(ExtContext context) { this.context = context; current = new WeakReference<>(this); }
    @Override public void onActivityEvent(Activity host, ActivityEvent event) {
        if (!(host instanceof ComponentActivity)) return;
        ComponentActivity component = (ComponentActivity)host;
        if (event == ActivityEvent.CREATED) {
            activity = component;
            if (vpnConsent != null) vpnConsent.unregister();
            vpnConsent = component.getActivityResultRegistry().register(context.resultKey("vpn"), new ActivityResultContracts.StartActivityForResult(), reply -> {
                if (!vpnRequests.returned()) return;
                try {
                    if (reply.getResultCode() == Activity.RESULT_OK && VpnService.prepare(context.app()) == null) startVpn();
                    else emitCurrent("authorization", new JSONObject().put("kind", "vpn").put("granted", false));
                } catch (Exception failure) { context.log("App Lock VPN start failed"); emitError("authorization", "vpn_start_failed"); }
            });
        } else if (event == ActivityEvent.RESUMED) {
            LockAccessibilityService.hostResumed();
            try { emitCurrent("authorization", authorization()); } catch (JSONException failure) { context.log("App Lock authorization state could not be encoded"); }
        } else if (event == ActivityEvent.DESTROYED && host == activity) {
            if (vpnConsent != null) vpnConsent.unregister(); vpnConsent = null; activity = null;
            if (vpnDisclosure != null) { vpnDisclosure.dismiss(); vpnDisclosure = null; }
            if (selectionDialog != null) { selectionDialog.dismiss(); selectionDialog = null; }
            selectionPending = false;
            if (host.isFinishing()) vpnRequests.hostFinished(); else vpnRequests.hostDestroyed();
        }
    }
    @Override public void handle(ExtCall call, ExtResult.Callback reply) {
        try {
            LockStore store = new LockStore(context.app());
            switch (call.route) {
                case "capabilities": reply.reply(ExtResult.ok(new JSONObject().put("provider", "accessibility_and_dns").put("apps", true).put("categories", Build.VERSION.SDK_INT >= 26).put("domains", true).put("native_picker", true).put("schedules", true).put("usage_thresholds", false).put("package_suspension", false).put("foreground_shield_only", true).put("udp_dns_only", true).put("doh_blocking", false).put("https_path_blocking", false).put("revocable", true))); return;
                case "authorization":
                    if ("child".equals(call.params.get("member"))) { reply.reply(ExtResult.error("platform_limit", "Family Controls child authorization is an iOS operation")); return; }
                    if (Boolean.TRUE.equals(call.params.get("revoke"))) { releaseAll(context.app()); reply.reply(ExtResult.ok(authorization().put("settings_required_to_disable_accessibility", true))); return; }
                    if (Boolean.TRUE.equals(call.params.get("request"))) showAccessibilityDisclosure();
                    reply.reply(ExtResult.ok(authorization())); return;
                case "android_accessibility": if (Boolean.TRUE.equals(call.params.get("request"))) showAccessibilityDisclosure(); reply.reply(ExtResult.ok(authorization())); return;
                case "android_vpn":
                    String action = (String)call.params.getOrDefault("action", "status");
                    if ("stop".equals(action)) { cancelVpnRequest(); LockDnsService.stop(context.app()); }
                    else if ("start".equals(action)) { configureVpn(store, call.params.get("data")); requestVpn(); }
                    else if (!"status".equals(action)) throw new IllegalArgumentException("action must be status, start or stop");
                    reply.reply(ExtResult.ok(LockDnsService.status().put("pending", vpnRequests.isPending()).put("config", store.read().optJSONObject("android_vpn")))); return;
                case "groups": reply.reply(ExtResult.ok(new JSONObject().put("groups", store.read().getJSONArray("groups")))); return;
                case "group_get": reply.reply(ExtResult.ok(new JSONObject().put("group", LockState.group(store.read(), id(call))))); return;
                case "group_save": {
                    JSONObject input = object(call.params.get("data")); String id = input.optString("id", UUID.randomUUID().toString()); input.put("id", id);
                    JSONObject saved = store.change(state -> {
                        JSONObject previous; try { previous = LockState.group(state, id); } catch (NoSuchElementException missing) { previous = null; }
                        JSONObject group = LockState.normalizeGroup(input, previous); LockState.replace(state.getJSONArray("groups"), group); LockState.record(state, "group_saved", id, null); return group;
                    });
                    changed("group_saved"); reply.reply(ExtResult.ok(new JSONObject().put("group", saved))); return;
                }
                case "group_delete": {
                    String id = id(call); store.change(state -> {
                        if (!LockState.remove(state.getJSONArray("groups"), id)) throw new NoSuchElementException("Group not found");
                        JSONArray schedules = state.getJSONArray("schedules"); for (int index = schedules.length() - 1; index >= 0; index--) if (id.equals(schedules.getJSONObject(index).getString("group_id"))) schedules.remove(index);
                        LockState.record(state, "group_deleted", id, null); return null;
                    }); changed("group_deleted"); reply.reply(ExtResult.ok(new JSONObject().put("id", id).put("deleted", true))); return;
                }
                case "select": select(id(call), store); reply.reply(ExtResult.ok(new JSONObject().put("pending", true).put("group_id", id(call)))); return;
                case "apply": case "release": {
                    String id = id(call); boolean enabled = "apply".equals(call.route);
                    JSONObject group = LockState.group(store.read(), id);
                    if (enabled) requireCapabilities(group);
                    store.change(state -> { LockState.group(state, id).put("manual_enabled", enabled); LockState.record(state, enabled ? "applied" : "released", id, null); return null; });
                    boolean active = LockState.activeGroups(store.read(), Instant.now()).stream().anyMatch(entry -> id.equals(entry.optString("id")));
                    changed(enabled ? "applied" : "released"); reply.reply(ExtResult.ok(new JSONObject().put("group_id", id).put("manual_enabled", enabled).put("active", active))); return;
                }
                case "release_all": releaseAll(context.app()); reply.reply(ExtResult.ok(new JSONObject().put("released", true))); return;
                case "schedules": reply.reply(ExtResult.ok(new JSONObject().put("schedules", store.read().getJSONArray("schedules")))); return;
                case "schedule_save": {
                    JSONObject schedule = object(call.params.get("data"));
                    if (schedule.has("threshold_minutes") && !schedule.isNull("threshold_minutes")) { reply.reply(ExtResult.error("platform_limit", "Android does not expose iOS Screen Time usage threshold monitoring through this sample")); return; }
                    schedule.put("id", schedule.optString("id", UUID.randomUUID().toString())); LockRules.identifier(schedule.getString("id"));
                    if (!schedule.has("enabled")) schedule.put("enabled", true);
                    if (!schedule.has("weekdays")) schedule.put("weekdays", new JSONArray(Arrays.asList(1, 2, 3, 4, 5, 6, 7)));
                    if (!schedule.has("time_zone")) schedule.put("time_zone", ZoneId.systemDefault().getId());
                    LockState.active(schedule, Instant.now());
                    JSONObject group = LockState.group(store.read(), schedule.getString("group_id"));
                    if (schedule.getBoolean("enabled")) requireCapabilities(group);
                    store.change(state -> { LockState.replace(state.getJSONArray("schedules"), schedule); LockState.record(state, "schedule_saved", group.getString("id"), schedule.getString("id")); return null; });
                    changed("schedule_saved"); reply.reply(ExtResult.ok(new JSONObject().put("schedule", schedule))); return;
                }
                case "schedule_delete": {
                    String id = id(call); store.change(state -> { if (!LockState.remove(state.getJSONArray("schedules"), id)) throw new NoSuchElementException("Schedule not found"); LockState.record(state, "schedule_deleted", null, id); return null; }); changed("schedule_deleted"); reply.reply(ExtResult.ok(new JSONObject().put("id", id).put("deleted", true))); return;
                }
                case "events": {
                    JSONArray events = store.change(state -> { JSONArray eventsInStore = state.getJSONArray("events"); if (Boolean.TRUE.equals(call.params.get("clear"))) state.put("events", new JSONArray()); return eventsInStore; }); reply.reply(ExtResult.ok(new JSONObject().put("events", events))); return;
                }
                case "android_apps": reply.reply(ExtResult.ok(new JSONObject().put("apps", new JSONArray(apps())).put("visible_launcher_apps_only", true))); return;
                default: reply.reply(ExtResult.error("not_registered", call.route));
            }
        } catch (NoSuchElementException missing) { reply.reply(ExtResult.error("not_found", missing.getMessage())); }
        catch (IllegalArgumentException | JSONException malformed) { reply.reply(ExtResult.error("bad_request", malformed.getMessage())); }
        catch (SecurityException missingPermission) { reply.reply(ExtResult.error("permission_required", missingPermission.getMessage())); }
        catch (Exception failure) { context.log("App Lock operation failed: " + failure.getClass().getSimpleName()); reply.reply(ExtResult.error("app_lock_error", "The native operation could not finish")); }
    }
    private JSONObject authorization() throws JSONException { return new JSONObject().put("status", accessibilityEnabled() ? "approved" : "not_granted").put("accessibility", accessibilityEnabled()).put("vpn_consent", VpnService.prepare(context.app()) == null).put("vpn_running", LockDnsService.isRunning()).put("pending", vpnRequests.isPending()); }
    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(context.app().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false; String expected = new ComponentName(context.app(), LockAccessibilityService.class).flattenToString();
        for (String entry : enabled.split(":")) { ComponentName service = ComponentName.unflattenFromString(entry); if (service != null && expected.equals(service.flattenToString())) return true; } return false;
    }
    private void requireCapabilities(JSONObject group) throws JSONException {
        JSONObject android = group.optJSONObject("android");
        if (android != null && (android.optJSONArray("packages").length() > 0 || android.optJSONArray("categories").length() > 0) && !accessibilityEnabled()) throw new SecurityException("Enable App Lock through the Accessibility system screen first");
        if (group.optJSONArray("domains").length() > 0 && !LockDnsService.isRunning()) throw new SecurityException("Start the consented DNS VPN first");
    }
    private void showAccessibilityDisclosure() {
        if (activity == null) throw new IllegalStateException("No foreground Activity");
        new AlertDialog.Builder(activity).setTitle("Enable App Lock?").setMessage("App Lock reads foreground app package names and displays a blocking screen for your chosen groups. It does not read screen text. You can stop blocking or disable this service in Accessibility settings at any time. This is a self-control tool, not an accessibility tool or tamper-proof parental control.").setNegativeButton("Cancel", null).setPositiveButton("Open Accessibility settings", (dialog, which) -> activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();
    }
    private void requestVpn() throws Exception {
        if (activity == null || vpnConsent == null) throw new IllegalStateException("No foreground Activity");
        JSONObject config = new LockStore(context.app()).read().optJSONObject("android_vpn"); String resolver = config == null ? "dns.google" : config.optString("upstream_tls_name", "dns.google");
        final long generation = vpnRequests.begin();
        vpnDisclosure = new AlertDialog.Builder(activity).setTitle("Start domain filtering?").setMessage("This uses Android's VPN slot and can replace your existing VPN. DNS queries sent through the local UDP DNS proxy are checked against active groups. Allowed queries go over TLS to " + resolver + ". App traffic is not tunneled. Encrypted DNS, cached addresses, IP addresses and other DNS transports can bypass this filter. You can stop it in the notification or Android VPN settings.")
            .setNegativeButton("Cancel", (dialog, which) -> vpnRequests.cancel()).setOnCancelListener(dialog -> vpnRequests.cancel()).setPositiveButton("Continue", (dialog, which) -> {
                if (!vpnRequests.isCurrent(generation)) return;
                try { Intent consent = VpnService.prepare(context.app()); if (consent == null) { if (vpnRequests.grantedWithoutPrompt(generation)) startVpn(); } else { vpnRequests.launched(generation); vpnConsent.launch(consent); } }
                catch (Exception failure) { vpnRequests.launchFailed(generation); emitError("authorization", "vpn_start_failed"); }
            }).create();
        try { vpnDisclosure.show(); }
        catch (RuntimeException failure) { vpnRequests.launchFailed(generation); throw failure; }
    }
    private void cancelVpnRequest() {
        vpnRequests.cancel();
        if (vpnDisclosure != null) { vpnDisclosure.dismiss(); vpnDisclosure = null; }
    }
    static void cancelPendingVpn() {
        AppLockExtension extension = current.get();
        if (extension != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) extension.cancelVpnRequest();
            else new Handler(Looper.getMainLooper()).post(extension::cancelVpnRequest);
        }
    }
    private void startVpn() { ContextCompat.startForegroundService(context.app(), new Intent(context.app(), LockDnsService.class).putExtra("DESPIA_NX_VPN_GENERATION", vpnRequests.generation())); }
    static boolean isCurrentVpnStart(long generation) { AppLockExtension extension = current.get(); return extension != null && extension.vpnRequests.isCurrent(generation); }
    private void configureVpn(LockStore store, Object encoded) throws Exception {
        if (encoded == null) return; JSONObject config = object(encoded);
        String ip = config.optString("upstream_ip", "8.8.8.8"), hostname = LockRules.domain(config.optString("upstream_tls_name", "dns.google")); int port = config.optInt("upstream_port", 853);
        if (!ip.matches("[0-9.]+") || InetAddress.getByName(ip).getAddress().length != 4 || port < 1 || port > 65535) throw new IllegalArgumentException("Use a numeric IPv4 upstream_ip, TLS hostname and valid port");
        if (LockDnsService.isRunning()) throw new IllegalStateException("Stop the DNS service before changing its upstream");
        JSONObject normalized = new JSONObject().put("upstream_ip", ip).put("upstream_tls_name", hostname).put("upstream_port", port);
        store.change(state -> { state.put("android_vpn", normalized); return null; });
    }
    private List<JSONObject> apps() throws JSONException {
        List<ResolveInfo> launchers = context.app().getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        List<JSONObject> apps = new ArrayList<>(); Set<String> included = new HashSet<>();
        for (ResolveInfo launcher : launchers) {
            String name = launcher.activityInfo.packageName; if (!included.add(name) || name.equals(context.app().getPackageName())) continue;
            JSONObject app = new JSONObject().put("package", name).put("label", launcher.loadLabel(context.app().getPackageManager()).toString());
            if (Build.VERSION.SDK_INT >= 26) app.put("category", launcher.activityInfo.applicationInfo.category);
            apps.add(app);
        }
        apps.sort(Comparator.comparing(app -> app.optString("label"))); return apps;
    }
    private void select(String id, LockStore store) throws Exception {
        if (activity == null) throw new IllegalStateException("No foreground Activity"); if (selectionPending) throw new IllegalStateException("The app picker is open");
        JSONObject group = LockState.group(store.read(), id); List<JSONObject> apps = apps(); boolean[] selected = new boolean[apps.size()]; String[] labels = new String[apps.size()];
        Set<String> prior = new HashSet<>(); JSONArray packages = group.getJSONObject("android").getJSONArray("packages"); for (int index = 0; index < packages.length(); index++) prior.add(packages.getString(index));
        for (int index = 0; index < apps.size(); index++) { selected[index] = prior.contains(apps.get(index).getString("package")); labels[index] = apps.get(index).getString("label") + "\n" + apps.get(index).getString("package"); }
        selectionPending = true;
        AlertDialog picker = new AlertDialog.Builder(activity).setTitle(group.getString("name")).setMultiChoiceItems(labels, selected, (dialog, index, checked) -> selected[index] = checked).setNegativeButton("Cancel", (dialog, which) -> emitSelection(id, true)).setPositiveButton("Save", (dialog, which) -> {
            try {
                JSONArray choices = new JSONArray(); Set<String> visible = new HashSet<>();
                for (int index = 0; index < apps.size(); index++) { visible.add(apps.get(index).getString("package")); if (selected[index]) choices.put(apps.get(index).getString("package")); }
                for (String invisible : prior) if (!visible.contains(invisible)) choices.put(invisible);
                store.change(state -> { LockState.group(state, id).getJSONObject("android").put("packages", choices); LockState.record(state, "selected", id, null); return null; }); changed("selected"); emitSelection(id, false);
            } catch (Exception failure) { emitError("selection", "selection_save_failed"); }
        }).create();
        picker.setOnCancelListener(dialog -> emitSelection(id, true)); picker.setOnDismissListener(dialog -> { if (selectionDialog == picker) { selectionPending = false; selectionDialog = null; } }); selectionDialog = picker;
        try { picker.show(); }
        catch (RuntimeException failure) { selectionDialog = null; selectionPending = false; throw failure; }
    }
    private void emitSelection(String id, boolean cancelled) { try { emitCurrent("selection", new JSONObject().put("group_id", id).put("cancelled", cancelled)); } catch (JSONException failure) { context.log("Selection response could not be encoded"); } }
    private void changed(String kind) throws JSONException { LockAccessibilityService.refreshCurrent(); emitCurrent("changed", new JSONObject().put("kind", kind)); }
    static void releaseAll(Context context) throws Exception {
        cancelPendingVpn();
        new LockStore(context).change(state -> {
            JSONArray groups = state.getJSONArray("groups"), schedules = state.getJSONArray("schedules");
            for (int index = 0; index < groups.length(); index++) groups.getJSONObject(index).put("manual_enabled", false);
            for (int index = 0; index < schedules.length(); index++) schedules.getJSONObject(index).put("enabled", false);
            LockState.record(state, "released_all", null, null); return null;
        });
        LockDnsService.stop(context); LockAccessibilityService.refreshCurrent();
        emitCurrent("changed", new JSONObject().put("kind", "released_all"));
    }
    static void emitCurrent(String event, JSONObject payload) { AppLockExtension extension = current.get(); if (extension != null) extension.context.emit(event, payload); }
    private void emitError(String event, String code) { try { emitCurrent(event, new JSONObject().put("error", code)); } catch (JSONException failure) { context.log("App Lock event could not be encoded"); } }
    private static String id(ExtCall call) { Object id = call.params.get("id"); if (!(id instanceof String) || ((String)id).isEmpty()) throw new IllegalArgumentException("id is required"); return (String)id; }
    private static JSONObject object(Object encoded) throws JSONException { if (!(encoded instanceof String)) throw new IllegalArgumentException("data must encode a JSON object"); return new JSONObject((String)encoded); }
}
