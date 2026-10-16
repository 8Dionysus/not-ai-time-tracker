# Напоминание, окружение Focus и фактические наблюдения — направление C

Основа: фактический `main` `01f7ee97424140286245019ffe22b6cc8ce26d90`, прочитан 16 октября 2026. Ветка C: `work/c-focus-reminder-observations`. Код не содержит изменений Compose, ViewModel, ресурсов, экранов и прямых UI callers. Общие Tracker/Model/Room/backup/build-файлы не менялись напрямую; их точная последовательная дельта находится в `integration/attention-preferences.patch` и должна быть применена A перед компиляцией новых модулей. Сборка, JVM, Robolectric, APK, lint и устройство в этой среде **не проверены**: есть JDK 21, отсутствуют Kotlin/Gradle/Android SDK и подготовленные кэши. Новый toolchain не устанавливался.

## Источники и границы выбора

- [Основной журнал, Focus](https://chatgpt.com/space/page_cc7a02fed97081918f59ffa4517bb6dc#block-3e2a9235-e9ba-4340-a324-0447ff8670b6), sequence 212, block hash `c4e47c7c3ce29e69ad2076bd58a9169b9cbd054f8c275a3a652effd991c23fda`: отдельно включаемые управление отвлечениями и музыка при технической возможности Android.
- [Карточка функций, мягкое напоминание](https://chatgpt.com/space/page_e89e68117f608191b098acd706c45f89#block-f8356f97-af3b-4ac3-923c-1ede68cb0c56), sequence 22. Соседние подтверждённые блоки `696ac0bd-d06d-4581-bbb2-4c02b917877a` и `1ad8d9e1-26e4-4ab3-8286-25105924950d`: заметить забытый таймер, продолжить/остановить/исправить без автоматического обрезания.
- [Наблюдения и добровольные вехи](https://chatgpt.com/space/page_0cbb78733570819186606339ff20ae84#block-b2a072bb-7a57-40da-bee2-ff1107ca9846), sequence 63, block hash `01279d13071cb1efe8248a717ab3107eac7117dfe6e5c8dad4eaf953cd3d8fbf`; [защита от неверных стимулов](https://chatgpt.com/space/page_0cbb78733570819186606339ff20ae84#block-0a540a83-09d1-4b1d-be28-40c488a9463e), hash `998b6a47e8c3e17c59655b629db55957551153096e8b4fd43ad8600f8c65f4c6`. Это кандидаты оформления, не утверждённая игровая механика.
- [Официальные изменения DND при target 35+](https://developer.android.com/about/versions/15/behavior-changes-15#dnd-changes): приложение вносит собственное AutomaticZenRule, глобальное состояние собирает ОС. [Конструктор AutomaticZenRule API 29](https://developer.android.com/reference/android/app/AutomaticZenRule): без ConditionProviderService требуется настоящий configuration Activity.
- [Официальный audio focus](https://developer.android.com/media/optimize/audio-focus): для target 35+ запрос разрешён верхнему приложению либо foreground service; отказ не даёт права воспроизведения. Проверено по текущей официальной документации 16 октября 2026; поведение OEM/устройства не проверено.

Применены инженерные навыки `aoa-engineering-shape` (port-adapter) и `aoa-verification` (contract): consumer — существующий TrackerRuntime и будущая привязка Opus; domain timer и коррекция остаются у Tracker, Android-возможности/доставка/локальное аудио — у узких ports. Проверяемая граница — отсутствие новых фактов времени от фонового эффекта, текущий token перед командой и durable receipt перед доставкой. Проверки источников и patch не заменяют исполнение сценариев.

## Обычный таймер

Production: `core/.../LongRecordReminder.kt`, `app/.../LongRecordReminders.kt`.

`LongRecordReminder.configure(expected, LongRecordPreference(...), controlRevision)` проверяет domain Token и локальную ревизию, сохраняет изменённое переносимое предпочтение через существующий `Tracker.settings`, затем отдельно подтверждает активацию на этом устройстве. Сохранение неизменённой формы не сбрасывает receipt и не заменяет явный `confirm` после restore. По умолчанию `enabled=false`. `afterMs=2 часа`, `snoozeMs=15 минут` — обратимые инженерные defaults, **не назначенные пользователем значения**. Измеряется доверенная записанная длительность текущей обычной сессии, без пауз; Focus и recovery исключены. Переход на другой timer target, stop, pause, потеря timebase делают прежний callback недействительным. После возобновления длительной обычной записи возможен новый вопрос.

Пользовательский выбор `enabled/afterMs/snoozeMs` хранится в `Settings.longRecordReminder` и входит в полный JSON 12. Owner-patch добавляет Room10, чтение JSON1–12 и отказ от nondefault новой настройки, подставленной в старый format. Локально в `noBackupFilesDir/long-record-reminder.json` остаются receipts и подтверждение активации для `(datasetId, controlBarrier, preferenceHash)`. Полный restore создаёт новый barrier: предпочтение сохраняется, `confirmationRequired=true`, alarm отсутствует до отдельного `confirm(expected, controlRevision)`. Preview показывает входящие settings и `ordinaryReminderConfirmationRequired`. Потеря permission/channel не выключает переносимое намерение и не выдаёт grant за полученный. Ошибка после domain preference commit, но до сохранения device activation оставляет безопасное состояние с требованием подтверждения. Legacy device-local поле C-v2 читается, но не является authority и не переписывает Dataset.

`synchronize()` поддерживает один elapsed inexact alarm. `onAlarm(hash)` проверяет актуальные opt-in, timer target, boot, segment и срок, сохраняет RESERVED до внешнего эффекта, готовит уведомление вне StateStore gate и повторно проверяет состояние перед коротким `post()`. Используются существующие статусы receipt-first Schedule, но отдельный ticket без поддельных Schedule occurrence. Остаточный RESERVED означает UNKNOWN; автоматически не повторяется. POST_ACCEPTED — только возврат `notify`, без утверждения о показе человеку. Служебный файл ограничен текущим циклом; продолжение/откладывание явно создаёт новый цикл.

`respond(expected, hash, action)`:

| Действие | Эффект |
|---|---|
| CONTINUE | Никаких изменений истории; следующий вопрос после `afterMs` дополнительного доверенного времени. |
| SNOOZE | Никаких изменений истории; следующий вопрос после `snoozeMs`. |
| DISABLE | Выключает локальную настройку, отзывает alarm/уведомление. |
| STOP | Существующий `Tracker.stop(expected)`, граница — текущий момент. |
| REVIEW | Явный **«Остановить и исправить»**: тот же stop сейчас, затем `reviewSessionId` для существующих `previewCorrection` → `applyCorrection`. Предполагаемый прошлый конец не выбирается. Отмена формы не отменяет явно выбранный stop. |

Старый token не заменять новым для повтора прежней команды. Действия уведомления не должны передавать старую команду как новую без показа актуального состояния. UI должен различать запрос внимания, permission/channel blocker и результат попытки.

Opus устанавливает process-wide `LongRecordNoticeBinding.factory`, создаёт канал `AndroidLongRecordPlatform.CHANNEL` и оформляет действия. Отсутствие renderer/channel — явный blocker; backend не придумывает тексты и не запрашивает permission. Уведомления должны открывать существующие контроллеры, а не писать строки базы.

## Окружение существующего Focus

Production: `core/.../FocusEnvironment.kt`, `app/.../FocusEnvironmentAdapter.kt`, `app/.../AttentionRuntime.kt`.

`begin(expected, commandUuid, FocusEnvironmentChoice(...), controlRevision)` — явное включение для текущего **рабочего** интервала, не новая Focus-сессия. Тот же живой command UUID идемпотентен, изменение payload отклоняется. По умолчанию обе функции выключены. Pause, смена фазы, DUE, отмена, смена timer target/process/boot отзывают эффекты. Следующий рабочий интервал или повторный запуск музыки требует нового явного begin; автоматического переигрывания после отказа/потери процесса нет.

DND: API 29+, отдельно предоставленный пользователем policy access и `FocusEnvironmentBinding.rule` с настоящим configuration Activity, названием и подписью. Backend не открывает настройки и не выдаёт grant за полученный. Создаётся неактивное собственное правило; ID и fingerprint сохраняются **до** STATE_TRUE. Cleanup-одиночный alarm регистрируется до активации. Используется только `setAutomaticZenRuleState` своего правила, без глобальных `setInterruptionFilter`, `setNotificationPolicy` и сохранения/перезаписи чужой политики. Возврат API — APPLY_ACCEPTED, не доказательство глобального DND. При cleanup посылается только STATE_FALSE неизменённой собственной condition; правило не удаляется, а сохранённая неактивная lease переиспользуется при следующем явном begin. Это исключает удаление пользовательской конфигурации при гонке и обычное накопление правил. Изменённый fingerprint даёт USER_CHANGED_RULE и сохраняется. Parcel fingerprint привязан к SDK/build; после смены ОС неизвестная provenance даёт OUTCOME_UNKNOWN, а не выдуманную пользовательскую правку. Отказ доступа/неопределённость сохраняет точную lease с releasePending для дальнейшей очистки, без новой активации.

`releaseFocusEnvironment` и `AndroidFocusEnvironment.releaseOwned` работают без открытия истории: boot/update/expiry/recovery могут отозвать только ранее сохранённую lease. Старый expiry с другим ID не касается новой lease. Inexact alarm может опоздать из-за ОС/OEM; точного системного срока отключения не обещаем. Фоновый процесс может быть ограничен/остановлен ОС. Неизменённая lease остаётся доступной для foreground cleanup. Произвольная блокировка других приложений и управление их уведомлениями здесь не заявлены.

Музыка: минимальный предлагаемый вариант — явно выбранный локальный обычный файл через `FocusEnvironmentBinding.localFile`, без URI/HTTP/accounts и без управления внешним player/session/volume. Файл готовится вне domain gate; короткий start перепроверяет Focus и foreground. Audio focus запрашивается перед start; отказ/потеря фокуса не запускает или останавливает **наш** player. Чужая уже звучащая музыка даёт OTHER_AUDIO_ACTIVE. При уходе host в фон наш player освобождается, автоматического resume нет. Lifecycle зарегистрирован невизуальным runtime. Долгое фоновое воспроизведение и интеграция со Spotify/другим приложением — **открытые продуктовые варианты**, не выбранные этим кодом. Открытие выполняется один раз с NOFOLLOW/NONBLOCK, fstat проверяет открытый regular inode; descriptor удерживается до release. Это защищает от замены пути/ссылки, но не делает содержимое неизменяемым при записи в тот же inode. Callback источника — чистый lookup без Tracker/maintenance locks и копирования; если Opus использует app-private копию, её существующая lifecycle-защита и завершение выбора подключаются отдельно. Нового foreground service нет. DND-политика ОС может ограничить слышимость; сам факт start не подтверждает слышимый звук.

## Фактический выразительный слой

Production: `core/.../ObservationFacts.kt`.

`ObservationFacts.week(data, now, date, explicitZone, request)` выключен при `enabled=false`. Использует существующий `Reports.calendar`, ISO-неделю с понедельника и существующие `Goals.progress`. Возвращает источник Token/asOf, семь дат, recordedMs, число дней с положительной записью, uncertainty и добровольные duration thresholds. Никаких XP, рейтингов, штрафов, обязательного streak или durable achievement ledger. Коррекция заново вычисляет результат и снимает ложную веху; число фрагментов не увеличивает duration/число дней.

Цели возвращаются со своей существующей window/zone. `acknowledgementEligible` возможен только для включённой MINIMUM цели **по времени**, с фактическими записями, достигнутым target и без unresolved; session-count цель не получает игрового поощрения за дробление. Метод не посылает ни уведомления, ни animation. Визуальная мозаика, вид/ритм празднования и сохранение пользовательского выбора остаются у продукта/Opus. Предложенный минимальный вариант — добровольно выбранные записанные вехи и семь фактических клеток недели.

## Последовательная интеграция A

`integration/attention-runtime.patch` — отдельный owner-applied patch только для `AndroidRuntime.kt` и manifest. Он не применён в C из-за shared-file ownership. На исходном `01f7ee9` проверка применимости обеих дельт прошла. При другом main обновить привязку по смыслу, не перезаписывать изменения A.

`integration/attention-preferences.patch` затрагивает Model/Backup/Migration/StreamingBackup, TrackerDatabase, projected schema10 и существующие version/migration fixtures. Его необходимо применить **до компиляции**: новые модули намеренно используют `Settings.longRecordReminder`. Schema10 имеет ту же SQL-структуру и identityHash, что schema9, и изменённый metadata contract/version; A должен сгенерировать её штатным KSP и сравнить с кандидатом.

Patch runtime добавляет lazy `runtime.attention`, вызов из существующего event-driven observer/refresh, cleanup до закрытия runtime и manifest receivers/декларацию ACCESS_NOTIFICATION_POLICY. Декларация permission не выдаёт доступ. UI bindings остаются отсутствующими до отдельного подключения Opus; сборка только новых файлов без patch не является end-to-end включением функции.

Проверки в уже подготовленной среде A, без установки новых сред и без устройства пользователя:

```sh
git apply --check integration/attention-preferences.patch
git apply --check integration/attention-runtime.patch
# A применяет согласованную дельту после сверки своего actual main.
git apply integration/attention-preferences.patch
git apply integration/attention-runtime.patch
./gradlew :core:test --tests dev.notai.tracker.AttentionScenariosTest --tests dev.notai.tracker.AttentionSafetyTest --tests dev.notai.tracker.AttentionAuditRegressionTest --tests dev.notai.tracker.AttentionPreferenceArchiveTest
./gradlew :app:testDebugUnitTest --tests dev.notai.tracker.android.AttentionScenariosTest --tests dev.notai.tracker.android.AttentionPlatformTest --tests dev.notai.tracker.android.AttentionPreferencesMigrationTest -PtrackerUnsigned=true
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintRelease -PtrackerUnsigned=true
```

Подготовлены четыре shared-сценария для Memory и Room, семь safety, семь audit-regression, один archive, три Android adapter и один Room migration: 27 исполнений. Полный перечень инвариантов, дефектов и остатка — в [ATTENTION_STATIC_AUDIT](ATTENTION_STATIC_AUDIT.md). Они подготовлены, **не выполнены в C**. Coverage: неизменность истории до явного stop/correction; snooze/disable; старый callback/token; permission и Focus isolation; failed reserve/unknown post без replay; lease-before-activate; отсутствие auto-reassert; cleanup при revoked access/user edit; пересчёт milestones без бонуса за дробление. Android tests — Robolectric simulation, не физическое устройство. Реальное DND, audio focus, delivery, background/OEM timing и доступность UI требуют будущей отдельной приёмки.

Не выполнены push, merge, публикация или разрешения устройства. Source patches/commit доступны координатору для проверки; конечная последовательная main/Library-интеграция остаётся у A/root.
