package com.example.ttreader.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;

import com.example.ttreader.R;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.FeatureMetadata;
import com.example.ttreader.model.MorphFeature;
import com.example.ttreader.model.Morphology;
import com.example.ttreader.util.MorphologyParser;

import com.example.ttreader.util.GrammarResources;

import java.util.List;

public class TokenInfoBottomSheet extends DialogFragment {
    private static final String ARG_SURFACE = "surface";
    private static final String ARG_ANALYSIS = "analysis";
    private static final String ARG_RU = "ru";

    private UsageStatsDao usageStatsDao;
    private String languagePair = "";
    private String workId = "";
    private int charIndex = -1;

    private int fixedWidthPx = ViewGroup.LayoutParams.WRAP_CONTENT;
    private int fixedHeightPx = ViewGroup.LayoutParams.WRAP_CONTENT;
    private View rootView;

    public static TokenInfoBottomSheet newInstance(String surface, String analysis, String ruCsv) {
        TokenInfoBottomSheet f = new TokenInfoBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_SURFACE, surface);
        b.putString(ARG_ANALYSIS, analysis);
        b.putString(ARG_RU, ruCsv);
        f.setArguments(b);
        return f;
    }

    public void setFixedCardDimensions(int widthPx, int heightPx) {
        if (widthPx > 0) {
            fixedWidthPx = widthPx;
        }
        if (heightPx > 0) {
            fixedHeightPx = heightPx;
        }
    }

    public void setUsageStatsDao(UsageStatsDao dao) {
        this.usageStatsDao = dao;
    }

    public void setUsageContext(String languagePair, String workId, int charIndex) {
        this.languagePair = languagePair == null ? "" : languagePair;
        this.workId = workId == null ? "" : workId;
        this.charIndex = charIndex;
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.bottomsheet_token_info, container, false);
        Bundle a = getArguments();
        String surface = a.getString(ARG_SURFACE, "");
        String analysis = a.getString(ARG_ANALYSIS, null);
        String ru = a.getString(ARG_RU, "—");

        Morphology morphology = MorphologyParser.parse(surface, analysis);
        TokenInfoViewBinder.bind(rootView, inflater.getContext(), surface, morphology, ru,
                (m, feature) -> showFeatureDetails(m, feature));
        return rootView;
    }

    @Override public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            if (fixedWidthPx > 0 || fixedHeightPx > 0) {
                int width = fixedWidthPx > 0 ? fixedWidthPx : ViewGroup.LayoutParams.WRAP_CONTENT;
                int height = fixedHeightPx > 0 ? fixedHeightPx : ViewGroup.LayoutParams.WRAP_CONTENT;
                dialog.getWindow().setLayout(width, height);
            }
        }
        if (rootView != null) {
            ViewGroup.LayoutParams lp = rootView.getLayoutParams();
            if (lp == null) {
                lp = new ViewGroup.LayoutParams(fixedWidthPx, fixedHeightPx);
            } else {
                if (fixedWidthPx > 0) {
                    lp.width = fixedWidthPx;
                }
                if (fixedHeightPx > 0) {
                    lp.height = fixedHeightPx;
                }
            }
            if (fixedWidthPx > 0 || fixedHeightPx > 0) {
                rootView.setLayoutParams(lp);
                rootView.setMinimumWidth(fixedWidthPx > 0 ? fixedWidthPx : rootView.getMinimumWidth());
                rootView.setMinimumHeight(fixedHeightPx > 0 ? fixedHeightPx : rootView.getMinimumHeight());
            }
        }
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
            message.append(getString(R.string.feature_dialog_actual,
                    TextUtils.isEmpty(feature.actual) ? getCanonicalSample(feature.canonical) : feature.actual));
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
        if (TextUtils.isEmpty(canonical)) {
            return getString(R.string.feature_no_form);
        }
        String first = canonical.split("/")[0];
        return first.isEmpty() ? getString(R.string.feature_no_form) : first;
    }
}
