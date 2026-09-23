package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stock-style clock and rabbit home, drawn in the device's 480 by 640 coordinate space. */
public final class RabbitHomeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private static final String MASCOT_SHA256 = "a98649570dd00094d1a95c468f607b5cb3102f94203bc8a1b2aa501ee68165fa";
    private static Bitmap cachedMascot;
    private static long cachedModified;
    private static final int MAX_MASCOT_BYTES = 262_144;
    // Original guide PNG, with its outer border/blank canvas excluded only while drawing.
    private final Rect mascotSource = new Rect(248, 176, 768, 868);
    private final RectF mascotDestination = new RectF(100f, 244f, 380f, 616.6f);
    private final Bitmap privateMascot;
    private String time = "--:--";
    private int battery = -1;
    private Runnable openStack;

    public RabbitHomeView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        paint.setTypeface(RabbitTypography.regular(context));
        privateMascot = loadPrivateMascot(context);
        setFocusable(true);
        setClickable(true);
        setContentDescription("Rabbit home. Swipe up or use the wheel to open the card stack.");
    }

    public void setOpenStack(Runnable action) { openStack = action; }
    public void setStatus(String time, int battery) {
        if (this.time.equals(time) && this.battery == battery) return;
        this.time = time; this.battery = battery; invalidate();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if ((action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) && openStack != null) {
            openStack.run(); return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.scale(getWidth() / 480f, getHeight() / 640f);
        // Draw before the clock so the source PNG's black background cannot
        // erase clock pixels where the ears share its lower edge.
        if (privateMascot != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(privateMascot, mascotSource, mascotDestination, paint);
        }
        paint.setColor(0xfff5efe1); paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(30);
        canvas.drawText(battery < 0 ? "--%" : battery + "%", 212, 84, paint);
        int batteryShape = canvas.save();
        canvas.translate(260, 64); canvas.scale(1.35f, 1.35f);
        drawBattery(canvas, 0, 0, battery); canvas.restoreToCount(batteryShape);
        paint.setTextSize(144); paint.setTextAlign(Paint.Align.CENTER);
        float timeWidth = paint.measureText(time);
        if (timeWidth > 432f) paint.setTextSize(144f * 432f / timeWidth);
        canvas.drawText(time, 240, 220, paint);
        if (privateMascot == null) drawRabbit(canvas, 254, 446, 1.6f);
        canvas.restoreToCount(save);
    }

    /** Optional owner-installed asset, shared after verification; no file polling or animation. */
    private static synchronized Bitmap loadPrivateMascot(Context context) {
        File file = new File(context.getFilesDir(), "theme/rabbit-head.png");
        if (!file.isFile() || file.length() < 24 || file.length() > MAX_MASCOT_BYTES) return null;
        long modified = file.lastModified();
        if (cachedMascot != null && !cachedMascot.isRecycled() && cachedModified == modified) return cachedMascot;
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (bytes.size() + count > MAX_MASCOT_BYTES) return null;
                bytes.write(chunk, 0, count);
            }
            byte[] encoded = bytes.toByteArray();
            byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            if (encoded.length < 24) return null;
            for (int i = 0; i < signature.length; i++) if (encoded[i] != signature[i]) return null;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            char[] hex = new char[digest.length * 2];
            String digits = "0123456789abcdef";
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = digits.charAt((digest[i] & 255) >>> 4);
                hex[i * 2 + 1] = digits.charAt(digest[i] & 15);
            }
            if (!MASCOT_SHA256.equals(new String(hex))) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            if (options.outWidth != 1040 || options.outHeight != 1044 || !"image/png".equals(options.outMimeType)) return null;
            options.inJustDecodeBounds = false;
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            if (decoded != null && (decoded.getWidth() != 1040 || decoded.getHeight() != 1044)) {
                decoded.recycle(); return null;
            }
            cachedMascot = decoded; cachedModified = modified;
            return decoded;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unavailable) {
            return null;
        } catch (OutOfMemoryError unavailable) {
            return null;
        }
    }

    private void drawBattery(Canvas canvas, float x, float y, int percent) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
        canvas.drawRoundRect(x, y, x + 28, y + 15, 3, 3, paint);
        paint.setStyle(Paint.Style.FILL);
        if (percent > 0) canvas.drawRoundRect(x + 3, y + 3,
                x + 3 + 22 * Math.min(100, percent) / 100f, y + 12, 1, 1, paint);
        canvas.drawRoundRect(x + 29, y + 4, x + 32, y + 11, 1, 1, paint);
    }

    private void polygon(Canvas canvas, int color, float... points) {
        shape.reset(); shape.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) shape.lineTo(points[i], points[i + 1]);
        shape.close(); paint.setColor(color); paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(shape, paint);
    }

    private void drawRabbit(Canvas canvas, float x, float y, float scale) {
        int save = canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale);
        paint.setColor(0xfff5efe1); paint.setStyle(Paint.Style.FILL);
        shape.reset(); shape.moveTo(-77,-121); shape.quadTo(-88,-127,-85,-115);
        shape.lineTo(-53,-44); shape.quadTo(-51,-40,-46,-38);
        shape.lineTo(-27,-30); shape.lineTo(-3,-35); shape.lineTo(-28,-94);
        shape.quadTo(-30,-99,-37,-102); shape.close(); canvas.drawPath(shape,paint);
        shape.reset(); shape.moveTo(20,-108); shape.quadTo(20,-118,27,-111);
        shape.lineTo(63,-78); shape.lineTo(63,-8); shape.quadTo(62,5,53,0);
        shape.lineTo(20,-33); shape.close(); canvas.drawPath(shape,paint);
        shape.reset(); shape.moveTo(-71,0); shape.quadTo(-81,7,-80,20);
        shape.lineTo(-80,55); shape.quadTo(-80,63,-71,69);
        shape.lineTo(-27,97); shape.quadTo(-11,107,4,103);
        shape.lineTo(59,73); shape.quadTo(69,68,69,57); shape.lineTo(69,7);
        shape.quadTo(59,15,50,6); shape.lineTo(16,-27);
        shape.quadTo(6,-37,-9,-34); shape.quadTo(-20,-34,-34,-25);
        shape.close(); canvas.drawPath(shape,paint);
        paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeWidth(5f);
        canvas.drawLine(-64,58,-41,73,paint); canvas.drawLine(-52,53,-52,78,paint);
        shape.reset(); shape.moveTo(-32,71); shape.quadTo(-17,53,-9,61); shape.lineTo(9,76);
        canvas.drawPath(shape,paint);
        canvas.restoreToCount(save);
    }
}
