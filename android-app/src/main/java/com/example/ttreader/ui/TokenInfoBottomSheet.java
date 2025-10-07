package com.example.ttreader.ui;

import android.app.AlertDialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.example.ttreader.R;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.FeatureMetadata;
import com.example.ttreader.model.MorphFeature;
import com.example.ttreader.model.Morphology;
import com.example.ttreader.util.GrammarResources;
import com.example.ttreader.util.MorphologyParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TokenInfoBottomSheet extends DialogFragment {
    private static final String ARG_SURFACE = "surface";
    private static final String ARG_ANALYSIS = "analysis";
    private static final String ARG_TRANSLATIONS = "translations";

    private UsageStatsDao usageStatsDao;
    private String languagePair = "";
    private String workId = "";
    private int charIndex = -1;
    private int fixedWidthPx = 0;
    private int fixedHeightPx = 0;

    public static TokenInfoBottomSheet newInstance(String surface, String analysis, List<String> translations) {
        TokenInfoBottomSheet f = new TokenInfoBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_SURFACE, surface);
        b.putString(ARG_ANALYSIS, analysis);
        if (translations != null) {
            b.putStringArrayList(ARG_TRANSLATIONS, new ArrayList<>(translations));
        }
        f.setArguments(b);
        return f;
    }

    public void setUsageStatsDao(UsageStatsDao dao) {
        this.usageStatsDao = dao;
    }

    public void setUsageContext(String languagePair, String workId, int charIndex) {
        this.languagePair = languagePair == null ? "" : languagePair;
        this.workId = workId == null ? "" : workId;
        this.charIndex = charIndex;
    }

    public void setFixedSize(int widthPx, int heightPx) {
        this.fixedWidthPx = widthPx;
        this.fixedHeightPx = heightPx;
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_token_info, container, false);
        GrammarResources.initialize(inflater.getContext());
        Bundle a = getArguments();
        String surface = a.getString(ARG_SURFACE, "");
        String analysis = a.getString(ARG_ANALYSIS, null);
        List<String> translations = a.getStringArrayList(ARG_TRANSLATIONS);
        if (translations == null) {
            translations = Collections.emptyList();
        }

        Morphology morphology = MorphologyParser.parse(surface, analysis);

        TokenInfoViewBinder.bind(v, surface, morphology, translations,
                (morph, feature) -> showFeatureDetails(morph, feature));

        if (fixedWidthPx > 0) {
            v.setMinimumWidth(fixedWidthPx);
        }
        if (fixedHeightPx > 0) {
            v.setMinimumHeight(fixedHeightPx);
        }
        return v;
    }

    @Override public void onStart() {
        super.onStart();
        if (getDialog() == null) {
            return;
        }
        Window window = getDialog().getWindow();
        if (window == null) {
            return;
        }
        int width = fixedWidthPx > 0 ? fixedWidthPx : WindowManager.LayoutParams.WRAP_CONTENT;
        int height = fixedHeightPx > 0 ? fixedHeightPx : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setLayout(width, height);
    }

    private void showFeatureDetails(Morphology morphology, MorphFeature feature) {
        FeatureMetadata metadata = GrammarResources.getFeatureMetadata(feature.code);
        StringBuilder message = new StringBuilder();
        if (metadata != null) {
            message.append(getString(R.string.feature_dialog_tt, metadata.titleTt)).append('\n');
            message.append(getString(R.string.feature_dialog_ru, metadata.titleRu)).append('\n');
            message.append(getString(R.string.feature_dialog_desc, metadata.descriptionRu)).append('\n');
            if (!TextUtils.isEmpty(feature.actual)) {
                message.append(getString(R.string.feature_dialog_actual, feature.actual)).append('\n');
            }
            if (!metadata.phoneticForms.isEmpty()) {
                message.append(getString(R.string.feature_dialog_variants,
                        TextUtils.join(", ", metadata.phoneticForms))).append('\n');
            }
            if (!metadata.examples.isEmpty()) {
                message.append(getString(R.string.feature_dialog_examples,
                        TextUtils.join("\n", metadata.examples)));
            }
        } else {
            String actual = TextUtils.isEmpty(feature.actual)
                    ? getCanonicalSample(feature.canonical)
                    : feature.actual;
            message.append(getString(R.string.feature_dialog_actual, actual));
        }

        new AlertDialog.Builder(getActivity())
                .setTitle(feature.code)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();

        if (usageStatsDao != null) {
            usageStatsDao.recordEvent(languagePair, workId, morphology.lemma, morphology.pos, feature.code,
                    UsageStatsDao.EVENT_FEATURE, System.currentTimeMillis(), charIndex);
        }
    }

    private String getCanonicalSample(String canonical) {
        if (TextUtils.isEmpty(canonical)) return getString(R.string.feature_no_form);
        String first = canonical.split("/")[0];
        return first.isEmpty() ? getString(R.string.feature_no_form) : first;
    }
}
