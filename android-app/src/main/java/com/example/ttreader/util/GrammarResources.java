package com.example.ttreader.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

import com.example.ttreader.model.FeatureMetadata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GrammarResources {
    private static final String TAG = "GrammarResources";
    private static final String DEFAULT_ASSET = "tt-ru.json";
    private static final Map<String, String[]> POS_MAP = new HashMap<>();
    private static final Map<String, FeatureMetadata> FEATURE_MAP = new HashMap<>();

    private static boolean initialized = false;
    private static String assetName = DEFAULT_ASSET;

    private GrammarResources() {}

    public static synchronized void useLanguagePairAsset(String asset) {
        if (TextUtils.isEmpty(asset)) return;
        if (!asset.equals(assetName)) {
            assetName = asset;
            initialized = false;
            POS_MAP.clear();
            FEATURE_MAP.clear();
        }
    }

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        if (context == null) {
            Log.w(TAG, "Context is null, cannot initialize grammar resources");
            return;
        }
        loadFromAsset(context.getApplicationContext(), assetName);
    }

    private static void loadFromAsset(Context context, String asset) {
        AssetManager assets = context.getAssets();
        try (InputStream is = assets.open(asset);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            parseJson(sb.toString());
            loadBuiltInFallbacks();
            initialized = true;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load grammar resources from " + asset, e);
            loadBuiltInFallbacks();
            initialized = true;
        }
    }

    private static void parseJson(String json) throws JSONException {
        POS_MAP.clear();
        FEATURE_MAP.clear();

        JSONObject root = new JSONObject(json);
        JSONArray posArray = root.optJSONArray("pos");
        if (posArray != null) {
            for (int i = 0; i < posArray.length(); i++) {
                JSONObject posObj = posArray.optJSONObject(i);
                if (posObj == null) continue;
                String code = posObj.optString("code");
                String tt = posObj.optString("titleTt");
                String ru = posObj.optString("titleRu");
                if (!TextUtils.isEmpty(code) && (!TextUtils.isEmpty(tt) || !TextUtils.isEmpty(ru))) {
                    POS_MAP.put(code, new String[]{tt, ru});
                }
            }
        }

        JSONArray featureArray = root.optJSONArray("features");
        if (featureArray != null) {
            for (int i = 0; i < featureArray.length(); i++) {
                JSONObject featureObj = featureArray.optJSONObject(i);
                if (featureObj == null) continue;
                String code = featureObj.optString("code");
                if (TextUtils.isEmpty(code)) continue;
                String titleRu = featureObj.optString("titleRu");
                String titleTt = featureObj.optString("titleTt");
                String descriptionRu = featureObj.optString("descriptionRu");
                List<String> phoneticForms = jsonArrayToList(featureObj.optJSONArray("phoneticForms"));
                List<String> examples = jsonArrayToList(featureObj.optJSONArray("examples"));
                FEATURE_MAP.put(code, new FeatureMetadata(code, titleRu, titleTt, descriptionRu, phoneticForms, examples));
            }
        }
    }

    private static List<String> jsonArrayToList(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, null);
            if (!TextUtils.isEmpty(value)) {
                list.add(value);
            }
        }
        return list.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    public static String formatPos(String code) {
        if (code == null) return "";
        String[] names = POS_MAP.get(code);
        if (names == null) return code;
        String tt = names.length > 0 ? names[0] : null;
        String ru = names.length > 1 ? names[1] : null;
        if (!TextUtils.isEmpty(tt) && !TextUtils.isEmpty(ru)) {
            return tt + " / " + ru;
        }
        if (!TextUtils.isEmpty(tt)) {
            return tt;
        }
        if (!TextUtils.isEmpty(ru)) {
            return ru;
        }
        return code;
    }

    public static FeatureMetadata getFeatureMetadata(String code) {
        return FEATURE_MAP.get(code);
    }

    private static void loadBuiltInFallbacks() {
        putPos("Adj", "сыйфат", "прилагательное");
        putPos("Adv", "рәвеш", "наречие");
        putPos("CNJ", "теркәгеч", "союз");
        putPos("IMIT", "ияртем сүз", "звукоподражание");
        putPos("INTRJ", "ымлык", "междометие");
        putPos("MOD", "модаль сүз", "модальное слово");
        putPos("N", "исем", "существительное");
        putPos("Num", "сан", "числительное");
        putPos("PART", "кисәкчә", "частица");
        putPos("PN", "алмашлык", "местоимение");
        putPos("POST", "бәйлек", "послелог");
        putPos("PROP", "ялгызлык исем", "имя собственное");
        putPos("V", "фигыль", "глагол");
        putPos("Error", "танылмаган", "неразобранный токен");
        putPos("Latin", "латин язуы", "латинский фрагмент");
        putPos("Letter", "хәреф", "буква");
        putPos("NL", "яңа юл", "перенос строки");
        putPos("NR", "сан билгесе", "числовая запись");
        putPos("Rus", "рус сүзе", "русский фрагмент");
        putPos("Sign", "тыныш билгесе", "знак препинания");
        putPos("Type1", "токен төре 1", "служебный тип токена 1");
        putPos("Type2", "токен төре 2", "служебный тип токена 2");
        putPos("Type3", "токен төре 3", "служебный тип токена 3");
        putPos("Type4", "токен төре 4", "служебный тип токена 4");

        putFeature("1PL", "1-е лицо множественного числа", "беренче зат күплек саны", "Форма говорящего вместе с другими: мы делаем.", "-быз, -без", "язабыз - мы пишем");
        putFeature("1SG", "1-е лицо единственного числа", "беренче зат берлек саны", "Форма говорящего: я делаю.", "-мын, -мен, -м", "язам - я пишу");
        putFeature("2PL", "2-е лицо множественного числа / вежливое обращение", "икенче зат күплек саны", "Форма обращения к нескольким людям или вежливо к одному.", "-сыз, -сез", "язасыз - вы пишете");
        putFeature("2SG", "2-е лицо единственного числа", "икенче зат берлек саны", "Форма обращения к одному собеседнику: ты делаешь.", "-сың, -сең", "язасың - ты пишешь");
        putFeature("3PL", "3-е лицо множественного числа", "өченче зат күплек саны", "Форма действия нескольких лиц или предметов.", "-лар, -ләр", "килделәр - они пришли");
        putFeature("3SG", "3-е лицо единственного числа", "өченче зат берлек саны", "Форма действия одного лица или предмета.", "нулевое окончание, -дыр, -дер", "килә - он/она приходит");
        putFeature("ABL", "исходный падеж", "чыгыш килеше", "Откуда? от чего? из чего? источник или отправная точка.", "-дан, -дән, -тан, -тән, -нан, -нән", "бала - баладан, ребенок - от ребенка");
        putFeature("ACC", "винительный падеж", "төшем килеше", "Кого? что? прямой объект действия.", "-ны, -не", "бала - баланы, ребенок - ребенка");
        putFeature("ADVV_ACC", "деепричастие последовательного/сопутствующего действия", "-ып/-еп хәл фигыль", "Действие, связанное с основным: сделав, делая.", "-ып, -еп, -п", "укып чыкты - прочитал, читая/прочитав");
        putFeature("ADVV_ANT", "деепричастие предшествования", "-гач/-гәч хәл фигыль", "Действие, после которого происходит основное.", "-гач, -гәч, -кач, -кәч", "килгәч сөйләштек - когда пришел, поговорили");
        putFeature("ADVV_NEG", "отрицательное деепричастие", "-мыйча/-мичә хәл фигыль", "Действие не выполняется перед основным.", "-мыйча, -мичә", "укымыйча китте - ушел, не прочитав");
        putFeature("ADVV_SUCC", "деепричастие последующего предела", "-ганчы/-гәнче хәл фигыль", "До тех пор, пока не произойдет действие.", "-ганчы, -гәнче, -канчы, -кәнче", "килгәнче көт - жди, пока не придет");
        putFeature("AFC", "аффикс принадлежности/связки", "бәйләүче кушымча", "Служебный аффикс, связывающий основу с последующей формой.", "", "форма с промежуточным аффиксом");
        putFeature("ATTR_ABES", "прилагательное отсутствия", "-сыз/-сез сыйфат", "Признак отсутствия чего-либо: без чего-то.", "-сыз, -сез", "акчасыз кеше - человек без денег");
        putFeature("ATTR_GEN", "притяжательная атрибутивная форма", "-ныкы/-неке атрибут", "Форма принадлежности: чей? принадлежащий кому-то.", "-ныкы, -неке", "минеке - мой");
        putFeature("ATTR_LOC", "локативный атрибут", "урын атрибуты", "Признак по месту нахождения: находящийся где-то.", "-дагы, -дәге, -тагы, -тәге", "авылдагы йорт - дом в деревне");
        putFeature("ATTR_MUN", "прилагательное наличия признака", "-лы/-ле сыйфат", "Предмет обладает указанным признаком или содержит что-то.", "-лы, -ле", "балалы йорт - дом с детьми");
        putFeature("CAUS", "каузатив", "йөкләтү юнәлеше", "Заставить или дать выполнить действие другому.", "-дыр, -дер, -тыр, -тер, -т", "укытты - заставил/дал учить");
        putFeature("COMP", "сравнительная степень", "чагыштыру дәрәҗәсе", "Более высокий уровень признака.", "-рак, -рәк", "зуррак - больше");
        putFeature("COND", "условное наклонение", "шарт фигыль", "Условие: если действие произойдет.", "-са, -сә", "килсә - если придет");
        putFeature("DESID", "желательное значение", "теләк мәгънәсе", "Желание или намерение совершить действие.", "-асы, -әсе", "барасы килә - хочется идти");
        putFeature("DIM", "уменьшительно-ласкательная форма", "кечерәйтү кушымчасы", "Малый размер или ласковая форма.", "-чык, -чек, -кай, -кәй", "кызчык - девочка");
        putFeature("DIR", "направительный падеж", "юнәлеш килеше", "Куда? кому? направление или адресат.", "-га, -гә, -ка, -кә, -на, -нә", "бала - балага, ребенок - ребенку");
        putFeature("DIR_LIM", "предельное направление", "чик юнәлеше", "Направление до предела: до какого места/момента.", "-гача, -гәчә, -кача, -кәчә", "өйгәчә - до дома");
        putFeature("DISTR", "распределительное значение", "бүлү мәгънәсе", "Распределение по одному/нескольку.", "-лап, -ләп", "икеләп - по двое");
        putFeature("EQU", "уподобительный падеж", "охшату килеше", "Как кто? подобно чему?", "-ча, -чә", "балаларча - как дети");
        putFeature("FUT_DEF", "определенное будущее время", "билгеле киләчәк заман", "Будущее действие, ожидаемое как определенное.", "-ачак, -әчәк, -ячак, -ячәк", "язачак - напишет");
        putFeature("FUT_INDF", "неопределенное будущее время", "билгесез киләчәк заман", "Будущее вероятное или общее действие.", "-ыр, -ер, -ар, -әр, -р", "язар - возможно напишет");
        putFeature("FUT_INDF_NEG", "отрицательное неопределенное будущее", "юклыктагы билгесез киләчәк", "Вероятное будущее с отрицанием.", "-мас, -мәс", "язмас - не напишет");
        putFeature("GEN", "родительный падеж", "иялек килеше", "Чей? кого? принадлежность или зависимость.", "-ның, -нең", "бала - баланың, ребенок - ребенка/детский");
        putFeature("HOR_PL", "желательное наклонение 1-го лица множественного числа", "теләк фигыль күплек", "Побуждение группы с говорящим: давайте сделаем.", "-ыйк, -ик", "барыйк - пойдемте");
        putFeature("HOR_SG", "желательное наклонение 1-го лица единственного числа", "теләк фигыль берлек", "Намерение говорящего: пусть я сделаю.", "-ыйм, -им, -ам, -әм", "барыйм - пойду-ка");
        putFeature("IMP_PL", "повелительное наклонение множественного числа", "боерык фигыль күплек", "Приказ или просьба нескольким людям / вежливо.", "-ыгыз, -егез", "килегез - приходите");
        putFeature("IMP_SG", "повелительное наклонение единственного числа", "боерык фигыль берлек", "Приказ или просьба одному человеку.", "нулевое окончание, -гын, -ген", "кил - приди");
        putFeature("INF_1", "инфинитив на -ырга/-ергә", "инфинитив", "Неопределенная форма глагола.", "-ырга, -ергә, -арга, -әргә", "укырга - читать");
        putFeature("INF_2", "книжный инфинитив на -мак/-мәк", "китаби инфинитив", "Книжная неопределенная форма глагола.", "-мак, -мәк", "бармак - идти");
        putFeature("INT", "вопросительная частица", "сорау кисәкчәсе", "Маркер вопроса.", "-мы, -ме", "киләме? - приходит ли?");
        putFeature("INT_MIR", "вопросительно-удивительная частица", "гаҗәпләнү соравы", "Вопрос с оттенком удивления или сомнения.", "-мыни, -мени", "синмени? - неужели ты?");
        putFeature("JUS_PL", "пожелательное наклонение 3-го лица множественного числа", "өченче зат теләк күплек", "Пусть они сделают.", "-сыннар, -сеннәр", "килсеннәр - пусть придут");
        putFeature("JUS_SG", "пожелательное наклонение 3-го лица единственного числа", "өченче зат теләк берлек", "Пусть он/она сделает.", "-сын, -сен", "килсен - пусть придет");
        putFeature("LOC", "местный падеж", "урын-вакыт килеше", "Где? когда? место или время действия.", "-да, -дә, -та, -тә, -нда, -ндә", "бала - балада, ребенок - у ребенка/в ребенке");
        putFeature("MSRE", "мера/степень", "микъдар дәрәҗәсе", "Форма меры, степени или количества.", "", "шулкадәр - настолько");
        putFeature("NEG", "отрицание", "юклык", "Отрицает действие или признак.", "-ма, -мә, -мый, -ми", "язмады - не писал");
        putFeature("NMLZ", "субстантивация", "исемләшү", "Превращает форму в существительное или предметное значение.", "", "килгәннәр - пришедшие");
        putFeature("NUM_APPR", "приблизительное числительное", "чама саны", "Приблизительное количество.", "-лап, -ләп", "унлап - около десяти");
        putFeature("NUM_COLL", "собирательное числительное", "җыю саны", "Количество как группа.", "-ау, -әү", "икәү - двое");
        putFeature("NUM_DISR", "разделительное числительное", "бүлем саны", "Распределение по числу.", "-шар, -шәр", "икешәр - по два");
        putFeature("NUM_ORD", "порядковое числительное", "тәртип саны", "Порядок при счете.", "-ынчы, -енче, -нчы, -нче", "икенче - второй");
        putFeature("Nom", "именительный падеж", "атау килеше", "Базовая словарная форма без падежного окончания.", "нулевое окончание", "бала - бала, ребенок - ребенок");
        putFeature("OBL", "облигатив / долженствование", "кирәклек мәгънәсе", "Необходимость или обязанность выполнить действие.", "-асы, -әсе", "барасы - надо идти");
        putFeature("PASS", "страдательный залог", "төшем юнәлеше", "Действие направлено на объект; объект становится подлежащим.", "-ыл, -ел, -л, -н", "ачылды - открылось/было открыто");
        putFeature("PCP_FUT", "причастие будущего времени", "киләчәк заман сыйфат фигыль", "Признак по будущему действию.", "-ачак, -әчәк, -ыр, -ер", "киләчәк кеше - человек, который придет");
        putFeature("PCP_PR", "причастие настоящего времени", "хәзерге заман сыйфат фигыль", "Признак по текущему или обычному действию.", "-учы, -үче, -а торган, -ә торган", "укучы бала - читающий/учащийся ребенок");
        putFeature("PCP_PS", "причастие прошедшего времени", "үткән заман сыйфат фигыль", "Признак по уже совершенному действию.", "-ган, -гән, -кан, -кән", "язган кеше - человек, который написал");
        putFeature("PL", "множественное число", "күплек саны", "Несколько предметов или лиц.", "-лар, -ләр", "бала - балалар, ребенок - дети");
        putFeature("POSS_1PL", "притяжательность 1-го лица множественного числа", "беренче зат күплек милек", "Принадлежит нам.", "-ыбыз, -ебез, -быз, -без", "китабыбыз - наша книга");
        putFeature("POSS_1SG", "притяжательность 1-го лица единственного числа", "беренче зат берлек милек", "Принадлежит мне.", "-ым, -ем, -м", "китабым - моя книга");
        putFeature("POSS_2PL", "притяжательность 2-го лица множественного числа", "икенче зат күплек милек", "Принадлежит вам.", "-ыгыз, -егез, -гыз, -гез", "китабыгыз - ваша книга");
        putFeature("POSS_2SG", "притяжательность 2-го лица единственного числа", "икенче зат берлек милек", "Принадлежит тебе.", "-ың, -ең, -ң", "китабың - твоя книга");
        putFeature("POSS_3", "притяжательность 3-го лица", "өченче зат милек", "Принадлежит ему/ей/им.", "-ы, -е, -сы, -се", "бала - баласы, ребенок - его ребенок");
        putFeature("PREC_1", "предельная/ограничительная форма", "чикләү формасы", "Ограничение действия или состояния пределом.", "", "форма с ограничительным значением");
        putFeature("PREM", "давнопрошедшее / преждепрошедшее значение", "күптән үткән заман", "Действие произошло раньше другого прошлого момента.", "-ган иде, -гән иде", "килгән иде - приходил раньше");
        putFeature("PRES", "настоящее время", "хәзерге заман", "Действие происходит сейчас или регулярно.", "-а, -ә, -ый, -и, -й", "яза - пишет");
        putFeature("PROB", "вероятностная частица", "ихтималлык кисәкчәсе", "Предположение: наверное, вероятно.", "-дыр, -дер, -тыр, -тер", "килгәндер - наверное пришел");
        putFeature("PROF", "профессия/носитель занятия", "һөнәр иясе", "Лицо по занятию, профессии или привычному действию.", "-чы, -че", "эшче - рабочий");
        putFeature("PSBL", "возможность / качество", "мөмкинлек кушымчасы", "Возможность, качество или отвлеченное существительное.", "-лык, -лек", "укучылык - учеба/ученичество");
        putFeature("PST_DEF", "прошедшее определенное время", "билгеле үткән заман", "Очевидное или засвидетельствованное прошедшее действие.", "-ды, -де, -ты, -те", "килде - пришел");
        putFeature("PST_INDF", "прошедшее неопределенное время", "билгесез үткән заман", "Прошедшее действие как результат или неочевидный факт.", "-ган, -гән, -кан, -кән", "килгән - пришел/оказывается пришел");
        putFeature("RAR_1", "редкая словообразовательная форма 1", "сирәк форма 1", "Редкий аффикс, встречающийся в словаре или старых текстах.", "", "редкая производная форма");
        putFeature("RAR_2", "редкая словообразовательная форма 2", "сирәк форма 2", "Редкий аффикс, встречающийся в словаре или старых текстах.", "", "редкая производная форма");
        putFeature("RECP", "взаимный залог", "уртаклык юнәлеше", "Действие выполняется взаимно несколькими участниками.", "-ыш, -еш, -ш", "күреште - встретились друг с другом");
        putFeature("REFL", "возвратный залог", "кайтым юнәлеше", "Действие направлено на самого субъекта.", "-ын, -ен, -н", "юынды - умылся");
        putFeature("SIM_1", "уподобительная форма 1", "охшату формасы 1", "Сравнение или подобие: как кто-то/что-то.", "-дай, -дәй, -тай, -тәй", "баладай - как ребенок");
        putFeature("SIM_2", "уподобительная форма 2", "охшату формасы 2", "Сравнение или образ действия.", "-ча, -чә", "татарча - по-татарски");
        putFeature("Sg", "единственное число", "берлек саны", "Один предмет или одно лицо.", "нулевое окончание", "бала - бала, ребенок - ребенок");
        putFeature("USIT", "обычное/привычное действие", "гадәти эш-хәл", "Действие как обычное, повторяющееся или свойственное.", "-учан, -үчән", "эшчән - трудолюбивый");
        putFeature("VN_1", "отглагольное существительное на -у/-ү", "исем фигыль", "Имя действия.", "-у, -ү, -в", "язу - письмо/писание");
        putFeature("VN_2", "отглагольное существительное на -ыш/-еш", "исем фигыль төре", "Имя действия или процесса.", "-ыш, -еш", "күреш - встреча");
    }

    private static void putPos(String code, String tt, String ru) {
        POS_MAP.put(code, new String[]{tt, ru});
    }

    private static void putFeature(String code, String titleRu, String titleTt, String descriptionRu,
                                   String forms, String... examples) {
        List<String> phoneticForms = TextUtils.isEmpty(forms)
                ? Collections.emptyList()
                : Collections.unmodifiableList(Arrays.asList(forms.split("\\s*,\\s*")));
        List<String> exampleList = examples == null || examples.length == 0
                ? Collections.emptyList()
                : Collections.unmodifiableList(Arrays.asList(examples));
        FEATURE_MAP.put(code, new FeatureMetadata(code, titleRu, titleTt, descriptionRu, phoneticForms, exampleList));
    }
}
