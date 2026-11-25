package despia.ext.alarms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AlarmAudioService extends Service {
    private static final String CHANNEL = "DESPIA_NX_ALARMS_RING";
    private static AlarmAudioService active;
    private final RingSessions<MediaPlayer> players = new RingSessions<>();
    private final Map<String, Integer> notifications = new LinkedHashMap<>();
    private int nextNotification = 112100;
    private Vibrator vibrator;
    @Override public void onCreate() { super.onCreate(); active = this; vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE); }
    @Override public IBinder onBind(Intent intent) { return null; }
    static void stop(Context context, String id) {
        if (active != null) active.stopAlarm(id);
    }
    private Notification notification(JSONObject alarm, int notificationId) throws Exception {
        String id = alarm.getString("id");
        NotificationManager center = getSystemService(NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null); channel.enableVibration(false); center.createNotificationChannel(channel);
        }
        Intent screen = new Intent(this, AlarmScreen.class).putExtra("alarm_id", id)
            .setData(android.net.Uri.parse("despia-nx-alarm:" + id));
        PendingIntent display = PendingIntent.getActivity(this, 0, screen, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(alarm.optString("label", "Alarm"))
            .setContentText(alarm.optString("body", "Open the app to complete your challenge"))
            .setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX).setOngoing(true)
            .setContentIntent(display).setAutoCancel(false)
            .addAction(new Notification.Action.Builder(null, alarm.optString("emergency_label", "Emergency stop"), AlarmStore.action(this, id, AlarmStore.STOP)).build());
        if (alarm.optDouble("snooze_seconds", 300) > 0) builder.addAction(new Notification.Action.Builder(null,
            alarm.optString("snooze_label", "Snooze"), AlarmStore.action(this, id, AlarmStore.SNOOZE)).build());
        boolean canFullScreen = android.os.Build.VERSION.SDK_INT < 34 || center.canUseFullScreenIntent();
        if (alarm.optBoolean("full_screen", true) && canFullScreen) builder.setFullScreenIntent(display, true);
        return builder.build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String id = intent == null ? null : intent.getStringExtra("alarm_id");
        try {
            if (id == null) { if (players.isEmpty()) stopSelf(); return START_NOT_STICKY; }
            JSONObject alarm = AlarmStore.get(this, id);
            int notificationId = notifications.containsKey(id) ? notifications.get(id) : nextNotification++;
            Notification notification = notification(alarm, notificationId);
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(notificationId, notification);
            notifications.put(id, notificationId);
            if (players.contains(id)) return START_NOT_STICKY;
            MediaPlayer player = new MediaPlayer();
            try {
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                player.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
                String sound = alarm.optString("sound", "default");
                android.net.Uri uri = "default".equals(sound) ? android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM) : android.net.Uri.parse(sound);
                if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) throw new IllegalArgumentException("Android sound must be default or an accessible content/file URI");
                player.setDataSource(this, uri); player.setLooping(alarm.optBoolean("loop", true));
                double volume = alarm.optDouble("volume", 1);
                if (!Double.isFinite(volume) || volume < 0 || volume > 1) throw new IllegalArgumentException("volume must be between zero and one");
                player.setVolume((float) volume, (float) volume);
                player.setOnPreparedListener(prepared -> {
                    if (!players.owns(id, prepared)) return;
                    try {
                        prepared.start(); players.prepared(id, prepared, alarm.optBoolean("vibrate", true)); updateVibration();
                    } catch (RuntimeException ex) { recordError(id, "Cannot start alarm audio: " + ex.getMessage()); stopAlarm(id); }
                });
                player.setOnCompletionListener(completed -> { if (players.owns(id, completed)) stopAlarm(id); });
                player.setOnErrorListener((failed, what, extra) -> {
                    if (players.owns(id, failed)) { recordError(id, "Audio playback failed: " + what + "/" + extra); stopAlarm(id); } return true;
                });
                players.add(id, player); player.prepareAsync();
            } catch (Exception ex) {
                if (players.owns(id, player)) players.remove(id);
                release(player); throw ex;
            }
        } catch (Exception ex) { recordError(id == null ? "" : id, ex.getMessage()); stopAlarm(id); }
        return START_NOT_STICKY;
    }
    private void recordError(String id, String message) {
        android.util.Log.e("DespiaAlarms", message);
        try { AlarmStore.event(this, "delivery_error", id, new JSONObject().put("message", message)); }
        catch (Exception ex) { android.util.Log.e("DespiaAlarms", "Cannot retain delivery error", ex); }
    }
    private void stopAlarm(String id) {
        MediaPlayer player = players.remove(id);
        if (player != null) release(player);
        updateVibration();
        Integer notificationId = notifications.remove(id);
        if (notificationId != null) getSystemService(NotificationManager.class).cancel(notificationId);
        if (players.isEmpty()) {
            if (vibrator != null) vibrator.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        } else {
            String remaining = players.firstId();
            try { startForeground(notifications.get(remaining), notification(AlarmStore.get(this, remaining), notifications.get(remaining))); }
            catch (Exception ex) { recordError(remaining, ex.getMessage()); }
        }
    }
    private void release(MediaPlayer player) {
        try { player.release(); }
        catch (RuntimeException ex) { android.util.Log.e("DespiaAlarms", "Cannot release alarm player", ex); }
    }
    private void updateVibration() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (!players.hasVibration()) { vibrator.cancel(); return; }
            if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 500}, 0));
            else vibrator.vibrate(new long[]{0, 500, 500}, 0);
        } catch (RuntimeException ex) { android.util.Log.e("DespiaAlarms", "Cannot update alarm vibration", ex); }
    }
    @Override public void onDestroy() {
        for (MediaPlayer player : players.drain()) release(player);
        for (Integer id : notifications.values()) getSystemService(NotificationManager.class).cancel(id);
        notifications.clear(); if (vibrator != null) vibrator.cancel();
        if (active == this) active = null; super.onDestroy();
    }
}
