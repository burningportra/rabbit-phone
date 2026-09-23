package com.kevtrinh.rabbitphone;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Explicit preview and controls for the Rabbit Phone system and lock wallpaper. */
public final class ThemeActivity extends Activity {
    private static final int BG = RabbitMarkRenderer.BACKGROUND;
    private static final int ORANGE = RabbitMarkRenderer.ORANGE;
    private static final int WARM_WHITE = Color.rgb(245, 239, 225);
    private static final int MUTED = Color.rgb(161, 154, 140);
    private static final int CARD = Color.rgb(27, 27, 24);
    private static final int DARK_INK = Color.rgb(22, 18, 14);
    private static final float ART_CENTER_Y = 0.54f;
    private static final float MARK_WIDTH = 0.23f;
    private static final float LABEL_GAP_EM = 1.35f;
    private static final int WALLPAPER_TARGETS =
            WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        root.setBackgroundColor(BG);

        TextView title = text("Rabbit theme", 30, WARM_WHITE, Typeface.BOLD);
        root.addView(title, matchWidth(dp(40)));

        TextView detail = text("Preview for Home and the Android lock screen", 14,
                MUTED, Typeface.NORMAL);
        root.addView(detail, matchWidth(dp(38)));

        WallpaperPreview preview = new WallpaperPreview();
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(150), dp(200));
        previewParams.bottomMargin = dp(14);
        root.addView(preview, previewParams);

        TextView apply = action("Apply Rabbit theme wallpaper", ORANGE, DARK_INK);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { applyWallpaper(); }
        });
        root.addView(apply, buttonParams());

        TextView restore = action("Restore Android default wallpaper", CARD, WARM_WHITE);
        restore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { confirmRestore(); }
        });
        LinearLayout.LayoutParams restoreParams = buttonParams();
        restoreParams.topMargin = dp(8);
        root.addView(restore, restoreParams);

        statusView = text("Wallpaper changes happen only when you choose an action.",
                12, MUTED, Typeface.NORMAL);
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams statusParams = matchWidth(dp(34));
        statusParams.topMargin = dp(8);
        root.addView(statusView, statusParams);

        setContentView(root);
    }

    private void applyWallpaper() {
        Bitmap wallpaper = null;
        try {
            WallpaperManager manager = WallpaperManager.getInstance(this);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            wallpaper = renderWallpaper(screenWidth, screenHeight);
            Rect visibleScreen = new Rect(0, 0, screenWidth, screenHeight);
            int wallpaperId = manager.setBitmap(
                    wallpaper, visibleScreen, true, WALLPAPER_TARGETS);
            if (wallpaperId <= 0) throw new IOException("Wallpaper service rejected image");
            showResult("Rabbit wallpaper applied to Home and lock screen", false);
        } catch (IOException | RuntimeException error) {
            showResult("Couldn't apply the wallpaper", true);
        } finally {
            if (wallpaper != null) wallpaper.recycle();
        }
    }

    private void confirmRestore() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Restore Android default?")
                .setMessage("This removes the Rabbit wallpaper from Home and the lock screen.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        restoreDefault();
                    }
                })
                .create();
        dialog.show();
        RabbitTypography.applyToDialog(this, dialog);
    }

    private void restoreDefault() {
        try {
            WallpaperManager.getInstance(this).clear(WALLPAPER_TARGETS);
            showResult("Android default wallpaper restored", false);
        } catch (IOException | RuntimeException error) {
            showResult("Couldn't restore the Android default wallpaper", true);
        }
    }

    private void showResult(String message, boolean error) {
        statusView.setText(message);
        statusView.setTextColor(error ? ORANGE : WARM_WHITE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private Bitmap renderWallpaper(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(BG);

        float markSize = width * MARK_WIDTH;
        float centerY = height * ART_CENTER_Y;
        RabbitMarkRenderer.draw(canvas, (width - markSize) / 2f,
                centerY - markSize / 2f, markSize, ORANGE, BG);

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(WARM_WHITE);
        label.setTypeface(RabbitTypography.regular(this));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Math.max(14f, getResources().getDisplayMetrics().scaledDensity * 13f));
        label.setLetterSpacing(0.08f);
        canvas.drawText("rabbit phone", width / 2f,
                centerY + markSize / 2f + label.getTextSize() * LABEL_GAP_EM, label);
        return bitmap;
    }

    private TextView action(String label, int backgroundColor, int textColor) {
        TextView view = text(label, 16, textColor, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(roundedBackground(backgroundColor, 14));
        return view;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(RabbitTypography.regular(this));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout.LayoutParams matchWidth(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams buttonParams() {
        return matchWidth(dp(48));
    }

    private GradientDrawable roundedBackground(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class WallpaperPreview extends View {
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        WallpaperPreview() {
            super(ThemeActivity.this);
            setContentDescription("Rabbit wallpaper preview. Dark background with the orange "
                    + "Rabbit mark below the clear lock screen notification area.");
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1));
            border.setColor(Color.argb(26, 255, 255, 255));
            label.setColor(WARM_WHITE);
            label.setTypeface(RabbitTypography.regular(ThemeActivity.this));
            label.setTextAlign(Paint.Align.CENTER);
            label.setLetterSpacing(0.08f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(BG);
            float markSize = getWidth() * MARK_WIDTH;
            float centerY = getHeight() * ART_CENTER_Y;
            RabbitMarkRenderer.draw(canvas, (getWidth() - markSize) / 2f,
                    centerY - markSize / 2f, markSize, ORANGE, BG);
            label.setTextSize(dp(11));
            canvas.drawText("rabbit phone", getWidth() / 2f,
                    centerY + markSize / 2f + label.getTextSize() * LABEL_GAP_EM, label);
            canvas.drawRect(border.getStrokeWidth() / 2f, border.getStrokeWidth() / 2f,
                    getWidth() - border.getStrokeWidth() / 2f,
                    getHeight() - border.getStrokeWidth() / 2f, border);
        }
    }
}
