package be.pa.launcher208;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.io.InputStream;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Ta 208 en 3D qui tourne : 72 vues calculées à l'avance (une tous les 5°), aucune 3D en direct.
 * - Glisser le doigt la fait tourner (avec élan), les flèches la tournent de 45°.
 * - Pendant le mouvement : vues en demi-taille gardées en mémoire (fluide) ; à l'arrêt : vue nette.
 * - Une portière ouverte : la voiture tourne toute seule vers elle, puis la portière s'allume.
 */
final class CarView extends View {
    static final int N = 72;            // nombre de vues (tous les 5°)
    /**
     * Vue où chaque ouverture se voit le mieux (choisi sur les 72 rendus) :
     * côté droit 35°, côté gauche 245° (conducteur), arrière 140° (coffre bien de face).
     */
    static final int IDX_RIGHT = 7, IDX_LEFT = 49, IDX_REAR = 28;
    /**
     * Sens de rotation : quand l'angle augmente, le flanc visible part vers la droite (vérifié sur les vues 0, 15, 30).
     * Glisser vers la droite fait donc avancer l'angle : la voiture suit le doigt, la flèche droite aussi.
     */
    static final int DIR = 1;

    private static final AtomicReferenceArray<Bitmap> SMALL = new AtomicReferenceArray<>(N); // partagé entre fils
    private static final HashMap<String, Bitmap> DOORS = new HashMap<>();
    private static final ExecutorService DEC = Executors.newSingleThreadExecutor();      // vues demi-taille
    private static final ExecutorService SHARP = Executors.newSingleThreadExecutor();    // vue nette (jamais en attente derrière les autres)
    private static volatile boolean smallStarted;
    private static Bitmap full;           // vue nette actuellement prête
    private static int fullIdx = -1;
    private static volatile int savedIdx; // garde l'angle quand l'écran est reconstruit (jour/nuit)
    private static int beforeDoor = -1;   // vue d'avant l'ouverture d'une porte (on y revient à la fermeture)

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint doorPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private float pos;                    // position (en vues, peut être décimale pendant le mouvement)
    private boolean moving;
    private ValueAnimator anim, doorAnim;
    private VelocityTracker vt;
    private float lastX;
    private int doorShown;                // porte allumée (0 = aucune)
    private Bitmap doorBmp;
    private float doorAlpha;

    CarView(Context c) {
        super(c);
        if (Pal.carDim < 1f) {
            ColorMatrix m = new ColorMatrix();
            m.setScale(Pal.carDim, Pal.carDim, Pal.carDim, 1f);
            ColorMatrixColorFilter f = new ColorMatrixColorFilter(m);
            paint.setColorFilter(f);
            doorPaint.setColorFilter(f);
        }
        pos = savedIdx;
        // La vue de départ est chargée tout de suite, en pleine taille, pour le premier affichage.
        if (fullIdx != savedIdx || full == null) {
            Bitmap b = decode(c, name(savedIdx), 1);
            if (b != null) { full = b; fullIdx = savedIdx; }
        }
    }

    static String name(int idx) {
        int a = ((idx % N) + N) % N * 5;
        return "v" + (a < 10 ? "00" : a < 100 ? "0" : "") + a;
    }

