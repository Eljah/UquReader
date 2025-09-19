# UquReader (Android, Maven)

Читалка татарских текстов с морфоразметкой и переводом лемм на русский.

## Данные

1) **py_tat_morphan → JSONL**
Пример строк (по одной строке на токен):
```
{"surface":"Китапны","lemma":"китап","pos":"NOUN","features":["ACC"],"start":0,"end":7}
{"surface":"укыдым","lemma":"укы","pos":"VERB","features":["PAST","1SG"],"start":8,"end":14}
```

2) **Apertium tat↔rus → SQLite**
Схема:
```sql
CREATE TABLE lemmas_tt(lemma TEXT PRIMARY KEY, pos TEXT);
CREATE TABLE tt_ru(lemma_tt TEXT, lemma_ru TEXT, score REAL, PRIMARY KEY(lemma_tt, lemma_ru));
CREATE INDEX tt_ru_tt_idx ON tt_ru(lemma_tt);
```
Положите файл `dictionary.db` в `src/main/assets/`.

## Сборка
Нужен Android SDK (`ANDROID_HOME`) и build-tools.

Пример команд (скрипт `./mvnw` перед запуском Maven автоматически собирает вспомогательный `sun.misc.BASE64Encoder` shim):
```bash
./mvnw -e clean install
# при подключенном устройстве / эмуляторе:
./mvnw android:deploy android:run
```

## Поведение памяти и подсветки
Экспоненциальное «забывание» по half-life (по умолчанию 7 дней). Чем слабее память пары (лемма, признаки), тем заметнее фон.
