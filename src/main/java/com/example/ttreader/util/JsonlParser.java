package com.example.UquReader.util;

import android.content.Context;
import com.example.UquReader.model.Token;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

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
                t.surface = obj.get("surface").getAsString();
                t.lemma = obj.get("lemma").getAsString();
                t.pos = obj.get("pos").getAsString();
                t.features = new ArrayList<>();
                if (obj.has("features") && obj.get("features").isJsonArray()) {
                    obj.get("features").getAsJsonArray().forEach(e -> t.features.add(e.getAsString()));
                }
                t.start = obj.has("start") ? obj.get("start").getAsInt() : -1;
                t.end = obj.has("end") ? obj.get("end").getAsInt() : -1;
                tokens.add(t);
            }
        }
        return tokens;
    }
}
