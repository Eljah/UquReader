# Morphology transducer comparison

This document compares the bundled `analyser-gt-desc.hfstol` transducer against the alternative `tatar_last.hfstol` transducer on two unannotated Tatar texts.

## Methodology

1. Compiled the morphology console application directly with `javac` so that it can be run without the Maven Android build chain.
2. Ran the console in batch mode against each text twice, once per transducer, by pointing the `MORPHOLOGY_TRANSDUCER` environment variable at the desired HFST file.
3. Diffed the TSV outputs to identify per-token analysis differences and aggregated recognition statistics.

The commands used:

```bash
# Compile the morphology console application and dependencies
javac -d /tmp/morpho-classes $(find web-app/src/main/java/com/example/uqureader/webapp/morphology -name '*.java') \
  web-app/src/main/java/com/example/uqureader/webapp/MorphologyException.java \
  web-app/src/main/java/com/example/uqureader/webapp/cli/MorphologyConsoleApplication.java

# Analyse an input text with the bundled analyser
MORPHOLOGY_TRANSDUCER="$PWD/web-app/src/main/resources/analyser-gt-desc.hfstol" \
  java -cp /tmp/morpho-classes com.example.uqureader.webapp.cli.MorphologyConsoleApplication \
  < web-app/src/main/resources/texts/harri_potter_ham_lagnetle_bala.txt > /tmp/harry_gt.tsv

# Analyse the same text with tatar_last
MORPHOLOGY_TRANSDUCER="$PWD/web-app/src/main/resources/tatar_last.hfstol" \
  java -cp /tmp/morpho-classes com.example.uqureader.webapp.cli.MorphologyConsoleApplication \
  < web-app/src/main/resources/texts/harri_potter_ham_lagnetle_bala.txt > /tmp/harry_last.tsv
```

## Results

| Text | Tokens | Recognised (`analyser-gt-desc`) | Recognised (`tatar_last`) | Difference count |
| --- | ---: | ---: | ---: | ---: |
| `harri_potter_ham_lagnetle_bala.txt` | 52,497 | 13,131 (25.0%) | 13,435 (25.6%) | 304 |
| `berenche_teatr.txt` | 6,378 | 1,988 (31.2%) | 2,020 (31.7%) | 36 |

All 304 differing tokens in the Harry Potter text were analysed as `NR` (not recognised) by `analyser-gt-desc.hfstol` but received lexical analyses from `tatar_last.hfstol`. The most common improvements include recognising high-frequency particles such as `юк` (`юк+MOD;`), locative forms like `янда`, and participles such as `язган`.

In the theatre text, `tatar_last.hfstol` also provided additional analyses for 34 tokens, mostly the same set of particles (`юк`, `янә`, `ян`) and conjugated verbs (`язды`). However, there were two tokens (`И`) that `analyser-gt-desc.hfstol` tagged (`и+Pcle;иӓш+V+Imprt+Sg2;`) while `tatar_last.hfstol` left them unanalysed.

## Conclusion

`tatar_last.hfstol` consistently recognises more tokens than `analyser-gt-desc.hfstol` on these samples, particularly improving coverage of particles (`юк`, `янә`), locatives (`янда`), and verb forms (`язган`, `язды`). The overall gain is modest (roughly +0.5 percentage points in recognition rate), but the additional lexical information can be valuable when analysing frequent discourse particles. The bundled analyser retains coverage for a couple of tokens (`И`) that the alternative transducer misses, so switching transducers would trade a small number of recognised forms for broader coverage overall.
