package dev.manualprecond;

import android.content.Context;
import android.content.SharedPreferences;

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

    /** HMG-API's "ok ..." line; a refusal throws, with the reason (see {@link #reason}) */
    static String start(boolean warm) throws IOException {
        return warm ? HmgApi.connect().command("conditioning", "start", "warm")
                : HmgApi.connect().command("conditioning", "start");
    }

    /** ends any request, ours or another mod's (a stop by hand wins over automatic starts) */
    static String stop() throws IOException {
        return HmgApi.connect().command("conditioning", "stop");
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
