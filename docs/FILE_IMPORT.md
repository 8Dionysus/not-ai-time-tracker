# Импорт файла: inspect → mapping → preview → apply

Вход ограниченного CSV/inspection API — `HistoryFiles.inspect(bytes, delimiter = null)`. Приложение, создавшее файл, выбирать не нужно. Расширение/имя файла не определяет формат: анализируется содержимое. Проверка лимита 10 MiB предшествует декодированию, затем используется строгий UTF-8.

Результат ImportInspection:

- `format`: DELIMITED_TEXT, BACKUP_JSON либо UNSUPPORTED.
- `candidates`: подходящие разделители, точные имена колонок, до пяти полных строк-примеров, `suggestions: Map<ImportField, List<String>>`.
- `issues/errorIssues/warningIssues`: коды, typed arguments, неоднозначность и причины отказа; `warnings` и `problems` сохраняют старые diagnostic strings для совместимости.

Для табличного текста инспектор пробует comma, semicolon, tab и pipe на заголовке и первых пяти записях. Это bounded sample, а не обещание пригодности всех последующих строк. Кавычки и многострочные поля разбираются тем же CSV parser. Если вариантов несколько, возвращаются все; автоматического «победителя» нет. Для другого/неоднозначного разделителя UI может повторить inspect с явно выбранным delimiter. Явно заданный delimiter выбирает табличную ветку, в том числе если имя первой колонки начинается со скобки.

Предложения колонок основаны на небольшом списке названий полей, не на брендах. Все совпадения сохраняются в списке; неизвестные заголовки можно назначить вручную. Эти предложения не создают CsvMapping и никогда не записывают данные.

Для продолжения UI вызывает `inspection.confirmCsv(delimiter, mapping, timeChoice, durationFormat)`. Разделитель обязателен отдельно, поэтому default ',' старого CsvMapping не становится молчаливым выбором. `CsvTimeChoice(null, "UTC")` означает ISO timestamp с явным offset; для локальных дат задаются точный pattern и IANA-zone. Например, 03/04/2026 не получает трактовку без явного dd/MM либо MM/dd. Если есть колонка длительности, нужно явно передать DurationFormat. Затем разбирается и проверяется весь файл, включая строки за пределами sample. Неоднозначное/несуществующее время DST отклоняется прежним механизмом.

Полученный TransferFile показывает `activities`, `errors`, `warnings`. UI отдельно сопоставляет пары занятие/категория с Classification и передаёт их в `HistoryTransfer.preview`. После показа интервалов, ошибок, повторов и последствий пользователь подтверждает `apply`. Это прежнее атомарное добавление с token, дедупликацией, provenance, Undo и проверенной откатной копией; инспекция не меняет эту семантику.

Для BACKUP_JSON используется `inspection.prepareRestore(backups)`, далее существующий `backups.apply(preview)`: это полная замена, а не добавление CSV. Поддержаны собственные форматы backup 1–12 с полной валидацией; экспорт использует формат 12. Для сохранения `manualConfirmation` нужен полный JSON12 либо selective3; ограниченный CSV этот признак не восстанавливает. Произвольный JSON, XLSX, другие бинарные форматы, не-UTF-8 кодировки и таблицы без заголовка не поддержаны. Неподтверждённая структура файла требует явного сопоставления/исправления или отдельной реализации формата, а не ожидания адаптера по названию приложения.

Старые CsvMapping/parseCsv и именованный профиль колонок остаются совместимыми низкоуровневыми входами. Основной пользовательский путь их брендовых имён не требует. Полный SAF restore использует staged file и потоковый Backups.prepare без общего 10 MiB cap; техническая CSV-форма позволяет явно выбрать все перечисленные параметры.

## Два сквозных примера

`a.csv`:
```csv
id,activity,start,end,note
1,Read,2026-10-08T08:00:00Z,2026-10-08T08:20:00Z,Chapter one
```

`b.csv`:
```csv
код;дело;начало;секунды;комментарий
2;Walk;09.10.2026 10:00:00;600;Park
```

После просмотра inspection и явного выбора пользователем:
```kotlin
val a = HistoryFiles.inspect(aBytes)
val aRows = a.confirmCsv(',',
    CsvMapping("source-a", "activity", "start", end = "end", note = "note", id = "id"),
    CsvTimeChoice(null, "UTC"), null)
val b = HistoryFiles.inspect(bBytes)
val bRows = b.confirmCsv(';',
    CsvMapping("source-b", "дело", "начало", duration = "секунды", note = "комментарий", id = "код"),
    CsvTimeChoice("dd.MM.uuuu HH:mm:ss", "Europe/Berlin"), DurationFormat.SECONDS)
for (file in listOf(aRows, bRows)) {
    // Здесь пользователь явно выбрал учитывать исходные названия без личных карточек.
    val chosen = file.activities.associateWith { Classification(name = it.activity) }
    val preview = runtime.historyTransfer.preview(file, chosen)
    require(preview.canApply) // Render preview.errorIssues with reviewed resources before confirmation.
    // В UI Apply вызывается только после показа preview и подтверждения.
    runtime.historyTransfer.apply(preview)
}
```

Итог: два интервала, 1 800 000 ms. Второй начинается 2026-10-09T08:00:00Z. Повтор того же файла в том же namespace добавит 0 записей. Эти два пути проверяются в общем сценарии на Memory и Room, вместе с неоднозначными разделителями/колонками/датами и повреждённой строкой после sample. Примеры синтетические, без названия приложения-источника.

## Полная замена и отказы

Preview не пишет данные и не создаёт rollback. Полный SAF JSON читает потоковый reader: BOM принимается, повреждённый UTF-8 и усечённый файл отклоняются. Общего 10 MiB cap для этой ветки нет; convenience inspector остаётся bounded. Apply требует подтверждения и проверенной откатной копии. [Полные копии и recovery](PORTABILITY.md) описывают сохранение исходников и восстановление недоступной базы.

## Локализуемый контракт результатов

Структурированные `issues/errorIssues/warningIssues` и `CoreError.importIssue` теперь сопровождают все создаваемые этой CSV/inspection веткой errors/warnings. Старые строки сохраняются для совместимости. UI должен перейти на stable keys и typed arguments; номера относятся к CSV-записям, а пользовательские значения не переводятся. Полная таблица кодов, аргументов и точные места интеграции: [IMPORT_ISSUES.md](IMPORT_ISSUES.md).
