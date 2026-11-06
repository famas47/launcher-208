package be.pa.launcher208;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Petits outils pour construire l'interface en code (plus rapide que les fichiers XML).
 * Toutes les tailles sont celles des maquettes (écran 1280 x 720) et sont mises à l'échelle
 * de l'écran réel, quelle que soit sa densité.
 */
final class U {
    static float k = 1f;
    static int W = 1280, H = 720;
    static final int M = ViewGroup.LayoutParams.MATCH_PARENT, WR = ViewGroup.LayoutParams.WRAP_CONTENT;

    static void init(Context c) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        W = Math.max(dm.widthPixels, dm.heightPixels);
        H = Math.min(dm.widthPixels, dm.heightPixels);
        if (W <= 0 || H <= 0) { W = 1280; H = 720; }
        k = Math.min(W / 1280f, H / 720f);
    }

    static int s(float px) { return Math.round(px * k); }
    static float f(float px) { return px * k; }

    // ---------- Polices ----------
    private static final Typeface[] TF = new Typeface[10];

    static Typeface tf(int weight) {
        int i = Math.max(1, Math.min(9, weight / 100));
        Typeface t = TF[i];
        if (t != null) return t;
        if (Build.VERSION.SDK_INT >= 28) t = Typeface.create(Typeface.SANS_SERIF, i * 100, false);
        else if (i <= 3) t = Typeface.create("sans-serif-light", Typeface.NORMAL);
        else if (i == 4) t = Typeface.SANS_SERIF;
        else if (i <= 6) t = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        else t = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        TF[i] = t;
        return t;
    }

    static TextView tv(Context c, CharSequence txt, float size, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(txt);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, f(size));
        t.setTextColor(color);
        t.setTypeface(tf(weight));
        t.setIncludeFontPadding(false);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    static TextView tvMulti(Context c, CharSequence txt, float size, int color, int weight, int maxLines) {
        TextView t = tv(c, txt, size, color, weight);
        t.setMaxLines(maxLines);
        t.setLineSpacing(f(3), 1f);
        return t;
    }

    static void spacing(TextView t, float px, float size) { t.setLetterSpacing(px / size); }

    static void text(TextView t, CharSequence s) {
        if (!android.text.TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    // ---------- Fonds ----------
    static GradientDrawable rr(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(f(r));
        return g;
    }

    static GradientDrawable rrStroke(int color, float r, int stroke, float w) {
        GradientDrawable g = rr(color, r);
        g.setStroke(s(w), stroke);
        return g;
    }

    static GradientDrawable grad(int a, int b, float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{a, b});
        g.setCornerRadius(f(r));
        return g;
    }

    static GradientDrawable gradDiag(int a, int b, float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        g.setCornerRadius(f(r));
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Fond de carte arrondi + ombre douce le jour. */
    static <V extends View> V card(V v, int color, float r) {
        v.setBackground(rr(color, r));
        shadow(v, 8);
        return v;
    }

    static void shadow(View v, float elev) {
        if (Pal.shadows) {
            v.setElevation(f(elev));
            v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        } else {
            v.setElevation(0);
        }
    }

    /** Effet au toucher (onde) avec les coins arrondis. */
    static void press(View v, float r) {
        GradientDrawable mask = rr(Color.WHITE, r);
        int ripple = Pal.night ? 0x33FFFFFF : 0x22000000;
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(ripple), null, mask));
        v.setClickable(true);
    }

    static void click(View v, float r, final Runnable run) {
        press(v, r);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { run.run(); }
        });
    }

    // ---------- Icônes ----------
    static Drawable dr(Context c, int res, int color) {
        Drawable d = c.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        return d;
    }

    static ImageView icon(Context c, int res, float size, int color) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(dr(c, res, color));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s(size), s(size)));
        return iv;
    }

    /** Pastille avec une icône centrée. */
    static FrameLayout box(Context c, float size, Drawable bg, int res, float iconSize, int iconColor) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(bg);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(dr(c, res, iconColor));
        f.addView(iv, new FrameLayout.LayoutParams(s(iconSize), s(iconSize), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(s(size), s(size)));
        return f;
    }

    // ---------- Mises en page ----------
    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lpW(float weight) { return new LinearLayout.LayoutParams(0, M, weight); }

    static LinearLayout.LayoutParams lpH(float weight) { return new LinearLayout.LayoutParams(M, 0, weight); }

    static LinearLayout.LayoutParams lpm(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(s(l), s(t), s(r), s(b));
        return p;
    }

    static FrameLayout.LayoutParams fl(int w, int h, int gravity) { return new FrameLayout.LayoutParams(w, h, gravity); }

    static FrameLayout.LayoutParams flm(int w, int h, int gravity, float l, float t, float r, float b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(s(l), s(t), s(r), s(b));
        return p;
    }

    static View gap(Context c, float w, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(w < 0 ? M : s(w), h < 0 ? M : s(h)));
        return v;
    }

    static View flex(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    static View flexV(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        return v;
    }

    static View line(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Pal.divider);
        v.setLayoutParams(new LinearLayout.LayoutParams(M, Math.max(1, s(1))));
        return v;
    }

    static void pad(View v, float l, float t, float r, float b) { v.setPadding(s(l), s(t), s(r), s(b)); }
}
