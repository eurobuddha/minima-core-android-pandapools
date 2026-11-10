package com.eurobuddha.pandapools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Restarts PandaPools once, after the Trading Account is created, so every engine boots on the account. Runs in its OWN
 * process (":phoenix"): started from the foreground activity, it ends the main process, then relaunches the main
 * activity — so the relaunch is never lost with the process it replaces. (Dexicon's, same shape.)
 */
public final class RestartActivity extends Activity {
    static final String EXTRA_PID = "pid";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pid = getIntent().getIntExtra(EXTRA_PID, -1);
        if (pid > 0 && pid != android.os.Process.myPid()) android.os.Process.killProcess(pid);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    static void restart(Activity from) {
        from.startActivity(new Intent(from, RestartActivity.class).putExtra(EXTRA_PID, android.os.Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
