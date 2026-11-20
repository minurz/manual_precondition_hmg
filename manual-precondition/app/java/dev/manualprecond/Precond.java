package dev.manualprecond;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;

import hmgapi.client.HmgApi;

/**
 * Manual battery conditioning through HMG-API (mod.json "vehicle"): the reviewed
 * {@code conditioning} command and its readings. HMG-API does the car side: route values
 * 9 km / 18 min (Launchpad's), then the station event; a 5 s keep-alive of the route
 * values; the checks before a start (READY, Battery Conditioning on in the EV settings,
 * not already conditioning on its own, the pack not warm unless "warm" is added); the
 * activation timeout (2 minutes, 5 on the EV6); and a stop when the drive ends or this mod
 * is turned off. This class only asks.
 *
 * Every call blocks on hmgapid (up to 10 s): never call it on the main thread.
 *
 * Logging: every start and stop, HMG-API's answer, and what the car did after a start go
 * to HMG-API's log as "[manual-precondition] ..." lines (see {@link #log}): the HMG-API app
 * shows them under Mods, Manual Preconditioning, Log, and in its Logs tab ("Mods").
 *
 * Not used on purpose: {@code conditioning status}. HMG-API asks the owner the first time a
 * mod uses a command with anything but stop, so polling it would put that question on screen
 * before the owner ever tapped Start. The {@code conditioning.state} reading says the same.
 */
final class Precond {
    private Precond() {}

    static final String TOGGLE = "dev.manualprecond.TOGGLE";

    /** HMG-API's reading names (each one listed in mod.json "vehicle": {"read": [...]}) */
    static final String STATE = "conditioning.state", INDICATOR = "hv.conditioning_active",
            TEMP = "hv.battery_temp_min", READY = "hv.ready", SETTING = "hv.conditioning",
            FAILURE = "hv.conditioning_failure", SOC = "hv.soc", PARK = "gear.park";
    static final String[] READS = { STATE, INDICATOR, TEMP, READY, SETTING, FAILURE, SOC, PARK };

    /** HMG-API's raw values: READY 1; Battery Conditioning setting 2 on, 1 off (HMG-API's Commands) */
    static final int READY_ON = 1, SETTING_ON = 2;

    /**
     * How long HMG-API serves a reading from its cache (its Vehicle.READINGS freshMs: 1000 ms
     * for conditioning.state, 500 ms or more for the rest). A command doesn't clear that cache,
     * so a read within this long of a start or stop can still show the state before it.
     */
    static final long READING_FRESH_MS = 1000;

    /** a second press within this long after a "pack is warm" refusal sends the start anyway */
    static final long WARM_CONFIRM_MS = 15000;

    static final class Status {
        /** HMG-API's request: off, requested, waiting, active, stopping, ended, timed_out; "-" unknown */
        String state = "-";
        /** the car's conditioning indicator: 1 on, 0 off, -1 unknown */
        int indicator = -1;
        /** HMG-API's raw values, -1 unknown */
        int ready = -1, setting = -1;
        /** the pack's minimum temperature in °C, or null when the car didn't say */
        Integer tempC;
        String soc = "-", failure = "-";
        boolean park;
        /** why HMG-API couldn't be asked, or null */
        String error;

        /** a request (ours or another mod's) that a stop would end */
        boolean requestRunning() {
            return "waiting".equals(state) || "active".equals(state) || "requested".equals(state);
        }

        /** the car shows conditioning with no request of HMG-API's: its own navigation started it */
        boolean carOnItsOwn() {
            return indicator == 1 && !requestRunning() && !"stopping".equals(state);
        }
    }

    static Status read() {
        Status s = new Status();
        try {
            Map<String, String> v = HmgApi.connect().vehicle(READS);
            s.state = text(v.get(STATE));
            s.indicator = number(v.get(INDICATOR));
            s.ready = number(v.get(READY));
            s.setting = number(v.get(SETTING));
            int t = number(v.get(TEMP), Integer.MIN_VALUE);
            s.tempC = t == Integer.MIN_VALUE ? null : Integer.valueOf(t);
            s.soc = text(v.get(SOC));
            s.failure = text(v.get(FAILURE));
            s.park = "1".equals(v.get(PARK));
        } catch (IOException e) {
            s.error = reason(e);
        }
        return s;
    }

