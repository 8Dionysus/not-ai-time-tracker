# Проверки и пределы результатов

## Текущий Compose-код и поставка (2026-10-13)

Проверенный code commit: `d5ade78d173c091b00a20aba99247e6f671e7d2d`. Текущие указатели: [машинный отчёт](../evidence/compose-current/verification.json) и [матрица действий Android](../evidence/compose-current/android-action-matrix.json). Они включают Compose `TimerScreen`, `TrackerViewModel`, повторный переключатель темы, уникальные ключи одного сегмента в разных днях и ActivityResult экспорт. Исторические результаты ниже относятся к своим прежним состояниям.

Текущие регрессии: **3 Core + 11 Room + 11 UiScreens + 1 UiExportLifecycle = 26**, failures/errors/skips = 0, 23 теста app выполнены в aggregate BUILD SUCCESSFUL in 1m 28s, три Core отдельно повторены с `--rerun-tasks` (32s). Это JVM/Robolectric API35. Lint текущего кода: 0 errors, 56 warnings, 1 hint; APK и Android test APK собраны. SHA-256 каждого из четырёх итоговых raw XML закреплён в машинном отчёте; сами XML сохранены локально и не опубликованы.

APK `not-ai-time-tracker-0.10.0-d5ade78-debug.apk`: **12 461 614 B**, SHA-256 `a9103b9fb725d899bc23c17ee3b715b9fd9c7f39f0014f4ec71b157953c43026`, версия 0.10.0 / code 11, min26 / target35. Сертификат SHA-256 `86e221956fd1ed4dab261a10c1baeac3537816d271601037b309f2ddf186c3cc` совпадает с предыдущими локальными сборками. Три assets совпадают с закреплённым готовым каталогом; тестовые instrumentation fixtures отсутствуют в продукте. Library v5 скачана обратно; её размер и SHA совпали с сохранённым APK. Установка v5 поверх QA-приложения заблокирована нехваткой места: разрешение на перенос проверенных старых QA-архивов ещё не получено. V4 ранее установилась обычным update install без удаления приложения/данных. V5 имеет тот же локальный сертификат; её install/launch и повтор нативной прокрутки пока не подтверждены.

Нативная QA проведена на отдельном AOSP API35 x86_64 AVD с синтетической историей более 73 000 записей. Реальный DocumentsUI/SAF, CalendarProvider denial/grant/recurring/all-day, widget и launcher shortcuts, разрешения уведомления/overlay, Theme/zone и Focus проверены отдельно от Robolectric. Inexact Focus alarm создала новый процесс после подтверждённого завершения старого и доставила notification 43/ключ текущей фазы без открытия приложения; это один запуск AOSP, без проверки OEM idle или выключения питания. Обычный Compose restore проверен через SAF preview/cancel/Replace на свежей копии 106 822 991 B: 73 016 записей и 73 022 сегмента, семь таблиц совпали по canonical hash, цели/настройки сохранены. Изменились ревизия, controlBarrier и store generation; отдельно прочитанный rollback совпал с исходной копией по данным. Подробности, даты и ограничения отдельных действий — в матрице.

Нативная прокрутка при крупном шрифте выявила duplicate-key crash одного сегмента в двух днях. Он воспроизведён на JVM; ключ day+segment исправлен и 26 регрессий прошли. Нативный повтор текущего APK ждёт решения storage blocker. Крупный шрифт 150% также выявил обрезанную двухстрочную подсказку поиска; три системные кнопки навигации обрезают нижние подписи/иконки. Визуальные исправления ждут разрешения. TalkBack отсутствует в AOSP image. Полная приёмка UI и физического телефона остаётся открытой. GitHub checks проверяются отдельно; отсутствие checks не является CI pass.

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

Большой restore использует 10 000 records с длинными заметками и фактический файл больше 10 MiB: export→preview/cancel→replace→verified rollback→повтор→reopen→точный report на Memory и Room. Полный graph остаётся в памяти; дополнительный encoded файл не удерживается. Максимум одного JSON record —16 MiB characters, nesting128. Импорт CSV отдельно ограничен 10 MiB /50 000 строк /128 колонок; текущий экспорт CSV выполняется потоково, нативно проверен файл 37 880 167 B.

## Исторический aggregate до Compose

