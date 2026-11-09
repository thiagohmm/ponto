import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * The PWA icons: polished clock mark, same drawing as web/public/icon.svg, in the
 * theme colours (ink background, paper dial, stamp-red hand).
 * iOS needs real PNGs, so they are generated once and committed; this file is
 * the source. Run from the project root:
 *
 *   java web/tools/Icon.java web/public
 */
public final class Icon {

    private static final Color INK = new Color(0x1d, 0x1d, 0x1f);
    private static final Color PAPER = new Color(0xe8, 0xe8, 0xed);
    private static final Color STAMP = new Color(0xff, 0x3b, 0x30);

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "web/public");
        draw(dir.resolve("icon-192.png"), 192);
        draw(dir.resolve("icon-512.png"), 512);
        draw(dir.resolve("apple-touch-icon.png"), 180);
        System.out.println("ok  " + dir);
    }

    private static void draw(Path f, int s) throws Exception {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(INK);
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s * 0.21875f, s * 0.21875f));

        float cx = s * 0.5f, cy = s * 0.5f, r = s * 0.328f, w = s * 0.055f;
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(PAPER);
        g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));

        float major = s * 0.043f, minor = s * 0.031f;
        float tip = r * 0.86f, deep = r * 0.67f, shallow = r * 0.74f;
        g.setStroke(new BasicStroke(major, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        tick(g, cx, cy, tip, deep, 0);
        tick(g, cx, cy, tip, deep, 90);
        tick(g, cx, cy, tip, deep, 180);
        tick(g, cx, cy, tip, deep, 270);
        g.setColor(new Color(PAPER.getRed(), PAPER.getGreen(), PAPER.getBlue(), 140));
        g.setStroke(new BasicStroke(minor, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        tick(g, cx, cy, tip, shallow, 45);
        tick(g, cx, cy, tip, shallow, 135);
        tick(g, cx, cy, tip, shallow, 225);
        tick(g, cx, cy, tip, shallow, 315);

        g.setColor(PAPER);
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(cx, cy, cx, cy - r * 0.68f)); // minute → 12
        g.setColor(STAMP);
        g.draw(new Line2D.Float(cx, cy, cx + r * 0.49f, cy + r * 0.29f)); // hour
        float hub = w * 0.93f;
        g.fill(new Ellipse2D.Float(cx - hub, cy - hub, hub * 2, hub * 2));

        g.dispose();
        ImageIO.write(img, "png", f.toFile());
        System.out.println("png " + f + "  " + s + "x" + s);
    }

    private static void tick(Graphics2D g, float cx, float cy, float outer, float inner, double deg) {
        double a = Math.toRadians(deg - 90);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        g.draw(new Line2D.Float(cx + cos * inner, cy + sin * inner, cx + cos * outer, cy + sin * outer));
    }
}
