package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/** A tiny original geometric rabbit mark drawn without bundled artwork. */
final class RabbitMarkView extends View {
    RabbitMarkView(Context context) {
        super(context);
        setContentDescription("Rabbit Phone");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        RabbitMarkRenderer.draw(canvas, (getWidth() - size) / 2f,
                (getHeight() - size) / 2f, size,
                RabbitMarkRenderer.ORANGE, RabbitMarkRenderer.BACKGROUND);
    }
}
