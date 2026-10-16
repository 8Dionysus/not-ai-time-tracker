# Напоминание, окружение Focus и фактические наблюдения — направление C

Текущая последовательная интеграция A сохраняет опубликованный baseline `7e240e45990fdcda2b938464f2f7f8697c3c7be1`, исходную поставку C `f6fcadff696198d1bb4d34d0207340da1c0ac850` и обязательную audit-дельту C v3 `c403bc6216519aed817245830b19a3e81bc7aa7d`. Обе owner-дельты интегрированы по смыслу: текущие Room11 / полный JSON13 (read1–13), selective1–3. Исходная projected schema10 C не заменяла существующую schema10 A. Сгенерированная KSP schema11 отличается от10 только номером версии. Compose, ViewModel, ресурсы, экраны и UI callers не изменены. Фактические команды, результаты и границы приёмки находятся в [проверке интеграции](ATTENTION_VERIFICATION_2026_10_16.md); исходный C выполнял только source review в своей среде.

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

Пользовательский выбор `enabled/afterMs/snoozeMs` хранится в `Settings.longRecordReminder` и входит в полный JSON13. Интеграция добавляет Room11/migration10→11, чтение JSON1–13 и отказ от nondefault новой настройки, подставленной в старый format. Локально в `noBackupFilesDir/long-record-reminder.json` остаются receipts и подтверждение активации для `(datasetId, controlBarrier, preferenceHash)`. Полный restore создаёт новый barrier: предпочтение сохраняется, `confirmationRequired=true`, alarm отсутствует до отдельного `confirm(expected, controlRevision)`. Preview показывает входящие settings и `ordinaryReminderConfirmationRequired`. Потеря permission/channel не выключает переносимое намерение и не выдаёт grant за полученный. Ошибка после domain preference commit, но до сохранения device activation оставляет безопасное состояние с требованием подтверждения. Legacy device-local поле C-v2 читается, но не является authority и не переписывает Dataset.

`synchronize()` поддерживает один elapsed inexact alarm. `onAlarm(hash)` проверяет актуальные opt-in, timer target, boot, segment и срок, сохраняет RESERVED до внешнего эффекта, готовит уведомление вне StateStore gate и повторно проверяет состояние перед коротким `post()`. Используются существующие статусы receipt-first Schedule, но отдельный ticket без поддельных Schedule occurrence. Остаточный RESERVED означает UNKNOWN; автоматически не повторяется. POST_ACCEPTED — только возврат `notify`, без утверждения о показе человеку. Служебный файл ограничен текущим циклом; продолжение/откладывание явно создаёт новый цикл.

`respond(expected, hash, action)`:

| Действие | Эффект |
|---|---|
| CONTINUE | Никаких изменений истории; следующий вопрос после `afterMs` дополнительного доверенного времени. |
| SNOOZE | Никаких изменений истории; следующий вопрос после `snoozeMs`. |
| DISABLE | Выключает переносимое предпочтение через Tracker.settings, отзывает alarm/уведомление. |
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

## Интеграция и проверяемые границы

`runtime.attention` уже связан с существующим event-driven observer/refresh. Cleanup выполняется до закрытия runtime; manifest содержит receivers и ACCESS_NOTIFICATION_POLICY. Декларация permission не выдаёт доступ. API28 block-state actions регистрируются только на API28+. Новый service, polling или INTERNET permission не добавлены.

`integration/attention-runtime.patch` сохраняет исходную owner-дельту к `01f7ee9`; она уже применена. `integration/attention-preferences.patch` — актуализированная справочная дельта shared-файлов к интегрированному C v3, с Room11/JSON13 и generated schema11. **Повторно применять эти файлы к текущей ветке не нужно.** Историю применения и авторство сохраняют commits. Миграция9→10 и nullable manualConfirmation остаются на месте; 10→11 меняет только defaults metadata. Readers сохраняют реальную legacy12 ручную пометку и отклоняют nondefault ordinary preference, подставленный в format≤12.

Непредпринятый check-in пересчитывает elapsed deadline после подтверждённого дополнения начала и Undo, меняя generation; использованный/UNKNOWN receipt не переигрывается. Временная потеря часов при неизменном target только приостанавливает доставку. Реальный reboot требует существующего Recovery и явного разрешения истории; старый alarm не становится новым фактом.

Исходный C подготовил 27 исполнений: shared4×2, safety7, audit7, archive1, platform3, migration1. A добавил Memory/Room regressions для backfill/Undo и timebase, сохранение DND status после synchronize, точную парковку своего SDK36 rule и сохранение чужого/отредактированного rule, legacy12+current13 round-trip и миграцию10→11 всех SessionStatus с reached-refusal/rollback. Это перечень проверок; фактические исходы берутся только из [source-bound record](ATTENTION_VERIFICATION_2026_10_16.md).

Файловый Robolectric-тест использует узкий host bridge: штатный ShadowLinux4.17 игнорирует flags и возвращает fstat(fd) как stat(null). Bridge проверяет переданные Android flags, использует NIO NOFOLLOW и metadata открытого descriptor через /proc. Это не выполнение Android Os/O_NONBLOCK и не device-проверка музыки.

Opus подключает notification renderer/channel/actions, явное device confirmation, настоящий DND configuration Activity, чистый lookup выбранного локального файла и его lifetime, показ controlRevision/confirmationRequired/sourceAvailable/cleanupPending. Отсутствующий binding остаётся явным blocker. [Потоки данных](ATTENTION_DATA_FLOWS.md) описывают добавленную capability-границу.

Физическое DND, audio focus/playback, actual delivery, background/OEM timing и доступность UI требуют отдельной приёмки. Фоновая музыка, внешние музыкальные интеграции и рисунок празднования остаются продуктовыми вариантами. JVM/Robolectric/APK/lint не закрывают эти пункты.

Local music requires API27+ because the public O_CLOEXEC constant is API27. API26 returns UNSUPPORTED and the direct open helper refuses before opening a source; minSdk26 for the application is unchanged. [Android API](https://developer.android.com/reference/android/system/OsConstants#O_CLOEXEC).

Reference owner patch is a zero-context diff from46128a3 toC949; isolated-index `git apply --cached --check --unidiff-zero` passed. It is already integrated and must not be reapplied to current main.
