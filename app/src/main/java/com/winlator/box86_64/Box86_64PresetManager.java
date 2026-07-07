package com.winlator.box86_64;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

import app.gamenative.R;
import com.winlator.PrefManager;
import com.winlator.core.envvars.EnvVars;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

import timber.log.Timber;

public abstract class Box86_64PresetManager {
    public static EnvVars getEnvVars(String prefix, Context context, String id) {
        String ucPrefix = prefix.toUpperCase(Locale.ENGLISH);
        EnvVars envVars = new EnvVars();

        if (id.equals(Box86_64Preset.STABILITY)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "2");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.equals(Box86_64Preset.COMPATIBILITY)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.equals(Box86_64Preset.INTERMEDIATE)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "1");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");
            }
        } else if (id.equals(Box86_64Preset.PERFORMANCE)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");
            }
        } else if (id.equals(Box86_64Preset.DENUVO)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "3");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.equals(Box86_64Preset.UNITY)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "2");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.equals(Box86_64Preset.UNITY_MONO_BLEEDING_EDGE)) {
            envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix + "_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "0");
            }
        } else if (id.startsWith(Box86_64Preset.CUSTOM)) {
            for (String[] preset : customPresetsIterator(prefix, context)) {
                if (preset[0].equals(id)) {
                    envVars.putAll(preset[2]);
                    break;
                }
            }
        }

        return envVars;
    }

    public static ArrayList<Box86_64Preset> getPresets(String prefix, Context context) {
        ArrayList<Box86_64Preset> presets = new ArrayList<>();
        presets.add(new Box86_64Preset(Box86_64Preset.STABILITY, context.getString(R.string.stability)));
        presets.add(new Box86_64Preset(Box86_64Preset.COMPATIBILITY, context.getString(R.string.compatibility)));
        presets.add(new Box86_64Preset(Box86_64Preset.INTERMEDIATE, context.getString(R.string.intermediate)));
        presets.add(new Box86_64Preset(Box86_64Preset.PERFORMANCE, context.getString(R.string.performance)));
        presets.add(new Box86_64Preset(Box86_64Preset.UNITY, context.getString(R.string.unity)));
        presets.add(new Box86_64Preset(Box86_64Preset.UNITY_MONO_BLEEDING_EDGE, context.getString(R.string.unity_mono_bleeding_edge)));
        presets.add(new Box86_64Preset(Box86_64Preset.DENUVO, context.getString(R.string.denuvo)));
        for (String[] preset : customPresetsIterator(prefix, context))
            presets.add(new Box86_64Preset(preset[0], preset[1]));
        return presets;
    }

    public static Box86_64Preset getPreset(String prefix, Context context, String id) {
        for (Box86_64Preset preset : getPresets(prefix, context))
            if (preset.id.equals(id)) return preset;
        return null;
    }

    private static Iterable<String[]> customPresetsIterator(String prefix, Context context) {
        return loadCustomPresets(prefix, context);
    }

    // Custom presets are stored as a JSON array of {id, name, envVars} objects.
    // Older versions stored them as "id|name|envVars" entries joined with ",",
    // which corrupts as soon as a name or env value contains "|" or "," (e.g.
    // ZINK_DEBUG=compact,deck_emu). Legacy strings are still read and migrated
    // to JSON on the next write.
    private static ArrayList<String[]> loadCustomPresets(String prefix, Context context) {
        PrefManager.init(context);
        final String customPresetsStr = PrefManager.getString(prefix + "_custom_presets", "");
        ArrayList<String[]> presets = new ArrayList<>();
        if (customPresetsStr.isEmpty()) return presets;

        if (customPresetsStr.trim().startsWith("[")) {
            try {
                JSONArray data = new JSONArray(customPresetsStr);
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.getJSONObject(i);
                    presets.add(new String[]{item.getString("id"), item.getString("name"), item.optString("envVars", "")});
                }
            } catch (JSONException e) {
                Timber.e("Failed to parse custom presets: " + e);
            }
        } else {
            for (String entry : customPresetsStr.split(",")) {
                String[] preset = entry.split("\\|");
                // Skip malformed entries (corrupted by the legacy separator format)
                if (preset.length >= 3 && preset[0].startsWith(Box86_64Preset.CUSTOM)) presets.add(preset);
            }
        }
        return presets;
    }

    private static void saveCustomPresets(String prefix, Context context, ArrayList<String[]> presets) {
        PrefManager.init(context);
        JSONArray data = new JSONArray();
        try {
            for (String[] preset : presets) {
                JSONObject item = new JSONObject();
                item.put("id", preset[0]);
                item.put("name", preset[1]);
                item.put("envVars", preset[2]);
                data.put(item);
            }
            PrefManager.putString(prefix + "_custom_presets", data.toString()).get();
        } catch (Exception e) {
            Timber.e("Failed to save custom presets: " + e);
        }
    }

    public static int getNextPresetId(Context context, String prefix) {
        int maxId = 0;
        for (String[] preset : customPresetsIterator(prefix, context)) {
            maxId = Math.max(maxId, Integer.parseInt(preset[0].replace(Box86_64Preset.CUSTOM + "-", "")));
        }
        return maxId + 1;
    }

    public static String editPreset(String prefix, Context context, String id, String name, EnvVars envVars) {
        ArrayList<String[]> presets = loadCustomPresets(prefix, context);
        String presetId = id;

        if (presetId != null) {
            for (String[] preset : presets) {
                if (preset[0].equals(presetId)) {
                    preset[1] = name;
                    preset[2] = envVars.toString();
                    break;
                }
            }
        } else {
            presetId = Box86_64Preset.CUSTOM + "-" + getNextPresetId(context, prefix);
            presets.add(new String[]{presetId, name, envVars.toString()});
        }
        saveCustomPresets(prefix, context, presets);
        return presetId;
    }

    public static String duplicatePreset(String prefix, Context context, String id) {
        ArrayList<Box86_64Preset> presets = getPresets(prefix, context);
        Box86_64Preset originPreset = null;
        for (Box86_64Preset preset : presets) {
            if (preset.id.equals(id)) {
                originPreset = preset;
                break;
            }
        }
        if (originPreset == null) return null;

        String newName;
        for (int i = 1; ; i++) {
            newName = originPreset.name + " (" + i + ")";
            boolean found = false;
            for (Box86_64Preset preset : presets) {
                if (preset.name.equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        return editPreset(prefix, context, null, newName, getEnvVars(prefix, context, originPreset.id));
    }

    public static void removePreset(String prefix, Context context, String id) {
        ArrayList<String[]> presets = loadCustomPresets(prefix, context);
        Iterator<String[]> it = presets.iterator();
        while (it.hasNext()) {
            if (it.next()[0].equals(id)) it.remove();
        }
        saveCustomPresets(prefix, context, presets);
    }

    public static void loadSpinner(String prefix, Spinner spinner, String selectedId) {
        Context context = spinner.getContext();
        ArrayList<Box86_64Preset> presets = getPresets(prefix, context);

        int selectedPosition = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(selectedId)) {
                selectedPosition = i;
                break;
            }
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, presets));
        spinner.setSelection(selectedPosition);
    }

    public static String getSpinnerSelectedId(Spinner spinner) {
        SpinnerAdapter adapter = spinner.getAdapter();
        int selectedPosition = spinner.getSelectedItemPosition();
        if (adapter != null && adapter.getCount() > 0 && selectedPosition >= 0) {
            return ((Box86_64Preset) adapter.getItem(selectedPosition)).id;
        } else return Box86_64Preset.COMPATIBILITY;
    }
}
