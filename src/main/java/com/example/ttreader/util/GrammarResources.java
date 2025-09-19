package com.example.ttreader.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

import com.example.ttreader.model.FeatureMetadata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GrammarResources {
    private static final String TAG = "GrammarResources";
    private static final String DEFAULT_ASSET = "tt-ru.json";
    private static final Map<String, String[]> POS_MAP = new HashMap<>();
    private static final Map<String, FeatureMetadata> FEATURE_MAP = new HashMap<>();

    private static boolean initialized = false;
    private static String assetName = DEFAULT_ASSET;

    private GrammarResources() {}

    public static synchronized void useLanguagePairAsset(String asset) {
        if (TextUtils.isEmpty(asset)) return;
        if (!asset.equals(assetName)) {
            assetName = asset;
            initialized = false;
            POS_MAP.clear();
            FEATURE_MAP.clear();
        }
    }

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        if (context == null) {
            Log.w(TAG, "Context is null, cannot initialize grammar resources");
            return;
        }
        loadFromAsset(context.getApplicationContext(), assetName);
    }

    private static void loadFromAsset(Context context, String asset) {
        AssetManager assets = context.getAssets();
        try (InputStream is = assets.open(asset);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            parseJson(sb.toString());
            initialized = true;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load grammar resources from " + asset, e);
        }
    }

    private static void parseJson(String json) throws JSONException {
        POS_MAP.clear();
        FEATURE_MAP.clear();

        JSONObject root = new JSONObject(json);
        JSONArray posArray = root.optJSONArray("pos");
        if (posArray != null) {
            for (int i = 0; i < posArray.length(); i++) {
                JSONObject posObj = posArray.optJSONObject(i);
                if (posObj == null) continue;
                String code = posObj.optString("code");
                String tt = posObj.optString("titleTt");
                String ru = posObj.optString("titleRu");
                if (!TextUtils.isEmpty(code) && (!TextUtils.isEmpty(tt) || !TextUtils.isEmpty(ru))) {
                    POS_MAP.put(code, new String[]{tt, ru});
                }
            }
        }

        JSONArray featureArray = root.optJSONArray("features");
        if (featureArray != null) {
            for (int i = 0; i < featureArray.length(); i++) {
                JSONObject featureObj = featureArray.optJSONObject(i);
                if (featureObj == null) continue;
                String code = featureObj.optString("code");
                if (TextUtils.isEmpty(code)) continue;
                String titleRu = featureObj.optString("titleRu");
                String titleTt = featureObj.optString("titleTt");
                String descriptionRu = featureObj.optString("descriptionRu");
                List<String> phoneticForms = jsonArrayToList(featureObj.optJSONArray("phoneticForms"));
                List<String> examples = jsonArrayToList(featureObj.optJSONArray("examples"));
                FEATURE_MAP.put(code, new FeatureMetadata(code, titleRu, titleTt, descriptionRu, phoneticForms, examples));
            }
        }
    }

    private static List<String> jsonArrayToList(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, null);
            if (!TextUtils.isEmpty(value)) {
                list.add(value);
            }
        }
        return list.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    public static String formatPos(String code) {
        if (code == null) return "";
        String[] names = POS_MAP.get(code);
        if (names == null) return code;
        String tt = names.length > 0 ? names[0] : null;
        String ru = names.length > 1 ? names[1] : null;
        if (!TextUtils.isEmpty(tt) && !TextUtils.isEmpty(ru)) {
            return tt + " / " + ru;
        }
        if (!TextUtils.isEmpty(tt)) {
            return tt;
        }
        if (!TextUtils.isEmpty(ru)) {
            return ru;
        }
        return code;
    }

    public static FeatureMetadata getFeatureMetadata(String code) {
        return FEATURE_MAP.get(code);
    }
}
