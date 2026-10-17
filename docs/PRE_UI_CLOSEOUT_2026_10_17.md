# Backend r14 — проверенный кандидат после принятого r12

Принят координатором r12 в **AOSP API35 emulator scope**; его exact source/docs checkpoint — `3644317868b6718da467bfcba157975dbe39a8ac`, QA4 APK b06fdafe37e57af36948e9133b43033815c1bdaf4d7cc8f64f86247bdcf2d10f, private Library version13. Новое продолжение **r14 ещё требует координаторской приёмки**. Ветка `prep/backend-completion-2026-10-17`; code/build source `de587c73b5f38cc5774e0d1acff2bae5c5581508`; финальный docs HEAD и bundle указаны в source receipt. Принятый branch сохранён. Main841448b и пользовательский checkout не изменены, push/PR/merge отсутствуют.

## Доведённые границы

- `491e00ae3cc001996158570b7d12a50cf51cf3d6`: заблокированная Focus delivery возвращает текущий checked snapshot под domain gate. До fix реальный TechnicalActivity caller показывал завершение уже продвинутой фазы. Red XML сохранён; targeted green16. Memory/Room stop/advance без Dataset/revision mutation, без нового чтения Room на main и без резервирования receipt при blocked.
- `9bdcec5be566c5e75de88f3b3ddf659500dc4f14`: existing setSurfaceEnabled(SHORTCUT,…) подключён к уже существующему preference через transactional command. До fix ошибочно «нет enable preference». Red XML сохранён; весь UiReviewRegressionTest30green. Disable→reenable сохраняет остальные settings, history, running timer/epoch/barrier и увеличивает revision ровно дважды. Экран/ресурс не добавлен.
- Debug QA commits217ed1e/de587c7: bounded native probes в том же разрешённом primary debug package, без дополнительного helper APK, ключа или permission.

Fresh полный offline **207core+255app=462**, **54XML**,0 failures/errors/skips. Test up-to-date cache отключён для обоих модулей; все XML timestamps после старта final run. `assembleDebug`, `assembleDebugAndroidTest`, `lintRelease` успешны. Все332 build inputs/hash проверены. Raw `backend-r14-final/`, final build/resource logs и input manifest. Первая compile попытка QA с неоднозначным Clock и первая native QA попытка без processId сохранены как **ошибки фикстуры**, не product regressions. Первый native admission был blocked по physical reserve и не исполнился; повтор сделан только после terminal/release собственной сборки, безforce.

## Нативно на текущем APK

Own AOSP API35 x86_64, `emulator-5590`, `not_ai_preui_20261016`,2560MiB/2vCPU. Existing QA signer86e221956fd1ed4dab261a10c1baeac3537816d271601037b309f2ddf186c3cc. Final primary APK **7725e7d39783a71b78a6dfb0eae206a3f1e6662e1066c69aaf969890fa092b81**, 16649940B. Source `de587c73b5f38cc5774e0d1acff2bae5c5581508`. Подпись не является release identity.

На этом APK native Focus Memory/Room stop/advance —4cases; actual TrackerViewModel shortcut disable/reenable с synthetic Memory/Room —2cases. Это нативные программные controller probes; новый launcher UI/физическое устройство ими не объявляются проверенными. Все проверки PASS. `backend-r14-final-native/` содержит instrumentation reports, raw logs, package/appops/channel snapshots, installed APK hashes; integrity directories — SQL state и потоковые hashes всех исходных строк/словарей.

Default dataset **73015sessions/73020segments,revision83,IDLE,Focus=null,pendingAssignments=[]** сохранён полностью. Все прежние4 helper packages точны;6 grants/flags, POST ignore/overlay default, timer channel policy сохранены. Штатные application channels возвращены в accepted deleted state; Android tombstones не выдаются за полное побайтное равенство всего dumpsys. Own AVD остановлен после проверки; все данные/архивы сохранены. Original AVD backing проверяется по пяти size/mtime/ctime, не по новому6GB hash.

