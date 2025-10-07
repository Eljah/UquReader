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

import java.util.List;

/**
 * Utility responsible for populating the token information card view.
 */
public final class TokenInfoViewBinder {

    private TokenInfoViewBinder() {
    }

    public interface FeatureClickHandler {
        void onFeatureClick(Morphology morphology, MorphFeature feature);
    }

    public static void bind(View root, Context context, String surface,
            Morphology morphology, String translationText,
            FeatureClickHandler featureClickHandler) {
        if (root == null || context == null) {
            return;
        }

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
                tvLemma.setVisibility(View.VISIBLE);
                tvLemma.setText(context.getString(R.string.lemma_format, morphology.lemma));
            }
            if (tvPos != null) {
                tvPos.setVisibility(View.VISIBLE);
                tvPos.setText(context.getString(R.string.pos_format,
                        GrammarResources.formatPos(morphology.pos)));
            }
            String segmented = morphology.getSegmentedSurface();
            if (tvSegments != null) {
                if (!TextUtils.isEmpty(segmented)) {
                    tvSegments.setVisibility(View.VISIBLE);
                    tvSegments.setText(context.getString(R.string.segments_format, segmented));
                } else {
                    tvSegments.setVisibility(View.GONE);
                }
            }
            if (tvFeatureCodes != null) {
                List<String> codes = morphology.getFeatureCodes();
                if (!codes.isEmpty()) {
                    tvFeatureCodes.setVisibility(View.VISIBLE);
                    tvFeatureCodes.setText(context.getString(R.string.feature_codes_format,
                            TextUtils.join(" + ", codes)));
                } else {
                    tvFeatureCodes.setVisibility(View.GONE);
                }
            }
            if (featureContainer != null) {
                populateFeatures(context, featureContainer, morphology, featureClickHandler);
            }
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
            String safeTranslation = TextUtils.isEmpty(translationText) ? "—" : translationText;
            tvTranslation.setText(context.getString(R.string.translation_format, safeTranslation));
        }
    }

    private static void populateFeatures(Context context, LinearLayout container,
            Morphology morphology, FeatureClickHandler featureClickHandler) {
        container.removeAllViews();
        List<MorphFeature> features = morphology.features;
        if (features == null || features.isEmpty()) {
            TextView tv = new TextView(context);
            tv.setText(R.string.no_features_placeholder);
            container.addView(tv);
            return;
        }
        int topMargin = (int) context.getResources()
                .getDimension(R.dimen.feature_chip_margin_vertical);
        for (MorphFeature feature : features) {
            if (feature == null) continue;
            TextView chip = new TextView(context);
            chip.setBackgroundResource(R.drawable.feature_chip_background);
            chip.setTextSize(16f);
            String actual = TextUtils.isEmpty(feature.actual)
                    ? getCanonicalSample(context, feature.canonical)
                    : feature.actual;
            chip.setText(context.getString(R.string.feature_chip_format,
                    feature.code, actual));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = topMargin;
            chip.setLayoutParams(lp);
            if (featureClickHandler != null) {
                chip.setOnClickListener(v -> featureClickHandler.onFeatureClick(morphology, feature));
            }
            container.addView(chip);
        }
    }

    private static String getCanonicalSample(Context context, String canonical) {
        if (TextUtils.isEmpty(canonical)) {
            return context.getString(R.string.feature_no_form);
        }
        String first = canonical.split("/")[0];
        if (TextUtils.isEmpty(first)) {
            return context.getString(R.string.feature_no_form);
        }
        return first;
    }
}
