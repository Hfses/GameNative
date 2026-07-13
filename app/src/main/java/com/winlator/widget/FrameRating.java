package com.winlator.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.xenvironment.ImageFs;

import app.gamenative.R;
import timber.log.Timber;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Performance overlay: FPS, average frame time, 1% low FPS and RAM usage.
 *
 * All per-frame work is O(1) (ring buffer write + a few compares). The heavier statistics
 * (percentile sort, PSS read) only run on the 500 ms UI refresh tick or the 2 s RAM tick,
 * never in the frame path.
 */
public class FrameRating extends FrameLayout implements Runnable {
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private final TextView textView;

    // FPS reading tracking
    private static final int READING_INTERVAL_MS = 1000; // Take reading every 1 second
    private int readingCount = 0;
    private long sessionStartTime = 0;
    private int maxFPS = 0;
    private int minFPS = Integer.MAX_VALUE;
    private long lastReadingTime = 0;
    private long fpsSum = 0; // Sum of all FPS readings for average calculation

    // Frame time tracking (sliding window over the last WINDOW_SIZE frames)
    private static final int WINDOW_SIZE = 120;
    private final float[] frameTimes = new float[WINDOW_SIZE];
    private int frameTimeIndex = 0;
    private int frameTimeCount = 0;
    private long lastFrameTimestamp = 0;
    private final float[] sortScratch = new float[WINDOW_SIZE];
    private float lastAvgFrameTimeMs = 0;
    private float lastOnePercentLowFPS = 0;
    // Session-wide worst 1% low (for the JSON summary)
    private float sessionWorstOnePercentLow = Float.MAX_VALUE;

    // RAM tracking (sampled every 2s, off the frame path)
    private static final int RAM_INTERVAL_MS = 2000;
    private long lastRamTime = 0;
    private int lastRamMb = 0;
    private int maxRamMb = 0;

    // Boot milestones (elapsedRealtime ms), recorded by XServerScreen via markBootMilestone().
    private static volatile long bootStartMs = 0;
    private static volatile long xServerReadyMs = 0;
    private static volatile long firstFrameMs = 0;

    public FrameRating(Context context) {
        this(context, null);
    }

