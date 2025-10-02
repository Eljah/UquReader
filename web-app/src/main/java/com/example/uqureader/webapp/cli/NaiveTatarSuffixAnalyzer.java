package com.example.uqureader.webapp.cli;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Наивный морфоанализатор татарского:
 * - идёт от конца слова, снимает суффиксы по правилам;
 * - правила загружаются из JSON (см. пример ниже);
 * - навешивает теги, НЕ проверяя часть речи и лемму.
 *
 * Подходит как бэкап-анализатор (подсветка морфем, OOV).
 */
public class NaiveTatarSuffixAnalyzer {

    /** Одна поверхностная форма суффикса, уже развёрнутая из rules[].forms[]. */
    public static final class Affix {
        public final String surface;     // строка в орфографии (на письме)
        public final String tag;         // метка (Case=Loc, Number=Plur, Part=DA, ...)
        public final boolean repeatable; // можно ли применять несколько раз (обычно false)
        public final int order;          // порядок снятия: меньше — снимается раньше

        public Affix(String surface, String tag, boolean repeatable, int order) {
            this.surface = surface;
            this.tag = tag;
            this.repeatable = repeatable;
            this.order = order;
        }
    }

    /** Описание правила (как в JSON), с набором surface-форм. */
    public static final class AffixRule {
        public List<String> forms = new ArrayList<>();
        public String tag;
        public boolean repeatable = false;
        public int order = 100;

        public List<Affix> expand() {
            List<Affix> out = new ArrayList<>();
            for (String f : forms) {
                out.add(new Affix(f, tag, repeatable, order));
            }
            return out;
        }
    }

    /** JSON-обёртка для загрузки. */
    public static final class RuleSet {
        public List<AffixRule> rules = new ArrayList<>();
        public Integer maxStrips; // опционально
    }

    /** Результат анализа: основа + теги + снятые суффиксы. */
    public static final class Analysis {
        public final String stem;
        public final List<String> tags;
        public final List<String> removedAffixes;

        public Analysis(String stem, List<String> tags, List<String> removedAffixes) {
            this.stem = stem;
            this.tags = Collections.unmodifiableList(tags);
            this.removedAffixes = Collections.unmodifiableList(removedAffixes);
        }

        @Override public String toString() {
            return stem + "\t" + String.join("+", tags) + "  [" + String.join("-", removedAffixes) + "]";
        }
    }

    // -------------------- Состояние поиска (бэктрекинг) --------------------

    private static class State {
        final String word;
        final List<String> tags;
        final List<String> removedAffixes;
        final Map<String, Integer> usedTagsCount;
        final int strips;

        State(String word, List<String> tags, List<String> removedAffixes,
              Map<String, Integer> usedTagsCount, int strips) {
            this.word = word;
            this.tags = tags;
            this.removedAffixes = removedAffixes;
            this.usedTagsCount = usedTagsCount;
            this.strips = strips;
        }
    }

    // -------------------- Поля анализатора --------------------

    private final List<Affix> affixes; // развёрнутые формы, отсортированы по (order, длине)
    private final int maxStrips;

    private NaiveTatarSuffixAnalyzer(List<Affix> affixes, int maxStrips) {
        this.affixes = affixes;
        this.maxStrips = maxStrips;
    }

    // -------------------- Фабрики --------------------

    /**
     * Загрузить правила из JSON Reader (например, из файла/ресурса).
     * Если maxStrips не указан в JSON — используется defaultMaxStrips.
     */
    public static NaiveTatarSuffixAnalyzer fromJson(Reader jsonReader, int defaultMaxStrips) {
        Gson gson = new GsonBuilder().create();
        RuleSet rs = gson.fromJson(new JsonReader(jsonReader), RuleSet.class);
        if (rs == null || rs.rules == null || rs.rules.isEmpty()) {
            // если JSON пуст — вернём дефолт
            return defaultTatar(defaultMaxStrips);
        }
        int ms = rs.maxStrips != null ? rs.maxStrips : defaultMaxStrips;
        List<Affix> expanded = new ArrayList<>();
        for (AffixRule r : rs.rules) expanded.addAll(r.expand());
        sortAffixes(expanded);
        return new NaiveTatarSuffixAnalyzer(Collections.unmodifiableList(expanded), ms);
    }

