package despia.ext.alarms;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

public final class AlarmScreen extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        String id = getIntent().getStringExtra("alarm_id");
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 48, 32, 32);
        TextView label = new TextView(this); label.setTextSize(28); layout.addView(label);
        TextView message = new TextView(this); layout.addView(message);
        try {
            JSONObject alarm = AlarmStore.get(this, id); label.setText(alarm.optString("label", "Alarm"));
            message.setText(alarm.optString("body", "Complete your challenge in the app, or use emergency stop."));
            Button open = new Button(this); open.setText(alarm.optString("open_label", "Open app")); layout.addView(open);
            open.setOnClickListener(view -> {
                android.content.Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (launch != null) { launch.putExtra("DESPIA_NX_ALARM_ID", id); launch.putExtra("DESPIA_NX_ALARM_OPEN_TOKEN", java.util.UUID.randomUUID().toString()); startActivity(launch); finish(); }
            });
            Button snooze = new Button(this); snooze.setText(alarm.optString("snooze_label", "Snooze")); layout.addView(snooze);
            snooze.setOnClickListener(view -> { try { AlarmStore.snooze(this, id, alarm.optDouble("snooze_seconds", 300)); finish(); } catch (Exception ex) { message.setText(ex.getMessage()); } });
            Button stop = new Button(this); stop.setText(alarm.optString("stop_label", "Stop")); layout.addView(stop);
            stop.setOnClickListener(view -> { try { AlarmStore.stop(this, id, false); finish(); } catch (Exception ex) { message.setText(ex.getMessage()); } });
            Button emergency = new Button(this); emergency.setText(alarm.optString("emergency_label", "Emergency stop")); layout.addView(emergency);
            emergency.setOnClickListener(view -> { try { AlarmStore.stop(this, id, true); finish(); } catch (Exception ex) { message.setText(ex.getMessage()); } });
        } catch (Exception ex) { label.setText("Alarm unavailable"); message.setText(ex.getMessage()); }
        setContentView(layout);
    }
}
