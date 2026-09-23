package com.kevtrinh.rabbitphone.gallerytest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Separate signed test APK. Creates only deterministic synthetic photos; never uses a camera. */
public final class GalleryFixtures extends Instrumentation {
    private static final String OWNER = "com.kevtrinh.rabbitphone";
    private static final String FOLDER = "Pictures/Rabbit Phone/";
    private static final Uri IMAGES = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    private static final String SCOPE = MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND "
            + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            if (!OWNER.equals(getTargetContext().getPackageName())) throw new IllegalStateException("Wrong target app");
            String action = arguments.getString("action", "");
            String token = arguments.getString("token", "");
            JSONObject result = new JSONObject();
            if ("snapshot".equals(action)) result.put("photos", snapshot());
            else {
                if (!token.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid fixture token");
                if ("seed".equals(action)) result.put("photos", seed(token));
                else if ("cleanup".equals(action)) result.put("removed", cleanup(token));
                else throw new IllegalArgumentException("Unknown fixture action");
            }
            result.put("ok", true);
            report.putString("result", result.toString());
            finish(Activity.RESULT_OK, report);
        } catch (Exception error) {
            report.putString("result", "{\"ok\":false,\"error\":\"" + error.getClass().getSimpleName() + "\"}");
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private ContentResolver resolver() { return getTargetContext().getContentResolver(); }

    private static String name(String token, int index) { return "Rabbit-gallery-test-" + token + "-" + index + ".png"; }

    private byte[] pixels(int index) throws Exception {
        int width = index == 1 ? 360 : 640, height = index == 1 ? 640 : 480;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(new int[] {0xff1af6ff, 0xffff009f, 0xff6b63ff}[index]);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            canvas.drawRect(24, 24, width - 24, 42, paint);
            canvas.drawRect(24, height - 42, width - 24, height - 24, paint);
            canvas.drawCircle(width / 2f, height / 2f, Math.min(width, height) / 4f, paint);
            paint.setColor(Color.WHITE);
            for (int dot = 0; dot <= index; dot++)
                canvas.drawCircle(width / 2f + (dot - index / 2f) * 36, height / 2f, 10, paint);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG failed");
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private JSONArray seed(String token) throws Exception {
        // Refuse a collision before the first write, including an interrupted older run.
        for (int i = 0; i < 3; i++) if (lookup(name(token, i)) != null)
            throw new IllegalStateException("Fixture already exists");
        JSONArray created = new JSONArray();
        try {
            for (int i = 0; i < 3; i++) {
                byte[] bytes = pixels(i);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name(token, i));
                values.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = resolver().insert(IMAGES, values);
                if (uri == null) throw new IllegalStateException("Insert failed");
                boolean complete = false;
                try {
                    try (OutputStream output = resolver().openOutputStream(uri, "w")) {
                        if (output == null) throw new IllegalStateException("Output unavailable");
                        output.write(bytes);
                    }
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                    if (resolver().update(uri, ready, null, null) != 1) throw new IllegalStateException("Publish failed");
                    JSONObject photo = lookup(name(token, i));
                    if (photo == null || !digest(bytes).equals(photo.getString("sha256")))
                        throw new IllegalStateException("Fixture ownership/hash mismatch");
                    complete = true;
                    created.put(photo);
                } finally {
                    if (!complete) resolver().delete(uri, SCOPE + " AND " + MediaStore.Images.Media.DISPLAY_NAME + "=?",
                            new String[] {OWNER, FOLDER, name(token, i)});
                }
            }
            return created;
        } catch (Exception error) {
            cleanup(token); // Completed deterministic rows only; changed bytes are refused.
            throw error;
        }
    }

    private int cleanup(String token) throws Exception {
        int removed = 0;
        for (int i = 0; i < 3; i++) {
            String filename = name(token, i);
            JSONObject photo = lookup(filename);
            if (photo == null) continue; // UI deletion may already have removed this fixture.
            if (!digest(pixels(i)).equals(photo.getString("sha256")))
                throw new IllegalStateException("Fixture changed; refusing removal");
            if (!photo.getString("version").equals(MediaStore.getVersion(getTargetContext(), "external_primary")))
                throw new IllegalStateException("Media database changed; refusing removal");
            String selection = SCOPE + " AND " + MediaStore.Images.Media._ID + "=? AND "
                    + MediaStore.Images.Media.DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.SIZE + "=? AND "
                    + MediaStore.Images.Media.GENERATION_ADDED + "=? AND " + MediaStore.Images.Media.GENERATION_MODIFIED + "=?";
            int count = resolver().delete(IMAGES, selection, new String[] {OWNER, FOLDER,
                    Long.toString(photo.getLong("id")), filename, Long.toString(photo.getLong("size")),
                    Long.toString(photo.getLong("added")), Long.toString(photo.getLong("modified"))});
            if (count != 1) throw new IllegalStateException("Fixture removal did not affect exactly one row");
            removed += count;
        }
        return removed;
    }

    private JSONObject lookup(String filename) throws Exception {
        JSONArray rows = read(SCOPE + " AND " + MediaStore.Images.Media.DISPLAY_NAME + "=?",
                new String[] {OWNER, FOLDER, filename});
        if (rows.length() > 1) throw new IllegalStateException("Ambiguous fixture filename");
        return rows.length() == 0 ? null : rows.getJSONObject(0);
    }

    private JSONArray snapshot() throws Exception {
        return read(SCOPE, new String[] {OWNER, FOLDER});
    }

    private JSONArray read(String selection, String[] arguments) throws Exception {
        JSONArray rows = new JSONArray();
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.IS_FAVORITE,
                MediaStore.Images.Media.GENERATION_ADDED, MediaStore.Images.Media.GENERATION_MODIFIED};
        String version = MediaStore.getVersion(getTargetContext(), "external_primary");
        long total = 0, streamed = 0;
        try (Cursor cursor = resolver().query(IMAGES, projection, selection, arguments,
                MediaStore.Images.Media._ID + " ASC")) {
            if (cursor == null) throw new IllegalStateException("Media query unavailable");
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0), size = cursor.getLong(2);
                total += size;
                if (size < 0 || total > 134_217_728L) throw new IllegalStateException("Snapshot budget exceeded");
                Uri uri = ContentUris.withAppendedId(IMAGES, id);
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                try (InputStream input = resolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("Photo unavailable");
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        streamed += count;
                        if (streamed > 134_217_728L) throw new IllegalStateException("Read budget exceeded");
                        hash.update(buffer, 0, count);
                    }
                }
                rows.put(new JSONObject().put("id", id).put("uri", uri.toString())
                        .put("name", cursor.getString(1)).put("size", size)
                        .put("favorite", cursor.getInt(3) == 1).put("sha256", hex(hash.digest()))
                        .put("added", cursor.getLong(4)).put("modified", cursor.getLong(5)).put("version", version));
            }
        }
        return rows;
    }

    private static String digest(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
