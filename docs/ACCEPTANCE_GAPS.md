# Текущее состояние и открытые проверки

Android 0.10.0 — основной интерфейс на Compose ([UI](UI.md)) и техническая оболочка для остальных API. Продуктовая приёмка на устройстве остаётся отдельной работой. Core и Room выполняют один контракт; результаты и ограничения — [VERIFICATION](VERIFICATION.md).

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

Физический Android/emulator не подключён. Не подтверждены OS chooser, календарь устройства, доставка уведомлений в idle/OEM, восстановление после реального убийства процесса/потери питания, память и задержки телефона. JVM-сценарии не закрывают этот остаток. Точные результаты замеров и текущего aggregate публикуются только после их завершения.

Прогресс целей показан в «Обзоре»; создание и правка целей пока только через core API, экрана редактирования нет. История не имеет merge или сетевой синхронизации. Rollback/archives не очищаются автоматически. Лицензия не выбрана.
