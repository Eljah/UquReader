package com.example.UquReader.reader;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;
import com.example.UquReader.data.DbHelper;
import com.example.UquReader.data.DictionaryDao;
import com.example.UquReader.data.MemoryDao;
import com.example.UquReader.model.Token;
import com.example.UquReader.util.JsonlParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ReaderView extends AppCompatTextView {
    public interface TokenInfoProvider { void onTokenLongPress(TokenSpan span, List<String> ruLemmas); }

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private DictionaryDao dictDao;
    private TokenInfoProvider provider;

    public ReaderView(Context context) { super(context); init(); }
    public ReaderView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ReaderView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        setTextIsSelectable(false);
        setLineSpacing(1.2f, 1.2f);
    }

    public void setup(DbHelper helper, TokenInfoProvider provider) {
        this.dbHelper = helper;
        this.memoryDao = new MemoryDao(helper.getWritableDatabase());
        this.provider = provider;
        try {
            File dict = helper.ensureDictionaryDb();
            this.dictDao = new DictionaryDao(dict);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void loadFromJsonlAsset(String assetName) {
        try {
            List<Token> tokens = JsonlParser.readTokensFromAssets(getContext(), assetName);
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            long now = System.currentTimeMillis();
            double halflife = 7.0; // days

            for (int i=0;i<tokens.size();i++) {
                Token t = tokens.get(i);
                String piece = t.surface;
                int start = ssb.length();
                ssb.append(piece);
                int end = ssb.length();

                String featKey = t.pos + (t.features != null && !t.features.isEmpty() ? "+" + String.join("+", t.features) : "");
                TokenSpan span = new TokenSpan(t.surface, t.lemma, t.pos, featKey);
                double s = memoryDao.getCurrentStrength(t.lemma, featKey, now, halflife);
                double alpha = Math.max(0, 1.0 - Math.min(1.0, s/5.0));
                span.lastAlpha = (float)alpha;
                ssb.setSpan(span, start, end, 0);
                if (i < tokens.size()-1) ssb.append(" ");
            }

            setText(ssb);
            setMovementMethod(new LongPressMovementMethod(span -> {
                List<String> ru = new ArrayList<>();
                if (dictDao != null) dictDao.translateLemmaToRu(span.lemma).forEach(p -> ru.add(p.first));
                if (provider != null) provider.onTokenLongPress(span, ru);
                memoryDao.updateOnLookup(span.lemma, span.featureKey, System.currentTimeMillis(), 1.0);
            }));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
