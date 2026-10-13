# Проверки и пределы результатов

Проверки относятся к конкретному исходному состоянию и окружению. Успешный JVM-набор или сборка APK не подтверждают запуск приложения, доступность UI, поведение OS-разрешений и OEM на физическом Android.

## Воспроизводимая команда

Требуются JDK 17 или 21, Android Platform 35 и Build Tools 35.0.0. SDK и кэш задаются локально через environment/local.properties, без персональных путей в репозитории.

```sh
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Gradle Wrapper 8.13 проверяет закреплённый SHA‑256 дистрибутива. `--offline` работает только при заранее заполненном кэше. Версии Kotlin/AGP/Room зафиксированы в Gradle-файлах.

## Общий набор

Одни и те же три сценария выполняются на Memory и Room/Robolectric API 35:

| Сценарий | Охват |
|---|---|
| `timerPath` | Start без карточки, последующая разметка, pause/resume/switch/stop, повтор/Favorite, токены и конкурентные команды, monotonic/boot recovery; Choices, Goals, Focus; storage/cache, CSV inspect/mapping/preview/apply; Android-поверхности и optional adapters |
| `smallHistoryAndExport` | История, correction/Undo и общая граница, Reports/CSV и DST, дерево/фасеты/Decimal, calendar/grouping/EntryDraft, полный исследовательский корпус и явный package import |
| `backupReplacementAndFailure` | JSON 1–7, чистое хранилище, preview/cancel, полная замена и rollback, повреждения/размер/ссылки, stale и конкурентная правка; Room SQL-failure rollback и SAF byte boundary |

Тестовый JVM Room/Robolectric имеет heap 1 GiB для полного исследовательского корпуса и Android sandbox. Это параметр тестов, а не largeHeap приложения и не проверка памяти телефона.

Room-проверки используют дисковую SQLite, миграции 1→2→3, независимые подключения и принудительные ошибки записи после изменения дочерних таблиц. Android-адаптеры используют контролируемые intent/cursor/permission inputs. Они не обращаются к личному календарю и не заменяют физическую проверку CalendarProvider/chooser.

Новые границы включают malformed UTF‑8 внутри JSON, BOM, повреждение/oversize, отказ без записи; trusted catalog manifest и все supporting inputs, conflict/stale/replay/provenance; PROCESS_TEXT draft-only, opt-in и отказ/отзыв Calendar permission, all-day без выдуманного рабочего интервала; дедупликацию Focus DUE callback без переключения фазы.

## Записанные результаты

Результат предыдущего состояния app 0.9.1 сохранён в [draft-validation-v18/verification.json](../evidence/draft-validation-v18/verification.json): 3 Core + 3 Room, 0 failures, lint 0 errors / 12 warnings, APK собран, deviceTested=false. Эти файлы сохранены как историческое доказательство и не пересчитываются под новые изменения.

Текущий код проверен полным aggregate: **BUILD SUCCESSFUL in 1m 33s**, 3 Core + 3 Room/Robolectric, 0 failures/errors/skips; lint **0 errors, 15 warnings и 1 hint**, APK собран и v2-подпись проверена. Code commit: `3fb7439c2255ea1f7b4536ceed63d4402db9b85b`. Публичная сводка с case names, coverage, SHA‑256 исходных отчётов и APK: [backend-integration/verification.json](../evidence/backend-integration/verification.json).

Сводка получена из нового запуска. Неизменённые оригиналы XML/build/lint/manifest/signature сохранены локально; исторические evidence не заменены. Предупреждения относятся к target/dependency suggestions, техническим строкам/иконке, overdraw и shortcut usage; они не скрывались. Ни этот run, ни APK не являются проверкой физического телефона.

| Историческая область | Материалы |
|---|---|
| Начальные сценарии / migration / backup | [Core XML](../evidence/core-tests.xml), [Room XML](../evidence/room-tests.xml), [manifest](../evidence/apk-manifest.txt) |
| Research baseline и семантический анализ | [summary](../evidence/research/summary.json), [followup](../evidence/research/followup-summary.json) |
| Полная исправленная проекция | [summary](../evidence/full-projection/summary.json), [outcomes](../evidence/full-projection/outcomes.json) |
| Delta writes и внешняя история | [backend-v10](../evidence/backend-v10/summary.json) |
| Лимит перед CSV parser | [acceptance-v11](../evidence/acceptance-v11/summary.json) |
| Форматная инспекция | [format-import-v12](../evidence/format-import-v12/summary.json) |
| CheckedState / Android-поверхности | [backend-v13](../evidence/backend-v13/verification.json) |
| Goals / Focus | [goals-v14](../evidence/goals-v14/verification.json), [focus-v15](../evidence/focus-v15/verification.json) |
| Choices / LocalEntries | [choices-v16](../evidence/choices-v16/verification.json), [local-entries-v17](../evidence/local-entries-v17/verification.json) |

Сырые файлы evidence сохраняются без редакторской подгонки результатов. Старые версии Room/JSON в них относятся к моменту запуска; действующие версии — Room 3 и JSON 7. Ранние документы и изменения доступны в истории Git.

## Проверка документации

Проверены все 20 исходных Markdown/README/AGENTS-файлов: 19 авторских документов переработаны, один checksum-bound исследовательский Markdown сохранён как сырой исторический источник. Добавлен research/README для навигации и объяснения его роли. Проверены 135 локальных Markdown-ссылок и парность code fences; отсутствующих целей нет. Частные Pages, упоминания участников/исполнителей и пути окружения удалены из авторской документации. Все исходные research/evidence сохранены; полный перечень файлов находится в новой verification.json.

## Непроверенные области

Физический Android: Activity/SAF, sleep/reboot/kill/restart, widgets/launcher/overlay, OS prompts и отзыв разрешений, CalendarProvider, PROCESS_TEXT chooser, OEM-поведение, TalkBack и увеличенный текст. Производительность и батарея на многолетней истории телефона не измерены. Малые JVM-замеры являются локальными измерениями, без гарантии p95, плавности или фоновой доставки сигнала.

Репозиторий не содержит GitHub Actions workflow; отсутствие check runs не означает успешное CI. Remote commit проверяется отдельно от локального результата команд. Текущие функциональные ограничения — [состояние проекта](ACCEPTANCE_GAPS.md).