Исторический aggregate от 2026-10-13 08:57 UTC: BUILD SUCCESSFUL in 53s; 3 Core + 3 Room, failures/errors/skips=0; lint 0 errors, 34 warnings, 1 hint. APK 3 285 313 байт, SHA-256 `cc68a1b358584c49bebad5114519c60c70a4226d9c870d8465533e93c091903b`, v2 signature verified. Три assets byte-identical подготовленному каталогу; source readers отсутствуют в DEX.

[Исторический машинный отчёт](../evidence/audit-updates/verification.json) включает case names, implementation fingerprint и SHA-256 исходных XML/lint/build reports. [Замеры](../evidence/audit-updates/scale-comparison.json) содержат полные before/after samples. Сырые отчёты этого исторического прогона сохранены отдельно без публикации локальных путей. Повтор aggregate после code commit завершился BUILD SUCCESSFUL in 8s (1 executed, 61 up-to-date), исходное состояние не менялось.

[Публикация](../evidence/audit-updates/publication.json): code commit проверен анонимным клонированием, 1132 публичных объектов без исходного приватного email, оригинальные 23 commit trees/messages/topology сохранены. GitHub statuses/check-runs/Actions для него отсутствуют; это не CI pass. Старые evidence относятся к указанным в них состояниям; их нельзя выдавать за проверки текущего кода. Исследовательские материалы вынесены из текущего дерева после проверки внешних архивов; прошлые commits сохранены.

## Открытый остаток

Текущий AVD подключён и проверен, как указано выше. Физический телефон, OEM idle delivery, потеря питания, память и задержки реального телефона не проверены. TalkBack недоступен; два обнаруженных визуальных дефекта остаются открытыми. Нативные сценарии не заменяют приёмку на целевом телефоне.

Новый локальный debug APK подписан другим сертификатом, чем APK исходной поставки. Установка поверх того APK требует исходного signing key. Сертификат исходного APK: `c4335948a9dbedb8bc1b557d23d0ea37013fb53c79a8ce57f19a7f24fb7f20b7`; локальные debug-сборки используют `86e221956fd1ed4dab261a10c1baeac3537816d271601037b309f2ddf186c3cc`. Совместимость update относится к прежней локальной QA-сборке, а не к исходному пользовательскому APK. Ключ не восстановлен из сертификата и не включён в Git; удаление приложения/данных не выполнялось. Исходный APK сохранён. Это ограничение установки бинарной сборки, отдельное от проверяемой совместимости Room/JSON и словаря.

## Историческая дополнительная приёмка: orphan sidecars и выбор карточки

После `dfd627a` исправлены только два найденных пути и добавлены связанные регрессии. Полный aggregate: **BUILD SUCCESSFUL in 46s**; 3 Core + 5 Room, failures/errors/skips=0; lint 0 errors, 34 warnings, 1 hint. Core и каталог не менялись; соответствующий Core run повторно использован Gradle по неизменным входам.

[Отчёт follow-up](../evidence/acceptance-followup/verification.json) содержит точный implementation fingerprint и hashes отчётов. `orphanSidecarsNeverInitialize` проверяет каждый sidecar и все семь непустых комбинаций, сохранение исходных/архивных bytes и SHA-256, отсутствие созданного DB, отказ runtime и работающий действительно новый store. `sessionCommandsIgnoreStaleSelectedCard` нажимает реальные кнопки Activity с карточкой, исчезнувшей после полного restore, и с архивированной выбранной карточкой: Undo, Repeat, resolveTail, Focus transitions, Pause/Resume/Stop сохраняют форму. Start/Start Focus требуют действительного выбора; положительный Start Focus сохраняет выбранный ID и details.

Подпись вновь собранного APK совпадает с предыдущей локальной debug-сборкой; ключи не менялись. Старый APK в Library относится к предыдущему implementation commit, не к этому follow-up.

На момент этого исторического follow-up проверка emulator была проведена отдельно от adb: executable не найден в PATH, рабочем SDK, стандартных SDK-путях и machine runtimes; system-images в рабочем SDK отсутствуют; штатные AVD-каталоги и конфигурации Android Studio отсутствуют. Тогда готового пути для emulator QA не было, эмулятор не запускался. Позднее создан отдельный QA AVD; актуальные результаты приведены в начале этого документа. Пользовательский телефон и его данные не затрагивались.
