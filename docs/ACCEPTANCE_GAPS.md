# Текущее состояние и открытые проверки

Android 0.11.0/code12 — основной интерфейс на Compose ([UI](UI.md)) и техническая оболочка для остальных API. Текущая backend-схема: Room12, full JSON14/read1–14, selective4/read1–4. Принятые четырнадцать направлений и исправления R2 описаны в [матрице покрытия](JOURNAL_14_COVERAGE_2026_10_16.md), [контракте](JOURNAL_COMBINED_CONTRACT_2026_10_16.md) и [точках UI-интеграции](JOURNAL_OPUS_INTEGRATION_2026_10_16.md). Все 11 проверенных каталогных переводов теперь разрешены для public Git и включены в assets; это не завершённая локализация Android UI.

Новые post-merge JVM/Room/сборочные результаты и точные SHA: [проверка объединения](JOURNAL_PUBLIC_MERGE_2026_10_16.md). Core и Room выполняют один контракт. Новая проверка на Android-устройстве не заявляется.

## Историческая Android QA (2026-10-13)

Нативная QA отдельного AOSP API35 AVD относится к code commit `a589f56779163aef07952f1696a97593d0e09b6b` и [сохранённой матрице действий](../evidence/compose-current/android-action-matrix.json). Следующая таблица и описание того прогона сохраняют прежние доказательства; они не являются device-приёмкой нынешнего объединённого backend. Приёмка на физическом телефоне остаётся открытой.

| № | Исправление | Проверяемый путь |
|---|---|---|
| 1 | Полный готовый каталог в APK; исследования вынесены | ReadyCatalog, три assets, новый Room store |
| 2 | Обновления provenance/описаний сохраняют личные данные | Старый готовый fixture, guards, custom name/archive/aliases, конфликт/replay |
| 3 | Полные копии больше 10 MiB без целого encoded файла | Потоковый file export/preview/restore/rollback, 10 000 длинных записей |
| 4 | Recovery без исправной базы, без пустого bootstrap | Нечитаемый DB/WAL/SHM, source-change refusal, проверяемый архив, readback |
| 5 | Stop переживает изменение настроек, старый Stop не останавливает новую сессию | TimerTarget epoch/barrier, атомарная permission/opt-in проверка, без retry с новым token |
| 6 | Focus продолжает фазу в новом процессе того же boot | Monotonic + boot/wall checks, cap одной фазы; opt-in inexact уведомление |
| 7 | Список/nearest календарных occurrences и выбор | Диапазон/cursor/recurring/all-day/denial; user-initiated permission |
| 8 | Устойчивый SAF и формы | Private operation/file, cancel/retry/readback, recreation |
| 9 | Локальная зона новой установки и явная смена | Полный каталог сразу, отдельные зоны целей; физические интервалы сохранены |
| 10 | Измерение 73 000 записей до и после изменений | Room cursor loading, shared frozen classifications, indexed reports/calendar |
| 11 | Обобщённое ядро | Нет обработчиков исследований или source-SHA whitelist в runtime |
| 12 | Технические CSV/Favorite/Quantity пути | Явные delimiter/columns/zone/units/classification, архивный saved snapshot, whole-target quantities |
| 13 | Неизменяемый подтверждённый preview | Мутация исходной correction/CSV формы не меняет применяемые данные; public collections защищены |
| 14 | Выбор по ID и хронологически последняя запись | Одинаковые имена, ручной/CSV старый интервал после нового, edited bounds, сохранение формы |
| 15 | Пустая группа не создаёт тупик выбора | area.self_directed_creation сохранён, из обычного поиска скрыт |

В пункте 4 дополнительно проверяется отсутствие любой записи при пропавших metadata/vocabulary/generation: число и хеш содержимого таблиц сохраняются; даже заполненный кэш не скрывает повреждение.

## Остаток

Исторический code SHA, APK SHA/размер, raw XML hashes и границы доказательств того прогона — в [VERIFICATION](VERIFICATION.md) и [машинном отчёте](../evidence/compose-current/verification.json). В том AVD-прогоне реальные SAF/CalendarProvider, смерть и пересоздание процесса, widget/shortcuts/overlay/settings и один фоновый inexact Focus alarm проверены на синтетических данных.

Открыто: физический Android, OEM idle delivery, отключение питания, память и задержки телефона. TalkBack не установлен в AOSP image. Два разрешённых дефекта (three-button отступы и подсказка поиска font150) исправлены и проверены нативно; тогдашний APK v6 установлен обычным обновлением, обе части интервала в соседних днях прокручены без падения. Перенос ровно десяти разрешённых QA-архивов завершён со сверкой 40 хешей и неизменностью текущей базы.

Полный font150 проход на ширине 320 dp выявил обрезания HH:mm ручной формы и некоторых однострочных кнопок. В 0.11.0 время уменьшается вместо переноса, поля начала и конца и пары кнопок встают в столбик, подписи переносятся на две строки; это проверено JVM/Robolectric-тестом, повторный нативный проход ещё не выполнен. Матрица: 24 passed, 1 failed (additional font capacity), 1 not-run (TalkBack). Полная зелёная приёмка не заявляется.

Прогресс целей показан в «Обзоре»; создание и правка целей пока только через core API, экрана редактирования нет. История не имеет merge или сетевой синхронизации. Rollback/archives не очищаются автоматически. Для собственного кода выбрана [Apache-2.0](../LICENSE); [NOTICE](../NOTICE) сохраняет Onest OFL и границы стороннего контента. Разрешение опубликовать каталогные переводы не переписывает их исходные rights/license metadata.


Текущий остаток backend/UI: подключение четырнадцати API, выбора языка и Android resources, трёх поверхностей расписания, review/share и quantity projections; overlay P2 и полноценный редактор целей; реальные OEM/RTL/TalkBack/permission/delivery проверки. Исторические AVD и новые JVM-тесты эти пункты не закрывают. Legacy cleanup-owner witness, нативное publisher provenance и независимая воспроизводимость остаются отдельными ограничениями. Выпуск в магазины, signing и публикация APK не выполнялись.
