package com.eurobuddha.pandapools;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The existing native SDK runs alone here. Large broadcasts never enter the UI process.
 * Private cache files carry payloads between our same-UID processes; Binder carries only a UUID.
 * No exported entry point, additional node permissions, RPC listener, or signing policy. */
public final class NodeTransportService extends Service {
    private MinimaAPI api;
    private final Handler handler = new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what != 1 || msg.replyTo == null || msg.sendingUid != android.os.Process.myUid()) return true;
        String id = msg.getData().getString("id", "");
        if (!NodeTransport.validId(id)) return true;
        Messenger target = msg.replyTo;
        File request = NodeTransport.file(this, id, ".cmd");
        try {
            String command = NodeTransport.read(request);
            request.delete();
            api.Command(command, reply -> respond(target, id, reply));
        } catch (Exception error) { respond(target, id, NodeTransport.failure("Node transport could not read the request.")); }
        return true;
    });
    private final Messenger messenger = new Messenger(handler);

    @Override public void onCreate() {
        super.onCreate();
        api = new MinimaAPI(getApplicationContext(), reply -> {});
        File[] files = NodeTransport.directory(this).listFiles();
        if (files != null) for (File f : files)
            if (System.currentTimeMillis() - f.lastModified() > 10 * 60_000L) f.delete();
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public void onDestroy() { if (api != null) api.onDestroy(); super.onDestroy(); }

    private void respond(Messenger target, String id, JSONObject reply) {
        File file = NodeTransport.file(this, id, ".json");
        Message result = Message.obtain(null, 1);
        Bundle data = new Bundle(); data.putString("id", id);
        try {
            byte[] bytes = (reply == null ? "{}" : reply.toString()).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > NodeTransport.MAX_BYTES) throw new java.io.IOException("Oversized reply");
            Files.write(file.toPath(), bytes);
        } catch (Exception error) { data.putString("error", "Node transport could not save the reply."); }
        result.setData(data);
        try { target.send(result); }
        catch (RemoteException gone) { file.delete(); }
    }
}
