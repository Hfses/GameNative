package com.winlator.xserver;

import android.graphics.Bitmap;

import com.winlator.core.Callback;
import com.winlator.math.Mathf;
import com.winlator.renderer.AHBImage;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.renderer.NativeTexture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Drawable extends XResource {
    private static boolean DRAWABLE_FOR_ASR = false;
    private ByteBuffer data;
    public final short height;
    private boolean offscreenStorage;
    private Callback<Drawable> onDestroyListener;
    private Runnable onDrawListener;
    public final Object renderLock;
    private Texture texture;
    private boolean useSharedData;
    public final Visual visual;
    public final short width;

    // Damage-rect tracking for partial texture uploads (GL path). Drawing operations record the
    // affected region; the renderer consumes it in Texture.updateFromDrawable() to upload only the
    // dirty sub-rectangle instead of the whole frame. Guarded by dirtyLock because drawing happens
    // on the X server thread while consumption happens on the GL thread.
    private final Object dirtyLock = new Object();
    private boolean hasDirtyRect = false;
    private boolean fullDirty = false;
    private int dirtyLeft, dirtyTop, dirtyRight, dirtyBottom;

    private static native void copyArea(short s, short s2, short s3, short s4, short s5, short s6, short s7, short s8, ByteBuffer byteBuffer, ByteBuffer byteBuffer2);

    private static native void copyAreaOp(short s, short s2, short s3, short s4, short s5, short s6, short s7, short s8, ByteBuffer byteBuffer, ByteBuffer byteBuffer2, int i);

    private static native void drawAlphaMaskedBitmap(byte b, byte b2, byte b3, byte b4, byte b5, byte b6, ByteBuffer byteBuffer, ByteBuffer byteBuffer2, ByteBuffer byteBuffer3);

    private static native void drawBitmap(short s, short s2, ByteBuffer byteBuffer, ByteBuffer byteBuffer2);

    private static native void drawLine(short s, short s2, short s3, short s4, int i, short s5, short s6, ByteBuffer byteBuffer);

    private static native void fillRect(short s, short s2, short s3, short s4, int i, short s5, ByteBuffer byteBuffer);

    private static native void fromBitmap(Bitmap bitmap, ByteBuffer byteBuffer);

    public static void DRAWABLE_ASR_MODE(boolean value) {
        DRAWABLE_FOR_ASR = value;
    }

    public static boolean IS_ASR() {
        return DRAWABLE_FOR_ASR;
    }

    static {
        System.loadLibrary("winlator_11");
    }

    public Drawable(int id, int width, int height, Visual visual) {
        super(id);
        this.texture = new Texture();
        this.offscreenStorage = false;
        this.renderLock = new Object();
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;

        if (Drawable.DRAWABLE_FOR_ASR) {
            AHBImage g = new AHBImage((short) width, (short) height);
            this.texture = g;
            this.data = g.getVirtualData();
        } else {
            this.data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    public static Drawable fromBitmap(Bitmap bitmap) {
        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null);
        fromBitmap(bitmap, drawable.data);
        return drawable;
    }

    public boolean isOffscreenStorage() {
        return this.offscreenStorage;
    }

    public void setOffscreenStorage(boolean offscreenStorage) {
        this.offscreenStorage = offscreenStorage;
    }

    public Texture getTexture() {
        return texture;
    }

    public void setTexture(Texture texture) {
        if (texture instanceof NativeTexture) data = ((NativeTexture)texture).getVirtualData();
        this.texture = texture;
    }

    public ByteBuffer getData() {
        return data;
    }

    // Alias for {@link #getData()}. Added to mirror the API expected by
    // {@code com.winlator.renderer.VulkanRenderer} (ported from Winlator-Ludashi),
    // which uses {@code getBuffer()}. Keeping the alias avoids diverging from upstream.
    public ByteBuffer getBuffer() {
        return data;
    }

    public boolean isDirectScanout() {
        return false;
    }

    public void setData(ByteBuffer data) {
        this.data = data;
    }

    private short getStride() {
        return texture instanceof NativeTexture ? ((NativeTexture)texture).getStride() : width;
    }

    public Runnable getOnDrawListener() {
        return onDrawListener;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public Callback<Drawable> getOnDestroyListener() {
        return onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    public void drawImage(short srcX, short srcY, short dstX, short dstY, short width, short height, byte depth, ByteBuffer data, short totalWidth, short totalHeight) {
        ByteBuffer byteBuffer = this.data;
        if (byteBuffer == null) {
            return;
        }
        int damageX = 0, damageY = 0, damageW = 0, damageH = 0;
        if (depth == 1) {
            // Clamp to the destination: drawBitmap writes width*height ints into byteBuffer with no
            // native bounds check, so an oversized width/height from a malicious client would write
            // past the destination allocation (heap corruption). The 24/32-bit path below already
            // clamps; this path must too.
            int w = Math.max(0, Math.min((int) width, this.width));
            int h = Math.max(0, Math.min((int) height, this.height));
            if (w > 0 && h > 0) drawBitmap((short) w, (short) h, data, byteBuffer);
            damageW = w;
            damageH = h;
        }
        else {
            if (depth == 24 || depth == 32) {
                dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
                dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
                if ((dstX + width) > this.width) width = (short)((this.width - dstX));
                if ((dstY + height) > this.height) height = (short)((this.height - dstY));

                copyArea(srcX, srcY, dstX, dstY, width, height, totalWidth, this.getStride(), data, this.data);
                damageX = dstX;
                damageY = dstY;
                damageW = width;
                damageH = height;
            }
        }
        // Single native submit for the whole image. This path (X_PutImage / MIT-SHM) is the hottest
        // request type in the server; the previous code called forceUpdate() twice for 24/32bpp,
        // submitting every frame to the native scanout — and doing the full JNI upload — twice.
        this.data.rewind();
        data.rewind();
        forceUpdate(damageX, damageY, damageW, damageH);
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        if (this.data == null) {
            return dstData;
        }
        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        copyArea(x, y, (short)0, (short)0, width, height, this.getStride(), width, this.data, dstData);

        this.data.rewind();
        dstData.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        if (this.data != null && drawable.data != null) {
            dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
            dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
            if ((dstX + width) > this.width) width = (short)(this.width - dstX);
            if ((dstY + height) > this.height) height = (short)(this.height - dstY);

            // Clamp the SOURCE too. srcX/srcY come straight from the X client and are otherwise
            // unbounded; native copyArea reads srcPixels[srcX + (y+srcY)*srcStride], so an
            // out-of-range source reads past the source drawable (OOB read / info leak or crash).
            srcX = (short)Mathf.clamp(srcX, 0, drawable.width-1);
            srcY = (short)Mathf.clamp(srcY, 0, drawable.height-1);
            if ((srcX + width) > drawable.width) width = (short)(drawable.width - srcX);
            if ((srcY + height) > drawable.height) height = (short)(drawable.height - srcY);
            if (width <= 0 || height <= 0) {
                this.data.rewind();
                drawable.data.rewind();
                return;
            }

            if (gcFunction == GraphicsContext.Function.COPY) {
                copyArea(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.data, this.data);
            }
            else copyAreaOp(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.data, this.data, gcFunction.ordinal());

            this.data.rewind();
            drawable.data.rewind();
            forceUpdate(dstX, dstY, width, height);
        }
    }

    public void fillColor(int color) {
        fillRect(0, 0, width, height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        if (this.data == null) {
            return;
        }
        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)((this.width - x));
        if ((y + height) > this.height) height = (short)((this.height - y));

        fillRect((short)x, (short)y, (short)width, (short)height, color, this.getStride(), this.data);
        this.data.rewind();
        forceUpdate(x, y, width, height);
    }

    public void drawLines(int color, int lineWidth, short... points) {
        for (int i = 2; i < points.length; i += 2) {
            drawLine(points[i-2], points[i-1], points[i+0], points[i+1], color, (short)lineWidth);
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        if (this.data == null) {
            return;
        }
        x0 = Mathf.clamp(x0, 0, width-lineWidth);
        y0 = Mathf.clamp(y0, 0, height-lineWidth);
        x1 = Mathf.clamp(x1, 0, width-lineWidth);
        y1 = Mathf.clamp(y1, 0, height-lineWidth);

        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), this.data);

        this.data.rewind();
        // Damage = bounding box of the line, expanded by the stroke width.
        int minX = Math.min(x0, x1);
        int minY = Math.min(y0, y1);
        forceUpdate(minX, minY, Math.abs(x1 - x0) + lineWidth, Math.abs(y1 - y0) + lineWidth);
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        ByteBuffer byteBuffer;
        ByteBuffer byteBuffer2 = this.data;
        if (byteBuffer2 != null && (byteBuffer = srcDrawable.data) != null) {
            ByteBuffer byteBuffer3 = maskDrawable.data;
            if (byteBuffer3 == null) {
                return;
            }
            drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, byteBuffer, byteBuffer3, byteBuffer2);
            this.data.rewind();
            forceUpdate();
        }
    }

    public void forceUpdate() {
        markAllDirty();
        submitUpdate();
    }

    /**
     * Partial-damage variant of {@link #forceUpdate()}: records only the affected rectangle so the
     * GL renderer can upload just that region ({@code glTexSubImage2D} with
     * {@code GL_UNPACK_ROW_LENGTH}) instead of the full frame.
     */
    public void forceUpdate(int x, int y, int rectWidth, int rectHeight) {
        markDirtyRect(x, y, rectWidth, rectHeight);
        submitUpdate();
    }

    private void submitUpdate() {
        if (!this.offscreenStorage) {
            this.texture.setNeedsUpdate(true);
            Runnable runnable = this.onDrawListener;
            if (runnable != null) {
                runnable.run();
            }
        }
    }

    private void markAllDirty() {
        synchronized (dirtyLock) {
            hasDirtyRect = true;
            fullDirty = true;
        }
    }

    private void markDirtyRect(int x, int y, int rectWidth, int rectHeight) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(this.width, x + rectWidth);
        int bottom = Math.min(this.height, y + rectHeight);
        if (right <= left || bottom <= top) return;
        synchronized (dirtyLock) {
            if (fullDirty) return;
            if (hasDirtyRect) {
                dirtyLeft = Math.min(dirtyLeft, left);
                dirtyTop = Math.min(dirtyTop, top);
                dirtyRight = Math.max(dirtyRight, right);
                dirtyBottom = Math.max(dirtyBottom, bottom);
            }
            else {
                hasDirtyRect = true;
                dirtyLeft = left;
                dirtyTop = top;
                dirtyRight = right;
                dirtyBottom = bottom;
            }
            // If the union covers (almost) everything, promote to a full update to skip the
            // row-by-row partial upload overhead.
            if (dirtyLeft == 0 && dirtyTop == 0 && dirtyRight >= this.width && dirtyBottom >= this.height) {
                fullDirty = true;
            }
        }
    }

    /**
     * Consumes the accumulated damage region. Returns {@code null} when the whole surface must be
     * uploaded (full damage, or no region was recorded — e.g. external components that only call
     * {@code texture.setNeedsUpdate(true)}). Otherwise returns {@code [x, y, width, height]}.
     */
    public int[] consumeDirtyRect() {
        synchronized (dirtyLock) {
            if (!hasDirtyRect || fullDirty) {
                hasDirtyRect = false;
                fullDirty = false;
                return null;
            }
            hasDirtyRect = false;
            return new int[]{dirtyLeft, dirtyTop, dirtyRight - dirtyLeft, dirtyBottom - dirtyTop};
        }
    }

    public boolean isUseSharedData() {
        return this.useSharedData;
    }

    public void setUseSharedData(boolean useSharedData) {
        this.useSharedData = useSharedData;
    }
}
