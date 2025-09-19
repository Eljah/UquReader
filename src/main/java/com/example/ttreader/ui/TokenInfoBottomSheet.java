package com.example.UquReader.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.example.UquReader.R;

public class TokenInfoBottomSheet extends DialogFragment {
    public static final String ARG_SURFACE = "surface";
    public static final String ARG_LEMMA = "lemma";
    public static final String ARG_POS = "pos";
    public static final String ARG_FEATURES = "features";
    public static final String ARG_RU = "ru";

    public static TokenInfoBottomSheet newInstance(String surface, String lemma, String pos, String features, String ruCsv) {
        TokenInfoBottomSheet f = new TokenInfoBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_SURFACE, surface);
        b.putString(ARG_LEMMA, lemma);
        b.putString(ARG_POS, pos);
        b.putString(ARG_FEATURES, features);
        b.putString(ARG_RU, ruCsv);
        f.setArguments(b);
        return f;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_token_info, container, false);
        Bundle a = getArguments();
        ((TextView)v.findViewById(R.id.tvSurface)).setText(a.getString(ARG_SURFACE, ""));
        ((TextView)v.findViewById(R.id.tvLemma)).setText("lemma: " + a.getString(ARG_LEMMA, ""));
        ((TextView)v.findViewById(R.id.tvPos)).setText("POS: " + a.getString(ARG_POS, ""));
        ((TextView)v.findViewById(R.id.tvFeatures)).setText("features: " + a.getString(ARG_FEATURES, ""));
        ((TextView)v.findViewById(R.id.tvTranslation)).setText("→ " + a.getString(ARG_RU, "—"));
        return v;
    }
}
