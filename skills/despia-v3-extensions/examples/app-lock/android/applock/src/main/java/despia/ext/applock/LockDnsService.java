package despia.ext.applock;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.*;
import android.system.OsConstants;
import org.json.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;

public final class LockDnsService extends VpnService {
    static final String STOP = "despia.ext.applock.STOP_DNS";
    private static volatile LockDnsService active;
    private static final int NOTIFICATION = 0x4e584c;
    private final Object sessions = new Object();
    private volatile Session session;
    private static final class Session {
        final ParcelFileDescriptor tunnel;
        volatile int startId;
        volatile boolean running = true;
        volatile Socket upstream;
        Thread reader;
        volatile long forwarded, blocked, failed, rejected;
        volatile String lastError;
        Session(ParcelFileDescriptor tunnel, int startId) { this.tunnel = tunnel; this.startId = startId; }
    }
    static boolean isRunning() { LockDnsService service = active; Session session = service == null ? null : service.session; return session != null && session.running; }
    static JSONObject status() throws JSONException {
        LockDnsService service = active; Session session = service == null ? null : service.session;
        JSONObject status = new JSONObject().put("running", session != null && session.running).put("udp_dns_only", true).put("tcp_dns", false).put("doh_blocking", false).put("https_path_blocking", false).put("packet_logging", false);
        if (session != null) status.put("forwarded", session.forwarded).put("blocked", session.blocked).put("failed", session.failed).put("rejected", session.rejected).put("last_error", session.lastError == null ? JSONObject.NULL : session.lastError);
        return status;
    }
    static void stop(Context context) {
        LockDnsService service = active;
        if (service != null) {
            synchronized (service.sessions) {
                // Android can keep a VPN service bound after stopService clears its start request.
                if (service.session != null) service.close(service.session);
            }
        }
        context.stopService(new Intent(context, LockDnsService.class));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (sessions) {
            if (intent != null && STOP.equals(intent.getAction())) {
                AppLockExtension.cancelPendingVpn();
                if (session != null) close(session);
                stopSelf(startId); return START_NOT_STICKY;
            }
            if (intent == null || !AppLockExtension.isCurrentVpnStart(intent.getLongExtra("DESPIA_NX_VPN_GENERATION", -1))) { stopSelf(startId); return START_NOT_STICKY; }
            if (session != null && session.running) { session.startId = startId; return START_NOT_STICKY; }
            if (VpnService.prepare(this) != null) { record("vpn_error", "consent_required"); stopSelf(startId); return START_NOT_STICKY; }
            ParcelFileDescriptor descriptor = null;
            try {
                String channel = getPackageName() + ".despia.nx.applock.dns";
                NotificationManager notifications = getSystemService(NotificationManager.class);
                if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(new NotificationChannel(channel, "App Lock DNS", NotificationManager.IMPORTANCE_LOW));
                PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, LockDnsService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
                Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
                builder.setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("App Lock DNS is active").setContentText("Filters selected domains. Tap Stop to disconnect.").setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop", stop).build());
                if (launch != null) builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED); else startForeground(NOTIFICATION, builder.build());
                // Only the synthetic DNS server is routed. Other app traffic stays on its network.
                descriptor = new Builder().setSession("App Lock DNS").setMtu(1500).addAddress("10.231.0.1", 32).addDnsServer("10.231.0.2").addRoute("10.231.0.2", 32).allowFamily(OsConstants.AF_INET6).setBlocking(true).establish();
                if (descriptor == null) throw new IOException("VPN consent was revoked");
                Session created = new Session(descriptor, startId); session = created; active = this;
                created.reader = new Thread(() -> readPackets(created), "despia-nx-applock-dns"); created.reader.start(); record("vpn_started", null);
            } catch (Exception failure) {
                record("vpn_error", "vpn_start_failed");
                if (descriptor != null) try { descriptor.close(); } catch (IOException closing) { android.util.Log.w("DespiaAppLock", "VPN descriptor close failed"); }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
    }
    private void readPackets(Session owner) {
        try (FileInputStream input = new FileInputStream(owner.tunnel.getFileDescriptor()); FileOutputStream output = new FileOutputStream(owner.tunnel.getFileDescriptor())) {
            byte[] packet = new byte[65535];
            while (owner.running) {
                int length = input.read(packet); if (length < 0) break;
                DnsPacket.Request request;
                try { request = DnsPacket.parse(packet, length); }
                catch (IllegalArgumentException malformed) { owner.rejected++; continue; }
                try {
                    JSONObject state = new LockStore(this).read(); byte[] response;
                    if (LockRules.matchesDomain(request.hostname, LockState.domains(state))) { response = request.blocked(); owner.blocked++; }
                    else { response = request.response(resolve(owner, request.dns, state.optJSONObject("android_vpn"))); owner.forwarded++; }
                    if (owner.running) output.write(response);
                } catch (Exception failure) {
                    owner.failed++; owner.lastError = "dns_resolution_failed";
                    if (owner.running) output.write(request.failure());
                }
            }
        } catch (IOException failure) { if (owner.running) { owner.lastError = "vpn_io_failed"; record("vpn_error", owner.lastError); } }
        finally {
            synchronized (sessions) { boolean current = session == owner; close(owner); if (current) stopSelf(owner.startId); }
        }
    }
    private byte[] resolve(Session owner, byte[] query, JSONObject config) throws Exception {
        String address = config == null ? "8.8.8.8" : config.optString("upstream_ip", "8.8.8.8");
        String hostname = config == null ? "dns.google" : config.optString("upstream_tls_name", "dns.google");
        int port = config == null ? 853 : config.optInt("upstream_port", 853);
        Socket socket = new Socket();
        synchronized (sessions) { if (!owner.running || session != owner) { socket.close(); throw new IOException("DNS session ended"); } owner.upstream = socket; }
        try {
            socket.bind(null);
            if (!protect(socket)) throw new IOException("DNS socket could not be protected from the VPN");
            socket.connect(new InetSocketAddress(address, port), 5000); socket.setSoTimeout(5000);
            try (SSLSocket tls = (SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket, hostname, port, true)) {
                synchronized (sessions) { if (!owner.running || session != owner) throw new IOException("DNS session ended"); owner.upstream = tls; }
                SSLParameters parameters = tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); tls.setSSLParameters(parameters); tls.startHandshake();
                DataOutputStream output = new DataOutputStream(tls.getOutputStream()); output.writeShort(query.length); output.write(query); output.flush();
                DataInputStream input = new DataInputStream(tls.getInputStream()); int length = input.readUnsignedShort();
                if (length < 12 || length > 65507) throw new IOException("Invalid DNS-over-TLS response length");
                byte[] answer = new byte[length]; input.readFully(answer); return answer;
            }
        } finally { synchronized (sessions) { owner.upstream = null; } socket.close(); }
    }
    private void close(Session owner) {
        owner.running = false;
        Socket socket = owner.upstream; owner.upstream = null;
        if (socket != null) try { socket.close(); } catch (IOException failure) { android.util.Log.w("DespiaAppLock", "DNS socket close failed"); }
        try { owner.tunnel.close(); } catch (IOException failure) { android.util.Log.w("DespiaAppLock", "VPN descriptor close failed"); }
        if (session == owner) { session = null; if (active == this) active = null; record("vpn_stopped", owner.lastError); stopForeground(STOP_FOREGROUND_REMOVE); }
    }
    private void record(String kind, String detail) {
        try { new LockStore(this).change(state -> { LockState.record(state, kind, null, detail); return null; }); AppLockExtension.emitCurrent("changed", new JSONObject().put("kind", kind)); }
        catch (Exception failure) { android.util.Log.w("DespiaAppLock", "VPN state could not be recorded"); }
    }
    @Override public void onRevoke() { synchronized (sessions) { if (session != null) { Session owner = session; close(owner); stopSelf(owner.startId); } } }
    @Override public void onDestroy() {
        Session owner;
        synchronized (sessions) { owner = session; if (owner != null) close(owner); }
        if (owner != null && owner.reader != Thread.currentThread()) {
            try { owner.reader.join(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        super.onDestroy();
    }
}
