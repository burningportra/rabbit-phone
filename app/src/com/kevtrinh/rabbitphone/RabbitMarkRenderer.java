package com.kevtrinh.rabbitphone;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Shared renderer for the original pixel Rabbit Phone mark. */
final class RabbitMarkRenderer {
    static final int BACKGROUND = 0xff0a0a09;
    static final int ORANGE = 0xffff5a1f;

    private RabbitMarkRenderer() {}

    static void draw(Canvas canvas, float left, float top, float size,
            int foreground, int cutout) {
        float unit = Math.max(1f, (float) Math.floor(size / 10f));
        float markSize = unit * 10f;
        float x = left + (size - markSize) / 2f;
        float y = top + (size - markSize) / 2f;
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(false);
        paint.setColor(foreground);
        canvas.drawRect(x + 2f * unit, y + unit, x + 4f * unit, y + 5f * unit, paint);
        canvas.drawRect(x + 6f * unit, y + unit, x + 8f * unit, y + 5f * unit, paint);
        canvas.drawRect(x + unit, y + 4f * unit, x + 9f * unit, y + 8f * unit, paint);
        canvas.drawRect(x + 2f * unit, y + 8f * unit, x + 8f * unit, y + 9f * unit, paint);

        paint.setColor(cutout);
        canvas.drawRect(x + 3f * unit, y + 5f * unit, x + 4f * unit, y + 6f * unit, paint);
        canvas.drawRect(x + 6f * unit, y + 5f * unit, x + 7f * unit, y + 6f * unit, paint);
    }
}
