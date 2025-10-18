# Yazgi cillar FB2 lemma misalignment investigation

## Symptom
The FB2 edition of *Yazgi cillar* that ships with the reader shows lemmas that belong to other tokens: after the first mismatch every subsequent word is shifted by one entry in the morphology stream when you tap it in the Android app.

## How the FB2 is generated
The MorphToFb2Pipeline chains the morphology processors and finally calls `Morph3Fb2Exporter`, which aligns the tokens from the `*.morph3.tsv` file with the plain text before writing the FB2 output.【F:web-app/src/main/java/com/example/uqureader/webapp/cli/MorphToFb2Pipeline.java†L20-L103】【F:web-app/src/main/java/com/example/uqureader/webapp/cli/Morph3Fb2Exporter.java†L325-L367】

## What goes wrong
The exporter tokenises the original text with the `nextToken` helper. For punctuation it keeps reading characters while the code points stay the same, so a run of dots such as `...` is emitted as a single surface token.【F:web-app/src/main/java/com/example/uqureader/webapp/cli/Morph3Fb2Exporter.java†L369-L403】

In the `yazgi_cillar.txt` source the introduction indeed contains the ASCII sequence `...`, followed immediately by two commas `,,`.【F:web-app/src/main/resources/texts/yazgi_cillar.txt†L1-L8】 The morphology file, however, stores these as individual tokens: it normalises the ellipsis to the single Unicode character `…` and keeps each comma on its own row.【F:web-app/src/main/resources/markup/yazgi_cillar.txt.morph3.tsv†L1-L16】 Because the punctuation strings differ, the exporter cannot match the surface `...` to the stored token `…`.

When a surface string is pure punctuation and there is no match, the exporter returns a synthetic token but leaves the morphology index unchanged. The very next word therefore consumes the previous morphology entry as a fallback, shifting the whole alignment by one (`findMatchingToken` falls back to the first unmatched entry and advances the pointer).【F:web-app/src/main/java/com/example/uqureader/webapp/cli/Morph3Fb2Exporter.java†L435-L455】 Subsequent text tokens continue to draw from the wrong rows, so the lemma displayed in the app always belongs to the preceding morphology row.

## Conclusion
The shift is not introduced by the Android parser; it originates during FB2 generation. Any place where the original text contains repeated punctuation (three dots, double commas, repeated dashes, etc.) will desynchronise the exporter because the text tokeniser groups the run while the morphology table stores each character separately. Normalising punctuation in the exporter before alignment (or tokenising the text the same way as the morphology pipeline) would prevent the misalignment.
