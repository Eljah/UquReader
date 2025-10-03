package com.example.ttreader.util;

import android.content.Context;

import com.example.ttreader.model.Token;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Utility that loads morphologically annotated documents from assets.
 *
 * <p>The reader currently supports JSONL exports as well as FB2 documents
 * produced by {@code Morph3Fb2Exporter}. The loader chooses the
 * appropriate parser based on the file extension and falls back to the FB2
 * parser if the extension is unknown.</p>
 */
public final class MorphDocumentParser {

    private MorphDocumentParser() {
    }

    public static List<Token> loadFromAssets(Context context, String assetName) throws IOException {
        if (context == null || assetName == null || assetName.isEmpty()) {
            return Collections.emptyList();
        }
        String lower = assetName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jsonl")) {
            return JsonlParser.readTokensFromAssets(context, assetName);
        }
        return Fb2MorphParser.parseAsset(context, assetName);
    }
}