Новая native проверка адресная. Принятый r12 real AlarmManager→SIGKILL→cold process→видимые Schedule/ordinary cards, neutral SystemUI tap, export callback contention/death/own retry сохраняет свой source scope. Migration/full JSON/selective/SAF/widget/Focus rows в полной `NATIVE_MATRIX_2026_10_16.md` сохраняют свои версии/уровни evidence. Новый полный native rerun всех старых версий не заявляется.

## Производительность

Большая исходная база73000/73000,3650дней×20,JSON66,840,051B. Принятые paired n7 scanner29.664203702→21.410060489s (**−27.8253%**), archive registration30.821839462→1.890261639s (**−93.8671%**), exact output/forest/registry. Cold/warm/history/search/reports/import/restore/memory raw stages/config сохраняются в `PRE_UI_ACCEPTED_V10.md` и cumulative evidence.

Cold startup ~6.2s **не ускорен** в этом продолжении. R12 seven-cold median activity→frame6229.69ms /→checked history5881.93ms — измеренная остаточная задержка. Native cursor hypothesis на той же73015/73020 DB:7alternating samples/variant, полные Dataset равны, median checked load keyset5121016.043ms против streaming1470.830ms; SQL191.923→476.585ms,6/7pairs медленнее. Streaming отклонён, current512 сохранён. Это cursor load, не process-cold benchmark; expected full Dataset retained в обоих вариантах, javaUsed не production PSS. Один normal pre-open preservation archive58,449,920B сохранён; отдельной benchmark DB не копировали. Raw `backend-r14-cursor-native/`, comparison/config/archive qualification.

Pre-open gap не приписывается одному backend методу; archive hashes, immutable full graph и validation не обходились. Подтверждённой безопасной дополнительной startup оптимизации из этой гипотезы нет; произвольный SLO/лимит записей не введён. Прежние измеренные IO/fsync сохраняют durable order.

## Полный остаток и точная граница landing

См. `BACKEND_COMPLETION_2026_10_17.md`: все14 journal API, commands, schedule/reminder/Focus lifecycle, CSV/full/selective/export/restore/cleanup/quick surfaces и каждый вид незавершённости сопоставлены с кодом/тестами. Typed Focus callers, switchDraft, reporting boundary и goal display уже подключены; новые исправленные adapter gaps перечислены выше.

Действительно остаются: новые UI forms/preview/navigation/controls, CSV sourceLocale input/mapping form persistence, restored-preferences confirmation UI, реальные Focus rule configuration/local-file choices и права; реальные device/OEM idle/power-loss/TalkBack/RTL/storage/pin evidence; release/signing/store и публикация/main. Generic retirement legacy UNKNOWN witness не закрыт: текущие refs/hashes не содержат исторического owner origin/полного lifetime census; нужен авторитетный handoff или отдельно заданная owner policy. Это ограничение данных/политики, не только экран. Автопурга/expiry не добавлена.

Lint **75warnings+1hint**, все76 current matches,52retain/24futureUI; индивидуальная таблица/JSON актуальны,7durable commits оставлены,3InlinedApi action strings. Никаких suppressions/dependency updates ради счётчика.

Read-only landing preflight: live main841448b, accepted364 ahead24/behind0; исходные58files=16production+18test/debug+16docs/history+8evidence. Новый r14 candidate содержит дополнительные адресные изменения, его точный diff/HEAD в финальном source receipt. `.github` workflow/обязательного PR/releasing skill не найдено; main protected=false/rules[]. Четыре старых commit имеют personal metadata; accepted history не переписывалась. Нужен явный выбор: публиковать сохранённую историю или новый single landing commit с exact verified tree и неперсональным identity. Draft PR также публикует source/metadata и не заменяет разрешение. Минимальный следующий шаг — координаторская приёмка exact candidate, затем явно адресованное разрешение публикации и landing; release/store не следуют из него автоматически.

Cumulative поставка сохраняет все3636payloads Libraryv13 и предыдущие retention maps, exact current source patch/bundle, APK, XML/logs и эту карту остатка. Версия/размер/SHA Library подтверждаются отдельной квитанцией после загрузки. Immutable ZIP после upload не изменяется. Полные переписки не включались; GitHub ничего не публиковалось.
