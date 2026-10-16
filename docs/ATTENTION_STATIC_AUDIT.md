# Полный статический проход C

Срез: 16 октября 2026. Проверены все шесть новых production-модулей и обе owner-applied дельты. Это source review и структурные проверки, **не компиляция, исполнение Kotlin, Robolectric или проверка устройства**. JDK21 есть, Kotlin/Gradle/Android SDK отсутствуют; установка не предпринималась.

Поставка v2 с commit `f6fcadff696198d1bb4d34d0207340da1c0ac850` получена root: SHA-256 `be48712c551cdb7f6e4a469deae9581d60efeba3a4df7bfabd50bbe18802a48e`, CRC совпали. Ранее указанный C tunnel403 не является оставшимся препятствием доставки **v2**. Последующая audit-поставка меняет исходники и имеет собственный hash/version.

## Все production-файлы и защищаемые инварианты

| Файл | Разобранные границы и результат |
|---|---|
| `core/src/main/kotlin/dev/notai/tracker/LongRecordReminder.kt` | Порог только из доверенной обычной сессии; Focus/RECOVERY исключены; pause/stop/switch и barrier меняют subject. Callback hash — wake-up, не authority. Reserve до post; RESERVED/UNKNOWN не replay. Временная потеря clock trust при том же timer target больше не удаляет receipt. Notice связан с source Token и отзывается после смены сессии/label. Domain Token и локальный controlRevision различны. Переносимое предпочтение сохраняется существующим Tracker.settings; локальная activation связывает dataset/barrier/preferenceHash и после restore не совпадает. CONTINUE/SNOOZE/confirm не меняют history; DISABLE меняет переносимое preference, а STOP/REVIEW только существующий Tracker.stop сейчас. Закрытый runtime не даёт терминального post. |
| `core/src/main/kotlin/dev/notai/tracker/FocusEnvironment.kt` | Только явный begin в текущем WORK/RUNNING; точные timer target/focus/phase/boot/process. Локальная revision предотвращает replay завершённого begin при неизменном Dataset Token. Подготовка не активирует правило; lease/activation intent durable до STATE_TRUE. Утрата результата создания сохраняет request UUID и creationPending, блокирует новые эффекты и не забывается cleanup. ReleasePending липкий до результата. Cleanup не требует базы; нечитаемые часы/история ведут к отзыву, sourceAvailable=false не выдаёт неизвестный домен за IDLE. Неактивная lease сохраняется и переиспользуется только при новом явном begin; нет автоматического старта на permission grant или после process death. |
| `core/src/main/kotlin/dev/notai/tracker/ObservationFacts.kt` | Использует Reports.calendar и Goals.progress, ISO-неделю/явную зону, immutable capture входных фильтров/выбора. Коррекция пересчитывает duration/days/вехи; дробление не даёт награды. Session-count цель не eligible для празднования. Никаких persisted awards, XP, streak, rankings, уведомлений или анимации. |
| `app/src/main/kotlin/dev/notai/tracker/android/LongRecordReminders.kt` | Один replaceable elapsed inexact alarm, immutable PendingIntent, свежая core-проверка каждого wake-up. Factory/channel/permission — blockers, не opt-in. Нет создания канала/текстов/запроса grant за Opus. Receiver не создаёт пустую базу. Metadata атомарная и durable через существующий helper; scope/gate общий для экземпляров. Ни local consent, ни receipts не входят в Dataset. |
| `app/src/main/kotlin/dev/notai/tracker/android/FocusEnvironmentAdapter.kt` | API29 policy access и настоящий rule Activity требуются явно. Только свой rule ID + condition key + own package/config + fingerprint; нет global DND setters. Правило не удаляется: cleanup посылает STATE_FALSE и сохраняет configuration. Парковка предотвращает обычное накопление rules. Parcel stamp ограничен SDK/build; неизвестная provenance сохраняется для разбора. Музыка использует один pure cached lookup, regular local FD с NOFOLLOW/NONBLOCK/fstat, удерживает открытый inode до release. Нет URI/HTTP/account/external media control. Callback сверяет конкретный player, а не только повторно использованный UUID. Request/abandon audio focus проверяются; каждый ресурс освобождается отдельно, ошибка release не пропускает abandon. Ни focus gain, ни lifecycle resume сами не запускают player. Expiry/boot cleanup не открывает Room. |
| `app/src/main/kotlin/dev/notai/tracker/android/AttentionRuntime.kt` | Lifecycle callback делает только короткий foreground hint под маленьким visibility gate; IO/decoder/Room не блокируют callback. Откат регистрации при неудачном init. closed выставляется до остановки observers: финальные guards запрещают уже подготовленный post/start, journal gate обеспечивает последующий cancel. Поздний lifecycle callback не переоткрывает closed runtime. ordinary и Focus ошибки независимы. При retirement отменяются обычный alarm/notice и отзывается собственный Focus intent; portable preference остаётся. Нет ticker/polling/service. |

