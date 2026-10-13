# Холодная загрузка: текущий SQL шаг

Проверенная промежуточная поставка; дальнейшее ускорение и системная QA продолжаются. [Полные измерения и регрессии](../evidence/cold-start-sql/verification.json).

Room 4 добавляет `(position,id)` индексы для sessions/segments и читает историю keyset страницами по 512 записей. Полная предметная проверка сохранена; пользовательские строки не переписываются при миграции 3→4. Границы 0/1/511/512/513/1024/1025, одинаковые позиции, Unicode ID, reopen, точные строки миграции и single-active проверены.

На одном AOSP API35 x86_64 AVD: KVM включён, emulator37.2.12, 2 CPU/2 GiB RAM, swangle software GLES, Vulkan отключён. SwiftShader GLES ранее завершался SIGSEGV внутри QEMU; смена software renderer загрузила тот же AVD без изменений безопасности хоста. Системные команды после восстановления соединения реально отвечают; emulator-5580 доступен.

Одинаковые 73 000 сессий/сегментов и полный каталог, пять холодных процессов и пять новых Activity в живом процессе на вариант, без отброшенных прогревов и без очистки OS page cache.

| Android метрика, медиана | До | SQL вариант |
|---|---:|---:|
| Activity create → пригодный action frame | 12,483 с | 8,323 с |
| Новый экран в живом процессе | 248,674 мс | 290,417 мс |
| SQL sessions | 2,762 с | 0,420 с |
| SQL segments | 2,662 с | 0,360 с |
| Decode sessions/segments | 2,802 с | 3,030 с |
| Freeze | 0,939 с | 0,851 с |
| Domain validate | 1,566 с | 1,530 с |

Первый Android кадр (am start -W) не означает готовность команд. Отдельные scalar debug markers измеряют process-start, Activity, полную history graph и action-ready/first-frame. Release не пишет маркеры и не собирает per-row phase timing. UI и domain validation не подменены пустым состоянием. 0,958 с — JVM/Robolectric медиана другой серии, **не Android результат**. Software graphics, ART/JIT, порядок серий и температура могут влиять; физический телефон не проверен.

Остаток Android задержки уже преимущественно decode/freeze/validation полного графа. Следующий ограниченный эксперимент — компактная запись существующего JSON payload без изменения предметных значений и без ослабления проверки; принимается только после одинаковых данных, повторных измерений и миграционных регрессий. Если это не даст нужной отзывчивости, потребуется отдельная доказанная граница быстрого timer state и истории; произвольный trusted flag недопустим.

Сырые samples сохранены в evidence/cold-start-sql. Воспроизводимый сбор Android timing: `python3 scripts/android_startup_samples.py --serial emulator-5580 --avd not_ai_qa_api35 --label NAME --output OUT --count 5`. Только явно выбранный тестовый AVD и debug package.

## Следующий шаг: типизированная история и проверенное сохранение

[Исходные samples, fingerprints и проверки](../evidence/normalized-history/verification.json). Согласованный проход продолжается; это отдельная поставка для независимой приёмки.

После реального Start/Pause/Resume/Stop набор содержит 73 001 сессию /73 002 сегмента. Все девять canonical table value hashes и counts совпадают до compact experiment и после Room 4→5. Это одинаковые данные для новой серии, а не буквальное сравнение с прежними 73 000 записями.

| Android Activity→action frame, пять samples | Медиана | Диапазон |
|---|---:|---:|
| SQL4 перед compact experiment | 7,791 с | 7,492–8,593 с |
| Тот же APK/данные, compact JSON | 7,398 с | 6,478–7,810 с |
| Room5 typed history + immutable classification reuse + preservation reuse | 2,998 с | 2,770–3,493 с |
| Room5 новый экран в живом процессе | 111,365 мс | 102,621–125,904 мс |

Компактный JSON уменьшил payload с 106 711 378 до 76 913 211 байт (28%), но дал лишь около 5% cold median: не принят как самостоятельное исправление отзывчивости и не введена production миграция, переписывающая JSON ради compact formatting. Experiment был только в изолированном AVD, значения подтверждены.

Типизированные Session/Segment поля и общая таблица исторических классификаций устранили повторный JSON decode одинаковой selection. Полный immutable/domain graph validation сохранён. Native median field conversion: Session256ms/Segment358ms вместо JSON decode1497ms/1352ms; freeze229ms вместо819ms; validation535ms вместо1518ms. Остальные стадии/полные process-start и graph-ready timestamps находятся в raw JSON; суммы медиан не являются общим median. Разовая миграция 4→5 измерена отдельно:22,027s Activity→action frame.

Preservation — production механизм Android-приложения. До изменения обычный open при любом sidecar создавал UUID directory и полную новую копию. Теперь version migration, damaged header и sidecars сохраняют pre-SQLite boundary; полностью совпавший raw snapshot переиспользуется только после новых source/archive hashes, изменённый snapshot может разделить проверенные immutable archival files. На этой серии первый capture817ms, четыре repeated captures возвращали существующий archive (median713ms), без новых копий DB. Старое1,49s store-open→store-initialized включает другие стадии и не объявлено точной длительностью архива. Старые archives не удалены; оптимизация не является объяснением мусора всего компьютера.

Реальный SAF export106,8MB после process death в DocumentsUI завершился COMPLETE с совпавшим private/provider SHA и неизменёнными table fingerprints. Другие платформенные сценарии продолжаются. Library APK предыдущей поставки пока не заменён этой промежуточной сборкой.
