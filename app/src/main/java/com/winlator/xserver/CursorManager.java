package com.winlator.xserver;

import android.util.SparseArray;

import java.nio.IntBuffer;

public class CursorManager extends XResourceManager {
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final DrawableManager drawableManager;

    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(0, sourcePixmap.drawable.width, sourcePixmap.drawable.height, sourcePixmap.drawable.visual);
        Cursor cursor = new Cursor(id, x, y, drawable, sourcePixmap.drawable, maskPixmap != null ? maskPixmap.drawable : null);
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    public void freeCursor(int id) {
        Cursor cursor = cursors.get(id);
        // Null guard: freeing an unknown/already-freed cursor id used to pass null into the
        // lifecycle listeners (potential NPE on the X server thread).
        if (cursor == null) return;
        triggerOnFreeResourceListener(cursor);
        cursors.remove(id);
        // The cursor image drawable is created with id 0 (untracked), so DrawableManager never
        // destroys its GL texture — release it here to avoid leaking one texture per cursor change.
        drawableManager.destroyUntrackedDrawable(cursor.cursorImage);
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.getData().asIntBuffer();
        boolean result = true;
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0x000000) {
                result = false;
                break;
            }
        }
        return result;
    }

    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue) {
        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) cursor.cursorImage.drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, cursor.sourceImage, cursor.maskImage);
        }
    }
}
