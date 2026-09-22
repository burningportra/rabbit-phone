package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A tiny original geometric rabbit mark drawn without bundled artwork. */
final class RabbitMarkView extends View {
    private final Paint paint = new Paint();

    RabbitMarkView(Context context) {
        super(context);
        paint.setColor(0xffff5a1f);
        setContentDescription("Rabbit Phone");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float unit = Math.min(w, h) / 10f;
        canvas.drawRect(2f * unit, unit, 4f * unit, 5f * unit, paint);
        canvas.drawRect(6f * unit, unit, 8f * unit, 5f * unit, paint);
        canvas.drawRect(unit, 4f * unit, 9f * unit, 8f * unit, paint);
        canvas.drawRect(2f * unit, 8f * unit, 8f * unit, 9f * unit, paint);

        paint.setColor(0xff0a0a09);
        canvas.drawRect(3f * unit, 5f * unit, 4f * unit, 6f * unit, paint);
        canvas.drawRect(6f * unit, 5f * unit, 7f * unit, 6f * unit, paint);
        paint.setColor(0xffff5a1f);
    }
}
