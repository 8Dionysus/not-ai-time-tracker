# Текущее состояние и открытые проверки

Android 0.10.0 — основной интерфейс на Compose ([UI](UI.md)) и техническая оболочка для остальных API. Нативная QA на отдельном AOSP API35 AVD выполнена по [матрице действий](../evidence/compose-current/android-action-matrix.json); приёмка на физическом телефоне остаётся открытой. Core и Room выполняют один контракт; результаты и ограничения — [VERIFICATION](VERIFICATION.md).

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

Текущий code SHA, APK SHA/размер, raw XML hashes и границы доказательств — в [VERIFICATION](VERIFICATION.md) и [машинном отчёте](../evidence/compose-current/verification.json). AVD доступен: реальные SAF/CalendarProvider, смерть и пересоздание процесса, widget/shortcuts/overlay/settings и один фоновый inexact Focus alarm проверены на синтетических данных.

Открыто: физический Android, OEM idle delivery, отключение питания, память и задержки телефона. TalkBack не установлен в AOSP image. Два разрешённых дефекта (three-button отступы и подсказка поиска font150) исправлены и проверены нативно; текущий APK v6 установлен обычным обновлением, обе части интервала в соседних днях прокручены без падения. Перенос ровно десяти разрешённых QA-архивов завершён со сверкой 40 хешей и неизменностью текущей базы.

Полный font150 проход на ширине 320 dp выявил дополнительные обрезания HH:mm ручной формы и некоторых однострочных кнопок. Они вне прежнего разрешения на два UI-исправления; отдельный запрос на минимальное исправление вместимости пока ожидает ответа. Матрица: 24 passed, 1 failed (additional font capacity), 1 not-run (TalkBack). Полная зелёная приёмка не заявляется.

Прогресс целей показан в «Обзоре»; создание и правка целей пока только через core API, экрана редактирования нет. История не имеет merge или сетевой синхронизации. Rollback/archives не очищаются автоматически. Лицензия не выбрана.
