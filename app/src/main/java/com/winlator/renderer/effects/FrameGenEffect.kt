package com.winlator.renderer.effects

import android.opengl.GLES20
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.material.ScreenMaterial
import com.winlator.renderer.material.ShaderMaterial

/**
 * GameNative's own frame-generation effect — no external DLLs or paid tools.
 *
 * v1 technique: temporal interpolation. The effect keeps the previously PRESENTED frame in a
 * history texture and outputs `mix(previous, current, weight)`. Combined with the vsync pump in
 * [GLRenderer] (which keeps presenting at display refresh even when the game renders slower),
 * the display receives interpolated in-between images instead of repeats of the same frame —
 * i.e. generated frames. The exponential history trail smooths motion; the cost is mild
 * ghosting on high-contrast edges (v2 will add block motion estimation to warp instead of blend).
 *
 * The weight rises with how "stale" the current game frame is: right after a fresh game frame we
 * show it (weight ~1); on pumped vsyncs between game frames we blend more history for a smoother
 * transition.
 */
class FrameGenEffect : Effect() {

    private var historyTexture = 0
    private var historyWidth = 0
    private var historyHeight = 0
    private var historyValid = false

    /** Blend weight toward the CURRENT frame (1.0 = only current, ignore history). */
    @Volatile var weight: Float = 0.6f

    override fun createMaterial(): ShaderMaterial = FrameGenMaterial()

    override fun onUse(material: ShaderMaterial, renderer: GLRenderer) {
        // History goes on unit 1; the composer binds the current frame on unit 0.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (historyValid) historyTexture else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        material.setUniformInt("prevTexture", 1)
        material.setUniformFloat("fgWeight", if (historyValid) weight.coerceIn(0.05f, 1.0f) else 1.0f)
    }

    /**
     * Copies the frame just presented (currently bound framebuffer) into the history texture.
     * Called by the composer right after this effect's pass renders to screen.
     */
    fun captureHistory(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (historyTexture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            historyTexture = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTexture)
        if (width != historyWidth || height != historyHeight) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, 0, 0, width, height, 0)
            historyWidth = width
            historyHeight = height
        } else {
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        historyValid = true
    }

    /** Drops GL resources (context loss or effect removal). */
    fun invalidate() {
        if (historyTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(historyTexture), 0)
            historyTexture = 0
        }
        historyWidth = 0
        historyHeight = 0
        historyValid = false
    }

    override fun destroy() {
        invalidate()
        super.destroy()
    }

    private class FrameGenMaterial : ScreenMaterial() {
        init {
            setUniformNames("screenTexture", "resolution", "prevTexture", "fgWeight")
        }

        override fun getFragmentShader(): String =
            """
            precision mediump float;
            uniform sampler2D screenTexture;
            uniform sampler2D prevTexture;
            uniform float fgWeight;
            varying vec2 vUV;
            void main() {
                vec3 cur = texture2D(screenTexture, vUV).rgb;
                vec3 prev = texture2D(prevTexture, vUV).rgb;
                gl_FragColor = vec4(mix(prev, cur, fgWeight), 1.0);
            }
            """.trimIndent()
    }
}