## Исправленные реальные дефекты v2

1. Старое уведомление могло пережить смену обычной сессии или label. Теперь оно связано с source Token и отзывается; receipt сохраняется без автоматического нового показа.
2. Clock uncertainty удаляла receipt и после repair позволяла новую автоматическую попытку. Теперь это только suspension при неизменном timer target.
3. Dataset Token не замечает локальную команду Focus/подтверждение. Для новых команд требуется отдельная controlRevision; stale intent нельзя переиграть с тем же domain Token.
4. DND cleanup удалял правило после неатомарной проверки и мог удалить одновременно отредактированную configuration. Удаления нет; только своя condition, parked lease и явное повторное использование.
5. Parcel fingerprint ошибочно считался стабильным между OS builds. Теперь иной stamp — UNKNOWN и сохранённая зависимость разбора.
6. Потеря OS rule ID после prepare могла потерять след неактивного частичного правила. Creation intent durable до prepare; UNKNOWN сохраняет UUID/creationPending и не replay.
7. Старый музыкальный callback мог затронуть другой player с повторным request ID. Теперь проверяется object identity player; ни один callback не начинает музыку.
8. Path-level isFile и повторный resolver не фиксировали открытый source. Теперь lookup один раз, FD проверяется после nonblocking/no-follow open и удерживается до release.
9. Ошибка одного cleanup-ресурса могла пропустить другой. Теперь release player, abandon focus и close FD имеют независимые попытки; отказ/неопределённость сохраняет owner для retry.
10. Закрытие runtime не отменяло обычный alarm/notice и не блокировало подготовленный effect. Добавлены closed guard, suspension и rollback незавершённой регистрации.
11. Нечитаемый clock мог оставить Focus effect до expiry и лишить результата cleanup. Cleanup выполняется независимо, с sourceAvailable=false.
12. **Полнота full backup:** device-local хранение enabled/afterMs/snoozeMs было неверным исключением из полного Settings-контракта. Переносимые значения перенесены в Settings, operational state отделён; после restore нужна новая локальная activation.

## Portable / device-local / platform

| Данные | Authority и перенос |
|---|---|
| LongRecordPreference.enabled/afterMs/snoozeMs | Settings; Tracker.settings transaction; полный JSON12. Работает и для disabled preference с сохранёнными пользовательскими длительностями. |
| Activation для delivery | Только device-local `(datasetId,controlBarrier,preferenceHash)`; не архивируется. Full restore уже создаёт свежий barrier. Preview предупреждает, confirm — отдельное явное действие. |
| Receipt, pending ticket, dueElapsed, boot, local revision | Только operational file; не полная история и не архив настроек. |
| DND request/lease/parked rule ID/creationPending | Только operational file конкретного устройства; не переносится. Неподтверждённая операция не становится выполненной. |
| Permission/channel/current DND/audio focus | Читается из ОС. Не подменяется portable preference или acknowledged device activation. |
| FocusEnvironmentChoice | Явный выбор эффектов для **этого активного интервала**, а не введённая здесь глобальная пользовательская настройка. IDs локальных файлов и активные эффекты не переносимы. |

## Полная shared-дельта для последовательного применения A

`integration/attention-runtime.patch` по-прежнему затрагивает только `AndroidRuntime.kt` и manifest: lazy binding, существующий observer/refresh, close до retirement, три receivers и ACCESS_NOTIFICATION_POLICY declaration. Patch не является grant. Новые services/INTERNET permission не добавлены.

`integration/attention-preferences.patch` затрагивает ровно:

