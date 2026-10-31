import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * The PWA icons: clock + "P", the same drawing as web/public/icon.svg, in the
 * theme colours (ink background, paper clock, stamp-red hand, accent-blue P).
 * iOS needs real PNGs, so they are generated once and committed; this file is
 * the source. Run from the project root:
 *
 *   java web/tools/Icon.java web/public
 */
public final class Icon {

    private static final Color INK = new Color(0x1d, 0x1d, 0x1f);
    private static final Color PAPER = new Color(0xe8, 0xe8, 0xed);
    private static final Color STAMP = new Color(0xff, 0x3b, 0x30);
    private static final Color ACCENT = new Color(0x00, 0x7a, 0xff);

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
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s * 0.1875f, s * 0.1875f));

        float cx = s * 0.5f, cy = s * 0.43f, r = s * 0.254f, w = s * 0.047f;
        g.setStroke(new java.awt.BasicStroke(w, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.setColor(PAPER);
        g.draw(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g.drawLine((int) cx, (int) cy, (int) cx, (int) (cy - r * 0.7f));      // minute hand, 12
        g.setColor(STAMP);
        g.drawLine((int) cx, (int) cy, (int) (cx + r * 0.5f), (int) (cy + r * 0.3f));  // hour hand
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - w * 0.85f, cy - w * 0.85f, w * 1.7f, w * 1.7f));

        g.setColor(ACCENT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(s * 0.29f)));
        String p = "P";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(p, (s - fm.stringWidth(p)) / 2f, s * 0.9f);

        g.dispose();
        ImageIO.write(img, "png", f.toFile());
        System.out.println("png " + f + "  " + s + "x" + s);
    }
}
