package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Stock-style clock and rabbit home, drawn in the device's 480 by 640 coordinate space. */
public final class RabbitHomeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private String time = "--:--";
    private int battery = -1;
    private Runnable openStack;

    public RabbitHomeView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        paint.setTypeface(RabbitTypography.regular(context));
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
        paint.setColor(0xfff5efe1); paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(24);
        canvas.drawText(battery < 0 ? "--%" : battery + "%", 226, 145, paint);
        drawBattery(canvas, 269, 127, battery);
        paint.setTextSize(78); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(time, 240, 230, paint);
        drawRabbit(canvas, 240, 316, .82f);
        canvas.restoreToCount(save);
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
