package com.subhub.app.studio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Bounded decorative thumbnails. Call from a background storage worker. */
public final class PackCoverArtwork {
    private PackCoverArtwork() { }

    public static Bitmap decode(byte[] bytes, int maximumEdge) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0
                || options.outWidth > 32768 || options.outHeight > 32768) return null;
        int edge = Math.max(1, maximumEdge);
        options.inSampleSize = 1;
        while ((options.outWidth - 1) / options.inSampleSize + 1 > edge
                || (options.outHeight - 1) / options.inSampleSize + 1 > edge) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
