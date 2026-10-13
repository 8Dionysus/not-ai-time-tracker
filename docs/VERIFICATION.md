# Проверки и пределы результатов

## Воспроизводимая команда

JDK 17/21, Android SDK Platform 35 / Build Tools 35.0.0, Gradle Wrapper 8.13 с закреплённым distribution SHA-256. SDK/cache задаются локально и не включаются в Git.

```sh
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew -PtrackerScale=true :app:testDebugUnitTest --tests '*RoomScenariosTest.timerPath'
```

Один контракт из трёх интеграционных сценариев выполняется на Memory и дисковом Room/Robolectric API35. Проверки включают timer domain freshness, старые JSON 1–7, large streaming restore/rollback, SQL failure, неподвижный correction/CSV preview, полный старый→новый каталог и личные изменения, same-boot Focus, Calendar paging/recurring/all-day/denial, SAF failure/cancel/retry, все поля Activity recreation, нечитаемый DB/WAL/SHM и прерванный recovery. При потере metadata/vocabulary/generation число и хеш содержимого таблиц не меняются; уже загруженный кэш не скрывает повреждение.

Тесты Android permissions и provider используют управляемые входы Robolectric. Они не обращаются к личному календарю и не выдают приложению реальные разрешения. Платформенные directory fsync/rename выполняются настоящей JVM filesystem через test seam: стандартные Robolectric shadows не реализуют эти операции. Наличие вызовов Android fsync не является проверкой отключения питания телефона.

## Измерение большой истории

73 000 sessions/segments: десять лет ×20 записей в день, 30 минут каждая, 128 символов заметки, три классификации. Полный готовый каталог; JVM heap 1 GiB. Одно cold measurement и семь timer/day samples; этот разовый замер не является статистической или device-гарантией.

| Операция | До | После |
|---|---:|---:|
| Холодное RoomStore open/read | 11,814 с | 4,933 с |
| Таймерный Start/Pause/Resume/Stop, медиана | 56,449 мс | 20,873 мс |
| Дневной отчёт, медиана | 72,630 мс | 0,044 мс |
| Первый дневной запрос | 173,502 мс | 47,953 мс |
| Календарь 30 дней | 399,458 мс | 2,831 мс |

После измерения изменены cursor loading (без полного списка JSON DTO), переиспользование пустых immutable collections и одинаковых frozen classifications, индекс выбранной неизменяемой ревизии для Reports/Calendar. Индекс строится лениво при первом запросе. Его результаты сравниваются с прежним полным oracle для фильтров, границ, зон и unresolved данных. Замораживание публичных report rows и предварительное сохранение sidecars добавлены после этого performance run; окончательный aggregate проверяет итоговый код. Замер закрывает RoomStore, не всю инициализацию Activity/runtime. Crash-open с WAL/SHM дополнительно копирует оригиналы до SQLite и требует I/O/места; это не включено в холодный чистый RoomStore sample.

Большой restore использует 10 000 records с длинными заметками и фактический файл больше 10 MiB: export→preview/cancel→replace→verified rollback→повтор→reopen→точный report на Memory и Room. Полный graph остаётся в памяти; дополнительный encoded файл не удерживается. Максимум одного JSON record —16 MiB characters, nesting128. CSV отдельно ограничен 10 MiB /50 000 строк /128 колонок.

## Сохранённые доказательства

Финальный aggregate: BUILD SUCCESSFUL in 53s; 3 Core + 3 Room, failures/errors/skips=0; lint 0 errors, 34 warnings, 1 hint. APK 3 285 313 байт, SHA-256 `cc68a1b358584c49bebad5114519c60c70a4226d9c870d8465533e93c091903b`, v2 signature verified. Три assets byte-identical подготовленному каталогу; source readers отсутствуют в DEX.

[Машинный отчёт](../evidence/audit-updates/verification.json) включает case names, implementation fingerprint и SHA-256 исходных XML/lint/build reports. [Замеры](../evidence/audit-updates/scale-comparison.json) содержат полные before/after samples. Сырые текущие отчёты сохранены отдельно без публикации локальных путей. Повтор aggregate после code commit завершился BUILD SUCCESSFUL in 8s (1 executed, 61 up-to-date), исходное состояние не менялось.

[Публикация](../evidence/audit-updates/publication.json): code commit проверен анонимным клонированием, 1132 публичных объектов без исходного приватного email, оригинальные 23 commit trees/messages/topology сохранены. GitHub statuses/check-runs/Actions для него отсутствуют; это не CI pass. Старые evidence относятся к указанным в них состояниям; их нельзя выдавать за проверки текущего кода. Исследовательские материалы вынесены из текущего дерева после проверки внешних архивов; прошлые commits сохранены.

## Открытый остаток

Физический Android/emulator не подключён (`adb devices -l`: пусто). Не подтверждены устройство, OEM idle delivery, реальный OS chooser/CalendarProvider, убийство процесса/потеря питания, память и задержки телефона. Полный визуальный UI остаётся отдельной работой. GitHub checks проверяются отдельно для публикуемого commit; отсутствие checks не означает CI pass.

Новый локальный debug APK подписан другим сертификатом, чем APK исходной поставки. Установка поверх того APK требует исходного signing key. Ключ не восстановлен из сертификата и не включён в Git; удаление приложения/данных не выполнялось. Исходный APK сохранён. Это ограничение установки бинарной сборки, отдельное от проверяемой совместимости Room/JSON и словаря.
