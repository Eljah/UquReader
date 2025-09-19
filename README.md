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

Пример команд (скрипт `./mvnw` перед запуском Maven автоматически собирает вспомогательный `sun.misc.BASE64Encoder` shim и при необходимости создает debug-keystore для установки APK; существующий `~/.android/debug.keystore` переиспользуется, поэтому обновление установленного приложения не ломается. В GitHub Actions ключ сохраняется в кэше и повторно используется между сборками). По умолчанию используется платформа Android 28 и build-tools 33.0.2, их можно переопределить через `-Dandroid.platform` и `-Dandroid.build-tools`:
```bash
./mvnw -e clean install
# при подключенном устройстве / эмуляторе:
./mvnw android:deploy android:run
```

## RHVoice Talgat

* В приложении добавлена кнопка «Установить RHVoice Talgat». Она открывает страницу RHVoice в магазине или на GitHub и помогает перейти к инструкции по установке.
* При запуске и при возвращении в `MainActivity` приложение проверяет, установлены ли движок RHVoice и голос Talgat. Если компоненты отсутствуют, появляется диалог с быстрыми ссылками: на установку движка, на страницу загрузки голосов или для открытия установленного приложения RHVoice.
* После установки RHVoice и загрузки голоса Talgat вернитесь в UquReader — чтение начнется автоматически, а голос будет сопровождать каждое слово и подсказку.

### Codex container

* В контейнере Codex Android SDK уже установлен в `/usr/lib/android-sdk`. Если переменные окружения `ANDROID_SDK_ROOT` и `ANDROID_HOME` не заданы, скрипт `./mvnw` автоматически подставит этот путь и добавит в `PATH` каталоги `cmdline-tools`, `platform-tools` и `emulator`.
* Перед коммитом запускайте `./mvnw -e clean install`, чтобы проверить сборку и статическую упаковку APK.
* Для регрессионного тестирования запуска можно создать AVD с образом `system-images;android-28;google_apis;x86` и запустить его командой `emulator -avd codex-avd-api28 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`. На хостах без аппаратной виртуализации эмулятор завершится с сообщением о недоступности `/dev/kvm` — это ограничение окружения.

## Поведение памяти и подсветки
Экспоненциальное «забывание» по half-life (по умолчанию 7 дней). Чем слабее память пары (лемма, признаки), тем заметнее фон.
