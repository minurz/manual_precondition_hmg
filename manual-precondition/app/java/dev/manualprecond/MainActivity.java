package dev.manualprecond;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Precondition screen: HMG-API's conditioning state, the checks a start needs, and
 * Start / Stop. Built in code (the head unit's 1920x720 screen, the TUNE knob moves the
 * focus between the buttons). Readings refresh every 2 s while it's on screen; every
 * hmgapid call runs on one background thread.
 */
public class MainActivity extends Activity {
    static final long REFRESH_MS = 2000;
    static final int BG = 0xff0e1216, CARD = 0xff1a2027, TEXT = 0xffe8edf2, GREY = 0xff8a96a3,
            GREEN = 0xff3ddc97, AMBER = 0xfff2b84b, RED = 0xffff6b6b, CYAN = 0xff32b6ba;

    private final Handler ui = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean shown, reading;   // UI thread only
    private TextView state, temp, ready, setting, indicator, soc, message, hint;
    private Button start, stop;
    private boolean startWarm;   // the last start was refused for a warm pack: Start sends "warm"
    private String units = "both";
    private Precond.Status last;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!shown) return;
            refresh();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final String u = Precond.units(Precond.settings());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        units = u;
                    }
                });
            }
        });
        ui.removeCallbacks(tick);
        ui.post(tick);
        start.requestFocus();
    }

    @Override
    protected void onPause() {
        shown = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        if (reading) return;   // hmgapid can take up to 10 s: one read at a time, never a queue of them
        reading = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final Precond.Status s = Precond.read();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        reading = false;
                        show(s);
                    }
                });
            }
        });
    }

    private void show(Precond.Status s) {
        last = s;
        if (s.error != null) {
            state.setText("HMG-API isn't answering");
            state.setTextColor(RED);
            hint.setText(s.error);
            start.setEnabled(false);
            stop.setEnabled(false);
            return;
        }
        state.setText(s.carOnItsOwn() ? "Conditioning (started by the car)" : Precond.stateText(s.state));
        state.setTextColor(color(s));
        temp.setText(Precond.temperature(s.tempC, units));
        ready.setText(s.ready < 0 ? "–" : s.ready == Precond.READY_ON ? "Yes" : "No: switch the car to READY");
        setting.setText(s.setting < 0 ? "–" : s.setting == Precond.SETTING_ON ? "On"
                : "Off: turn on Battery Conditioning in the car's EV settings");
        indicator.setText(s.indicator < 0 ? "–" : s.indicator == 1 ? "On" : "Off");
        soc.setText("-".equals(s.soc) ? "–" : s.soc + " %");

        boolean running = s.requestRunning(), stopping = "stopping".equals(s.state);
        start.setEnabled(!running && !stopping && !s.carOnItsOwn());
        stop.setEnabled(running);
        start.setText(startWarm ? "Start anyway" : "Start");
        if ("waiting".equals(s.state)) {
            hint.setText("The car usually confirms within " + (Precond.timeoutMinutes() == 5 ? "5 minutes" : "about a minute")
                    + ". HMG-API stops the request if it hasn't after " + Precond.timeoutMinutes() + " minutes.");
        } else if ("active".equals(s.state)) {
            hint.setText("Conditioning runs until you stop it, the car ends it, or the drive ends.");
        } else if ("timed_out".equals(s.state)) {
            hint.setText("HMG-API sent the stop. A pack that's already warm, or the car's own settings, can keep it from starting.");
        } else if (s.carOnItsOwn()) {
            hint.setText("The car's navigation is conditioning the battery; Stop here can't end it.");
        } else {
            hint.setText("Uses the route values the car's own navigation sends for a charger 9 km / 18 min away.");
        }
    }

    private int color(Precond.Status s) {
        if ("active".equals(s.state) || s.carOnItsOwn()) return GREEN;
        if ("waiting".equals(s.state) || "requested".equals(s.state) || "stopping".equals(s.state)) return AMBER;
        if ("timed_out".equals(s.state)) return RED;
        return TEXT;
    }

    private void startConditioning() {
        final boolean warm = startWarm;
        start.setEnabled(false);
        say(warm ? "Starting (pack is warm: sending anyway)…" : "Starting…", GREY);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String ok = null, why = null;
                try {
                    ok = Precond.start(warm);
                } catch (Exception e) {
                    why = Precond.reason(e);
                }
                final String fOk = ok, fWhy = why;
                if (fOk != null) WatchService.watch(MainActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        startWarm = false;
                        if (fOk != null) {
                            say(fOk.startsWith("ok already") ? "Already conditioning" : "Requested", GREEN);
                        } else if (Precond.warmRefusal(fWhy)) {
                            startWarm = true;
                            say("The battery is already warm (" + Precond.temperature(last == null ? null : last.tempC, units)
                                    + "), so the car may not heat it. Tap Start anyway to send the request regardless.", AMBER);
                        } else if (Precond.ownerQuestion(fWhy)) {
                            say("HMG-API is asking whether Manual Preconditioning may use \"conditioning\": allow it, then tap Start again.", AMBER);
                        } else {
                            say("Not started: " + fWhy, RED);
                        }
                        refresh();
                    }
                });
            }
        });
    }

    private void stopConditioning() {
        stop.setEnabled(false);
        say("Stopping…", GREY);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String r;
                int c;
                try {
                    Precond.stop();
                    r = "Stopped";
                    c = TEXT;
                } catch (Exception e) {
                    r = "Couldn't stop: " + Precond.reason(e);
                    c = RED;
                }
                final String fr = r;
                final int fc = c;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        startWarm = false;
                        say(fr, fc);
                        refresh();
                    }
                });
            }
        });
    }

    private void say(String text, int color) {
        message.setText(text);
        message.setTextColor(color);
    }

    // ---- layout

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(32), dp(48), dp(32));

        LinearLayout left = column();
        left.addView(label("Battery conditioning", 24, GREY, false));
        state = label("…", 40, TEXT, true);
        left.addView(state);
        message = label("", 22, GREY, false);
        message.setPadding(0, dp(12), 0, 0);
        left.addView(message);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(28), 0, 0);
        start = button("Start", CYAN);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startConditioning();
            }
        });
        stop = button("Stop", 0xff5a6672);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopConditioning();
            }
        });
        buttons.addView(start, new LinearLayout.LayoutParams(dp(300), dp(96)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(300), dp(96));
        gap.leftMargin = dp(24);
        buttons.addView(stop, gap);
        left.addView(buttons);

        hint = label("", 18, GREY, false);
        hint.setPadding(0, dp(24), 0, 0);
        left.addView(hint);

        LinearLayout right = column();
        right.setBackground(round(CARD, dp(16)));
        right.setPadding(dp(32), dp(24), dp(32), dp(24));
        temp = row(right, "Battery temperature (coldest)");
        ready = row(right, "Car READY");
        setting = row(right, "Battery Conditioning (EV settings)");
        indicator = row(right, "Conditioning indicator");
        soc = row(right, "State of charge");

        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = dp(40);
        rp.gravity = Gravity.CENTER_VERTICAL;
        root.addView(right, rp);
        return root;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView row(LinearLayout parent, String name) {
        parent.addView(label(name, 18, GREY, false));
        TextView v = label("–", 26, TEXT, false);
        v.setPadding(0, 0, 0, dp(14));
        parent.addView(v);
        return v;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        b.setTextColor(Color.WHITE);
        b.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = round(color, dp(14));
        focused.setStroke(dp(4), Color.WHITE);   // the TUNE knob's focus
        bg.addState(new int[] { -android.R.attr.state_enabled }, round(0xff2a323b, dp(14)));
        bg.addState(new int[] { android.R.attr.state_pressed }, round(darker(color), dp(14)));
        bg.addState(new int[] { android.R.attr.state_focused }, focused);
        bg.addState(new int[] {}, round(color, dp(14)));
        b.setBackground(bg);
        return b;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static int darker(int c) {
        return Color.rgb(Color.red(c) * 3 / 4, Color.green(c) * 3 / 4, Color.blue(c) * 3 / 4);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
