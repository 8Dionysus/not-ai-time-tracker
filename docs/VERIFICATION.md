# Фактическая проверка · 11 октября 2026

Проект: `/workspace/not-ai-time-tracker`, отдельный Git-репозиторий. Соседние репозитории не изменялись. Дополнительные агенты не запускались; фактические модель/effort runtime не раскрыл, исполнитель их не менял.

## Среда и разрешение

- OpenJDK 21.0.12.1; target bytecode Java 17. В системном runtime отсутствовал launcher javac, но модули компилятора уже были. В `/workspace/toolchains/jdk21` создана отдельная копия с launcher-скриптами существующих jdk.compiler/jdk.jartool/jdk.jlink. javac и jlink проверены; системная Java не менялась. Adoptium API ответил proxy CONNECT 403, ограничения не обходились.
- Gradle 8.13 (Wrapper включён), Kotlin 2.1.20, AGP 8.11.1, KSP 2.1.20-2.0.1, Room 2.7.2.
- SHA-256 Gradle: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` — проверен и закреплён в wrapper properties.
- Дионис разрешил Android SDK License 11 октября 2026, 05:13:16 UTC. Принята только `android-sdk-license` для согласованных пакетов. sdkmanager завершился с кодом 0: Platform 35 revision 2, Build-tools 35.0.0, Platform-tools 37.0.1 установлены в `/workspace/toolchains/android-sdk`.
- Загрузки Java используют уже настроенный прокси; Android-кэш перенесён в writable workspace. TLS/trust stores не менялись. Robolectric использует тестовый user.home внутри `.gradle/robolectric`, потому что системный home read-only. Credentials не менялись.

## Команда и результат

```sh
cd /workspace/not-ai-time-tracker
JAVA_HOME=/workspace/toolchains/jdk21 \
ANDROID_HOME=/workspace/toolchains/android-sdk \
ANDROID_USER_HOME=/workspace/toolchains/android-user \
GRADLE_USER_HOME=/workspace/toolchains/gradle-home \
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Последний запуск: **BUILD SUCCESSFUL in 15s**. [Полный журнал](../evidence/android-build.log).

- Core: 3 tests, 0 failures, 0 errors, 0 skipped — [XML](../evidence/core-tests.xml).
- Room/Robolectric API 35: те же 3 сценария на дисковой SQLite, 0 failures, 0 errors, 0 skipped — [XML](../evidence/room-tests.xml). Это не тест на Android-устройстве.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, debug-сборка приложения `dev.notai.tracker`, minSdk 26 / targetSdk 35.
- `apksigner verify --verbose`: Verifies, v2=true, один подписант — [вывод](../evidence/apk-signature.txt).
- Room KSP: сгенерированы implementation и [schema 1](../app/schemas/dev.notai.tracker.android.TrackerDatabase/1.json). Destructive migration fallback не включён.
- Lint: **0 errors, 7 warnings** — [отчёт](../evidence/lint-results-debug.txt). Предупреждения: старшие доступные SDK/версии зависимостей, отсутствие продуктовой иконки и строковый литерал UTC в технической оболочке. Они не скрывались; обновление targetSdk и продуктовые ресурсы требуют отдельной проверки UI.
- В APK через aapt2 проверены allowBackup=false, fullBackupContent=false, ссылка на dataExtractionRules, отсутствие uses-permission — [manifest](../evidence/apk-manifest.txt). Manifest содержит служебный неэкспортируемый Room MultiInstanceInvalidationService; постоянный сервис отсчёта не запускается.

## Три сценария

1. **timerPath**: старт без карточки; поздняя разметка; pause/resume; повторное открытие состояния; stale Stop/Undo с актуальным Snapshot; switch и забытое переключение; repeat/favorite, в том числе после переименования и архивирования карточки; изменение деталей; два конкурентных switch (один принят, другой STALE); скачок часов; другая загрузка; подтверждение/исключение хвоста; отказ недопустимых крайних значений часов и elapsed duration. Core восстанавливает сериализованный объект, Room закрывает и заново открывает дисковую базу.
2. **smallHistoryAndExport**: полночь; пауза; разные сегменты равной длительности; пересекающиеся tags; одинаковые суммы отчёта и CSV; session quantity не удваивается; исправление/Undo; зафиксированный результат правки с отчётом; удаление измерения с affected ID; атомарная правка сессии/заметки; копия карточки без истории/количеств; переименование/архивирование карточки и типа без смены прошлой связи; отказ отсутствующей ссылки и пересечения; календарные сравнения 23/24 и 25/24 часов Europe/Berlin; метка неполного периода; quantityTotals по field/version/unit/scope без удвоения и с различением нуля/отсутствия; отказы переполнения сумм и безопасное целочисленное пересечение больших длительностей.
3. **backupReplacementAndFailure**: оба JSON-примера; полная замена чистого состояния; история/настройки/типы/избранное/измерения; сохранение переименованного архивного типа и ссылки; активный хвост в RECOVERY без выдуманного end; повреждённый файл, неизвестная версия, пропущенный datasetId, дубликат ID; stale preview; повреждённая rollback-копия; ошибка SQLite trigger на последней записи metadata после фактической замены дочерних таблиц (в Room) с полным rollback; в Core — моделируемый отказ StateStore; конкурентное изменение настройки во время внешней записи rollback-файла с отказом устаревшего импорта; отказ неподтверждённой длительности активного хвоста; quantity-суммы после JSON-восстановления; явное подтверждение границы.

Расширения добавлены внутрь этих трёх сценариев, отдельной большой тестовой системы нет. Исправлены обнаруженные дефекты Android-компиляции (O_DIRECTORY), тестовой среды (Robolectric lock), STALE/Undo, affected IDs измерений и отсутствующего реестра типов. Дополнительная приёмка закрыла календарное сравнение через DST, безопасные quantity-суммы, диапазоны часов/длительностей и усилила доказательство атомарности импорта.

## Не выполнено

- Нет проверки Activity/SAF/sleep/reboot/kill-process на устройстве или эмуляторе; нет screenshots/logcat сценария. Android QA-навык теперь доступен и прочитан. Первоначальный запуск adb 37.0.1 упирался в read-only home. После явного указания родителя отдельному дочернему процессу adb передан HOME=/workspace/toolchains/adb-home; родительское окружение не менялось. `adb devices -l` завершился с кодом 0 и пустым списком — [вывод](../evidence/adb-devices.txt). Эмулятор/qemu-процесс и `/dev/kvm` не обнаружены. Новые образы не устанавливались, filesystem-разрешения не расширялись. Доступного adb-target нет.
- Политика Android backup проверена в собранном APK, но не проверялась на OEM-устройствах. Производительность и батарея не измерялись.
- Private GitHub-репозиторий не создан: create-repository нет в доступном коннекторе, существующая CLI-авторизация невалидна. Токен не читался/не выводился; remote не настроен, публичной публикации не было.
- Остальные продуктовые ограничения и этапы перечислены в [ACCEPTANCE_GAPS.md](ACCEPTANCE_GAPS.md).

## Источники

[Совместимость AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes), [Room](https://developer.android.com/jetpack/androidx/releases/room), [Android SDK License](https://developer.android.com/studio/terms).
