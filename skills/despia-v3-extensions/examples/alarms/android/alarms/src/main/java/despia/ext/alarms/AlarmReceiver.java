package despia.ext.alarms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction(), id = intent.getStringExtra("alarm_id");
        try {
            if (AlarmStore.FIRE.equals(action)) {
                AlarmStore.fire(context, id, intent.getStringExtra("native_key"));
                Intent ring = new Intent(context, AlarmAudioService.class).putExtra("alarm_id", id);
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(ring); else context.startService(ring);
            } else if (AlarmStore.STOP.equals(action)) AlarmStore.stop(context, id, true);
            else if (AlarmStore.SNOOZE.equals(action)) {
                JSONObject alarm = AlarmStore.get(context, id);
                AlarmStore.snooze(context, id, alarm.optDouble("snooze_seconds", 300));
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
                AlarmStore.restore(context, Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action));
            }
        } catch (Exception ex) {
            android.util.Log.e("DespiaAlarms", "Alarm receiver action failed: " + action, ex);
            try { AlarmStore.event(context, "error", id == null ? "" : id, new JSONObject().put("message", ex.getMessage())); }
            catch (Exception storageError) { android.util.Log.e("DespiaAlarms", "Cannot retain alarm error", storageError); }
        }
    }
}
