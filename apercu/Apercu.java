import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.labels.TileBasedLabelStore;
import org.mapsforge.map.layer.renderer.DatabaseRenderer;
import org.mapsforge.map.layer.renderer.RendererJob;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.ExternalRenderTheme;
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture;

/**
 * Aperçus de la minimap (hors appli) : dessine une zone de la carte de Belgique avec un thème donné,
 * exactement avec le moteur de l'appli (Mapsforge).
 * Usage : java Apercu carte.map theme.xml lat lon zoom largeur hauteur sortie.png
 */
public class Apercu {
    public static void main(String[] a) throws Exception {
        File map = new File(a[0]);
        File theme = new File(a[1]);
        double lat = Double.parseDouble(a[2]), lon = Double.parseDouble(a[3]);
        byte zoom = Byte.parseByte(a[4]);
        int w = Integer.parseInt(a[5]), h = Integer.parseInt(a[6]);
        File out = new File(a[7]);

        GraphicFactory gf = AwtGraphicFactory.INSTANCE;
        DisplayModel dm = new DisplayModel();
        int ts = dm.getTileSize();
        MapFile mf = new MapFile(map, "fr");
        InMemoryTileCache cache = new InMemoryTileCache(256);
        TileBasedLabelStore labels = new TileBasedLabelStore(256);
        DatabaseRenderer r = new DatabaseRenderer(mf, gf, cache, labels, true, true, null);
        RenderThemeFuture rtf = new RenderThemeFuture(gf, new ExternalRenderTheme(theme), dm);
        rtf.run();

        long size = MercatorProjection.getMapSize(zoom, ts);
        double cx = MercatorProjection.longitudeToPixelX(lon, size);
        double cy = MercatorProjection.latitudeToPixelY(lat, size);
        int left = (int) Math.round(cx - w / 2.0), top = (int) Math.round(cy - h / 2.0);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int ty = Math.floorDiv(top, ts); ty <= Math.floorDiv(top + h - 1, ts); ty++) {
            for (int tx = Math.floorDiv(left, ts); tx <= Math.floorDiv(left + w - 1, ts); tx++) {
                Tile t = new Tile(tx, ty, zoom, ts);
                RendererJob job = new RendererJob(t, mf, rtf, dm, 1.0f, false, false);
                TileBitmap tb = r.executeJob(job);
                BufferedImage ti = AwtGraphicFactory.getBitmap(tb);
                g.drawImage(ti, tx * ts - left, ty * ts - top, null);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("ok " + out + " (" + w + "x" + h + ", zoom " + zoom + ", tuiles de " + ts + " px)");
        mf.close();
        System.exit(0);
    }
}
