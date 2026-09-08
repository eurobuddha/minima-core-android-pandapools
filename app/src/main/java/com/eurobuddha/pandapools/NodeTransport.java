package com.eurobuddha.pandapools;

import android.content.*;
import android.os.*;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPIListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Same SDK callback contract as MinimaAPI, with small internal Binder messages. */
final class NodeTransport {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Map<String, MinimaAPIListener> callbacks = new LinkedHashMap<>();
    private final Set<String> unsent = new LinkedHashSet<>();
    private Messenger remote;
    private boolean bound, closed;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        String id = msg.getData().getString("id", "");
        if (!validId(id) || !callbacks.containsKey(id)) return true;
        String error = msg.getData().getString("error");
        File file = file(context, id, ".json");
        reader.execute(() -> {
            JSONObject reply;
            try { reply = error == null ? new JSONObject(read(file)) : failure(error); }
            catch (Exception bad) { reply = failure("Node transport could not read the reply."); }
            finally { file.delete(); }
            final JSONObject answer = reply;
            main.post(() -> complete(id, answer));
        });
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            remote = new Messenger(binder);
            for (String id : new ArrayList<>(unsent)) send(id);
        }
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            // Never replay a dispatched command after process death: a write might have completed.
            failAll("The node reply connection stopped. The app is still running.");
        }
        public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };

    NodeTransport(Context ctx, MinimaAPIListener registration) {
        context = ctx.getApplicationContext();
        bound = context.bindService(new Intent(context, NodeTransportService.class), connection, Context.BIND_AUTO_CREATE);
        Command("checkmode", reply -> {
            JSONObject state = new JSONObject();
            try { state.put("enabled", reply.optBoolean("enabled", reply.optBoolean("status", false))); }
            catch (Exception ignored) { }
            registration.response(state);
        });
    }

    void Command(String command, MinimaAPIListener cb) {
        if (closed || !bound) { cb.response(failure("Node reply service is unavailable.")); return; }
        String id = UUID.randomUUID().toString();
        try {
            byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Oversized command");
            java.nio.file.Files.write(file(context, id, ".cmd").toPath(), bytes);
        } catch (Exception error) { cb.response(failure("Could not stage node command.")); return; }
        callbacks.put(id, cb); unsent.add(id);
        if (remote != null) send(id);
        // Cancel a command that has NOT crossed the service boundary before the outer read timeout.
        main.postDelayed(() -> {
            if (unsent.remove(id)) complete(id, failure("Node reply service did not connect. Command not sent."));
        }, 20_000);
    }
    private void send(String id) {
        if (!unsent.remove(id)) return;
        Message request = Message.obtain(null, 1);
        Bundle b = new Bundle(); b.putString("id", id); request.setData(b); request.replyTo = replies;
        try { remote.send(request); }
        catch (RemoteException error) { complete(id, failure("Node reply service disconnected.")); }
    }
    private void complete(String id, JSONObject reply) {
        unsent.remove(id); file(context, id, ".cmd").delete();
        MinimaAPIListener cb = callbacks.remove(id);
        if (cb != null) cb.response(reply);
    }
    private void failAll(String error) {
        for (String id : new ArrayList<>(callbacks.keySet())) complete(id, failure(error));
    }
    void onDestroy() {
        closed = true;
        failAll("Node reply connection closed.");
        if (bound) { context.unbindService(connection); bound = false; }
        reader.shutdown();
    }
    static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"); }
    static File directory(Context ctx) {
        File d = new File(ctx.getCacheDir(), "node-transport");
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("Cannot create private transport directory");
        return d;
    }
    static File file(Context ctx, String id, String suffix) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid request ID");
        return new File(directory(ctx), id + suffix);
    }
    static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n, count = 0;
            while ((n = in.read(buffer)) != -1) {
                count += n; if (count > MAX_BYTES) throw new IOException("Payload too large");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    static JSONObject failure(String message) {
        JSONObject result = new JSONObject();
        try { result.put("transporterror", message); } catch (Exception ignored) { }
        // Deliberately NO status: a transport failure must never clear a pending signing marker.
        return result;
    }
}
