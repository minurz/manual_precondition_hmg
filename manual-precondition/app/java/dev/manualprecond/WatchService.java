package dev.manualprecond;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import org.json.JSONObject;

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
 * The automatic restart (setting "restart_after_route", on by default): the car cancels
 * conditioning when an Android Auto route starts guiding (seen on an Ioniq 6, CAN 260206,
 * with every other mod's part in it turned off). When the car ends it within 5 minutes of
 * switching it on (8 on the EV6), with the pack still below HMG-API's warm limit and the
 * BMS's failure flag clear, this waits {@link Precond#RESTART_DELAY_MS} and sends one new
 * start, with its own notice saying why; then follows that one. Once per press.
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
        JSONObject settings = Precond.settings();
        boolean notices = Precond.notices(settings), restart = Precond.restartAfterRoute(settings);
        Precond.Watch w = new Precond.Watch(SystemClock.elapsedRealtime(), false);
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
                if (w.done) {
                    if (restart && w.endedByCar()) {
                        String why = Precond.restartBlocker(w, s);
                        if (why == null) {
                            Precond.Watch next = restart(w, mine);
                            if (next != null) {
                                w = next;
                                last = null;
                                SystemClock.sleep(Precond.READING_FRESH_MS + 200);
                                continue;
                            }
                            return;   // not sent: restart() said why (a newer press says what happens itself)
                        } else {
                            Precond.log("not started again: " + why);
                        }
                    }
                    if (notice != null && notices) HmgApi.toast(this, notice);
                    return;
                }
                if (notice != null && notices) HmgApi.toast(this, notice);
            }
            SystemClock.sleep(w.activeAt > 0 ? ACTIVE_POLL_MS : POLL_MS);
        }
        Precond.log("a newer start took over the watch");
    }

    /**
     * The car ended conditioning early with the pack still cold: wait, check the car again,
     * send one new start and say so. The watch for it, or null when it wasn't sent (a newer
     * press, the car not ready for it, or HMG-API refusing; each one logged, and the last two
     * shown, since they replace the "car ended it" notice).
     */
    private Precond.Watch restart(Precond.Watch ended, int mine) {
        long onFor = (ended.endedAt - ended.activeAt) / 1000;
        Precond.log("the car ended it " + onFor + " s after it came on, with the pack cold: starting it again in "
                + Precond.RESTART_DELAY_MS / 1000 + " s (setting \"Start again when a route cancels it\")");
        SystemClock.sleep(Precond.RESTART_DELAY_MS);
        if (GENERATION.get() != mine) {
            Precond.log("not started again: a newer start or stop came first");
            return null;
        }
        Precond.Status s = Precond.read();
        String blocker = s.error != null ? "HMG-API didn't answer (" + s.error + ")"
                : s.requestRunning() ? "a request is already running (" + s.state + ")"
                : s.indicator == 1 ? "the car is already conditioning on its own"
                : null;
        if (blocker != null) {
            Precond.log("not started again: " + blocker);
            HmgApi.toast(this, "The car cancelled battery conditioning; not started again: " + blocker);
            return null;
        }
        try {
            Precond.start(false, "automatic restart, after the car ended it " + onFor + " s after it came on");
        } catch (Exception e) {
            String why = Precond.reason(e);   // Precond.start logged it
            HmgApi.toast(this, "The car cancelled battery conditioning, and starting it again didn't work: " + why);
            return null;
        }
        HmgApi.toast(this, "Battery conditioning started again: the car cancelled it after " + onFor
                + " s (a route starting can do that), with the battery still cold ("
                + Precond.temperature(s.tempC, Precond.units(Precond.settings())) + ")");
        return new Precond.Watch(SystemClock.elapsedRealtime(), true);
    }
}
