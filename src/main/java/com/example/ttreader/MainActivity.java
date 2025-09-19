package com.example.UquReader;

import android.app.Activity;
import android.os.Bundle;
import com.example.UquReader.data.DbHelper;
import com.example.UquReader.reader.ReaderView;
import com.example.UquReader.reader.TokenSpan;
import com.example.UquReader.ui.TokenInfoBottomSheet;
import java.util.List;

public class MainActivity extends Activity implements ReaderView.TokenInfoProvider {
    private DbHelper dbHelper;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new DbHelper(this);

        ReaderView reader = findViewById(R.id.readerView);
        reader.setup(dbHelper, this);
        reader.loadFromJsonlAsset("sample_book.ttmorph.jsonl");
    }

    @Override public void onTokenLongPress(TokenSpan span, List<String> ruLemmas) {
        String ruCsv = ruLemmas.isEmpty()? "—" : String.join(", ", ruLemmas);
        TokenInfoBottomSheet.newInstance(span.surface, span.lemma, span.pos, span.featureKey, ruCsv)
                .show(getFragmentManager(), "token-info");
    }
}
