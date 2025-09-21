package com.example.ttreader.util;

import android.content.Context;

import com.example.ttreader.model.Token;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class JsonlParser {
    public static List<Token> readTokensFromAssets(Context ctx, String assetName) throws IOException {
        List<Token> tokens = new ArrayList<>();
        Gson gson = new Gson();
        try (InputStream is = ctx.getAssets().open(assetName);
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            String line;
            while ((line = br.readLine()) != null) {
                JsonObject obj = gson.fromJson(line, JsonObject.class);
                Token t = new Token();
                if (obj.has("prefix") && !obj.get("prefix").isJsonNull()) {
                    t.prefix = obj.get("prefix").getAsString();
                }
                if (obj.has("surface") && !obj.get("surface").isJsonNull()) {
                    t.surface = obj.get("surface").getAsString();
                }
                if (obj.has("analysis") && !obj.get("analysis").isJsonNull()) {
                    t.analysis = obj.get("analysis").getAsString();
                    t.morphology = MorphologyParser.parse(t.surface, t.analysis);
                }
                if (obj.has("translations") && obj.get("translations").isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray("translations");
                    List<String> translations = new ArrayList<>();
                    for (JsonElement element : arr) {
                        if (element != null && !element.isJsonNull()) {
                            translations.add(element.getAsString());
                        }
                    }
                    if (!translations.isEmpty()) {
                        t.translations = translations;
                    }
                }
                tokens.add(t);
            }
        }
        return tokens;
    }
}
