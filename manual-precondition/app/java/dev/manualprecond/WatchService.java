package dev.manualprecond;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicInteger;

import hmgapi.client.HmgApi;

/**
 * After a start: follows HMG-API's conditioning.state and the car's readings with a
 * {@link Precond.Watch}, which logs to HMG-API's log: how long the car took to show its
 * conditioning indicator, a timeout (HMG-API already sent the stop), the car ending it,
 * changes of the BMS's conditioning failure flag, and the pack's temperature every 2
 * minutes while conditioning runs. With the "notices" setting on (the default), a change
 * also shows the car's own notice.
 *
 * Polls every 3 s until the car answers (at most 6 minutes, past HMG-API's longest timeout:
 * 5 minutes on the EV6), then every 10 s while conditioning runs (at most an hour). A newer
 * start replaces this watch rather than queueing behind it.
 */
public class WatchService extends IntentService {
    static final long POLL_MS = 3000, ACTIVE_POLL_MS = 10000;
    static final long WAIT_LIMIT_MS = 6 * 60 * 1000L, ACTIVE_LIMIT_MS = 60 * 60 * 1000L;
    private static final AtomicInteger GENERATION = new AtomicInteger();

    public WatchService() {
        super("precond-watch");
    }

    static void watch(Context c) {
        c.startService(new Intent(c, WatchService.class).putExtra("generation", GENERATION.incrementAndGet()));
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        int mine = intent.getIntExtra("generation", 0);
        boolean notices = Precond.notices(Precond.settings());
        Precond.Watch w = new Precond.Watch(SystemClock.elapsedRealtime());
        Precond.Status last = null;
        int misses = 0;
        // HMG-API's cached conditioning.state can still be the one from before the start
        SystemClock.sleep(Precond.READING_FRESH_MS + 200);
        while (GENERATION.get() == mine) {
            long now = SystemClock.elapsedRealtime();
            if (w.activeAt > 0 ? now - w.activeAt > ACTIVE_LIMIT_MS : now - w.startedAt > WAIT_LIMIT_MS) {
                w.gaveUp(now, last);
                return;
            }
            Precond.Status s = Precond.read();
            if (s.error != null) {
                if (++misses >= 5) {   // HMG-API gone for a while: nothing to follow
                    Precond.warn("stopped watching: HMG-API didn't answer (" + s.error + ")");
                    return;
                }
            } else {
                misses = 0;
                last = s;
                String notice = w.step(s, now);
                if (notice != null && notices) HmgApi.toast(this, notice);
                if (w.done) return;
            }
            SystemClock.sleep(w.activeAt > 0 ? ACTIVE_POLL_MS : POLL_MS);
        }
        Precond.log("a newer start took over the watch");
    }
}
