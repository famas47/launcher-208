package be.pa.launcher208;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Plan de la voiture vu du dessus : les ouvertures sont en orange. */
final class TopView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int mask;

    TopView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
    }

    void set(int m) { if (m != mask) { mask = m; invalidate(); } }

    @Override protected void onDraw(Canvas c) {
        float sx = getWidth() / 96f, sy = getHeight() / 176f, s = Math.min(sx, sy);
        c.save();
        c.translate((getWidth() - 96 * s) / 2f, (getHeight() - 176 * s) / 2f);
        c.scale(s, s);
        line.setStrokeWidth(2f);
        int body = Pal.night ? 0xFF23272D : 0xFFEEF1F4, edge = Pal.night ? 0xFF4B5563 : 0xFFB9C1CA;
        int glass1 = Pal.night ? 0xFF3A4048 : 0xFFC9D0D8, glass2 = Pal.night ? 0xFF2F343B : 0xFFDDE2E7;
        rect(c, 18, 4, 60, 168, 26, body, edge);
        rect(c, 25, 44, 46, 18, 6, glass1, 0);
        rect(c, 25, 66, 46, 62, 5, glass2, 0);
        rect(c, 25, 132, 46, 12, 5, glass1, 0);
        rect(c, 7, 52, 6, 9, 2, edge, 0);
        rect(c, 83, 52, 6, 9, 2, edge, 0);
        door(c, 10, 64, 11, 34, Doors.FL);
        door(c, 10, 101, 11, 30, Doors.RL);
        door(c, 75, 64, 11, 34, Doors.FR);
        door(c, 75, 101, 11, 30, Doors.RR);
        door(c, 27, 148, 42, 18, Doors.TRUNK);
        c.restore();
    }

    private void door(Canvas c, float x, float y, float w, float h, int d) {
        boolean open = (mask & d) != 0;
        int closedFill = Pal.night ? 0xFF16181C : 0xFFFFFFFF, closedEdge = Pal.night ? 0xFF4B5563 : 0xFFB9C1CA;
        rect(c, x, y, w, h, d == Doors.TRUNK ? 6 : 3, open ? Pal.ORANGE : closedFill, open ? Pal.ORANGE_D : closedEdge);
    }

    private void rect(Canvas c, float x, float y, float w, float h, float rad, int f, int stroke) {
        r.set(x, y, x + w, y + h);
        fill.setColor(f);
        c.drawRoundRect(r, rad, rad, fill);
        if (stroke != 0) {
            line.setColor(stroke);
            c.drawRoundRect(r, rad, rad, line);
        }
    }
}
