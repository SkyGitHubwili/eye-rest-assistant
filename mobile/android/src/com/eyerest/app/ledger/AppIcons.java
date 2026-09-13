package com.eyerest.app.ledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/** Equal visible bounds for adaptive, legacy and missing-package icons. */
final class AppIcons {
    private static final int SIZE = 128;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(128);

    static Drawable load(Context context, String pkg) {
        Bitmap bitmap = CACHE.get(pkg);
        if (bitmap == null) {
            Drawable source;
            try {
                source = context.getPackageManager().getApplicationIcon(pkg);
            } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
                source = context.getDrawable(android.R.drawable.sym_def_app_icon);
            }
            Drawable.ConstantState state = source.getConstantState();
            if (state != null) source = state.newDrawable(context.getResources()).mutate();
            bitmap = normalize(source);
            CACHE.put(pkg, bitmap);
        }
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    private static Bitmap normalize(Drawable source) {
        Bitmap raw = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Rect previous = new Rect(source.getBounds());
        try {
            source.setBounds(0, 0, SIZE, SIZE);
            source.draw(new Canvas(raw));
        } finally {
            source.setBounds(previous);
        }
        int[] pixels = new int[SIZE * SIZE];
        raw.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        int left = SIZE, top = SIZE, right = -1, bottom = -1;
        // Ignore nearly transparent shadows when measuring the visible icon.
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            if ((pixels[y * SIZE + x] >>> 24) < 16) continue;
            left = Math.min(left, x); right = Math.max(right, x);
            top = Math.min(top, y); bottom = Math.max(bottom, y);
        }
        if (right < left) return raw;
        Rect crop = new Rect(left, top, right + 1, bottom + 1);
        float scale = (SIZE - 4f) / Math.max(crop.width(), crop.height());
        float width = crop.width() * scale, height = crop.height() * scale;
        Bitmap result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(raw, crop,
                new RectF((SIZE - width) / 2, (SIZE - height) / 2,
                        (SIZE + width) / 2, (SIZE + height) / 2),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        raw.recycle();
        return result;
    }
}
