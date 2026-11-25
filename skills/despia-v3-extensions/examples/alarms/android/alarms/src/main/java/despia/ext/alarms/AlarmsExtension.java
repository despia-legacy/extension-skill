package despia.ext.alarms;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.despia.nativeext.ExtCall;
import com.despia.nativeext.ExtContext;
import com.despia.nativeext.ExtResult;
import com.despia.nativeext.NativeExtension;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AlarmsExtension implements NativeExtension {
    private ExtContext context;
    private ComponentActivity activity;
    private ActivityResultLauncher<String> notifications;
    private boolean isRequesting;
    @Override public void onCreate(ExtContext context) { this.context = context; AlarmStore.attach(context); }
    @Override public void onActivityEvent(Activity host, ActivityEvent event) {
        if (event == ActivityEvent.CREATED && host instanceof ComponentActivity) {
            activity = (ComponentActivity) host;
            retainOpen(host.getIntent());
            notifications = activity.getActivityResultRegistry().register(context.resultKey("notifications"), activity,
                new ActivityResultContracts.RequestPermission(), granted -> {
                    isRequesting = false;
                    try { context.emit("permission", permissionState()); }
                    catch (Exception ex) { context.log("Alarm permission result failed: " + ex.getMessage()); }
                });
        } else if (event == ActivityEvent.RESUMED) {
            retainOpen(host.getIntent());
            try { context.emit("permission", permissionState()); }
            catch (Exception ex) { context.log("Alarm permission status failed: " + ex.getMessage()); }
        } else if (event == ActivityEvent.DESTROYED && host == activity) {
            if (host.isFinishing()) isRequesting = false;
            activity = null; notifications = null;
        }
    }
    private void retainOpen(Intent intent) {
        if (intent == null || !intent.hasExtra("DESPIA_NX_ALARM_ID")) return;
        String id = intent.getStringExtra("DESPIA_NX_ALARM_ID");
        try {
            AlarmStore.opened(context.app(), id, intent.getStringExtra("DESPIA_NX_ALARM_OPEN_TOKEN"));
            intent.removeExtra("DESPIA_NX_ALARM_ID"); intent.removeExtra("DESPIA_NX_ALARM_OPEN_TOKEN");
        } catch (Exception ex) { context.log("Cannot retain opened alarm: " + ex.getMessage()); }
    }
    private JSONObject permissionState() throws Exception {
        NotificationManager center = context.app().getSystemService(NotificationManager.class);
        return new JSONObject().put("notifications", Build.VERSION.SDK_INT < 33 || context.app().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            .put("notifications_enabled", center.areNotificationsEnabled())
            .put("exact_alarms", Build.VERSION.SDK_INT < 31 || context.app().getSystemService(AlarmManager.class).canScheduleExactAlarms())
            .put("full_screen", Build.VERSION.SDK_INT < 34 || center.canUseFullScreenIntent());
    }
    private String id(ExtCall call) { return (String) call.params.get("id"); }
    @Override public void handle(ExtCall call, ExtResult.Callback reply) {
        try {
            JSONObject answer;
            switch (call.route) {
                case "capabilities": answer = new JSONObject().put("platform", "android").put("provider", "alarmmanager")
                    .put("system_alarms", true).put("countdown", true).put("weekly", true).put("pause", true).put("resume", true)
                    .put("challenge", "app_reported_progress").put("physical_exercise_detection", false).put("notification_fallback", false)
                    .put("timezone", true).put("custom_sound", "accessible_content_or_file_uri"); break;
                case "permissions":
                    answer = permissionState();
                    if (Boolean.TRUE.equals(call.params.get("request")) && Build.VERSION.SDK_INT >= 33 && !answer.getBoolean("notifications")) {
                        if (notifications == null) { reply.reply(ExtResult.error("no_activity", "Open a host app page before requesting notifications")); return; }
                        if (isRequesting) { reply.reply(ExtResult.error("busy", "A notification permission request is pending")); return; }
                        isRequesting = true;
                        try { notifications.launch(Manifest.permission.POST_NOTIFICATIONS); }
                        catch (RuntimeException ex) { isRequesting = false; throw ex; }
                        answer.put("pending", true);
                    }
                    break;
                case "android_access":
                    if (activity == null) { reply.reply(ExtResult.error("no_activity", "A foreground host Activity is required")); return; }
                    String access = (String) call.params.get("access");
                    Intent settings;
                    if ("exact_alarms".equals(access) && Build.VERSION.SDK_INT >= 31) settings = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    else if ("full_screen".equals(access) && Build.VERSION.SDK_INT >= 34) settings = new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                    else if ("notifications".equals(access)) settings = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.app().getPackageName());
                    else { answer = permissionState(); break; }
                    if (!"notifications".equals(access)) settings.setData(android.net.Uri.parse("package:" + context.app().getPackageName()));
                    activity.startActivity(settings); answer = permissionState().put("opened", true); break;
                case "save":
                    JSONObject candidate = new JSONObject((String) call.params.get("alarm"));
                    if (candidate.has("delivery") && !"system".equals(candidate.getString("delivery"))) { reply.reply(ExtResult.error("unsupported", "Android uses system alarms")); return; }
                    answer = new JSONObject().put("alarm", AlarmStore.save(context.app(), candidate.toString())); break;
                case "get": answer = new JSONObject().put("alarm", AlarmStore.get(context.app(), id(call))); break;
                case "list": answer = new JSONObject().put("alarms", AlarmStore.list(context.app(), (String) call.params.get("group"))); break;
                case "cancel": answer = new JSONObject().put("alarm", AlarmStore.cancel(context.app(), id(call))); break;
                case "stop": answer = new JSONObject().put("alarm", AlarmStore.stop(context.app(), id(call), Boolean.TRUE.equals(call.params.get("emergency")))); break;
                case "snooze":
                    JSONObject alarm = AlarmStore.get(context.app(), id(call));
                    double seconds = call.params.containsKey("seconds") ? ((Number) call.params.get("seconds")).doubleValue() : alarm.optDouble("snooze_seconds", 300);
                    answer = new JSONObject().put("alarm", AlarmStore.snooze(context.app(), id(call), seconds)); break;
                case "pause": answer = new JSONObject().put("alarm", AlarmStore.pause(context.app(), id(call))); break;
                case "resume": answer = new JSONObject().put("alarm", AlarmStore.resume(context.app(), id(call))); break;
                case "challenge": answer = new JSONObject().put("alarm", AlarmStore.challenge(context.app(), id(call), AlarmValues.progressText((String) call.params.get("progress")))); break;
                case "events": answer = new JSONObject().put("events", AlarmStore.events(context.app())); break;
                case "events_ack": answer = new JSONObject().put("acknowledged", AlarmStore.acknowledge(context.app(), new JSONArray((String) call.params.get("ids")))); break;
                default: reply.reply(ExtResult.error("unknown_route", "Unknown alarm route")); return;
            }
            reply.reply(ExtResult.ok(answer));
        } catch (SecurityException ex) { reply.reply(ExtResult.error("permission_required", ex.getMessage())); }
        catch (IllegalStateException ex) { reply.reply(ExtResult.error("challenge_incomplete".equals(ex.getMessage()) ? "challenge_incomplete" : "invalid_state", ex.getMessage())); }
        catch (Exception ex) { reply.reply(ExtResult.error("alarm_error", ex.getMessage())); }
    }
}