    /**
     * HMG-API's "ok ..." line; a refusal throws, with the reason (see {@link #reason}).
     * Logs the car's state first, then HMG-API's answer. {@code from} says what asked
     * ("screen", "button or star key").
     */
    static String start(boolean warm, String from) throws IOException {
        log("start (" + from + (warm ? "; pack warm, sent anyway" : "") + "): " + snapshot(read()));
        try {
            String r = warm ? HmgApi.connect().command("conditioning", "start", "warm")
                    : HmgApi.connect().command("conditioning", "start");
            log("HMG-API: " + r.trim() + (r.startsWith("ok conditioning started")
                    ? " (the car's HEV service took the request; waiting for the car's indicator)" : ""));
            return r;
        } catch (IOException e) {
            logFailure("start", e);
            throw e;
        }
    }

    /** ends any request, ours or another mod's (a stop by hand wins over automatic starts) */
    static String stop(String from) throws IOException {
        log("stop (" + from + ")");
        try {
            String r = HmgApi.connect().command("conditioning", "stop");
            log("HMG-API: " + r.trim());
            return r;
        } catch (IOException e) {
            logFailure("stop", e);
            throw e;
        }
    }

    // ---- HMG-API's log

    /** HMG-API's log tag; the app shows a mod's lines by their "[<mod id>] " */
    static final String LOG_TAG = "HMG-API", MOD_ID = "manual-precondition";

    /**
     * A line in HMG-API's log, under this mod. HMG-API keeps its tag's lines
     * (/data/hmgapi/logs/hmgapi/log.log, across a drive) and its app lists them under the mod.
     * Not a documented HMG-API interface for apps: it's how the app picks out a mod's lines
     * today (ModsTab, LogsTab), so a later HMG-API could change it.
     */
    static void log(String line) {
        Log.i(LOG_TAG, "[" + MOD_ID + "] " + line);
    }

    /** the same, as a warning (the Logs tab's "Warnings and errors") */
    static void warn(String line) {
        Log.w(LOG_TAG, "[" + MOD_ID + "] " + line);
    }

    /** what a failed start or stop says: a refusal, the car's service failing, or HMG-API not answering */
    private static void logFailure(String what, IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().trim(), why = reason(e);
        if (m.startsWith("refused: the car's service failed")) {
            warn(what + ": a call into the car's HEV service failed, so the request may not have reached the car (" + why + ")");
        } else if (e instanceof HmgApi.HmgApiException && m.startsWith("refused: ")) {
            log(what + " refused by HMG-API: " + why);
        } else {
            warn(what + ": couldn't ask HMG-API (" + why + ")");
        }
    }

    /** the readings a start depends on, for the log */
    static String snapshot(Status s) {
        if (s.error != null) return "readings unavailable (" + s.error + ")";
        return "READY " + flag(s.ready, READY_ON)
                + ", Battery Conditioning " + (s.setting < 0 ? "–" : s.setting == SETTING_ON ? "on" : "off (" + s.setting + ")")
                + ", pack " + temperature(s.tempC, "both")
                + ", indicator " + (s.indicator < 0 ? "–" : s.indicator == 1 ? "on" : "off")
                + ", BMS failure flag " + s.failure
                + ", SOC " + ("-".equals(s.soc) ? "–" : s.soc + " %")
                + ", conditioning.state " + s.state;
    }

    private static String flag(int v, int on) {
        return v < 0 ? "–" : v == on ? "yes" : "no (" + v + ")";
    }

    /**
     * What the car does after a start, as WatchService polls it: logs each change of
     * HMG-API's state (with how long it took), every change of the BMS's conditioning
     * failure flag, and the pack's temperature every 2 minutes while conditioning runs.
     * {@link #step} returns the notice to show for a change (or null); {@link #done} once
     * there's nothing more to follow.
     */
    static final class Watch {
        static final long TEMP_LOG_MS = 2 * 60 * 1000L;
        /**
         * After a start, a state other than the request's own (off, or an ended/timed_out
         * left from before) is taken as the start not yet showing, for this long: past
         * HMG-API's reading cache ({@link #READING_FRESH_MS}) with room to spare.
         */
        static final long GRACE_MS = 3000;
        private boolean seen;   // the request's own state (waiting, requested, active) has shown
        final long startedAt;
        /** this watch follows an automatic restart (one per press) */
        final boolean restarted;
        long activeAt, tempLoggedAt, endedAt;
        boolean done;
        private String state, failure;
        private Integer startTemp;

        Watch(long now, boolean restarted) {
            startedAt = now;
            this.restarted = restarted;
        }

        /** the car turned its indicator off after it had been on (HMG-API's "ended") */
        boolean endedByCar() {
            return done && "ended".equals(state) && activeAt > 0;
        }

