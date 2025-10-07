package com.example.ttreader.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.ttreader.R;
import com.example.ttreader.model.MorphFeature;
import com.example.ttreader.model.Morphology;
import com.example.ttreader.util.GrammarResources;

import java.util.Collections;
import java.util.List;

public final class TokenInfoViewBinder {

    private TokenInfoViewBinder() {}

    public interface FeatureClickListener {
        void onFeatureClick(Morphology morphology, MorphFeature feature);
    }

    public static void bind(View root, String surface, Morphology morphology,
                            List<String> translations, FeatureClickListener featureClickListener) {
        if (root == null) {
            return;
        }
        Context context = root.getContext();
        GrammarResources.initialize(context);

        TextView tvSurface = root.findViewById(R.id.tvSurface);
        TextView tvLemma = root.findViewById(R.id.tvLemma);
        TextView tvPos = root.findViewById(R.id.tvPos);
        TextView tvSegments = root.findViewById(R.id.tvSegments);
        TextView tvFeatureCodes = root.findViewById(R.id.tvFeatureCodes);
        TextView tvTranslation = root.findViewById(R.id.tvTranslation);
        LinearLayout featureContainer = root.findViewById(R.id.featureContainer);

        if (tvSurface != null) {
            tvSurface.setText(surface == null ? "" : surface);
        }

        if (morphology != null) {
            if (tvLemma != null) {
                if (!TextUtils.isEmpty(morphology.lemma)) {
                    tvLemma.setText(context.getString(R.string.lemma_format, morphology.lemma));
                    tvLemma.setVisibility(View.VISIBLE);
                } else {
                    tvLemma.setVisibility(View.GONE);
                }
            }

            if (tvPos != null) {
                if (!TextUtils.isEmpty(morphology.pos)) {
                    tvPos.setText(context.getString(R.string.pos_format,
                            GrammarResources.formatPos(morphology.pos)));
                    tvPos.setVisibility(View.VISIBLE);
                } else {
                    tvPos.setVisibility(View.GONE);
                }
            }

            if (tvSegments != null) {
                String segmented = morphology.getSegmentedSurface();
                if (!TextUtils.isEmpty(segmented)) {
                    tvSegments.setText(context.getString(R.string.segments_format, segmented));
                    tvSegments.setVisibility(View.VISIBLE);
                } else {
                    tvSegments.setVisibility(View.GONE);
                }
            }

            if (tvFeatureCodes != null) {
                List<String> codes = morphology.getFeatureCodes();
                if (codes != null && !codes.isEmpty()) {
                    tvFeatureCodes.setText(context.getString(R.string.feature_codes_format,
                            TextUtils.join(" + ", codes)));
                    tvFeatureCodes.setVisibility(View.VISIBLE);
                } else {
                    tvFeatureCodes.setVisibility(View.GONE);
                }
            }

            populateFeatures(context, featureContainer, morphology, featureClickListener);
        } else {
            if (tvLemma != null) tvLemma.setVisibility(View.GONE);
            if (tvPos != null) tvPos.setVisibility(View.GONE);
            if (tvSegments != null) tvSegments.setVisibility(View.GONE);
            if (tvFeatureCodes != null) tvFeatureCodes.setVisibility(View.GONE);
            if (featureContainer != null) {
                featureContainer.removeAllViews();
            }
        }

        if (tvTranslation != null) {
            List<String> safeTranslations = translations == null ? Collections.emptyList() : translations;
            String translationText = safeTranslations.isEmpty()
                    ? "—"
                    : TextUtils.join(", ", safeTranslations);
            tvTranslation.setText(context.getString(R.string.translation_format, translationText));
        }
    }

    private static void populateFeatures(Context context, LinearLayout container, Morphology morphology,
                                         FeatureClickListener featureClickListener) {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        List<MorphFeature> features = morphology != null ? morphology.features : null;
        if (features == null || features.isEmpty()) {
            TextView tv = new TextView(context);
            tv.setText(R.string.no_features_placeholder);
            container.addView(tv);
            return;
        }
        int topMargin = context.getResources()
                .getDimensionPixelSize(R.dimen.feature_chip_margin_vertical);
        for (MorphFeature feature : features) {
            TextView chip = new TextView(context);
            chip.setBackgroundResource(R.drawable.feature_chip_background);
            chip.setTextSize(16f);
            String actual = TextUtils.isEmpty(feature.actual)
                    ? getCanonicalSample(feature.canonical, context)
                    : feature.actual;
            chip.setText(context.getString(R.string.feature_chip_format, feature.code, actual));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = topMargin;
            chip.setLayoutParams(lp);
            if (featureClickListener != null) {
                chip.setOnClickListener(v -> featureClickListener.onFeatureClick(morphology, feature));
            }
            container.addView(chip);
        }
    }

    private static String getCanonicalSample(String canonical, Context context) {
        if (TextUtils.isEmpty(canonical)) {
            return context.getString(R.string.feature_no_form);
        }
        String first = canonical.split("/")[0];
        return TextUtils.isEmpty(first)
                ? context.getString(R.string.feature_no_form)
                : first;
    }
}

