# Исследовательские источники каталога

Этот каталог содержит исходные данные и решения проекции, использованные Kotlin-адаптерами. Они описывают предметные определения, источники, отношения и ограничения. Исследовательская рекомендация сама по себе не означает реализованную функцию приложения.

| Материал | Роль |
|---|---|
| [catalog.research.json](catalog.research.json) | Базовый корпус схемы 0.5: 1584 исходные записи и 1587 исследовательских узлов |
| [supporting/](supporting/) | Пять сопроводительных документов, включая разрешение identity-конфликтов, совместимость ядра, 357 междоменных отношений и исследование мобильной навигации |
| [projections/manifest.json](projections/manifest.json) | Закреплённые размеры/хеши исправленных ISCO и спортивной проекции |
| [isco_full_projection_v2.json](projections/isco_full_projection_v2.json) | Все 353 исходные строки ISCO, явные предложения и типизированные отношения |
| [sports_additive_catalogue_projection.json](projections/sports_additive_catalogue_projection.json) | Все 223 спортивные строки, занятия, целые события и уточнения |

Сопроводительный Markdown `tracker_catalog_core_compatibility.md` — исходник с SHA‑256, закреплённым в базовом manifest. Его исторические формулировки сохраняются побайтно для проверки происхождения; действующий API описан в [UI_CONTRACT](../docs/UI_CONTRACT.md) и [CATALOG_IMPACT](../docs/CATALOG_IMPACT.md). Это относится и к историческим названиям инструментов/проектным рекомендациям внутри сырых JSON-источников.

[RESEARCH_ADAPTER](../docs/RESEARCH_ADAPTER.md) объясняет reader и архивирование, [RESEARCH_FOLLOWUP](../docs/RESEARCH_FOLLOWUP.md) — смысловые границы осей/значений, [QUANTITY_REVIEW](../docs/QUANTITY_REVIEW.md) — количественные представления, [FULL_CATALOG_07](../docs/FULL_CATALOG_07.md) — итоговую проекцию 1918 типов/337 осей и остаточные отображения.

Исходные документы не входят в обычный пользовательский JSON backup. При переносе доказательств нужен отдельный перенос ResearchArchive. В каталоге runtime отношения не создают дополнительные сегменты или время; невыбираемые группы служат навигации. Установка полного корпуса — явная операция с preview/проверкой и `Tracker.addCatalog`, без скрытой замены пользовательских определений.

Восемь принятых inputs включены в APK через generated assets. Явный `runtime.prepareBundledCatalog()` и последующий `catalogImports.apply(preview)` дают исполнимый путь установки; создание runtime не импортирует каталог. Внешний комплект и командный путь описаны в [FULL_CATALOG_07](../docs/FULL_CATALOG_07.md).