- `core/src/main/kotlin/dev/notai/tracker/Model.kt`: optional default Settings.longRecordReminder, валидация; default отсутствует в encoded legacy-compatible data.
- `core/src/main/kotlin/dev/notai/tracker/Backup.kt`: current write12 и preview flag `ordinaryReminderConfirmationRequired`.
- `core/src/main/kotlin/dev/notai/tracker/Migration.kt`: read1–12; nondefault нового preference в format≤11 отклоняется, а старое отсутствие даёт disabled default.
- `core/src/main/kotlin/dev/notai/tracker/StreamingBackup.kt`: тот же current12/read1–12 и legacy guard, без operational fields.
- `app/src/main/kotlin/dev/notai/tracker/android/TrackerDatabase.kt`: Room10 и 9→10; только metadata preference default, без стирания/изменения history/anchors.
- `app/schemas/dev.notai.tracker.android.TrackerDatabase/10.json`: точный **projected candidate**. Entity SQL/identityHash совпадают с9; A должен получить штатный generated output KSP и сравнить. Здесь генератор не запускался.
- `core/src/testFixtures/kotlin/dev/notai/tracker/ScenarioContract.kt`: current format assertions/probes 12, legacy semantics сохранены.
- `app/src/test/kotlin/dev/notai/tracker/android/RoomScenariosTest.kt`: новая migration в существующих chain lists, уже доходивших до9.

Tracker.kt, ImmutableData.kt и build-файлы дополнительной дельты не требуют: используется существующая транзакция settings, LongRecordPreference immutable и не содержит коллекций. **Новые модули ссылаются на Settings.longRecordReminder: core/app нельзя компилировать до применения preferences patch.** Общие файлы в C не переписаны напрямую.

## Подготовленные проверки и точный остаток

Подготовлены 27 исполнений: четыре shared-сценария × Memory/Room =8; AttentionSafetyTest7; AttentionAuditRegressionTest7; AttentionPreferenceArchiveTest1; AttentionPlatformTest3; AttentionPreferencesMigrationTest1. Команды — в ATTENTION_BACKEND_HANDOFF. Это **NOT_EXECUTED** в C. Помимо исходных случаев добавлены cold-decode UNKNOWN, repair часов, source notice invalidation, local revision, retirement during prepare, clock unavailable, lost DND creation, parked reuse, local FD replacement/symlink/empty source, full JSON guard и restore confirmation.

A: сверить фактический main; применить исходную поставку и audit follow-up без повторного ввода уже принятых файлов; последовательно применить обе shared-дельты по текущему ownership; сгенерировать schema10; исполнить targeted/core/app suite, APK/lint и сверить hashes. При изменившемся base переносить дельту по смыслу, не перезаписывать работу A/B. Push/merge остаются у A/root.

Opus: renderer/channel и разрешения; настоящий DND configuration Activity; pure local-file mapping/выбор; показ controlRevision, confirmationRequired/sourceAvailable/cleanupPending; отдельный confirm восстановленного preference. Если выбранная audio source — защищённая app-private copy, её существующие copy/chooser/lifetime claims подключаются в выборе файла; C не создаёт новый media catalog, writer или cleanup engine. FD pin не запрещает запись в тот же inode; для неизменяемого источника нужен заранее подготовленный snapshot.

Конкретная зависимость сбойной ветки DND: `creationPending` или несовместимый SDK/build stamp требуют явного разбора системного rule с сохранённым request/key/lease и актуального policy access. Backend не угадывает lost rule ID, не присваивает изменённую configuration и не выдаёт такой cleanup за успешный. Автоматической очистки по возрасту нет. Публичный Android API не даёт CAS для одновременного изменения condition человеком; гарантируется отсутствие удаления/перезаписи configuration и воздействия на чужие rules, а не атомарный контроль всей панели Modes.

Физическое устройство, фактическое DND/playback/delivery, OEM inexact timing, background behavior, permission flow и UI accessibility здесь не проверялись. Фоновая музыка/внешние музыкальные интеграции и выбранный рисунок/эффект празднования остаются продуктовыми вариантами. Ни один из этих вариантов не скрыт под заявлением о готовой реализации.

Официальная дополнительная проверка: [MediaPlayer.setDataSource](https://developer.android.com/reference/android/media/MediaPlayer#setDataSource(java.io.FileDescriptor)) допускает закрытие переданного FD после вызова; прежнее раннее close само по себе не было доказанным багом. Новая удерживаемая handle уточняет source lifetime. [OsConstants.S_ISREG](https://developer.android.com/reference/android/system/OsConstants#S_ISREG(int)) проверяет открытый тип. [AudioManager.abandonAudioFocusRequest](https://developer.android.com/reference/android/media/AudioManager#abandonAudioFocusRequest(android.media.AudioFocusRequest)) возвращает отдельный результат отказа/подтверждения; он теперь учитывается.
