package com.kevtrinh.rabbitphone;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Size;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker-thread access to this installation's own, published camera photos only. */
final class GalleryStore {
    static final String DIRECTORY = "Pictures/Rabbit Phone/";
    static final Uri COLLECTION = MediaStore.Images.Media.getContentUri("external_primary");
    private static final String OWNED = "owner_package_name=? AND relative_path=?"
            + " AND is_pending=0 AND is_trashed=0";
    private static final String IDENTITY = OWNED + " AND _id=? AND _display_name=? AND _size=?"
            + " AND generation_added=? AND generation_modified=?";
    private static final String[] COLUMNS = {"_id", "_display_name", "_size",
            "generation_added", "generation_modified", "is_favorite"};
    private final ContentResolver resolver;
    private final Context context;
    private final String owner;

    static final class Photo {
        final Uri uri;
        final long id, size, added, modified;
        final String name, version;
        final boolean favorite;

        Photo(long id, String name, long size, long added, long modified,
                boolean favorite, String version) {
            this.id = id; this.name = name; this.size = size; this.added = added;
            this.modified = modified; this.favorite = favorite; this.version = version;
            uri = ContentUris.withAppendedId(COLLECTION, id);
        }

        String cacheKey() { return version + ":" + id + ":" + added + ":" + modified + ":" + size; }
    }

    GalleryStore(Context context) {
        this.context = context.getApplicationContext();
        resolver = this.context.getContentResolver();
        owner = context.getPackageName();
    }

    List<Photo> query(CancellationSignal signal) {
        if (Build.VERSION.SDK_INT < 30) throw new IllegalStateException("Gallery needs Android 11 or newer");
        String version = MediaStore.getVersion(context, "external_primary");
        List<Photo> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(COLLECTION, COLUMNS, OWNED,
                new String[] {owner, DIRECTORY}, "date_added DESC, _id DESC", signal)) {
            if (cursor == null) throw new IllegalStateException("Photo storage unavailable");
            while (cursor.moveToNext()) {
                signal.throwIfCanceled();
                String name = cursor.getString(1);
                if (name != null && cursor.getLong(2) > 0) {
                    result.add(new Photo(cursor.getLong(0), name, cursor.getLong(2), cursor.getLong(3),
                            cursor.getLong(4), cursor.getInt(5) != 0, version));
                }
            }
        }
        signal.throwIfCanceled();
        return result;
    }

    Bitmap thumbnail(Photo photo, CancellationSignal signal) throws IOException {
        return resolver.loadThumbnail(photo.uri, new Size(160, 160), signal);
    }

    Bitmap image(Photo photo, final CancellationSignal signal) throws IOException {
        signal.throwIfCanceled();
        Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, photo.uri),
                new ImageDecoder.OnHeaderDecodedListener() { @Override public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source source) {
                    signal.throwIfCanceled();
                    int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                    float factor = Math.min(1f, 960f / Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * factor)),
                            Math.max(1, Math.round(height * factor)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                } });
        signal.throwIfCanceled();
        return bitmap;
    }

    /** The permit is single-use and revoked by the UI on any interruption/navigation. */
    boolean mutate(Photo photo, boolean delete, AtomicBoolean permit) {
        if (!permit.get() || !photo.version.equals(MediaStore.getVersion(context, "external_primary")))
            return false;
        String[] identity = {owner, DIRECTORY, Long.toString(photo.id), photo.name,
                Long.toString(photo.size), Long.toString(photo.added), Long.toString(photo.modified)};
        // The selection is evaluated by MediaProvider with the write, so a changed/replaced
        // row never becomes a target between a separate validation query and the mutation.
        if (!permit.compareAndSet(true, false)) return false;
        if (delete) return resolver.delete(photo.uri, IDENTITY, identity) == 1;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_FAVORITE, photo.favorite ? 0 : 1);
        return resolver.update(photo.uri, values, IDENTITY, identity) == 1;
    }
}
