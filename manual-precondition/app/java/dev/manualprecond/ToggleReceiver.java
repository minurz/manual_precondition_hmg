package dev.manualprecond;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import hmgapi.client.HmgApi;

/**
 * A steering wheel star hold (mod.json "input", setting "toggle") or the star key menu's
 * "Battery conditioning: start / stop": starts conditioning, or stops the request that's
 * running. The answer is the car's own notice. A warm-pack refusal can be overridden by
 * the same press again within 15 s.
 */
public class ToggleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Precond.TOGGLE.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();   // hmgapid calls block: off the main thread
        new Thread("precond-toggle") {
            @Override
            public void run() {
                try {
                    toggle(app);
                } finally {
                    pending.finish();
                }
            }
        }.start();
    }

    static void toggle(Context c) {
        Precond.Status s = Precond.read();
        if (s.error != null) {
            HmgApi.toast(c, "Precondition: HMG-API didn't answer (" + s.error + ")");
            return;
        }
        if ("stopping".equals(s.state)) {
            HmgApi.toast(c, "Battery conditioning is already stopping");
            return;
        }
        if (s.requestRunning()) {
            try {
                Precond.stop();
                HmgApi.toast(c, "Battery conditioning stopped");
            } catch (Exception e) {
                HmgApi.toast(c, "Couldn't stop battery conditioning: " + Precond.reason(e));
            }
            return;
        }
        if (s.carOnItsOwn()) {
            HmgApi.toast(c, "The car is already conditioning on its own (its navigation started it)");
            return;
        }
        boolean warm = Precond.takeWarmConfirm(c);
        try {
            Precond.start(warm);
            HmgApi.toast(c, "Battery conditioning requested" + (warm ? " (pack is warm: sent anyway)" : "")
                    + ". The car usually confirms within " + (Precond.timeoutMinutes() == 5 ? "5 minutes" : "about a minute"));
            WatchService.watch(c);
        } catch (Exception e) {
            String why = Precond.reason(e);
            if (Precond.warmRefusal(why) && !warm) {
                Precond.warmRefused(c);
                String t = Precond.temperature(s.tempC, Precond.units(Precond.settings()));
                HmgApi.toast(c, "Battery is already warm (" + t + "): the car may not heat it. Press again within 15 s to start anyway");
            } else if (Precond.ownerQuestion(why)) {
                HmgApi.toast(c, "Allow \"conditioning\" for Manual Preconditioning in HMG-API's question, then press again");
            } else {
                HmgApi.toast(c, "Can't start battery conditioning: " + why);
            }
        }
    }
}
