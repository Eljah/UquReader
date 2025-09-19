package com.example.ttreader.ui;

import android.app.AlertDialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.ttreader.R;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.FeatureMetadata;
import com.example.ttreader.model.MorphFeature;
import com.example.ttreader.model.Morphology;
import com.example.ttreader.util.GrammarResources;
import com.example.ttreader.util.MorphologyParser;

import java.util.List;

public class TokenInfoBottomSheet extends DialogFragment {
    private static final String ARG_SURFACE = "surface";
    private static final String ARG_ANALYSIS = "analysis";
    private static final String ARG_RU = "ru";

    private UsageStatsDao usageStatsDao;

    public static TokenInfoBottomSheet newInstance(String surface, String analysis, String ruCsv) {
        TokenInfoBottomSheet f = new TokenInfoBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_SURFACE, surface);
        b.putString(ARG_ANALYSIS, analysis);
        b.putString(ARG_RU, ruCsv);
        f.setArguments(b);
        return f;
    }

    public void setUsageStatsDao(UsageStatsDao dao) {
        this.usageStatsDao = dao;
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_token_info, container, false);
        GrammarResources.initialize(inflater.getContext());
        Bundle a = getArguments();
        String surface = a.getString(ARG_SURFACE, "");
        String analysis = a.getString(ARG_ANALYSIS, null);
        String ru = a.getString(ARG_RU, "—");

        Morphology morphology = MorphologyParser.parse(surface, analysis);

        TextView tvSurface = v.findViewById(R.id.tvSurface);
        TextView tvLemma = v.findViewById(R.id.tvLemma);
        TextView tvPos = v.findViewById(R.id.tvPos);
        TextView tvSegments = v.findViewById(R.id.tvSegments);
        TextView tvFeatureCodes = v.findViewById(R.id.tvFeatureCodes);
        TextView tvTranslation = v.findViewById(R.id.tvTranslation);
        LinearLayout featureContainer = v.findViewById(R.id.featureContainer);

        tvSurface.setText(surface);

        if (morphology != null) {
            tvLemma.setText(getString(R.string.lemma_format, morphology.lemma));
            tvPos.setText(getString(R.string.pos_format, GrammarResources.formatPos(morphology.pos)));
            String segmented = morphology.getSegmentedSurface();
            if (!TextUtils.isEmpty(segmented)) {
                tvSegments.setText(getString(R.string.segments_format, segmented));
            } else {
                tvSegments.setVisibility(View.GONE);
            }
            List<String> codes = morphology.getFeatureCodes();
            if (!codes.isEmpty()) {
                tvFeatureCodes.setText(getString(R.string.feature_codes_format, TextUtils.join(" + ", codes)));
            } else {
                tvFeatureCodes.setVisibility(View.GONE);
            }
            populateFeatures(featureContainer, morphology);
        } else {
            tvLemma.setVisibility(View.GONE);
            tvPos.setVisibility(View.GONE);
            tvSegments.setVisibility(View.GONE);
            tvFeatureCodes.setVisibility(View.GONE);
        }

        tvTranslation.setText(getString(R.string.translation_format, ru));
        return v;
    }

    private void populateFeatures(LinearLayout container, Morphology morphology) {
        container.removeAllViews();
        List<MorphFeature> features = morphology.features;
        if (features == null || features.isEmpty()) {
            TextView tv = new TextView(getActivity());
            tv.setText(R.string.no_features_placeholder);
            container.addView(tv);
            return;
        }
        for (MorphFeature feature : features) {
            TextView chip = new TextView(getActivity());
            chip.setBackgroundResource(R.drawable.feature_chip_background);
            chip.setTextSize(16f);
            chip.setText(getString(R.string.feature_chip_format,
                    feature.code,
                    TextUtils.isEmpty(feature.actual) ? getCanonicalSample(feature.canonical) : feature.actual));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) getResources().getDimension(R.dimen.feature_chip_margin_vertical);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> showFeatureDetails(morphology, feature));
            container.addView(chip);
        }
    }

    private String getCanonicalSample(String canonical) {
        if (TextUtils.isEmpty(canonical)) return getString(R.string.feature_no_form);
        String first = canonical.split("/")[0];
        return first.isEmpty() ? getString(R.string.feature_no_form) : first;
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
            usageStatsDao.recordEvent(morphology.lemma, morphology.pos, feature.code,
                    UsageStatsDao.EVENT_FEATURE, System.currentTimeMillis());
        }
    }
}
