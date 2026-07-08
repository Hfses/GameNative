package com.winlator.core;

import android.app.ActivityManager;
import android.content.Context;

import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

public class DXVKHelper {
    // async/asyncCache included so containers that fall back to this default (empty dxWrapperConfig)
    // still get async pipeline compilation + the gplasync on-disk cache, matching the container-level
    // DEFAULT_DXWRAPPERCONFIG. Without them, edge-case/legacy containers ran with async disabled.
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0"
        + ",async="+DefaultVersion.ASYNC+",asyncCache="+DefaultVersion.ASYNC_CACHE;

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("DXVK_STATE_CACHE_PATH", "/data/data/app.gamenative/files/imagefs"+ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = ImageFs.find(context).getRootDir();
        File dxvkConfigFile = new File(imageFs.config_path+"/dxvk.conf");

        String content = "\"";
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+"\n";
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+"\n";
        } else {
            // No explicit cap: derive a generous one from physical RAM. On unified-memory SoCs an
            // unbounded (0) budget makes DXVK report the full Vulkan heap, so games size resource
            // pools as if they had discrete VRAM and over-commit → Android paging/lowmemorykiller
            // thrash or a guest OOM. A ~70% cap (floor 2GB) is well above any mobile game's real
            // need, so it only trims the pathological case, not normal play.
            long capMb = deviceMemoryCapMb(context);
            if (capMb > 0) {
                content += "dxgi.maxDeviceMemory = "+capMb+"\n";
                content += "dxgi.maxSharedMemory = "+capMb+"\n";
            }
        }

        String maxFeatureLevel = config.get("maxFeatureLevel");
        if (!maxFeatureLevel.isEmpty() && !maxFeatureLevel.equals("0")) {
            content += "d3d11.maxFeatureLevel = "+maxFeatureLevel+"\n";
            envVars.put("DXVK_FEATURE_LEVEL", maxFeatureLevel);
        }


        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        // maxFrameLatency (0-16): trade input latency for steadier frame pacing.
        String maxFrameLatency = config.get("maxFrameLatency");
        if (!maxFrameLatency.isEmpty() && !maxFrameLatency.equals("0")) {
            content += "dxvk.maxFrameLatency = " + maxFrameLatency + "\n";
        }

        // numCompilerThreads: cap pipeline-compilation threads so they don't steal the
        // big-cores from the game and Box64, which reduces frametime spikes on shader compile.
        String numCompilerThreads = config.get("numCompilerThreads");
        if (!numCompilerThreads.isEmpty() && !numCompilerThreads.equals("0")) {
            content += "dxvk.numCompilerThreads = " + numCompilerThreads + "\n";
        } else {
            // No explicit value: cap pipeline-compiler threads so shader compilation doesn't fight
            // Box64/Wine for the big cores (a common source of frametime spikes on shader-heavy
            // scenes). Clamp to [1,4]; the gplasync on-disk cache we enabled softens the first-run
            // compile cost this trades for.
            int cores = Runtime.getRuntime().availableProcessors();
            int threads = Math.max(1, Math.min(4, cores / 2 - 1));
            content += "dxvk.numCompilerThreads = " + threads + "\n";
        }
        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            String[] parts = customDevice.split(":");
            content = (((((content + "dxgi.customDeviceId = " + parts[0] + "\n") + "dxgi.customVendorId = " + parts[1] + "\n") + "d3d9.customDeviceId = " + parts[0] + "\n") + "d3d9.customVendorId = " + parts[1] + "\n") + "dxgi.customDeviceDesc = \"" + parts[2] + "\"\n") + "d3d9.customDeviceDesc = \"" + parts[2] + "\"\n";
        }
        if (config.getBoolean("constantBufferRangeCheck")) {
            content = content + "d3d11.constantBufferRangeCheck = \"True\"\n";
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        content = content + '\"';


        envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
        envVars.put("DXVK_CONFIG", content);
    }

    public static void setVKD3DEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String featureLevel = config.get("vkd3dFeatureLevel", "12_1");
        envVars.put("VKD3D_FEATURE_LEVEL", featureLevel);
        // Persist the vkd3d-proton pipeline cache alongside DXVK's. Without a stable path vkd3d
        // writes into the process CWD (or disables the cache), so D3D12 titles recompiled every PSO
        // on every launch — the worst first-minutes stutter. Shares the dir with DXVK safely
        // (vkd3d uses a fixed "vkd3d-proton.cache" name; DXVK names per-exe).
        envVars.put("VKD3D_SHADER_CACHE_PATH", "/data/data/app.gamenative/files/imagefs"+ImageFs.CACHE_PATH);
    }

    /** ~70% of physical RAM in MB (floor 2048), or 0 if it can't be read. */
    private static long deviceMemoryCapMb(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long totalMb = mi.totalMem / (1024L * 1024L);
            if (totalMb <= 0) return 0;
            return Math.max(2048L, totalMb * 70 / 100);
        } catch (Exception e) {
            return 0;
        }
    }
}
