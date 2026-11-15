package be.pa.launcher208;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Dessins de la navigation : flèche de la prochaine manœuvre et panneau de limitation de vitesse. */
final class NavViews {
    private NavViews() { }

    /** Flèche blanche de la prochaine manœuvre (virage, rond-point, demi-tour, arrivée). */
    static final class Arrow extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private String action = "droit";
        private double angle;
        private int exit;

        Arrow(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(0xFFFFFFFF);
            fill.setColor(0xFFFFFFFF);
            fill.setTextAlign(Paint.Align.CENTER);
            fill.setTypeface(U.tf(700));
        }

        void set(String action, double angle, int exit) {
            if (action.equals(this.action) && Math.abs(angle - this.angle) < 1 && exit == this.exit) return;
            this.action = action;
            this.angle = angle;
            this.exit = exit;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            float cx = w / 2, sw = s * 0.11f;
            stroke.setStrokeWidth(sw);
            path.reset();
            if (action.equals("arrivee")) {
                // repère d'arrivée : cercle et pointe
                float r = s * 0.2f, cy = h * 0.4f;
                path.moveTo(cx, h * 0.84f);
                path.lineTo(cx - r * 0.9f, cy + r * 0.45f);
                path.addCircle(cx, cy, r, Path.Direction.CW);
                c.drawPath(path, fill);
                Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
                hole.setColor(0xFF2563EB);
                c.drawCircle(cx, cy, r * 0.42f, hole);
                return;
            }
            if (action.startsWith("rp")) {
                // rond-point : anneau, entrée en bas, sortie vers sa direction, numéro au centre
                float r = s * 0.2f, cy = h * 0.47f;
                c.drawCircle(cx, cy, r, stroke);
                c.drawLine(cx, h * 0.9f, cx, cy + r, stroke);
                double a = Math.toRadians(angle);
                float ex = cx + (float) Math.sin(a) * r, ey = cy - (float) Math.cos(a) * r;
                float tx = cx + (float) Math.sin(a) * r * 2.1f, ty = cy - (float) Math.cos(a) * r * 2.1f;
                c.drawLine(ex, ey, tx, ty, stroke);
                head(c, tx, ty, a, s);
                fill.setTextSize(s * 0.24f);
                c.drawText(String.valueOf(Math.max(1, exit)), cx, cy + s * 0.085f, fill);
                return;
            }
            if (action.equals("demi_tour")) {
                float x1 = cx + s * 0.16f, x2 = cx - s * 0.16f, top = h * 0.32f;
                path.moveTo(x1, h * 0.86f);
                path.lineTo(x1, top);
                path.arcTo(new RectF(x2, top - s * 0.16f, x1, top + s * 0.16f), 0, -180);
                path.lineTo(x2, h * 0.62f);
                c.drawPath(path, stroke);
                head(c, x2, h * 0.66f, Math.PI, s);
                return;
            }
            // virage : tige depuis le bas, puis branche dans la direction du virage
            double a = Math.toRadians(Math.max(-160, Math.min(160, angle)));
            if (action.equals("droit")) a = 0;
            float jx = cx, jy = h * 0.56f;
            float len = s * 0.34f;
            float ex = jx + (float) Math.sin(a) * len, ey = jy - (float) Math.cos(a) * len;
            path.moveTo(cx, h * 0.9f);
            path.lineTo(jx, jy);
            path.lineTo(ex, ey);
            c.drawPath(path, stroke);
            head(c, ex, ey, a, s);
        }

        /** Pointe de flèche à (x, y), dans la direction a (radians, 0 = vers le haut). */
        private void head(Canvas c, float x, float y, double a, float s) {
            float l = s * 0.2f, wd = s * 0.17f;
            float dx = (float) Math.sin(a), dy = -(float) Math.cos(a);
            Path p = new Path();
            p.moveTo(x + dx * l * 0.55f, y + dy * l * 0.55f);
            p.lineTo(x - dx * l * 0.45f - dy * wd, y - dy * l * 0.45f + dx * wd);
            p.lineTo(x - dx * l * 0.45f + dy * wd, y - dy * l * 0.45f - dx * wd);
            p.close();
            c.drawPath(p, fill);
        }
    }

    /** Panneau rond de limitation de vitesse (bord rouge, chiffre noir). */
    static final class Sign extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int kmh;

        Sign(Context c) {
            super(c);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(U.tf(700));
            setContentDescription("Limitation de vitesse");
        }

        void set(int kmh) {
            if (kmh == this.kmh) return;
            this.kmh = kmh;
            setVisibility(kmh > 0 ? VISIBLE : GONE);
            setContentDescription("Limitation : " + kmh + " km/h");
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f, r = s / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFD7262B);
            c.drawCircle(cx, cy, r, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, r * 0.76f, p);
            p.setColor(0xFF111111);
            String t = String.valueOf(kmh);
            p.setTextSize(r * (t.length() >= 3 ? 0.78f : 0.95f));
            c.drawText(t, cx, cy - (p.descent() + p.ascent()) / 2, p);
        }
    }
}
