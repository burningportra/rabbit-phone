package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same-window, original-photo gallery; all media I/O stays off the UI thread. */
public final class GalleryView extends ViewGroup implements HardwarePage {
    private static final int CYAN = 0xff25c8ed, WHITE = 0xfff5efe1, MUTED = 0xffaaa7ad;
    private final GalleryStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>());
    private final Set<ReadJob> reads = new HashSet<>();
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final TextView heading, favorites, message, caption, favorite;
    private final View delete;
    private final ImageView viewer;
    private final GridView grid;
    private final PhotoAdapter adapter = new PhotoAdapter();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<GalleryStore.Photo> photos = new ArrayList<>(), visible = new ArrayList<>();
    private boolean inFavorites, inViewer, hostActive, attached, running, released, observing, busy;
    private boolean wheelFocus, loaded;
    private int selected, epoch, querySerial, viewerSerial;
    private float scale = 1, originX, originY, swipeY;
    private String selectedUri;
    private ReadJob queryJob, viewerJob;
    private AtomicBoolean mutationPermit;
    private DeletionPrompt prompt;
    private final IdentityHashMap<View, Integer> promptAccessibility = new IdentityHashMap<>();
    private final Runnable refresh = new Runnable() { @Override public void run() { if (active() && !busy) refreshPhotos(); } };
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            if (!active()) return;
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, 120);
        }
    };

    public GalleryView(Context context) {
        super(context);
        store = new GalleryStore(context);
        setBackgroundColor(Color.BLACK); setWillNotDraw(false);
        heading = label("magic gallery", 42, CYAN);
        heading.setContentDescription("Magic gallery");
        heading.setAccessibilityHeading(true);
        favorites = control("♥  favorites", "Favorites", new Runnable() { @Override public void run() {
            if (!inViewer && !inFavorites) { inFavorites = true; selected = 0; selectedUri = null; rebuild(); }
        } });
        favorites.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        favorites.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30);
        message = label("Loading photos…", 24, MUTED); message.setGravity(Gravity.CENTER);
        grid = new GridView(context);
        grid.setNumColumns(3); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false); grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setClipToPadding(false); grid.setAdapter(adapter);
        viewer = new ImageView(context); viewer.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.setContentDescription("Photo viewer");
        viewer.setOnTouchListener(new OnTouchListener() { @Override public boolean onTouch(View view, MotionEvent event) {
            if (!active() || prompt != null || busy) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { swipeY = event.getY(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float dy = event.getY() - swipeY;
                if (Math.abs(dy) > 40 * scale) browse(dy > 0 ? -1 : 1);
                else view.performClick();
                return true;
            }
            return true;
        } });
        caption = label("Original photo", 20, MUTED); caption.setGravity(Gravity.CENTER);
        favorite = control("♡", "Add to favorites", new Runnable() { @Override public void run() { toggleFavorite(); } });
        delete = new TrashControl();
        addView(heading); addView(favorites); addView(grid); addView(viewer);
        addView(caption); addView(favorite); addView(delete); addView(message);
        updateMode();
    }

    @Override public View getView() { return this; }
    public void setHostActive(boolean active) { hostActive = active; reconcileLifecycle(); }
    public void release() {
        if (released) return;
        released = true; hostActive = false; reconcileLifecycle();
        workers.shutdownNow(); clearImages(); thumbnails.evictAll();
        main.removeCallbacksAndMessages(null);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; reconcileLifecycle(); }
    @Override protected void onDetachedFromWindow() {
        attached = false; reconcileLifecycle(); super.onDetachedFromWindow();
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); reconcileLifecycle(); }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (store != null) reconcileLifecycle();
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (store != null) reconcileLifecycle();
    }
    private boolean active() { return !released && hostActive && attached && isEnabled() && isShown() && hasWindowFocus(); }
    private boolean actions() { return active() && !busy && prompt == null; }

    private void reconcileLifecycle() {
        boolean next = active();
        if (next == running) return;
        running = next; epoch++;
        if (!next) {
            main.removeCallbacks(refresh);
            cancelPrompt(); revokeMutation(); cancelReads();
            if (observing) { getContext().getContentResolver().unregisterContentObserver(observer); observing = false; }
            clearImages(); thumbnails.evictAll();
        } else {
            if (!observing) {
                getContext().getContentResolver().registerContentObserver(GalleryStore.COLLECTION, true, observer);
                observing = true;
            }
            refreshPhotos();
        }
    }

    private void cancelReads() {
        for (ReadJob job : new ArrayList<>(reads)) job.cancel();
        reads.clear(); workers.purge(); querySerial++; viewerSerial++;
    }
    private void clearImages() {
        viewer.setImageDrawable(null);
        for (int i = 0; i < grid.getChildCount(); i++) ((PhotoCell) grid.getChildAt(i)).clear();
    }
    private void revokeMutation() { if (mutationPermit != null) mutationPermit.set(false); mutationPermit = null; busy = false; }

    private void refreshPhotos() {
        if (!active() || busy) return;
        if (queryJob != null) queryJob.cancel();
        if (!loaded) { message.setText("Loading photos…"); message.setVisibility(VISIBLE); }
        final int request = ++querySerial, life = epoch;
        queryJob = new ReadJob();
        final ReadJob job = queryJob;
        job.future = workers.submit(new Runnable() { @Override public void run() {
            List<GalleryStore.Photo> result = null;
            String error = null;
            try { result = store.query(job.signal); }
            catch (RuntimeException e) { error = "Photos unavailable. Reopen gallery to retry."; }
            final List<GalleryStore.Photo> value = result; final String failure = error;
            main.post(new Runnable() { @Override public void run() {
                reads.remove(job);
                if (!active() || epoch != life || querySerial != request || job.signal.isCanceled()) return;
                loaded = true;
                if (value == null) {
                    photos = new ArrayList<>(); visible = new ArrayList<>(); inViewer = false;
                    cancelPrompt(); rebuild(); message.setText(failure); message.setVisibility(VISIBLE);
                    return;
                }
                photos = value; rebuild();
            } });
        } });
    }

    /** Reconcile identity before position: metadata updates must not silently select another photo. */
    private void rebuild() {
        GalleryStore.Photo previous = current();
        String wanted = selectedUri != null ? selectedUri : previous == null ? null : previous.uri.toString();
        visible = new ArrayList<>();
        for (GalleryStore.Photo photo : photos) if (!inFavorites || photo.favorite) visible.add(photo);
        int index = -1;
        for (int i = 0; i < visible.size(); i++) if (visible.get(i).uri.toString().equals(wanted)) { index = i; break; }
        if (index >= 0) selected = index + offset();
        else {
            if (inViewer) { inViewer = false; cancelPrompt(); }
            selected = Math.max(0, Math.min(selected, Math.max(0, visible.size() - 1 + offset())));
        }
        rememberSelection();
        adapter.notifyDataSetChanged(); updateMode();
        if (inViewer) loadViewer();
    }
    private int offset() { return inFavorites ? 0 : 1; }
    private GalleryStore.Photo current() {
        int index = selected - offset();
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
    }
    private void rememberSelection() { GalleryStore.Photo photo = current(); selectedUri = photo == null ? null : photo.uri.toString(); }

    private void updateMode() {
        heading.setText(inViewer ? "original photo" : inFavorites ? "favorites" : "magic gallery");
        heading.setContentDescription(inViewer ? "Original photo" : inFavorites ? "Favorites gallery" : "Magic gallery");
        favorites.setVisibility(!inViewer && !inFavorites ? VISIBLE : GONE);
        grid.setVisibility(inViewer ? GONE : VISIBLE);
        viewer.setVisibility(inViewer ? VISIBLE : GONE);
        caption.setVisibility(inViewer ? VISIBLE : GONE);
        favorite.setVisibility(inViewer ? VISIBLE : GONE); delete.setVisibility(inViewer ? VISIBLE : GONE);
        message.setVisibility(!inViewer && visible.isEmpty() ? VISIBLE : GONE);
        message.setText(!loaded ? "Loading photos…" : inFavorites ? "No favorites yet" : "No photos yet\nPhotos taken with Rabbit Phone appear here.");
        GalleryStore.Photo photo = current();
        favorite.setText(photo != null && photo.favorite ? "♥" : "♡");
        favorite.setContentDescription(photo != null && photo.favorite ? "Remove from favorites" : "Add to favorites");
        // This is the local hardware policy, not a claim about stock Rabbit's undocumented viewer layout.
        favorite.setTooltipText("Side button: favorite");
        caption.setText(photo == null ? "Original photo" : "Original photo · " + (selected - offset() + 1)
                + " / " + visible.size() + "\nSide button: favorite");
        caption.setContentDescription(photo == null ? "Original photo" : "Viewing photo " + photo.name);
        favorites.setSelected(wheelFocus && selected == 0);
        favorites.setTextColor(wheelFocus && selected == 0 ? CYAN : WHITE);
        for (int i = 0; i < grid.getChildCount(); i++) {
            PhotoCell cell = (PhotoCell) grid.getChildAt(i);
            cell.setSelected(wheelFocus && cell.photo != null && cell.photo.uri.toString().equals(selectedUri));
            cell.invalidate();
        }
        requestLayout();
    }

    @Override public boolean handleWheel(boolean up) {
        if (!active()) return false;
        if (busy) return true;
        if (prompt != null) { prompt.choose(up ? 0 : 1); return true; }
        if (inViewer) { browse(up ? -1 : 1); return true; }
        int next = Math.max(0, Math.min(Math.max(0, visible.size() - 1 + offset()), selected + (up ? -1 : 1)));
        wheelFocus = true;
        if (next != selected) { selected = next; rememberSelection(); tick(); }
        updateMode();
        if (selected >= offset()) grid.smoothScrollToPosition(selected - offset());
        return true;
    }
    @Override public boolean handleSingle() {
        if (!active()) return false;
        if (busy) return true;
        if (prompt != null) { if (prompt.choice == 0) cancelPrompt(); else confirmDelete(prompt); return true; }
        if (inViewer) toggleFavorite();
        else if (!inFavorites && selected == 0) { inFavorites = true; selected = 0; selectedUri = null; rebuild(); }
        else openCurrent();
        return true;
    }
    @Override public boolean handleBack() {
        if (!active()) return false;
        if (prompt != null) { cancelPrompt(); return true; }
        revokeMutation();
        if (inViewer) {
            inViewer = false; viewerSerial++; if (viewerJob != null) viewerJob.cancel(); viewer.setImageDrawable(null);
            updateMode(); refreshPhotos(); return true;
        }
        if (inFavorites) { inFavorites = false; selected = 0; selectedUri = null; rebuild(); return true; }
        return false;
    }
    private void tick() { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
    private void openCurrent() {
        if (!actions() || current() == null) return;
        inViewer = true; wheelFocus = false; updateMode(); loadViewer();
    }
    private void browse(int direction) {
        if (!actions() || !inViewer) return;
        int next = Math.max(offset(), Math.min(visible.size() - 1 + offset(), selected + direction));
        if (next == selected) return;
        selected = next; rememberSelection(); tick(); updateMode(); loadViewer();
    }

    private void loadViewer() {
        if (!active() || !inViewer) return;
        GalleryStore.Photo photo = current(); if (photo == null) return;
        if (viewerJob != null) viewerJob.cancel();
        viewer.setImageDrawable(null); message.setText("Loading photo…"); message.setVisibility(VISIBLE);
        final int request = ++viewerSerial, life = epoch;
        viewerJob = new ReadJob(); final ReadJob job = viewerJob;
        job.future = workers.submit(new Runnable() { @Override public void run() {
            Bitmap result = null;
            try { result = store.image(photo, job.signal); } catch (Exception ignored) { }
            final Bitmap bitmap = result;
            main.post(new Runnable() { @Override public void run() {
                reads.remove(job);
                if (!active() || epoch != life || request != viewerSerial || !inViewer || job.signal.isCanceled()) return;
                viewer.setImageBitmap(bitmap);
                message.setText("Unable to open this photo"); message.setVisibility(bitmap == null ? VISIBLE : GONE);
            } });
        } });
    }

    private void toggleFavorite() { if (actions() && inViewer && current() != null) mutate(current(), false); }
    private void askDelete() {
        if (!actions() || !inViewer || current() == null) return;
        prompt = new DeletionPrompt(current()); addView(prompt);
        setUnderlyingAccessibility(false); prompt.announceForAccessibility("Delete photo? Cancel selected.");
        requestLayout();
    }
    private void cancelPrompt() {
        if (prompt == null) return;
        removeView(prompt); prompt = null; setUnderlyingAccessibility(true);
    }
    private void setUnderlyingAccessibility(boolean visible) {
        if (!visible) {
            for (View view : new View[] {heading, favorites, grid, viewer, caption, favorite, delete, message}) {
                if (!promptAccessibility.containsKey(view))
                    promptAccessibility.put(view, view.getImportantForAccessibility());
                view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
        } else {
            // Static TextViews start with YES; replacing it with AUTO hides their
            // text from accessibility after a prompt. Restore each exact prior flag.
            for (Map.Entry<View, Integer> saved : promptAccessibility.entrySet()) {
                if (saved.getKey().getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    saved.getKey().setImportantForAccessibility(saved.getValue());
            }
            promptAccessibility.clear();
        }
    }
    private void confirmDelete(DeletionPrompt expected) {
        if (!active() || busy || prompt != expected || !inViewer || current() == null
                || !current().cacheKey().equals(expected.photo.cacheKey())) { cancelPrompt(); return; }
        GalleryStore.Photo photo = expected.photo; cancelPrompt(); mutate(photo, true);
    }
    private void mutate(GalleryStore.Photo photo, boolean deleting) {
        if (!actions()) return;
        busy = true; final int life = epoch;
        final AtomicBoolean permit = new AtomicBoolean(true); mutationPermit = permit;
        workers.submit(new Runnable() { @Override public void run() {
            boolean changed = false;
            try { changed = store.mutate(photo, deleting, permit); } catch (RuntimeException ignored) { }
            final boolean success = changed;
            main.post(new Runnable() { @Override public void run() {
                if (!active() || epoch != life || mutationPermit != permit) return;
                mutationPermit = null; busy = false;
                if (!success) announceForAccessibility("Photo changed or unavailable. Gallery refreshed.");
                else performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                if (deleting && success) { inViewer = false; viewer.setImageDrawable(null); }
                refreshPhotos();
            } });
        } });
    }

    private final class ReadJob {
        final CancellationSignal signal = new CancellationSignal();
        Future<?> future;
        ReadJob() { reads.add(this); }
        void cancel() { signal.cancel(); if (future != null) future.cancel(true); reads.remove(this); }
    }

    private final class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return visible.get(position).id; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            PhotoCell cell = recycled instanceof PhotoCell ? (PhotoCell) recycled : new PhotoCell();
            cell.bind(visible.get(position)); return cell;
        }
    }

    private final class PhotoCell extends FrameLayout {
        final ImageView image;
        final TextView fallback;
        GalleryStore.Photo photo;
        ReadJob job;
        PhotoCell() {
            super(GalleryView.this.getContext()); setWillNotDraw(false); setFocusable(true); setClickable(true);
            image = new ImageView(getContext()); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            fallback = label("photo", 19, MUTED); fallback.setGravity(Gravity.CENTER);
            addView(fallback, new FrameLayout.LayoutParams(-1, -1)); addView(image, new FrameLayout.LayoutParams(-1, -1));
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            fallback.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setOnClickListener(new OnClickListener() { @Override public void onClick(View view) {
                if (!actions() || inViewer || photo == null || !isAttachedToWindow()) return;
                for (int i = 0; i < visible.size(); i++) {
                    if (visible.get(i).cacheKey().equals(photo.cacheKey())) {
                        selected = i + offset(); rememberSelection(); openCurrent(); return;
                    }
                }
            } });
        }
        void clear() { if (job != null) job.cancel(); job = null; image.setImageDrawable(null); }
        void bind(GalleryStore.Photo value) {
            clear(); photo = value;
            // GridView marks its measured first cell forceAdd=true. Preserve
            // that framework-owned state when rebinding the recycled view.
            ViewGroup.LayoutParams previous = getLayoutParams();
            android.widget.AbsListView.LayoutParams params = previous instanceof android.widget.AbsListView.LayoutParams
                    ? (android.widget.AbsListView.LayoutParams) previous
                    : new android.widget.AbsListView.LayoutParams(-1, Math.round(138.67f * scale));
            params.width = -1; params.height = Math.round(138.67f * scale);
            setLayoutParams(params);
            setContentDescription("Photo " + value.name);
            setBackgroundColor(0xff171719); fallback.setText("photo");
            setSelected(value.uri.toString().equals(selectedUri));
            Bitmap cached = thumbnails.get(value.cacheKey());
            if (cached != null) { image.setImageBitmap(cached); return; }
            if (!active()) return;
            final int life = epoch;
            job = new ReadJob(); final ReadJob expected = job;
            expected.future = workers.submit(new Runnable() { @Override public void run() {
                Bitmap result = null;
                try { result = store.thumbnail(value, expected.signal); } catch (Exception ignored) { }
                final Bitmap bitmap = result;
                main.post(new Runnable() { @Override public void run() {
                    reads.remove(expected);
                    if (!active() || epoch != life || job != expected || expected.signal.isCanceled()
                            || photo == null || !photo.cacheKey().equals(value.cacheKey())) return;
                    if (bitmap != null) { thumbnails.put(value.cacheKey(), bitmap); image.setImageBitmap(bitmap); }
                    else fallback.setText("unavailable");
                } });
            } });
        }
        @Override protected void onDetachedFromWindow() { clear(); super.onDetachedFromWindow(); }
        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (wheelFocus && photo != null && photo.uri.toString().equals(selectedUri)) {
                paint.setColor(CYAN); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4 * scale);
                canvas.drawRect(2 * scale, 2 * scale, getWidth() - 2 * scale, getHeight() - 2 * scale, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    private final class TrashControl extends View {
        TrashControl() {
            super(GalleryView.this.getContext()); setContentDescription("Delete photo");
            setFocusable(true); setClickable(true);
            setOnClickListener(new OnClickListener() { @Override public void onClick(View view) {
                if (actions() && inViewer) askDelete();
            } });
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); int saved = canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f); canvas.scale(scale, scale);
            paint.setColor(WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.5f);
            canvas.drawRect(-11, -10, 11, 15, paint); canvas.drawLine(-15, -15, 15, -15, paint);
            canvas.drawLine(-6, -20, 6, -20, paint); canvas.drawLine(-4, -5, -4, 10, paint);
            canvas.drawLine(4, -5, 4, 10, paint); paint.setStyle(Paint.Style.FILL);
            canvas.restoreToCount(saved);
        }
    }

    private final class DeletionPrompt extends ViewGroup {
        final GalleryStore.Photo photo;
        final TextView question, name, cancel, confirm;
        int choice;
        DeletionPrompt(GalleryStore.Photo photo) {
            super(GalleryView.this.getContext()); this.photo = photo;
            setBackgroundColor(0xfa000000); setClickable(true);
            question = label("delete photo?", 38, CYAN);
            name = label(photo.name + "\nThis deletes the original photo.", 23, WHITE); name.setMaxLines(4);
            cancel = control("cancel", "Cancel deletion", new Runnable() { @Override public void run() { if (prompt == DeletionPrompt.this) cancelPrompt(); } });
            confirm = control("delete", "Confirm deletion", new Runnable() { @Override public void run() { confirmDelete(DeletionPrompt.this); } });
            addView(question); addView(name); addView(cancel); addView(confirm); choose(0);
        }
        void choose(int value) { if (value != choice) tick(); choice = value; cancel.setTextColor(choice == 0 ? CYAN : WHITE); confirm.setTextColor(choice == 1 ? CYAN : WHITE); cancel.setSelected(choice == 0); confirm.setSelected(choice == 1); }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
            measureAt(question, 432, 60); measureAt(name, 432, 108); measureAt(cancel, 204, 70); measureAt(confirm, 204, 70);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            place(question, 24, 205); place(name, 24, 278); place(cancel, 24, 418); place(confirm, 252, 418);
        }
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextColor(color);
        view.setTypeface(RabbitTypography.regular(getContext())); view.setIncludeFontPadding(false);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale); view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private TextView control(String text, String description, Runnable action) {
        TextView view = label(text, 30, WHITE); view.setContentDescription(description);
        view.setGravity(Gravity.CENTER); view.setFocusable(true); view.setClickable(true);
        view.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) {
            if (!active() || busy || !v.isShown()) return;
            if (prompt != null && v != prompt.cancel && v != prompt.confirm) return;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action.run();
        } });
        return view;
    }
    private void measureAt(View view, int w, int h) {
        view.measure(MeasureSpec.makeMeasureSpec(Math.round(w * scale), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(h * scale), MeasureSpec.EXACTLY));
    }
    private void place(View view, int x, int y) {
        int l = Math.round(originX + x * scale), t = Math.round(originY + y * scale);
        view.layout(l, t, l + view.getMeasuredWidth(), t + view.getMeasuredHeight());
    }
    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h); setMeasuredDimension(width, height);
        scale = Math.min(width / 480f, height / 640f); originX = (width - 480 * scale) / 2; originY = (height - 640 * scale) / 2;
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, 46 * scale);
        favorites.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32 * scale);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * scale);
        measureAt(heading, 384, 64); measureAt(favorites, 432, 56);
        grid.setHorizontalSpacing(Math.round(8 * scale)); grid.setVerticalSpacing(Math.round(8 * scale));
        measureAt(grid, 432, inFavorites ? 450 : 386);
        measureAt(viewer, 432, 350); measureAt(caption, 432, 44);
        measureAt(favorite, 204, 56); measureAt(delete, 204, 56); measureAt(message, 432, 160);
        if (prompt != null) prompt.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        place(heading, 72, 96); place(favorites, 24, 166); place(grid, 24, inFavorites ? 166 : 230);
        place(viewer, 24, 166); place(caption, 24, 516); place(favorite, 24, 560); place(delete, 252, 560);
        place(message, 24, 272); if (prompt != null) prompt.layout(0, 0, getWidth(), getHeight());
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); int save = canvas.save(); canvas.translate(originX, originY); canvas.scale(scale, scale);
        paint.setColor(CYAN); paint.setStrokeWidth(2.5f); paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(27, 114, 52, 141, paint); canvas.drawLine(30, 109, 50, 109, paint); canvas.drawLine(33, 104, 47, 104, paint);
        canvas.drawRect(34, 123, 44, 133, paint); canvas.drawLine(39, 119, 39, 137, paint); canvas.drawLine(30, 128, 48, 128, paint);
        if (!inViewer && !inFavorites) {
            paint.setColor(wheelFocus && selected == 0 ? CYAN : MUTED);
            canvas.drawLine(444, 186, 452, 194, paint); canvas.drawLine(452, 194, 444, 202, paint);
        }
        paint.setStyle(Paint.Style.FILL); canvas.restoreToCount(save);
    }
}
