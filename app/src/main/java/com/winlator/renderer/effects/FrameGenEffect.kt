package com.winlator.renderer.effects

import android.opengl.GLES20
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.RenderTarget
import com.winlator.renderer.material.ScreenMaterial
import com.winlator.renderer.material.ShaderMaterial

/**
 * GameNative's own frame-generation effect — no external DLLs or paid tools.
 *
 * v2 technique: motion-compensated extrapolation. Between two real game frames the display keeps
 * refreshing (vsync pump in [GLRenderer]); instead of repeating the last real frame, we estimate
 * where the on-screen content is moving and warp the current frame forward along that motion, so
 * the eye sees continued movement — generated in-between frames — rather than a stutter.
 *
 * Pipeline per present:
 *  1. [prePass] (run by the composer just before the main draw): a low-res block-matching pass
 *     estimates the per-region motion between the previous real frame ([historyTexture]) and the
 *     current one, packing the motion vector into an RG8 field ([mvBuffer]). Only recomputed when a
 *     new real frame actually arrived ([markRealFrame]); on pumped vsyncs the last field is reused.
 *  2. main draw: the warp shader samples the current frame offset by `phase * motion` — `phase` is
 *     the fraction of a game-frame elapsed since the last real frame (fed by [GLRenderer] timing),
 *     so a fresh real frame (phase≈0) is shown as-is and pumped frames extrapolate progressively.
 *  3. [captureHistory]: only on a real frame, snapshot it as the history for the next estimate — so
 *     the motion search always compares two genuine frames, never a warped one.
 *
 * Deliberately conservative: where block matching can't beat zero-motion it emits a null vector and
 * that region falls back to the plain current frame, so a bad estimate degrades to a repeat rather
 * than tearing. This is honest local motion extrapolation, not interpolation (no added latency).
 */
class FrameGenEffect : Effect() {

    private var historyTexture = 0
    private var historyWidth = 0
    private var historyHeight = 0
    private var historyValid = false

    // Packed prev→cur motion field (RG8), estimated at 1/[MV_DOWNSCALE] of the frame.
    private val mvBuffer = RenderTarget()
    private var mvMaterial: MotionEstimateMaterial? = null
    private var mvValid = false
    private var maxShiftX = 0f
    private var maxShiftY = 0f

    // Set from GLRenderer content/vsync timing on other threads → volatile.
    /** Fraction of a game-frame elapsed since the last real frame (0 = real frame just arrived). */
    @Volatile var phase: Float = 0f
    /** Per-mode cap on how far to extrapolate: higher modes trade a little accuracy for smoother motion. */
    @Volatile var motionAmount: Float = 1.0f
    @Volatile private var realFramePending = false

    // Whether THIS pass renders a real frame (present pure current so history stays clean).
    private var forcePureThisPass = false

    override fun createMaterial(): ShaderMaterial = FrameGenMaterial()

    /** Called from [GLRenderer.onUpdateWindowContent] when the game produced a genuinely new frame. */
    fun markRealFrame() {
        realFramePending = true
    }

    /**
     * Motion-estimation pre-pass. Run by the composer for this effect just BEFORE it binds the main
     * output target, so it can freely bind its own low-res FBO (the composer rebinds the target next).
     * @param currentTextureId the current game frame (the composer's source texture for this effect).
     */
    fun prePass(renderer: GLRenderer, currentTextureId: Int, width: Int, height: Int) {
        forcePureThisPass = realFramePending
        // Need a previous real frame and a genuinely new current frame to estimate motion.
        if (!historyValid || !realFramePending || width <= 0 || height <= 0) return

        val mvW = (width / MV_DOWNSCALE).coerceAtLeast(1)
        val mvH = (height / MV_DOWNSCALE).coerceAtLeast(1)
        mvBuffer.setFilters(GLES20.GL_LINEAR, GLES20.GL_LINEAR)
        mvBuffer.allocateFramebuffer(mvW, mvH)

        val material = mvMaterial ?: MotionEstimateMaterial().also { mvMaterial = it }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mvBuffer.getFramebuffer())
        GLES20.glViewport(0, 0, mvW, mvH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        material.use()
        maxShiftX = SEARCH_RADIUS_TEXELS / width.toFloat()
        maxShiftY = SEARCH_RADIUS_TEXELS / height.toFloat()
        material.setUniformVec2("step", maxShiftX / SEARCH_STEPS, maxShiftY / SEARCH_STEPS)
        material.setUniformVec2("maxShift", maxShiftX, maxShiftY)
        material.setUniformInt("screenTexture", 0)
        material.setUniformInt("prevTexture", 1)

        val quad = renderer.getQuadVertices()
        quad.bind(material.programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quad.count())
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        quad.disable()

        mvValid = true
    }

