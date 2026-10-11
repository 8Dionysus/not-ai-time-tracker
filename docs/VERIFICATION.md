# Проверка версии 0.2.0 / схемы 2 · 11 октября 2026

В этой среде выполнена команда:

```sh
JAVA_HOME=/workspace/toolchains/jdk21 \
ANDROID_HOME=/workspace/toolchains/android-sdk \
ANDROID_USER_HOME=/workspace/toolchains/android-user \
GRADLE_USER_HOME=/workspace/toolchains/gradle-home \
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Повторный предметный проход по четырём рискам: BUILD SUCCESSFUL. Три Core-сценария и те же три Room/Robolectric-сценария прошли без пропусков и ошибок. Lint: 0 errors, 7 warnings (зафиксированные версии SDK/зависимостей, техническая иконка/строка). APK собран; схемы 1 и 2 сохранены. Свидетельства: evidence/android-build.log, core-tests.xml, room-tests.xml, lint-results-debug.txt.

Внутри прежних трёх сценариев добавлены: прямой выбор широкой группы, null type, исторический roll-up после перемещения, отказ цикла, AND/OR фасетов без повторного времени, повтор с новым путём и прежними параметрами, точная дробь/конверсия 0.1 mi → 160.9344 m, одно измерение на два сегмента, словари в полном JSON. Проверены JSON v1 и настоящая SQLite-база по сохранённой Room-схеме 1 с миграцией в 2, Long.MAX_VALUE без потерь и неизвестные details, включая ключ partOf. Preview исправления не пишет в базу, показывает старые/новые суммы; сохранение заметки не переклассифицирует старый путь, явный пересчёт отменяется Undo. Миграция не угадывает новый каталог и валидирует граф до записи.

Дополнительно в те же сценарии включены: запрет смены версии/definition существующего типа; неизвестные suggestedAxisIds/suggestedMetricIds; raw Classification архивного/невыбираемого узла и доступного потомка; атомарный отказ switch; явный Repeat архива; отчёт ADDED/UNCHANGED/CONFLICT; дубликаты типов, осей, метрик, единиц и конфликт с пользовательским ID; периодическая дробь для зарегистрированной метрики с сохранением draft и успешным последующим явным вводом в canonicalUnitId. Схема Room/JSON остаётся 2, данных для новой миграции не добавлено.

Device QA не выполнен: прежний успешный adb devices -l вернул пустой список, новых образов/эмуляторов не устанавливали. GitHub remote не создан по прежнему препятствию авторизации. Входной каталог Library v1 не получен после ограниченных попыток передачи; точные ID/hash и остаток — CATALOG_IMPACT.md. Полный каталог не заявляется готовым.

---

## Историческое свидетельство предыдущего прохода v1

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

## Независимая проверка сохранности после доставки Library v3

11 октября 2026: по фактическому коду и локальному samples/history-v2.json расширен существующий backupReplacementAndFailure. Выполнена та же команда сборки выше: BUILD SUCCESSFUL (27s), 3 Core + 3 Room/Robolectric, 0 failures/errors/skips; lint 0 errors / 7 warnings. Это новые проверки согласованных механизмов, не повторный запуск ради статуса.

Проверено: rename/reparent без изменения сегментов; повторный addCatalog с тем же ID и иным meaningVersion/definition возвращает тот же CONFLICT и сохраняет старую семантику; экспорт/восстановление сохраняет старые пути; повторный полный restore не дублирует записи (только новая ревизия), удаляет записи после снимка как явная полная замена; реальное восстановление его rollback-файла возвращает эти локальные записи. Шесть повреждений графа — цикл, отсутствующий parent, карточка, measurement target, suggested axis и ID исторического пути — отклоняются до записи и создания rollback-файла. Прежний тест SQLite-триггера продолжает проверять откат после промежуточной записи дочерних таблиц.

Исправленный дефект: encode мог выдавать JSON больше лимита собственного decode, а для пустой базы не проверял часы. Теперь валидирует часы/граф, проверяет UTF-8 размер через общий MAX_BACKUP_BYTES и проверяет полученный JSON тем же decoder до возврата. Регрессии: недопустимые часы; >10 MiB с многобайтным текстом; откат календарных часов, при котором старые определённые записи оказались позже asOf. В последнем случае экспорт явно отклоняется, а исходные данные сохраняются: непригодная копия не объявляется успешной. Лимит не расширялся, форматы Room/JSON остались 2.

ZIP исследовательского пакета 0.4 не читался и не загружался повторно. Данные пакета, его manifest, source-kind/axis-value решения, шесть конфликтов исследовательских ID и source-qualified provenance не проверены. Ещё нужен доступ к фактическим байтам/исходникам, затем типизированный reader/адаптер, покрывающий каждый входной элемент преобразованием или обоснованным остатком, проверка ссылок/путей полного runtime-представления и повторного пакетного импорта. Локальные фикстуры не заменяют эту приёмку. UI и устройство не проверялись и не менялись.


## Полный полученный корпус 0.5 · APK 0.3.0

После снятия блокера через Library read API добавлен типизированный ResearchCatalog и FileResearchArchive. Фактический SHA-256 входа подтверждён; полный файл и результаты включены в research/ и evidence/research/. Новые проверки полного корпуса входят в прежние три сценария. Команда :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug прошла: BUILD SUCCESSFUL, 3 Core + 3 Room, 0 failures/errors/skips; lint 0 errors / 7 warnings. Это не device QA. JSON теперь 3 с чтением 1/2, SQL Room остаётся 2. Подробный контракт и непреобразованный остаток — RESEARCH_ADAPTER.md; предыдущие разделы о недоступности JSON/отсутствии адаптера являются историей прежних проходов.


## Полный baseline + ISCO v2 + спорт 0.7

11 октября 2026: `JAVA_HOME=/workspace/toolchains/jdk21 ANDROID_HOME=/workspace/toolchains/android-sdk ANDROID_USER_HOME=/workspace/toolchains/android-user GRADLE_USER_HOME=/workspace/toolchains/gradle-home ./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` — **BUILD SUCCESSFUL in 2m 1s**, 60 tasks (19 executed). Три Core и три Room/Robolectric сценария: 0 failures/errors/skips. Lint: 0 errors, 7 прежних warnings. APK собран; устройство не запускалось. Логи, XML, summary и полные runtime/outcomes: `evidence/full-projection/`.

Полный каталог: 2092 типа, 337 осей; baseline не изменён. Проверены все 353 ISCO и 223 спортивных строки, 37 целых занятий без деталей и Repeat (38 сегментов, ровно 38 секунд), полная установка, replay/conflict/stale, сохранность истории, JSON export/restore на чистой базе, отказ при повреждённом JSON/parent/hash/evidence. Прежние количественные и rollback проверки сохранены в трёх сценариях; schema Room 2 и JSON 3 не менялись. Контракт и неисполняемые связи описаны в FULL_CATALOG_07.md. Remote по-прежнему не настроен; репозиторий локальный, без публичной публикации.


## Исправление приёмки v9

11 октября 2026: итоговая команда `JAVA_HOME=/workspace/toolchains/jdk21 ANDROID_HOME=/workspace/toolchains/android-sdk ANDROID_USER_HOME=/workspace/toolchains/android-user GRADLE_USER_HOME=/workspace/toolchains/gradle-home ./gradlew --offline :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` — **BUILD SUCCESSFUL in 1m 45s**, 60 tasks (21 executed). 3 Core + 3 Room/Robolectric, 0 failures/errors/skips; lint 0 errors / 7 прежних warnings. APK собран. Устройство и производительность телефона не проверялись.

Удалены 171 неявный fallback-тип и три типа, ошибочно созданные из residualId. Осталось 1918 типов и 337 осей. Регрессии проверяют точное равенство новых ISCO ID набору 137 явных proposedActions + 18 components, отсутствие fallback у frozen-строк и всех residualId в runtime, четыре открытых residual без принятого отображения. Новые отрицательные проверки: валидный ResearchPlan с другим SHA и валидный ResearchPlan с четырьмя из пяти companions отклоняются до подготовки полного плана. Существующие проверки Repeat/семейств/истории/конфликтов/backup сохранены.

Сравнение с commit 0e9bf78886d4614a07ed6816ca8c95fdbe4ddce7: baseline и два оригинала побайтно равны; все оставшиеся типы, все оси и все спортивные outcomes равны точно. Машиночитаемый summary содержит хеши, полный список 174 исключённых ID и 353 + 223 исходных решения. При первом запуске новой регрессии обнаружена зависимость photosensitive_media_manufacture от удалённого fallback: ожидание трёх открытых residual уточнено до четырёх, без создания замены по догадке.

Загрузка в приложение/новые экраны/модель данных не менялись. Статический остаток секундного цикла MainActivity зафиксирован в ACCEPTANCE_GAPS.md; измерений фонового расхода нет. Исправленный импорт не удаляет автоматически данные, ранее импортированные пользователем из v8.
