package com.example.ttreader.reader;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.widget.TextView;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.DictionaryDao;
import com.example.ttreader.data.MemoryDao;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.Morphology;
import com.example.ttreader.model.Token;
import com.example.ttreader.util.JsonlParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ReaderView extends TextView {
    public interface TokenInfoProvider { void onTokenLongPress(TokenSpan span, List<String> ruLemmas); }

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageDao;
    private DictionaryDao dictDao;
    private TokenInfoProvider provider;

    public ReaderView(Context context) { super(context); init(); }
    public ReaderView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ReaderView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        setTextIsSelectable(false);
        setLineSpacing(1.2f, 1.2f);
    }

    public void setup(DbHelper helper, MemoryDao memoryDao, UsageStatsDao usageDao, TokenInfoProvider provider) {
        this.dbHelper = helper;
        this.memoryDao = memoryDao;
        this.usageDao = usageDao;
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

            for (Token t : tokens) {
                if (t.prefix != null && !t.prefix.isEmpty()) ssb.append(t.prefix);
                int start = ssb.length();
                if (t.surface != null) ssb.append(t.surface);
                int end = ssb.length();

                if (t.hasMorphology() && t.surface != null && !t.surface.isEmpty()) {
                    Morphology morph = t.morphology;
                    TokenSpan span = new TokenSpan(t);
                    double s = memoryDao.getCurrentStrength(morph.lemma, span.featureKey, now, halflife);
                    double alpha = Math.max(0, 1.0 - Math.min(1.0, s/5.0));
                    span.lastAlpha = (float)alpha;
                    ssb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (usageDao != null) {
                        usageDao.recordEvent(morph.lemma, morph.pos, null, UsageStatsDao.EVENT_EXPOSURE, now);
                    }
                }
            }

            setText(ssb);
            setMovementMethod(new LongPressMovementMethod(span -> {
                if (span == null || span.token == null || span.token.morphology == null) return;
                Morphology morph = span.token.morphology;
                List<String> ru = new ArrayList<>();
                if (dictDao != null) dictDao.translateLemmaToRu(morph.lemma).forEach(p -> ru.add(p.first));
                if (usageDao != null) {
                    usageDao.recordEvent(morph.lemma, morph.pos, null, UsageStatsDao.EVENT_LOOKUP, System.currentTimeMillis());
                }
                if (provider != null) provider.onTokenLongPress(span, ru);
                memoryDao.updateOnLookup(morph.lemma, span.featureKey, System.currentTimeMillis(), 1.0);
            }));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