    override fun onUse(material: ShaderMaterial, renderer: GLRenderer) {
        // History on unit 1, motion field on unit 2; the composer binds the current frame on unit 0.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (historyValid) historyTexture else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (mvValid) mvBuffer.getTextureId() else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        material.setUniformInt("prevTexture", 1)
        material.setUniformInt("mvTexture", 2)
        val active = mvValid && historyValid && !forcePureThisPass
        val effPhase = if (active) (phase * motionAmount).coerceIn(0f, MAX_PHASE) else 0f
        material.setUniformFloat("phase", effPhase)
        material.setUniformVec2("maxShift", maxShiftX, maxShiftY)
        // A touch of history fills gaps opened at the leading edge of large extrapolations.
        material.setUniformFloat("holeBlend", if (effPhase > 0f) 0.15f else 0f)
    }

    /**
     * Copies the frame just presented (currently bound framebuffer) into the history texture — but
     * only when it is a real game frame, so the motion search never compares against a warped frame.
     */
    fun captureHistory(width: Int, height: Int) {
        if (!realFramePending || width <= 0 || height <= 0) return
        realFramePending = false
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
        mvValid = false
        mvBuffer.invalidate()
    }

    override fun destroy() {
        invalidate()
        mvBuffer.destroy()
        mvMaterial = null
        super.destroy()
    }

    companion object {
        private const val MV_DOWNSCALE = 4          // motion field at 1/4 resolution (≈16x fewer texels)
        private const val SEARCH_RADIUS_TEXELS = 16f // max detectable motion, in current-frame texels
        private const val SEARCH_STEPS = 3f          // candidates per axis per side (loop is [-3..3])
        private const val MAX_PHASE = 1.6f           // clamp on extrapolation distance
    }

    /**
     * Low-res block matching: for each output texel, find the offset in the CURRENT frame that best
     * matches the PREVIOUS frame at that spot (prev→cur motion), packed into RG. Conservative — if no
     * offset clearly beats zero motion, emits the null vector (0.5,0.5) so the warp is a plain repeat.
     */
    private class MotionEstimateMaterial : ScreenMaterial() {
        init {
            setUniformNames("screenTexture", "prevTexture", "step", "maxShift")
        }

        override fun getFragmentShader(): String =
            """
            precision highp float;
            uniform sampler2D screenTexture; // current frame
            uniform sampler2D prevTexture;   // previous real frame
            uniform vec2 step;               // candidate offset step (current-frame uv)
            uniform vec2 maxShift;           // packing range (= step * 3)
            varying vec2 vUV;
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            void main() {
                float base = luma(texture2D(prevTexture, vUV).rgb);
                vec2 best = vec2(0.0);
                float bestErr = 1.0e9;
                for (int y = -3; y <= 3; y++) {
                    for (int x = -3; x <= 3; x++) {
                        vec2 s = vec2(float(x), float(y)) * step;
                        float d = abs(luma(texture2D(screenTexture, vUV + s).rgb) - base);
                        // Small penalty biases toward the smallest motion that explains the change.
                        d += 0.015 * (abs(float(x)) + abs(float(y)));
                        if (d < bestErr) { bestErr = d; best = s; }
                    }
                }
                float zeroErr = abs(luma(texture2D(screenTexture, vUV).rgb) - base);
                if (bestErr > zeroErr - 0.012) best = vec2(0.0);
                vec2 packed = clamp(best / (2.0 * maxShift) + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(packed, 0.0, 1.0);
            }
            """.trimIndent()
    }

    /** Warp pass: extrapolate the current frame forward along the estimated motion by `phase`. */
    private class FrameGenMaterial : ScreenMaterial() {
        init {
            setUniformNames("screenTexture", "resolution", "prevTexture", "mvTexture", "phase", "maxShift", "holeBlend")
        }

        override fun getFragmentShader(): String =
            """
            precision highp float;
            uniform sampler2D screenTexture; // current frame
            uniform sampler2D prevTexture;   // previous real frame
            uniform sampler2D mvTexture;     // packed prev->cur motion (RG)
            uniform float phase;             // extrapolation fraction (0 = current as-is)
            uniform vec2 maxShift;           // unpack range
            uniform float holeBlend;
            varying vec2 vUV;
            void main() {
                if (phase <= 0.0) {
                    gl_FragColor = vec4(texture2D(screenTexture, vUV).rgb, 1.0);
                    return;
                }
                vec2 packed = texture2D(mvTexture, vUV).rg;
                vec2 motion = (packed - 0.5) * 2.0 * maxShift; // prev->cur, in uv
                vec2 shift = phase * motion;
                vec3 cur = texture2D(screenTexture, vUV - shift).rgb;
                vec3 prev = texture2D(prevTexture, vUV).rgb;
                gl_FragColor = vec4(mix(cur, prev, holeBlend), 1.0);
            }
            """.trimIndent()
    }
}
