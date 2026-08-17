package com.example.uqureader.webapp.reader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ViennaGrammarCatalog {
    private static final Map<String, Description> POS = new LinkedHashMap<>();
    private static final Map<String, Description> SUFFIX_POS = new LinkedHashMap<>();
    private static final Map<String, Description> GLOSS = new LinkedHashMap<>();

    static {
        pos("ad", "прилагательное", "ош - ош мардеж", "белый - белый ветер");
        pos("av", "наречие", "чот - чот йӱк", "сильно - громкий звук");
        pos("co", "союз", "да - ачам да авам", "и - отец и мать");
        pos("de", "указательное/местоименное слово", "тиде - тиде книга", "этот - эта книга");
        pos("in", "междометие", "ой - ой, мом ыштет?", "ой - ой, что делаешь?");
        pos("na", "имя собственное", "Казань - Казаныште", "Казань - в Казани");
        pos("nm", "числительное", "ик - ик книга", "один - одна книга");
        pos("no", "существительное", "ача - ачам", "отец - отца");
        pos("pa", "частица", "ат - тудо ат толеш", "же/ведь - он ведь приходит");
        pos("pn", "личное/указательное местоимение", "тудо - тудын", "он/она - его/ее");
        pos("po", "послелог", "дене - ачам дене", "с - с отцом");
        pos("pr", "местоимение", "мыйын - мыйын книга", "мой - моя книга");
        pos("vb", "глагол", "тол - толеш", "приходить - приходит");
        pos("vb1", "глагол I спряжения", "лудаш - лудам", "читать - я читаю");
        pos("vb2", "глагол II спряжения", "каяш - каем", "идти - я иду");
        pos("vb.mood.pers", "глагольная форма наклонения с личным окончанием",
                "тол - толза", "приходить - придите");
        pos("***", "неразобранная словоформа", "тазбор - ***", "слово не найдено в анализаторе Vienna");

        suffixPos("-ad", "адъективный суффикс", "ача - ачаланлык", "отец - относящийся к отцу");
        suffixPos("-adv", "наречный суффикс", "чот - чотын", "сильный - сильно");
        suffixPos("-adv.pers", "наречный суффикс с личным показателем", "шке - шкемын", "сам - по-своему/моим образом");
        suffixPos("-case", "падежный показатель", "пӱнчер - пӱнчерыште", "сосновый бор - в сосновом бору");
        suffixPos("-conn", "соединительная частица/клитика", "ача - ачанат", "отец - и отец тоже");
        suffixPos("-deg", "степень признака", "кугу - кугырак", "большой - больше");
        suffixPos("-deriv.ad", "словообразование прилагательного", "марий - марийла", "мари - по-марийски/как мари");
        suffixPos("-deriv.n", "словообразование существительного", "шке - шкылык", "сам - самостоятельность");
        suffixPos("-deriv.v", "словообразование глагола", "ош - ошалташ", "белый - побелить");
        suffixPos("-enc", "энклитика", "тудо - тудат", "он/она - и он/она тоже");
        suffixPos("-inf", "инфинитивный/именной показатель глагола", "луд - лудаш", "читать - читать");
        suffixPos("-mood", "наклонение", "тол - толза", "приходить - приходи");
        suffixPos("-mood.pers", "наклонение с личным окончанием", "тол - толынет", "приходить - ты пришел бы");
        suffixPos("-num", "число", "ача - ача-влак", "отец - отцы");
        suffixPos("-pers", "личное окончание", "тол - толам", "приходить - я прихожу");
        suffixPos("-poss", "притяжательность", "ача - ачаже", "отец - его отец");
        suffixPos("-tense", "время", "тол - толын", "приходить - пришел");
        suffixPos("-tense.pers", "время с личным окончанием", "тол - толынам", "приходить - я пришел");
        suffixPos("-vb", "глагольный словообразовательный показатель", "ош - ошалташ", "белый - побелить");

        gloss("1PL", "1-е лицо множественного числа", "толына", "мы приходим");
        gloss("1SG", "1-е лицо единственного числа", "толам", "я прихожу");
        gloss("2PL", "2-е лицо множественного числа", "толыда", "вы приходите");
        gloss("2SG", "2-е лицо единственного числа", "толет", "ты приходишь");
        gloss("3PL", "3-е лицо множественного числа", "толыт", "они приходят");
        gloss("3SG", "3-е лицо единственного числа", "толеш", "он/она приходит");
        gloss("ABSTR", "отвлеченное существительное", "сай - сайлык", "хороший - доброта");
        gloss("ACC", "винительный падеж", "ача - ачам", "отец - отца");
        gloss("ADJ", "прилагательное/адъективная форма", "марий - марийла", "мари - марийский/по-марийски");
        gloss("and", "соединительная энклитика", "ачат", "и отец тоже");
        gloss("be", "вспомогательный глагол 'быть'", "уло", "есть/имеется");
        gloss("CAUS", "каузатив", "луд - лудыкташ", "читать - заставить читать");
        gloss("CNG", "соединительная/присоединительная форма", "тудат", "и он/она тоже");
        gloss("COM", "совместный падеж", "ача - ача дене", "отец - с отцом");
        gloss("COMP", "сравнительно-уподобительная форма", "спортсмен - спортсменла", "спортсмен - как спортсмен");
        gloss("CVB", "деепричастие", "лудын", "читая/прочитав");
        gloss("CVB.FUT", "деепричастие будущего/предстоящего действия", "толмешке", "пока не придет / до прихода");
        gloss("CVB.NEG", "отрицательное деепричастие", "лудде", "не читая");
        gloss("CVB.PRI", "деепричастие предшествующего действия", "лудмек", "после того как прочитал");
        gloss("CVB.SIM", "деепричастие одновременности", "лудын", "читая одновременно");
        gloss("CVB.SIM.2SG", "деепричастие одновременности с обращением к тебе",
                "лудынет", "когда ты читаешь");
        gloss("CVB.SIM.3PL", "деепричастие одновременности с 3-м лицом множественного числа",
                "лудыныт", "когда они читают");
        gloss("CVB.SIM.3SG", "деепричастие одновременности с 3-м лицом единственного числа",
                "лудынже", "когда он/она читает");
        gloss("DAT", "дательный падеж", "ача - ачалан", "отец - отцу");
        gloss("DES", "желательное наклонение", "толынем", "хочу прийти");
        gloss("GEN", "родительный падеж", "ача - ачан", "отец - отца/отцов");
        gloss("ILL", "иллатив, направление внутрь", "пӧрт - пӧртышкӧ", "дом - в дом");
        gloss("IMP", "повелительное наклонение", "тол!", "приходи!");
        gloss("INE", "инессив, нахождение внутри", "пӱнчер - пӱнчерыште", "сосновый бор - в сосновом бору");
        gloss("INF", "инфинитив", "лудаш", "читать");
        gloss("INF.FUT", "инфинитив будущей/предстоящей необходимости", "толшаш", "предстоит прийти");
        gloss("INF.NEC", "инфинитив необходимости", "толаш кӱлеш", "нужно прийти");
        gloss("LAT", "направительный падеж", "корно - корнеш", "дорога - к дороге/на дорогу");
        gloss("NMLZ", "субстантивация", "лудшо", "читающий/тот, кто читает");
        gloss("NMLZ.NEG", "отрицательная субстантивация", "луддымо", "не читающий");
        gloss("NEG", "отрицание", "ом луд", "я не читаю");
        gloss("PL", "множественное число", "ача - ача-влак", "отец - отцы");
        gloss("PL.SOC", "множественное число с социальной/групповой окраской", "ача-влак", "отец и его группа/отцы");
        gloss("PST", "прошедшее время", "толын", "пришел");
        gloss("PST1", "прошедшее очевидное время", "толынам", "я пришел");
        gloss("PST2", "прошедшее неочевидное/результативное время", "толын", "оказывается, пришел");
        gloss("PTCP.ACT", "действительное причастие", "лудшо", "читающий");
        gloss("PTCP.FUT", "причастие будущего времени", "толшаш", "тот, кто придет/должен прийти");
        gloss("PTCP.NEG", "отрицательное причастие", "луддымо", "не читавший/не читающий");
        gloss("PTCP.PASS", "страдательное причастие", "лудмо", "прочитанный");
        gloss("REF", "возвратность", "мушкын", "умыться");
        gloss("STR", "усилительная/выделительная энклитика", "тудоак", "именно он/она");
        gloss("TRANS", "переходная/транслативная форма", "сайемдаш", "сделать хорошим");
        gloss("VLAK", "множественное число через показатель -влак", "ача - ача-влак", "отец - отцы");
        gloss("WEAK", "ослабленная/редуцированная форма", "вариант с ослаблением основы", "фонетически ослабленная форма");
        gloss("with", "форма наличия/совместности", "вӱд дене", "с водой");
        gloss("without", "форма отсутствия", "ачадече", "без отца");
    }

    private ViennaGrammarCatalog() {
    }

    public static String describePos(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        Description direct = POS.get(code);
        if (direct != null) {
            return direct.format(code);
        }
        direct = SUFFIX_POS.get(code);
        if (direct != null) {
            return direct.format(code);
        }
        if (code.contains("/")) {
            List<String> parts = new ArrayList<>();
            for (String part : code.split("/")) {
                parts.add(describePos(part));
            }
            return String.join("; ", parts);
        }
        return code + " - служебная POS-метка Vienna, например \"" + code + " - вариант частеречной разметки\"";
    }

    public static String describeGloss(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String code = value.startsWith("-") ? value.substring(1) : value;
        Description direct = GLOSS.get(code);
        if (direct != null) {
            return direct.format(value);
        }
        String generated = describeCompositeGloss(code, value);
        if (!generated.isBlank()) {
            return generated;
        }
        return value + " - лексическое значение Vienna, например \"" + value + " - словарный перевод этой части\"";
    }

    public static String describeFeatureGloss(String value) {
        if (!isGrammarGloss(value)) {
            return "";
        }
        return describeGloss(value);
    }

    private static boolean isGrammarGloss(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String code = value.startsWith("-") ? value.substring(1) : value;
        if (GLOSS.containsKey(code)) {
            return true;
        }
        if (code.startsWith("[") || code.contains("class=") || code.matches(".*[a-z].*")) {
            return false;
        }
        return code.matches("[A-Z0-9_.]+");
    }

    private static String describeCompositeGloss(String code, String original) {
        String[] parts = code.split("\\.");
        if (parts.length <= 1) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (String part : parts) {
            Description description = GLOSS.get(part);
            labels.add(description == null ? part : description.title);
        }
        return original + " - " + String.join(" + ", labels)
                + ", например \"" + original + " - составная грамматическая метка\"";
    }

    private static void pos(String code, String title, String exampleMari, String exampleRu) {
        POS.put(code, new Description(title, exampleMari, exampleRu));
    }

    private static void suffixPos(String code, String title, String exampleMari, String exampleRu) {
        SUFFIX_POS.put(code, new Description(title, exampleMari, exampleRu));
    }

    private static void gloss(String code, String title, String exampleMari, String exampleRu) {
        GLOSS.put(code, new Description(title, exampleMari, exampleRu));
    }

    private record Description(String title, String exampleMari, String exampleRu) {
        String format(String code) {
            return String.format(Locale.ROOT, "%s - %s, например \"%s\", \"%s\"",
                    code, title, exampleMari, exampleRu);
        }
    }
}
