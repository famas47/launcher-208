package be.pa.launcher208;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.io.InputStream;
import java.util.HashMap;

/**
 * Ta 208 en 3D (images calculées à l'avance : aucune 3D en direct, donc très léger).
 * Glisser le doigt change de vue ; une portière ouverte affiche la vue où elle est allumée.
 */
final class CarView extends View {
    static final String[] VIEWS = {"droite", "gauche"};
    private static final HashMap<String, Bitmap> CACHE = new HashMap<>();

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private Bitmap cur, prev;
    private float fade = 1f;
    private int viewIdx;
    private String forced; // image de portière
    private float downX;
    Runnable onUserTurn;

    CarView(Context c) {
        super(c);
        if (Pal.carDim < 1f) {
            ColorMatrix m = new ColorMatrix();
            m.setScale(Pal.carDim, Pal.carDim, Pal.carDim, 1f);
            ColorMatrixColorFilter f = new ColorMatrixColorFilter(m);
            paint.setColorFilter(f);
            fadePaint.setColorFilter(f);
        }
        cur = load(c, VIEWS[0]);
    }

    /** Charge une image (gardée en mémoire ensuite). */
    static synchronized Bitmap load(Context c, String name) {
        Bitmap b = CACHE.get(name);
        if (b != null) return b;
        InputStream in = null;
        try {
            in = c.getAssets().open("car/" + name + ".webp");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeStream(in, null, o);
            if (b != null) CACHE.put(name, b);
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
        return b;
    }

    /** Après le premier affichage : on prépare les autres vues en arrière-plan. */
    static void preload(final Context c) {
        final Context app = c.getApplicationContext();
        App.I.bg.execute(new Runnable() {
            @Override public void run() {
                for (String v : VIEWS) load(app, v);
                for (String v : new String[]{"porte-fl", "porte-rl", "porte-fr", "porte-rr", "coffre"}) load(app, v);
            }
        });
    }

    static String doorImage(int door) {
        switch (door) {
            case Doors.FL: return "porte-fl";
            case Doors.RL: return "porte-rl";
            case Doors.FR: return "porte-fr";
            case Doors.RR: return "porte-rr";
            case Doors.TRUNK: return "coffre";
            default: return null;
        }
    }

    void showDoor(int door) {
        String img = doorImage(door);
        if (img == null ? forced == null : img.equals(forced)) return;
        forced = img;
        swap(load(getContext(), img != null ? img : VIEWS[viewIdx]));
    }

    void turn(int dir) {
        if (forced != null) return;
        viewIdx = (viewIdx + dir + VIEWS.length) % VIEWS.length;
        swap(load(getContext(), VIEWS[viewIdx]));
        if (onUserTurn != null) onUserTurn.run();
    }

    private void swap(Bitmap b) {
        if (b == null || b == cur) return;
        prev = cur;
        cur = b;
        fade = 0f;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(P.fluidMax() ? 350 : 200);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                fade = (Float) a.getAnimatedValue();
                if (fade >= 1f) prev = null;
                invalidate();
            }
        });
        va.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX;
                if (Math.abs(dx) > U.f(40)) turn(dx < 0 ? 1 : -1);
                return true;
            default:
                return true;
        }
    }

    @Override protected void onDraw(Canvas c) {
        dst.set(0, 0, getWidth(), getHeight());
        if (prev != null && fade < 1f) {
            fadePaint.setAlpha((int) (255 * (1f - fade)));
            c.drawBitmap(prev, null, dst, fadePaint);
            paint.setAlpha((int) (255 * fade));
        } else {
            paint.setAlpha(255);
        }
        if (cur != null) c.drawBitmap(cur, null, dst, paint);
    }
}