        String step(Status s, long now) {
            String notice = null;
            boolean own = "waiting".equals(s.state) || "requested".equals(s.state) || "active".equals(s.state);
            if (own) seen = true;
            else if (!seen && now - startedAt < GRACE_MS) return null;   // the state from before the start (cached)
            if (!seen) {   // past the grace: an ok start whose request never appeared (or was gone at once)
                warn("the request never showed: HMG-API's state is " + s.state + " " + since(startedAt, now)
                        + " after an ok start; " + snapshot(s));
                done = true;
                return null;
            }

            if (failure == null) {
                failure = s.failure;
                if (failureSet(failure)) warn("BMS conditioning failure flag is " + failure + " at the start");
            } else if (!failure.equals(s.failure)) {
                String line = "BMS conditioning failure flag " + failure + " -> " + s.failure + ", " + since(startedAt, now) + " after the start";
                if (failureSet(s.failure)) warn(line);
                else log(line);
                failure = s.failure;
            }
            if (startTemp == null) startTemp = s.tempC;

            if (!s.state.equals(state)) {
                String before = state;
                state = s.state;
                if ("active".equals(state)) {
                    activeAt = tempLoggedAt = now;
                    log("the car's indicator is on, " + since(startedAt, now) + " after the start; pack " + temperature(s.tempC, "both"));
                    notice = "Battery conditioning is on" + (s.tempC != null ? " (battery " + temperature(s.tempC, "both") + ")" : "");
                } else if ("timed_out".equals(state)) {
                    warn("timed out: the car's indicator didn't come on within " + timeoutMinutes()
                            + " min, so HMG-API sent the stop; pack " + trend(s.tempC) + ", BMS failure flag " + s.failure);
                    notice = "The car didn't start battery conditioning within " + timeoutMinutes() + " minutes; the request was stopped";
                    done = true;
                } else if ("ended".equals(state)) {
                    endedAt = now;
                    log("the car ended conditioning (indicator off) after " + since(activeAt > 0 ? activeAt : startedAt, now)
                            + "; pack " + trend(s.tempC));
                    notice = "The car ended battery conditioning";
                    done = true;
                } else if ("requested".equals(state)) {
                    log("requested; this car's indicator can't be read, so there's no confirmation to wait for");
                    notice = "Battery conditioning requested; this car's indicator can't be read, so there's no confirmation";
                    done = true;
                } else if ("off".equals(state) || "stopping".equals(state)) {
                    log("the request ended (stopped by hand, another mod's stop, the drive ended, or the mod was turned off) "
                            + since(startedAt, now) + " after the start, from " + before + "; indicator "
                            + (s.indicator < 0 ? "–" : s.indicator == 1 ? "on" : "off") + ", pack " + trend(s.tempC));
                    done = true;
                }
            }
            if (activeAt > 0 && !done && s.tempC != null && now - tempLoggedAt >= TEMP_LOG_MS) {
                tempLoggedAt = now;
                log("conditioning for " + since(activeAt, now) + "; pack " + trend(s.tempC));
            }
            return notice;
        }

        /** the watch ran out of time */
        void gaveUp(long now, Status last) {
            log("stopped watching " + since(startedAt, now) + " after the start (state " + state + "; pack "
                    + trend(last == null ? null : last.tempC) + ")");
        }

        /** the BMS flag holds a value that may mean a failure: not 0, and not "-" or -1 (not reported) */
        static boolean failureSet(String f) {
            return f != null && !"0".equals(f) && !"-".equals(f) && !f.startsWith("-");
        }

        /** "12 °C -> 15 °C (+3 °C)", in °F and °C */
        private String trend(Integer now) {
            if (now == null || startTemp == null) return temperature(now != null ? now : startTemp, "both");
            if (now.intValue() == startTemp.intValue()) return temperature(now, "both") + " (unchanged)";
            int d = now - startTemp;
            return temperature(startTemp, "both") + " -> " + temperature(now, "both")
                    + " (" + (d > 0 ? "+" : "") + Math.round(d * 9 / 5.0) + " °F / " + (d > 0 ? "+" : "") + d + " °C)";
        }

        private static String since(long from, long now) {
            long sec = Math.max(0, (now - from) / 1000);
            return sec < 120 ? sec + " s" : (sec / 60) + " min";
        }
    }

