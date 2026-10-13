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
