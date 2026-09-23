import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import java.io.File;

/** app_process probe; proprietary font files are provided separately on /data. */
public final class FontProbe {
    private static void raster(Typeface face, String variation) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(face);
        paint.setTextSize(48);
        paint.setColor(Color.WHITE);
        // Static fonts may report false for unsupported axes; that is valid.
        boolean axes = variation.isEmpty() || paint.setFontVariationSettings(variation);
        String[] samples = {"Rabbit ABC xyz", "0123456789"};
        for (String sample : samples) {
            Bitmap bitmap = Bitmap.createBitmap(480, 80, Bitmap.Config.ARGB_8888);
            new Canvas(bitmap).drawText(sample, 4, 58, paint);
            int pixels = 0;
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    if (Color.alpha(bitmap.getPixel(x, y)) != 0) pixels++;
                }
            }
            bitmap.recycle();
            if (pixels == 0 || paint.measureText(sample) <= 0) {
                throw new AssertionError("Empty raster: " + sample);
            }
        }
        System.out.println("raster OK axes=" + axes + " variation=" + variation);
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Pass padded font paths");
        // A raw app_process misses app/zygote font-map initialization. Android 16
        // Typeface.loadPreinstalledSystemFontMap() builds the system fallbacks
        // and assigns DEFAULT/SANS_SERIF before CustomFallbackBuilder uses them.
        // AOSP: frameworks/base, android16-release, graphics/java/android/graphics/Typeface.java
        Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        if (Typeface.DEFAULT == null || Typeface.SANS_SERIF == null) {
            throw new AssertionError("System Typeface initialization failed");
        }
        for (String path : args) {
            for (String axes : new String[] {"", "'wght' 400", "'wght' 700, 'wdth' 100"}) {
                Font.Builder builder = new Font.Builder(new File(path)).setWeight(400)
                    .setSlant(FontStyle.FONT_SLANT_UPRIGHT);
                if (!axes.isEmpty()) builder.setFontVariationSettings(axes);
                Font font = builder.build();
                FontFamily family = new FontFamily.Builder(font).build();
                for (int weight : new int[] {400, 700}) {
                    for (int slant : new int[] {FontStyle.FONT_SLANT_UPRIGHT,
                                                FontStyle.FONT_SLANT_ITALIC}) {
                        Typeface face = new Typeface.CustomFallbackBuilder(family)
                            .setStyle(new FontStyle(weight, slant)).build();
                        raster(face, axes);
                    }
                }
            }
            System.out.println("FONT_OK " + path);
        }
        System.out.println("PROBE_OK");
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable failure) {
            // RuntimeInit's uncaught handler can kill app_process before ADB gets
            // the cause. Emit and flush it, then return a deterministic failure.
            failure.printStackTrace(System.err);
            System.err.flush();
            System.out.flush();
            System.exit(1);
        }
    }
}
