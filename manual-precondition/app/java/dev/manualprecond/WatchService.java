package dev.manualprecond;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import hmgapi.client.HmgApi;

/**
 * After a start: follows HMG-API's conditioning.state and says, with the car's own notice,
 * when the car confirms (active), when HMG-API gives up on it (timed_out: it already sent
 * the stop), or when the car ends it. Off with the "notices" setting. Gives up after 6
 * minutes, past HMG-API's longest timeout (5 minutes on the EV6), or once the request is
 * gone (stopped by hand, the drive ended).
 */
public class WatchService extends IntentService {
    static final long POLL_MS = 3000, LIMIT_MS = 6 * 60 * 1000L;

    public WatchService() {
        super("precond-watch");
    }

    static void watch(Context c) {
        c.startService(new Intent(c, WatchService.class));
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (!Precond.notices(Precond.settings())) return;
        long end = SystemClock.elapsedRealtime() + LIMIT_MS;
        String last = null;
        int misses = 0;
        while (SystemClock.elapsedRealtime() < end) {
            Precond.Status s = Precond.read();
            if (s.error != null) {
                if (++misses >= 5) return;   // HMG-API gone for 15 s: nothing to follow
            } else {
                misses = 0;
                if (!s.state.equals(last)) {
                    if ("active".equals(s.state)) {
                        HmgApi.toast(this, "Battery conditioning is on"
                                + (s.tempC != null ? " (battery " + Precond.temperature(s.tempC, Precond.units(Precond.settings())) + ")" : ""));
                        return;
                    }
                    if ("timed_out".equals(s.state)) {
                        HmgApi.toast(this, "The car didn't start battery conditioning within " + Precond.timeoutMinutes()
                                + " minutes; the request was stopped");
                        return;
                    }
                    if ("ended".equals(s.state)) {
                        HmgApi.toast(this, "The car ended battery conditioning");
                        return;
                    }
                    if ("requested".equals(s.state)) {
                        HmgApi.toast(this, "Battery conditioning requested; this car's indicator can't be read, so there's no confirmation");
                        return;
                    }
                    if ("off".equals(s.state) || "stopping".equals(s.state)) return;   // stopped by hand, or the drive ended
                }
                last = s.state;
            }
            SystemClock.sleep(POLL_MS);
        }
    }
}