    public FrameRating(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrameRating(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        textView = view.findViewById(R.id.TVFPS);
        addView(view);
    }

    /** Marks the start of a game boot (container launch requested). */
    public static void markBootStart() {
        bootStartMs = SystemClock.elapsedRealtime();
        xServerReadyMs = 0;
        firstFrameMs = 0;
        Timber.i("[PERF] boot:start=0ms");
    }

    /** Marks the X server ready milestone. */
    public static void markXServerReady() {
        if (bootStartMs == 0 || xServerReadyMs != 0) return;
        xServerReadyMs = SystemClock.elapsedRealtime();
        Timber.i("[PERF] boot:xserver_ready=%dms", xServerReadyMs - bootStartMs);
    }

    /** Marks the first rendered game frame. */
    public static void markFirstFrame() {
        if (bootStartMs == 0 || firstFrameMs != 0) return;
        firstFrameMs = SystemClock.elapsedRealtime();
        Timber.i("[PERF] boot:first_frame=%dms", firstFrameMs - bootStartMs);
    }

    public void update() {
        long time = SystemClock.elapsedRealtime();
        if (lastTime == 0) {
            lastTime = time;
            sessionStartTime = time;
        }

        // Per-frame time sample for frame-time avg and 1% low.
        if (lastFrameTimestamp != 0) {
            float delta = time - lastFrameTimestamp;
            if (delta > 0 && delta < 5000) {
                frameTimes[frameTimeIndex] = delta;
                frameTimeIndex = (frameTimeIndex + 1) % WINDOW_SIZE;
                if (frameTimeCount < WINDOW_SIZE) frameTimeCount++;
            }
        }
        lastFrameTimestamp = time;

        if (time >= lastTime + 500) {
            lastFPS = ((float)(frameCount * 1000) / (time - lastTime));
            computeFrameStats();

            // Take reading at specified interval
            if (lastReadingTime == 0 || time >= lastReadingTime + READING_INTERVAL_MS) {
                int currentFPS = Math.round(lastFPS);
                readingCount++;
                fpsSum += currentFPS;

                // Track max and min FPS (min must be > 1)
                if (currentFPS > maxFPS) {
                    maxFPS = currentFPS;
                }
                if (currentFPS > 1 && currentFPS < minFPS) {
                    minFPS = currentFPS;
                }

                lastReadingTime = time;
            }

            // RAM sample every RAM_INTERVAL_MS, computed here (render thread tick, not per frame).
            if (lastRamTime == 0 || time >= lastRamTime + RAM_INTERVAL_MS) {
                sampleRam();
                lastRamTime = time;
            }

            post(this);
            lastTime = time;
            frameCount = 0;
        }

        frameCount++;
    }

    private void computeFrameStats() {
        int count = frameTimeCount;
        if (count < 10) {
            lastAvgFrameTimeMs = lastFPS > 0 ? 1000f / lastFPS : 0;
            lastOnePercentLowFPS = 0;
            return;
        }
        float sum = 0;
        System.arraycopy(frameTimes, 0, sortScratch, 0, count);
        for (int i = 0; i < count; i++) sum += sortScratch[i];
        lastAvgFrameTimeMs = sum / count;

        // 1% low = FPS equivalent of the mean of the worst 1% frame times (at least 1 frame).
        Arrays.sort(sortScratch, 0, count);
        int worstCount = Math.max(1, count / 100);
        float worstSum = 0;
        for (int i = count - worstCount; i < count; i++) worstSum += sortScratch[i];
        float worstAvgMs = worstSum / worstCount;
        lastOnePercentLowFPS = worstAvgMs > 0 ? 1000f / worstAvgMs : 0;
        if (lastOnePercentLowFPS > 0 && lastOnePercentLowFPS < sessionWorstOnePercentLow) {
            sessionWorstOnePercentLow = lastOnePercentLowFPS;
        }
    }

    private void sampleRam() {
        try {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            int[] pids = { android.os.Process.myPid() };
            Debug.MemoryInfo[] memInfo = am.getProcessMemoryInfo(pids);
            if (memInfo != null && memInfo.length > 0) {
                lastRamMb = memInfo[0].getTotalPss() / 1024;
                if (lastRamMb > maxRamMb) maxRamMb = lastRamMb;
            }
        } catch (Exception e) {
            // Never let metrics sampling take down the render loop.
        }
    }

    public void reset() {
        lastTime = 0;
        frameCount = 0;
        lastFPS = 0;
        readingCount = 0;
        sessionStartTime = 0;
        maxFPS = 0;
        minFPS = Integer.MAX_VALUE;
        lastReadingTime = 0;
        fpsSum = 0;
        frameTimeIndex = 0;
        frameTimeCount = 0;
        lastFrameTimestamp = 0;
        lastAvgFrameTimeMs = 0;
        lastOnePercentLowFPS = 0;
        sessionWorstOnePercentLow = Float.MAX_VALUE;
        lastRamTime = 0;
        lastRamMb = 0;
        maxRamMb = 0;
        post(() -> textView.setText(String.format(Locale.ENGLISH, "%.1f", 0f)));
    }

    /** Returns the most recent measured FPS value for the active session. */
    public float getCurrentFPS() {
        return lastFPS;
    }

    public float getAvgFPS() {
        if (readingCount == 0) return 0;
        return (float) fpsSum / readingCount;
    }

    public float getSessionLengthSec() {
        if (sessionStartTime == 0) return 0;
        return (SystemClock.elapsedRealtime() - sessionStartTime) / 1000.0f;
    }

    public void writeSessionSummary() {
        if (readingCount == 0) return;

        final long sessionLengthMs = sessionStartTime > 0 ?
            SystemClock.elapsedRealtime() - sessionStartTime : 0;
        final float sessionLengthSec = sessionLengthMs / 1000.0f;
        final int max = maxFPS;
        final int min = minFPS == Integer.MAX_VALUE ? 0 : minFPS;
        final float avgFPS = (float) fpsSum / readingCount;
        final float avgFrameTime = lastAvgFrameTimeMs;
        final float onePercentLow = sessionWorstOnePercentLow == Float.MAX_VALUE ? 0 : sessionWorstOnePercentLow;
        final int ramMax = maxRamMb;
        final long bootXServer = (bootStartMs > 0 && xServerReadyMs > 0) ? xServerReadyMs - bootStartMs : 0;
        final long bootFirstFrame = (bootStartMs > 0 && firstFrameMs > 0) ? firstFrameMs - bootStartMs : 0;

        Context context = getContext();
        ImageFs imageFs = ImageFs.find(context);

        File fpsLogFile = new File(imageFs.getTmpDir(), "fps_session" + ".json");
        ExecutorService fileWriteExecutor = Executors.newSingleThreadExecutor();

        fileWriteExecutor.execute(() -> {
            try {
                // Create file if it doesn't exist, or overwrite if it does
                if (!fpsLogFile.exists()) {
                    fpsLogFile.createNewFile();
                }

                // Write JSON format for easy parsing
                String json = String.format(Locale.ENGLISH,
                    "{\n" +
                    "  \"length_sec\": %.2f,\n" +
                    "  \"avg_fps\": %.1f,\n" +
                    "  \"max_fps\": %d,\n" +
                    "  \"min_fps\": %d,\n" +
                    "  \"readings\": %d,\n" +
                    "  \"avg_frame_time_ms\": %.2f,\n" +
                    "  \"one_percent_low_fps\": %.1f,\n" +
                    "  \"max_ram_mb\": %d,\n" +
                    "  \"boot_xserver_ready_ms\": %d,\n" +
                    "  \"boot_first_frame_ms\": %d\n" +
                    "}\n",
                    sessionLengthSec, avgFPS, max, min, readingCount,
                    avgFrameTime, onePercentLow, ramMax, bootXServer, bootFirstFrame);
                try (FileWriter fw = new FileWriter(fpsLogFile, false)) {
                    fw.write(json);
                    fw.flush();
                }
                Timber.d("Session summary written to: %s", fpsLogFile.getAbsolutePath());
            } catch (IOException e) {
                Timber.e(e, "Failed to write session summary");
            } finally {
                fileWriteExecutor.shutdown();
            }
        });
    }

    @Override
    public void run() {
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        if (frameTimeCount >= 10) {
            textView.setText(String.format(Locale.ENGLISH, "%.1f | %.1fms | 1%%: %.0f | %dMB",
                lastFPS, lastAvgFrameTimeMs, lastOnePercentLowFPS, lastRamMb));
        }
        else {
            textView.setText(String.format(Locale.ENGLISH, "%.1f", lastFPS));
        }
    }
}
