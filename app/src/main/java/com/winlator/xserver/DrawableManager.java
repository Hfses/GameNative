package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.core.Callback;
import com.winlator.renderer.Texture;
import com.winlator.xenvironment.components.VortekRendererComponent;

import java.util.Objects;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        return drawables.get(id);
    }

    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) return new Drawable(id, width, height, visual);
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual);
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);
        // Guard against double-removal: onFreeResource() (pixmap free) can race with a direct
        // removeDrawable() for the same id; the second call used to NPE on the X server thread,
        // killing the whole session.
        if (drawable == null) return;

        final Texture texture = drawable.getTexture();
//        if (texture != null) {
//            XServerView xServerView = this.xServer.getRenderer().xServerView;
//            Objects.requireNonNull(texture);
//            xServerView.queueEvent(() -> VortekRendererComponent.destroyTexture(texture));
//        }
        if (texture != null) xServer.getRenderer().getRendererView().queueEvent(texture::destroy);

        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) onDestroyListener.call(drawable);

        drawable.setOnDrawListener(null);
        drawables.remove(id);
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) removeDrawable(((Pixmap)resource).drawable.id);
    }

    /**
     * Destroys the GL texture of a drawable that is NOT tracked in the id map (e.g. cursor images,
     * which are created with id 0). Without this, every cursor change leaked one GPU texture.
     */
    public void destroyUntrackedDrawable(Drawable drawable) {
        if (drawable == null) return;
        final Texture texture = drawable.getTexture();
        if (texture != null) xServer.getRenderer().getRendererView().queueEvent(texture::destroy);
        drawable.setOnDrawListener(null);
    }

    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }

    public SparseArray<Drawable> all(){
        return drawables;
    }
}