    /**
     * Загрузить правила из classpath-ресурса (например, "/suffixes_tat.json").
     * Если ресурс не найден — используется встроенный набор.
     */
    public static NaiveTatarSuffixAnalyzer fromClasspathOrDefault(String resourcePath, int defaultMaxStrips) {
        try (InputStream is = NaiveTatarSuffixAnalyzer.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    return fromJson(r, defaultMaxStrips);
                }
            }
        } catch (IOException ignored) {}
        return defaultTatar(defaultMaxStrips);
    }

    /**
     * Быстрый старт: встроенные правила (базовые падежи, мн. число, частица -да/-дә, немного притяжательных).
     */
    public static NaiveTatarSuffixAnalyzer defaultTatar(int defaultMaxStrips) {
        List<AffixRule> base = new ArrayList<>();

        final int ORD_CASE = 10;
        final int ORD_NUM  = 20;
        final int ORD_POSS = 30;
        final int ORD_PART = 40;

        // Дательный (гармония/варианты):
        base.add(rule(List.of("га","кә","ка","гә"), "Case=Dat", false, ORD_CASE));

        // Местный:
        base.add(rule(List.of("да","дә"), "Case=Loc", false, ORD_CASE));

        // Исходный:
        base.add(rule(List.of("дан","дән","тан","тән"), "Case=Abl", false, ORD_CASE));

        // Родительный (если хотите — часто -ның/-нең):
        base.add(rule(List.of("ның","нең"), "Case=Gen", false, ORD_CASE));

        // Винительный (-ны/-не):
        base.add(rule(List.of("ны","не","ны", "не"), "Case=Acc", false, ORD_CASE));

        // Творительный/совместный (упрощённо: - белән — обычно аналитика; пропустим)
        // Равноправный/сравнительный Equ? (зависит от описания; опущено)

        // Множественное число:
        base.add(rule(List.of("лар","ләр"), "Number=Plur", false, ORD_NUM));

        // Притяжательные (очень грубо; полноценные парадигмы обширнее):
        base.add(rule(List.of("ым","ем"), "Poss=1Sg", false, ORD_POSS));
        base.add(rule(List.of("ың","ең"), "Poss=2Sg", false, ORD_POSS));
        base.add(rule(List.of("ы","е","сы","се"), "Poss=3Sg", false, ORD_POSS));
        base.add(rule(List.of("ыбыз","ебез","сыбыз","себез"), "Poss=1Pl", false, ORD_POSS));
        base.add(rule(List.of("ыгыз","егез","сигез","сегез"), "Poss=2Pl", false, ORD_POSS));

        // Частица -да/-дә (омоним к LOC). Помечаем повторяемой (может «прилипать» после падежа).
        base.add(rule(List.of("да","дә"), "Part=DA", true, ORD_PART));

        // Собираем
        List<Affix> expanded = new ArrayList<>();
        for (AffixRule r : base) expanded.addAll(r.expand());
        sortAffixes(expanded);

        return new NaiveTatarSuffixAnalyzer(Collections.unmodifiableList(expanded), defaultMaxStrips);
    }

    private static AffixRule rule(List<String> forms, String tag, boolean repeatable, int order) {
        AffixRule r = new AffixRule();
        r.forms = forms;
        r.tag = tag;
        r.repeatable = repeatable;
        r.order = order;
        return r;
    }

    private static void sortAffixes(List<Affix> affixes) {
        affixes.sort(
                Comparator.<Affix>comparingInt(a -> a.order)
                        .thenComparing((a, b) -> Integer.compare(b.surface.length(), a.surface.length()))
        );
    }

    // -------------------- Анализ --------------------

    /**
     * Возвращает ВСЕ наивные разборы (бэктрекинг с ограничением по maxStrips).
     * На каждом шаге снимаем один суффикс, если он совпадает с концом слова.
     */
    public List<Analysis> analyze(String token) {
        String w = normalize(token);
        if (w.isEmpty()) return List.of(new Analysis(token, List.of(), List.of()));

        List<Analysis> results = new ArrayList<>();
        Deque<State> stack = new ArrayDeque<>();
        stack.push(new State(w, new ArrayList<>(), new ArrayList<>(), new HashMap<>(), 0));

        while (!stack.isEmpty()) {
            State s = stack.pop();
            boolean stripped = false;

            for (Affix a : affixes) {
                if (s.strips >= maxStrips) break;
                if (s.word.length() <= a.surface.length()) continue;
                if (!s.word.endsWith(a.surface)) continue;

                // запретить повтор метки, если repeatable=false:
                int used = s.usedTagsCount.getOrDefault(a.tag, 0);
                if (!a.repeatable && used > 0) continue;

                String stem = s.word.substring(0, s.word.length() - a.surface.length());

                // простой фильтр, чтобы не «обдирать» до 1 буквы
                if (stem.length() < 2) continue;

                List<String> tags = new ArrayList<>(s.tags);
                tags.add(a.tag);
                List<String> removed = new ArrayList<>(s.removedAffixes);
                removed.add(a.surface);
                Map<String, Integer> usedMap = new HashMap<>(s.usedTagsCount);
                usedMap.put(a.tag, used + 1);

                stack.push(new State(stem, tags, removed, usedMap, s.strips + 1));
                stripped = true;
            }

            if (!stripped) {
                results.add(new Analysis(s.word, s.tags, s.removedAffixes));
            }
        }

        return dedup(results);
    }

    private static List<Analysis> dedup(List<Analysis> in) {
        LinkedHashMap<String, Analysis> map = new LinkedHashMap<>();
        for (Analysis a : in) {
            String key = a.stem + "|" + String.join("+", a.tags);
            map.putIfAbsent(key, a);
        }
        return new ArrayList<>(map.values());
    }

    // -------------------- Утилиты --------------------

    private static final Pattern CYRILLIC = Pattern.compile("[а-яәөүҗңһёА-ЯӘӨҮҖҢҺЁ]+");

    private static String normalize(String w) {
        if (w == null) return "";
        String s = w.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        // уберём не-буквы по краям
        int i = 0, j = s.length();
        while (i < j && !Character.isLetter(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i));
        while (j > i) {
            int cp = s.codePointBefore(j);
            if (Character.isLetter(cp)) break;
            j -= Character.charCount(cp);
        }
        return (i < j) ? s.substring(i, j) : "";
    }

    // -------------------- Пример запуска --------------------

    public static void main(String[] args) throws Exception {
        // 1) Попробуем загрузить из classpath ресурс "/suffixes_tat.json"
        NaiveTatarSuffixAnalyzer analyzer = NaiveTatarSuffixAnalyzer.fromClasspathOrDefault("/suffixes_tat.json", 4);

        // 2) Если передан путь к JSON, используем его
        if (args.length > 0) {
            try (Reader r = new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8)) {
                analyzer = NaiveTatarSuffixAnalyzer.fromJson(r, 4);
            }
        }

        List<String> tests = List.of(
                "татарчага",    // ожидаем X + Case=Dat
                "китаплардан",  // X + Plur + Abl
                "мәктәпләрдә",  // X + Plur + Loc
                "биредә",       // X + Loc (возможен и Part=DA, завист от правил)
                "сүземдә",      // X + Poss + Loc (упрощённо)
                "казандада"     // X + Loc + Part=DA (омонимия)
        );
        for (String t : tests) {
            System.out.println("== " + t);
            for (Analysis a : analyzer.analyze(t)) {
                System.out.println("  " + a);
            }
        }
    }
}
