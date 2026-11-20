package dev.manualprecond;

import android.app.Activity;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
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
 * Start / Stop. Built in code, on a FitFrame canvas scaled to the window. Readings refresh
 * every 2 s while it's on screen; every hmgapid call runs on one background thread.
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

    /**
     * The car draws its split-screen panel over the right of the screen without resizing
     * this window (Launchpad's SplitScreen: Settings.System split_screen_status is 1 while
     * the panel is open; it covers about 29% of the width, so a third is kept clear), and
     * the panel's handle sits on the right edge even when it's closed (Launchpad keeps 64 dp).
     */
    static final String SPLIT_SETTING = "split_screen_status";
    static final int HANDLE_DP = 64;
    private FitFrame fit;
    private boolean splitObserved;
    private final ContentObserver splitChanges = new ContentObserver(ui) {
        @Override
        public void onChange(boolean selfChange) {
            applySplit();
        }
    };

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
        try {
            getContentResolver().registerContentObserver(Settings.System.getUriFor(SPLIT_SETTING), false, splitChanges);
            splitObserved = true;
        } catch (SecurityException e) {
            splitObserved = false;
        }
        applySplit();
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
        if (splitObserved) {
            getContentResolver().unregisterContentObserver(splitChanges);
            splitObserved = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    /** pads the screen clear of the split-screen panel (when it's open) and its handle */
    private void applySplit() {
        int split = 0;
        try {
            split = Settings.System.getInt(getContentResolver(), SPLIT_SETTING, 0);
        } catch (Exception e) {
            split = 0;
        }
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int right = split == 1 ? Math.round(m.widthPixels / 3f) : Math.round(HANDLE_DP * m.density);
        if (fit.getPaddingRight() != right) fit.setPadding(0, 0, right, 0);
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
        ready.setText(s.ready < 0 ? "–" : s.ready == Precond.READY_ON ? "Yes" : "No: switch to READY");
        setting.setText(s.setting < 0 ? "–" : s.setting == Precond.SETTING_ON ? "On" : "Off: turn it on in EV settings");
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

    // ---- layout. Sizes are FitFrame canvas pixels (the canvas is 520 tall), not dp or sp:
    // FitFrame scales the whole screen to the window, so the head unit's density and font
    // scale can't make it overflow. Text has line limits, so nothing grows past its room.

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(40, 28, 40, 28);

        LinearLayout left = column();
        left.addView(label("Battery conditioning", 22, GREY, false, 1));
        state = label("…", 36, TEXT, true, 2);
        left.addView(state);
        message = label("", 20, GREY, false, 3);
        message.setPadding(0, 10, 0, 0);
        left.addView(message);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, 18, 0, 0);
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
        // the two buttons share the column's width: they can't run into the panel beside it
        buttons.addView(start, new LinearLayout.LayoutParams(0, 80, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, 80, 1f);
        gap.leftMargin = 16;
        buttons.addView(stop, gap);
        left.addView(buttons, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        hint = label("", 17, GREY, false, 3);
        hint.setPadding(0, 16, 0, 0);
        left.addView(hint);

        LinearLayout right = column();
        right.setBackground(round(CARD, 16));
        right.setPadding(28, 16, 28, 8);
        temp = row(right, "Battery temperature (coldest)");
        ready = row(right, "Car READY");
        setting = row(right, "Battery Conditioning (EV settings)");
        indicator = row(right, "Conditioning indicator");
        soc = row(right, "State of charge");

        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = 36;
        rp.gravity = Gravity.CENTER_VERTICAL;
        root.addView(right, rp);

        fit = new FitFrame(this);
        fit.setBackgroundColor(BG);
        fit.addView(root);
        return fit;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView row(LinearLayout parent, String name) {
        parent.addView(label(name, 16, GREY, false, 1));
        TextView v = label("–", 22, TEXT, false, 1);
        v.setPadding(0, 0, 0, 8);
        parent.addView(v);
        return v;
    }

    /** px: FitFrame canvas pixels; lines: the most it may take (it ends in … past that) */
    private TextView label(String text, int px, int color, boolean bold, int lines) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(px * 0.2f, 1f);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28);
        b.setTextColor(Color.WHITE);
        b.setSingleLine(true);
        b.setPadding(8, 0, 8, 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = round(color, 14);
        focused.setStroke(4, Color.WHITE);   // focus (the rotary controller)
        bg.addState(new int[] { -android.R.attr.state_enabled }, round(0xff2a323b, 14));
        bg.addState(new int[] { android.R.attr.state_pressed }, round(darker(color), 14));
        bg.addState(new int[] { android.R.attr.state_focused }, focused);
        bg.addState(new int[] {}, round(color, 14));
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
}