    /** HMG-API's reason, without its "refused: " */
    static String reason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.toString();
        m = m.trim();
        return m.startsWith("refused: ") ? m.substring("refused: ".length()) : m;
    }

    static boolean warmRefusal(String reason) {
        return reason != null && reason.startsWith("the pack is warm");
    }

    static boolean ownerQuestion(String reason) {
        return reason != null && reason.startsWith("waiting for the owner to allow");
    }

    /** this mod's settings (settings.json defaults, the owner's values over them); defaults if HMG-API can't say */
    static JSONObject settings() {
        try {
            return HmgApi.connect().settings();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static String units(JSONObject settings) {
        return settings.optString("units", "both");
    }

    static boolean notices(JSONObject settings) {
        return settings.optBoolean("notices", true);
    }

    static boolean restartAfterRoute(JSONObject settings) {
        return settings.optBoolean("restart_after_route", true);
    }

    static String temperature(Integer c, String units) {
        if (c == null) return "–";
        long f = Math.round(c * 9 / 5.0 + 32);
        if ("c".equals(units)) return c + " °C";
        if ("f".equals(units)) return f + " °F";
        return f + " °F / " + c + " °C";
    }

    static String stateText(String state) {
        if ("off".equals(state)) return "Off";
        if ("waiting".equals(state)) return "Requested: waiting for the car";
        if ("active".equals(state)) return "Conditioning";
        if ("requested".equals(state)) return "Requested (this car's indicator can't be read)";
        if ("stopping".equals(state)) return "Stopping";
        if ("ended".equals(state)) return "Ended by the car";
        if ("timed_out".equals(state)) return "The car didn't start conditioning";
        return "Unknown";
    }

    /** the head unit's model code (first field of ro.build.product: CEEV, NEEV23, CVEV ...), or "" */
    static String model() {
        try {
            String p = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, "ro.build.product");
            int dot = p == null ? -1 : p.indexOf('.');
            return dot > 0 ? p.substring(0, dot) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** HMG-API's "pack is warm" limit for this car (its Commands.warmC), °C: Ioniq 5 21, EV6 and Ioniq 6 20 */
    static int warmLimitC() {
        return model().startsWith("NEEV") ? 21 : 20;
    }

    // ---- the automatic restart (setting "restart_after_route")

    /** how long after the car ends it the start goes again (the route's handover settles first) */
    static final long RESTART_DELAY_MS = 4000;

    /** the car ending conditioning within this long of switching it on reads as a route starting, not the BMS done: 8 min on the EV6, 5 on the rest */
    static long restartWindowMs() {
        return ("CVEV".equals(model()) ? 8 : 5) * 60 * 1000L;
    }

    /**
     * Why the request the car just ended isn't started again, or null to start it again:
     * the car ended it (not a stop, a timeout or the drive ending), this press hasn't been
     * restarted already, it had been on for less than {@link #restartWindowMs}, the pack is
     * still below the warm limit, and the BMS's failure flag isn't set.
     */
    static String restartBlocker(Watch w, Status s) {
        if (!w.endedByCar()) return "the car didn't end it";
        if (w.restarted) return "already restarted once for this start";
        long on = w.endedAt - w.activeAt;
        if (on >= restartWindowMs()) return "it was on for " + (on / 60000) + " min, past the " + (restartWindowMs() / 60000) + " min window";
        if (s.tempC == null) return "the pack temperature isn't known";
        if (s.tempC >= warmLimitC()) return "the pack is warm (" + temperature(s.tempC, "both") + ")";
        if (Watch.failureSet(s.failure)) return "the BMS failure flag is " + s.failure;
        return null;
    }

    /** HMG-API's activation timeout for this car (its Commands.activationMs), in minutes */
    static int timeoutMinutes() {
        return "CVEV".equals(model()) ? 5 : 2;
    }

    // ---- "start anyway" after a warm-pack refusal (the star key toggle has no screen to ask on)

    static void warmRefused(Context c) {
        prefs(c).edit().putLong("warm_refused_at", System.currentTimeMillis()).commit();
    }

    /** true (once) if a warm-pack refusal came less than WARM_CONFIRM_MS ago */
    static boolean takeWarmConfirm(Context c) {
        SharedPreferences p = prefs(c);
        long at = p.getLong("warm_refused_at", 0), now = System.currentTimeMillis();
        p.edit().remove("warm_refused_at").commit();
        return at > 0 && now >= at && now - at < WARM_CONFIRM_MS;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("precond", Context.MODE_PRIVATE);
    }

    private static String text(String v) {
        return v == null || v.trim().isEmpty() ? "-" : v.trim();
    }

    private static int number(String v) {
        return number(v, -1);
    }

    private static int number(String v, int missing) {
        if (v == null) return missing;
        try {
            return (int) Math.round(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return missing;   // "-": the car didn't answer
        }
    }
}
