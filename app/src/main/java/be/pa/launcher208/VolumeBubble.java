package be.pa.launcher208;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Bulle du volume par-dessus CarPlay : discrète, transparente, déplaçable au doigt.
 * Un toucher l'ouvre en grand (−, barre, +), elle se referme seule après 3 secondes.
 */
final class VolumeBubble {
    private final Context c;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private BubbleView bubble;
    private WindowManager.LayoutParams bp;
    private LinearLayout panel;
    private WindowManager.LayoutParams pp;
    private Widgets.Slider slider;
    private TextView value;
    private boolean bubbleShown, panelShown, wanted;
    private final int size;

    private final Runnable volListener = new Runnable() {
        @Override public void run() { refresh(); }
    };

    VolumeBubble(Context c) {
        this.c = c;
        this.wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        this.size = U.s(60);
    }

    private static int type() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void build() {
        if (bubble != null) return;
        bubble = new BubbleView(c);
        bp = new WindowManager.LayoutParams(size, size, type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bp.gravity = Gravity.TOP | Gravity.LEFT;
        bp.setTitle("Bulle volume 208");
        placeFromPrefs();
        bubble.setOnTouchListener(new Drag());
        App.I.vol.add(volListener);
    }

    private void placeFromPrefs() {
        int x = P.i(P.BUBBLE_X, -1), y = P.i(P.BUBBLE_Y, -1);
        if (x < 0 || y < 0) {
            x = U.W - U.s(14) - size;
            y = defaultY();
        }
        bp.x = clamp(x, 0, U.W - size);
        bp.y = clamp(y, 0, U.H - size);
    }

    void show() {
        wanted = true;
        if (panelShown) return;
        build();
        if (!bubbleShown) {
            try {
                wm.addView(bubble, bp);
                bubbleShown = true;
                refresh();
            } catch (Exception ignored) { }
        }
    }

    void hide() {
        wanted = false;
        closePanel(false);
        if (bubbleShown) {
            try { wm.removeView(bubble); } catch (Exception ignored) { }
            bubbleShown = false;
        }
    }

    void resetPosition() {
        P.put(P.BUBBLE_X, -1);
        P.put(P.BUBBLE_Y, -1);
        if (bp == null) return;
        bp.x = U.W - U.s(14) - size;
        bp.y = defaultY();
        if (bubbleShown) try { wm.updateViewLayout(bubble, bp); } catch (Exception ignored) { }
    }

    void refresh() {
        int v = App.I.vol.get(), mx = App.I.vol.max();
        if (bubble != null) bubble.setLevel(v / (float) mx);
        if (slider != null) slider.set(v, mx);
        if (value != null) value.setText(String.valueOf(v));
    }

    /** En haut à droite : l'autoradio a déjà son propre bouton rond flottant à droite, au milieu. */
    private int defaultY() { return U.H * 22 / 100; }

    private static int clamp(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }

    // ---------- Grand volume (ouvert) ----------
    private void openPanel() {
        buildPanel();
        refresh();
        int pw = panelWidth(), ph = U.s(112);
        int bubbleCx = bp.x + size / 2;
        pp.x = bubbleCx > U.W / 2 ? clamp(bp.x + size - pw, 0, U.W - pw) : clamp(bp.x, 0, U.W - pw);
        pp.y = clamp(bp.y + size / 2 - ph / 2, 0, U.H - ph);
        try {
            if (bubbleShown) { wm.removeView(bubble); bubbleShown = false; }
            wm.addView(panel, pp);
            panelShown = true;
        } catch (Exception ignored) { }
        armClose();
    }

    private void closePanel(boolean backToBubble) {
        h.removeCallbacks(autoClose);
        if (panelShown) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panelShown = false;
        }
        if (backToBubble && wanted) show();
    }

    private final Runnable autoClose = new Runnable() {
        @Override public void run() { closePanel(true); }
    };

    private void armClose() {
        h.removeCallbacks(autoClose);
        h.postDelayed(autoClose, 3000);
    }

    private int panelWidth() { return U.s(10 + 92 + 20 + 420 + 20 + 48 + 20 + 92 + 10); }

    private void buildPanel() {
        if (panel != null) return;
        panel = U.row(c);
        panel.setBackground(U.rrStroke(0xE61C1F24, 56, 0x24FFFFFF, 1));
        U.pad(panel, 10, 0, 10, 0);
        panel.addView(roundBtn(R.drawable.ic_minus, -1), U.lp(U.s(92), U.s(92)));
        panel.addView(U.gap(c, 20, 1));
        slider = new Widgets.Slider(c, 16);
        slider.cb = new Widgets.IntCb() {
            @Override public void on(int v) {
                App.I.vol.set(v);
                armClose();
            }
        };
        panel.addView(slider, U.lp(U.s(420), U.s(92)));
        panel.addView(U.gap(c, 20, 1));
        value = U.tv(c, "0", 30, 0xFFFFFFFF, 700);
        value.setGravity(Gravity.CENTER);
        panel.addView(value, U.lp(U.s(48), U.WR));
        panel.addView(U.gap(c, 20, 1));
        panel.addView(roundBtn(R.drawable.ic_plus, 1), U.lp(U.s(92), U.s(92)));
        pp = new WindowManager.LayoutParams(panelWidth(), U.s(112), type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        pp.gravity = Gravity.TOP | Gravity.LEFT;
        pp.setTitle("Panneau volume 208");
        panel.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { closePanel(true); return true; }
                return false;
            }
        });
    }

    private View roundBtn(int icon, final int step) {
        FrameLayout f = U.box(c, 92, U.oval(0x1FFFFFFF), icon, 36, 0xFFFFFFFF);
        U.press(f, 46);
        f.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                App.I.vol.step(step);
                armClose();
            }
        });
        return f;
    }

    // ---------- Déplacement au doigt ----------
    private final class Drag implements View.OnTouchListener {
        float downX, downY;
        int startX, startY;
        boolean moved;

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = bp.x;
                    startY = bp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > U.f(10)) moved = true;
                    if (moved) {
                        bp.x = clamp(startX + (int) dx, 0, U.W - size);
                        bp.y = clamp(startY + (int) dy, 0, U.H - size);
                        try { wm.updateViewLayout(bubble, bp); } catch (Exception ignored) { }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) {
                        P.put(P.BUBBLE_X, bp.x);
                        P.put(P.BUBBLE_Y, bp.y);
                    } else {
                        openPanel();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    /** La petite bulle : fond transparent, haut-parleur, anneau bleu = niveau du volume. */
    private static final class BubbleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Drawable speaker;
        private float level;

        BubbleView(Context c) {
            super(c);
            speaker = U.dr(c, R.drawable.ic_speaker, 0xF2FFFFFF);
        }

        void setLevel(float l) {
            if (Math.abs(l - level) > 0.001f) { level = l; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2, cy = getHeight() / 2f;
            float R = w / 2 - U.f(1);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x4D14161A);
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, U.f(1)));
            p.setColor(0x47FFFFFF);
            c.drawCircle(cx, cy, R, p);
            float ring = U.f(26);
            p.setStrokeWidth(U.f(3));
            p.setColor(0x38FFFFFF);
            c.drawCircle(cx, cy, ring, p);
            p.setColor(0xE66EA2FF);
            p.setStrokeCap(Paint.Cap.ROUND);
            r.set(cx - ring, cy - ring, cx + ring, cy + ring);
            if (level > 0) c.drawArc(r, -90, 360 * level, false, p);
            if (speaker != null) {
                int s = U.s(24);
                speaker.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
                speaker.draw(c);
            }
        }
    }
}
