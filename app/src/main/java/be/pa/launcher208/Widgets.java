package be.pa.launcher208;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Petits composants dessinés à la main, légers et identiques aux maquettes. */
final class Widgets {

    interface IntCb { void on(int v); }
    interface BoolCb { void on(boolean v); }

    /** Interrupteur (comme dans les Réglages). */
    static final class Toggle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean on;
        private float pos;
        private BoolCb cb;

        Toggle(Context c, boolean on, BoolCb cb) {
            super(c);
            this.on = on;
            this.pos = on ? 1f : 0f;
            this.cb = cb;
            setLayoutParams(new LinearLayout.LayoutParams(U.s(56), U.s(32)));
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { set(!Toggle.this.on, true); }
            });
        }

        void set(boolean v, boolean notify) {
            on = v;
            ValueAnimator a = ValueAnimator.ofFloat(pos, v ? 1f : 0f);
            a.setDuration(150);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator va) { pos = (Float) va.getAnimatedValue(); invalidate(); }
            });
            a.start();
            if (notify && cb != null) cb.on(v);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int off = Pal.night ? 0xFF3A4048 : 0xFFC9D0D8;
            p.setColor(blend(off, Pal.BLUE, pos));
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, p);
            float pad = U.f(3), d = h - 2 * pad;
            float x = pad + (w - 2 * pad - d) * pos;
            p.setColor(Pal.WHITE);
            c.drawOval(x, pad, x + d, pad + d, p);
        }
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /** Choix entre plusieurs boutons (ex. Auto / Jour / Nuit). */
    static LinearLayout seg(Context c, String[] items, int sel, final IntCb cb) {
        final LinearLayout l = U.row(c);
        l.setBackground(U.rr(Pal.tabsBg, 13));
        U.pad(l, 4, 4, 4, 4);
        final TextView[] tvs = new TextView[items.length];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = U.tv(c, items[i], 14, Pal.text2, 500);
            t.setGravity(Gravity.CENTER);
            U.pad(t, 13, 0, 13, 0);
            LinearLayout.LayoutParams p = U.lp(U.WR, U.s(38));
            if (i > 0) p.leftMargin = U.s(4);
            l.addView(t, p);
            tvs[i] = t;
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { segSelect(tvs, idx); if (cb != null) cb.on(idx); }
            });
        }
        segSelect(tvs, sel);
        return l;
    }

    static void segSelect(TextView[] tvs, int sel) {
        for (int i = 0; i < tvs.length; i++) {
            TextView t = tvs[i];
            boolean on = i == sel;
            t.setTextColor(on ? Pal.text : Pal.text2);
            t.setTypeface(U.tf(on ? 600 : 500));
            t.setBackground(on ? U.rr(Pal.tabOn, 10) : null);
            if (on) U.shadow(t, 2); else t.setElevation(0);
        }
    }

    /** Barre de progression arrondie. */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        float value; // 0..1
        int color, trackColor;

        Bar(Context c, float value, int color) {
            super(c);
            this.value = value;
            this.color = color;
            this.trackColor = Pal.track;
        }

        void set(float v) {
            float n = Math.max(0f, Math.min(1f, v));
            if (n != value) { value = n; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(trackColor);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, p);
            if (value > 0) {
                p.setColor(color);
                r.set(0, 0, Math.max(h, w * value), h);
                c.drawRoundRect(r, h / 2, h / 2, p);
            }
        }
    }

    /** Curseur de volume : on peut glisser le doigt sur la barre. */
    static final class Slider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        int max = 30, value;
        float barH;
        int fill = Pal.BLUE_LL, track = 0x2EFFFFFF;
        IntCb cb;

        Slider(Context c, float barHeight) {
            super(c);
            barH = U.f(barHeight);
        }

        void set(int v, int mx) {
            max = Math.max(1, mx);
            value = Math.max(0, Math.min(max, v));
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                if (a == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                float knob = U.f(17);
                float t = (e.getX() - knob) / Math.max(1f, getWidth() - 2 * knob);
                int v = Math.round(Math.max(0f, Math.min(1f, t)) * max);
                if (v != value) {
                    value = v;
                    invalidate();
                    if (cb != null) cb.on(v);
                }
                return true;
            }
            return a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL || super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), knob = U.f(17);
            float cy = h / 2f, x0 = knob, x1 = w - knob;
            float x = x0 + (x1 - x0) * value / (float) max;
            p.setColor(track);
            r.set(x0, cy - barH / 2, x1, cy + barH / 2);
            c.drawRoundRect(r, barH / 2, barH / 2, p);
            p.setColor(fill);
            r.set(x0, cy - barH / 2, Math.max(x0 + barH, x), cy + barH / 2);
            c.drawRoundRect(r, barH / 2, barH / 2, p);
            p.setColor(0x73000000);
            c.drawCircle(x, cy + U.f(2), knob, p);
            p.setColor(Pal.WHITE);
            c.drawCircle(x, cy, knob, p);
        }
    }

    /** Fond de la grande carte centrale : dégradé en ellipse comme sur les maquettes. */
    static final class RadialBg extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Matrix m = new Matrix();
        private final float radius, cx, cy, sx, sy;
        private final int a, b, c;

        RadialBg(int a, int b, int c, float radius, float cx, float cy, float sx, float sy) {
            this.a = a; this.b = b; this.c = c;
            this.radius = U.f(radius); this.cx = cx; this.cy = cy; this.sx = sx; this.sy = sy;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            float w = bounds.width(), h = bounds.height();
            RadialGradient g = new RadialGradient(0, 0, 1f, new int[]{a, b, c}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            m.reset();
            m.setScale(w * sx, h * sy);
            m.postTranslate(bounds.left + w * cx, bounds.top + h * cy);
            g.setLocalMatrix(m);
            p.setShader(g);
            r.set(bounds);
        }

        @Override public void draw(Canvas canvas) { canvas.drawRoundRect(r, radius, radius, p); }

        @Override public void getOutline(android.graphics.Outline o) { o.setRoundRect(getBounds(), radius); }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Graphique en barres des 7 derniers jours (Trajets). */
    static final class Chart extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        float[] values = new float[7];
        String[] labels = new String[7];

        Chart(Context c) {
            super(c);
            p.setTypeface(U.tf(500));
        }

        void set(float[] v, String[] l) { values = v; labels = l; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            int n = values.length;
            if (n == 0) return;
            float w = getWidth(), h = getHeight();
            float labelH = U.f(22), valH = U.f(18);
            float max = 1f;
            for (float v : values) max = Math.max(max, v);
            float slot = w / n, bw = Math.min(U.f(26), slot * 0.62f);
            p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2;
                float bh = (h - labelH - valH) * values[i] / max;
                boolean last = i == n - 1;
                p.setColor(last ? Pal.accent : (Pal.night ? 0xFF2F4A7A : 0xFFB9CCF7));
                float top = h - labelH - Math.max(U.f(4), bh);
                r.set(cx - bw / 2, top, cx + bw / 2, h - labelH);
                c.drawRoundRect(r, U.f(5), U.f(5), p);
                p.setColor(Pal.text2);
                p.setTextSize(U.f(12));
                if (labels[i] != null) c.drawText(labels[i], cx, h - U.f(5), p);
                if (values[i] > 0) {
                    p.setTextSize(U.f(11));
                    c.drawText(String.valueOf(Math.round(values[i])), cx, top - U.f(5), p);
                }
            }
        }
    }

    /** Petite flèche de position pour la minimap. */
    static final class Marker extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        float bearing;

        Marker(Context c) { super(c); }

        void setBearing(float b) { if (Math.abs(b - bearing) > 1f) { bearing = b; invalidate(); } }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float R = Math.min(w, h) / 2;
            p.setColor(0x292563EB);
            c.drawCircle(cx, cy, R, p);
            p.setColor(Pal.WHITE);
            c.drawCircle(cx, cy, R * 0.46f, p);
            c.save();
            c.rotate(bearing, cx, cy);
            float s = R * 0.36f;
            path.reset();
            path.moveTo(cx, cy - s);
            path.lineTo(cx + s * 0.7f, cy + s * 0.75f);
            path.lineTo(cx, cy + s * 0.35f);
            path.lineTo(cx - s * 0.7f, cy + s * 0.75f);
            path.close();
            p.setColor(Pal.BLUE);
            c.drawPath(path, p);
            c.restore();
        }
    }
}