    private static Bitmap decode(Context c, String name, int sample) {
        InputStream in = null;
        try {
            in = c.getAssets().open("car/" + name + ".webp");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inSampleSize = sample;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    /** Après le premier affichage : les 72 vues en demi-taille sont préparées en arrière-plan (environ 23 Mo). */
    static void preload(Context c) {
        if (smallStarted) return;
        smallStarted = true;
        final Context app = c.getApplicationContext();
        DEC.execute(new Runnable() {
            @Override public void run() {
                // d'abord les vues proches de la vue de départ, puis de plus en plus loin
                for (int d = 0; d <= N / 2; d++) {
                    for (int s : new int[]{d, -d}) {
                        int i = ((savedIdx + s) % N + N) % N;
                        if (SMALL.get(i) == null) SMALL.set(i, decode(app, name(i), 2));
                        if (d == 0) break;
                    }
                }
            }
        });
    }

    /**
     * L'autoradio manque de mémoire pendant que CarPlay est devant : on rend les vues demi-taille
     * (la vue nette reste, l'accueil s'affiche donc toujours tout de suite ; le reste se recharge au retour).
     */
    static void trim() {
        for (int i = 0; i < N; i++) SMALL.set(i, null);
        DOORS.clear();
        smallStarted = false;
    }

    private int idx() { return ((Math.round(pos) % N) + N) % N; }

    // ---------- Rotation ----------
    /** Flèches : un huitième de tour. */
    void turn(int dir) {
        if (doorShown != 0) return;
        clearDoorOverlay();
        beforeDoor = -1;
        animateTo(Math.round(pos) + DIR * dir * 9, 450, null);
    }

    private void animateTo(float target, long ms, final Runnable end) {
        stopAnim();
        moving = true;
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(P.fluidMax() ? ms : (long) (ms * 0.7f));
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                settle();
                if (end != null) end.run();
            }
        });
        anim.start();
    }

    private void stopAnim() {
        if (anim != null) { anim.cancel(); anim = null; }
    }

    /** Fin de mouvement : position ronde et vue nette. */
    private void settle() {
        pos = Math.round(pos);
        moving = false;
        savedIdx = idx();
        requestFull(savedIdx);
        invalidate();
    }

    private void requestFull(final int i) {
        if (fullIdx == i && full != null) return;
        final Context app = getContext().getApplicationContext();
        SHARP.execute(new Runnable() {
            @Override public void run() {
                if (savedIdx != i) return; // déjà repartie ailleurs : inutile
                final Bitmap b = decode(app, name(i), 1);
                post(new Runnable() {
                    @Override public void run() {
                        if (b != null && savedIdx == i) {
                            full = b;
                            fullIdx = i;
                            invalidate();
                        }
                    }
                });
            }
        });
    }

    // ---------- Portières ----------
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

    static int doorIdx(int door) {
        if (door == Doors.FL || door == Doors.RL) return IDX_LEFT;
        if (door == Doors.TRUNK) return IDX_REAR;
        return IDX_RIGHT;
    }

    /** Nombre de vues à parcourir par le chemin le plus court (négatif = dans l'autre sens). */
    static int shortest(int from, int to) {
        int diff = ((to - from) % N + N) % N;
        return diff > N / 2 ? diff - N : diff;
    }

    private static long turnMs(int diff) { return Math.max(250, Math.min(900, Math.abs(diff) * 22L)); }

    void showDoor(final int door) {
        if (door == doorShown) return;
        if (door != 0 && beforeDoor < 0) beforeDoor = idx();
        doorShown = door;
        if (door == 0) {
            // la porte se ferme : l'éclairage s'éteint, puis la voiture revient là où elle était
            fadeDoor(0f, new Runnable() {
                @Override public void run() {
                    doorBmp = null;
                    if (doorShown != 0 || beforeDoor < 0) return;
                    int back = beforeDoor;
                    beforeDoor = -1;
                    int cur = Math.round(pos), diff = shortest(cur, back);
                    if (diff != 0) animateTo(cur + diff, turnMs(diff), null);
                }
            });
            return;
        }
        final String img = doorImage(door);
        Bitmap b = DOORS.get(img);
        if (b == null) {
            b = decode(getContext(), img, 1);
            if (b != null) DOORS.put(img, b);
        }
        final Bitmap bmp = b;
        clearDoorOverlay();
        int cur = Math.round(pos), diff = shortest(cur, doorIdx(door));
        Runnable lightUp = new Runnable() {
            @Override public void run() {
                if (doorShown != door) return;
                doorBmp = bmp;
                fadeDoor(1f, null);
            }
        };
        if (diff == 0) { settle(); lightUp.run(); }
        else animateTo(cur + diff, turnMs(diff), lightUp);
    }

    /** Efface tout de suite l'éclairage de porte (avant de faire tourner la voiture). */
    private void clearDoorOverlay() {
        if (doorAnim != null) { doorAnim.cancel(); doorAnim = null; }
        doorAlpha = 0f;
        doorBmp = null;
    }

    private void fadeDoor(float to, final Runnable end) {
        if (doorAnim != null) doorAnim.cancel();
        doorAnim = ValueAnimator.ofFloat(doorAlpha, to);
        doorAnim.setDuration(P.fluidMax() ? 300 : 200);
        doorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                doorAlpha = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        if (end != null) {
            doorAnim.addListener(new AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(Animator a) { if (!cancelled) end.run(); }
            });
        }
        doorAnim.start();
    }

    // ---------- Doigt ----------
    private float pxPerView() { return Math.max(1f, getWidth() / 36f); } // largeur de la voiture = un demi-tour

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (doorShown != 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopAnim();
                clearDoorOverlay();
                beforeDoor = -1; // l'utilisateur choisit lui-même la vue
                moving = true;
                lastX = e.getX();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                pos += DIR * (e.getX() - lastX) / pxPerView();
                lastX = e.getX();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float v = 0;
                if (vt != null) {
                    vt.addMovement(e);
                    vt.computeCurrentVelocity(1000);
                    v = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                }
                float extra = DIR * v / pxPerView() * 0.25f;          // élan
                extra = Math.max(-N, Math.min(N, extra));
                if (Math.abs(extra) < 0.6f) settle();
                else flingTo(Math.round(pos + extra), (long) Math.min(1200, 250 + Math.abs(extra) * 25));
                return true;
            default:
                return true;
        }
    }

    private void flingTo(float target, long ms) {
        stopAnim();
        moving = true;
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) { if (!cancelled) settle(); }
        });
        anim.start();
    }

    // ---------- Dessin ----------
    private Bitmap frame(int i) {
        if (!moving && fullIdx == i && full != null) return full;
        Bitmap s = SMALL.get(i);
        if (s != null) return s;
        // vue demi-taille pas encore prête : la plus proche disponible
        for (int d = 1; d < N / 2; d++) {
            Bitmap a = SMALL.get((i + d) % N);
            if (a != null) return a;
            Bitmap b = SMALL.get((i - d + N) % N);
            if (b != null) return b;
        }
        return full;
    }

    @Override protected void onDraw(Canvas c) {
        dst.set(0, 0, getWidth(), getHeight());
        Bitmap b = frame(idx());
        if (b != null) c.drawBitmap(b, null, dst, paint);
        if (doorBmp != null && doorAlpha > 0.01f) {
            doorPaint.setAlpha((int) (255 * doorAlpha));
            c.drawBitmap(doorBmp, null, dst, doorPaint);
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopAnim();
        if (doorAnim != null) doorAnim.cancel();
        if (vt != null) { vt.recycle(); vt = null; }
        super.onDetachedFromWindow();
    }
}
