package despia.ext.applock;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class LockStore {
    private static final Object MUTEX = new Object();
    private final AtomicFile file;
    interface Change<T> { T apply(JSONObject state) throws Exception; }
    LockStore(Context context) {
        File directory = new File(context.getFilesDir(), "nativeext/applock");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("App Lock directory could not be created");
        file = new AtomicFile(new File(directory, "state.json"));
    }
    JSONObject read() throws Exception { synchronized (MUTEX) { return load(); } }
    private JSONObject load() throws Exception {
        try (FileInputStream stream = file.openRead()) { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[4096]; int count; while ((count = stream.read(block)) != -1) bytes.write(block, 0, count); return new JSONObject(bytes.toString("UTF-8")); }
        catch (FileNotFoundException missing) { if (file.getBaseFile().exists()) throw missing; return new JSONObject().put("groups", new JSONArray()).put("schedules", new JSONArray()).put("events", new JSONArray()); }
    }
    <T> T change(Change<T> change) throws Exception {
        synchronized (MUTEX) {
            JSONObject state = load(); T answer = change.apply(state); FileOutputStream stream = null;
            try { stream = file.startWrite(); stream.write(state.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
            catch (IOException failure) { if (stream != null) file.failWrite(stream); throw failure; }
            return answer;
        }
    }
}
