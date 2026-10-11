# Фактическая проверка · 11 октября 2026

Рабочий каталог: `/workspace/not-ai-time-tracker`, отдельный Git-репозиторий; соседние проекты не изменялись. Доступные `.agents`/локальные skills проверены: дополнительных инструкций для нового каталога не было. Создан собственный AGENTS.md с границей ядра и ограничениями объёма.

## Среда и воспроизведение

- OpenJDK `21.0.12.1` (Debian); байткод проекта нацелен на Java 17. В системном runtime отсутствовал launcher `javac`, но модули `jdk.compiler`, `jdk.jartool`, `jdk.jlink` уже установлены. Создана отдельная копия `/workspace/toolchains/jdk21` с launcher-скриптами вызова этих существующих модулей; `javac -version` и `jlink --version` успешны. Системная Java не менялась. Попытка получить новый JDK через Adoptium API вернула proxy CONNECT 403; ограничения не обходились, использована независимая локальная возможность.
- Gradle `8.13`, Kotlin `2.1.20`, AGP `8.11.1`, Room `2.7.2`.
- Gradle и Android command-line tools получены с официальных источников. SHA-256 Gradle проверен: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
- Начальная ошибка Java-загрузок устранена обычным использованием уже настроенного прокси и переносом writable Android cache из `/home/agent/.android` в `/workspace/toolchains/android-user`. TLS-проверки и системные trust stores не менялись.
- Кэши и инструменты лежат вне репозитория: `/workspace/toolchains`. Настройки прокси не включены в Git.

Фактически исполненная команда:

```sh
cd /workspace/not-ai-time-tracker
ANDROID_USER_HOME=/workspace/toolchains/android-user \
GRADLE_USER_HOME=/workspace/toolchains/gradle-home \
./gradlew :core:test
```

Результат: `BUILD SUCCESSFUL`, три теста, 0 failures / 0 errors / 0 skipped. [Лог](../evidence/core-test.log), [JUnit XML](../evidence/core-tests.xml).

## Что проверено тремя сценариями

1. **timerPath**: старт без карточки; поздняя классификация без сброса времени; pause/resume; сериализация состояния и создание нового Tracker/хранилища в памяти; устаревший Stop; switch и забытое переключение; защита Undo; repeat/favorite; изменение деталей; два конкурирующих переключения (одно принято, другое STALE с актуальным состоянием); скачок часов; другая загрузка; явное исключение/исправление хвоста.
2. **smallHistoryAndExport**: пауза; пересечение полуночи; два разных сегмента равной длительности; пересекающиеся tags; дневные итоги и общий итог; CSV из тех же строк; одна запись session quantity на два сегмента; правка и Undo; архив/переименование без изменения прошлого; копия карточки без истории/количеств; запрет пересечения; 23-часовой день Europe/Berlin при DST.
3. **backupReplacementAndFailure**: разбор обоих поставляемых JSON-примеров; полная замена чистого состояния; сохранение настроек, карточек, избранного, измерений и истории; незавершённый импортированный хвост в RECOVERY без end; отказ повреждённого файла, неизвестной версии, отсутствующего dataset ID, дубликата ID; stale preview; повреждённая откатная копия; моделируемая ошибка транзакции без изменения старых данных; явное подтверждение границы.

Это доменные JVM-проверки; сериализация/новый объект не выдаются за kill/restart Android-процесса.

## Незавершённая Android-проверка

На текущем шаге установка SDK остановлена до принятия Android SDK License. Команда sdkmanager с закрытым stdin вернула:

```
Accept? (y/N): Skipping following packages as the license is not accepted:
Android SDK Build-Tools 35
Android SDK Platform-Tools
Android SDK Platform 35
```

Пакеты: `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`. Разрешение на принятие лицензии запрошено отдельно по уточнению родительского задания. Пока оно ожидается, APK/Room/lint не заявлены успешными. Подготовлен один повторно используемый набор из трёх сценариев для Room/Robolectric; он должен проверить дисковый SQLite и close/reopen базы.

Android-команда фактически выполнена с `JAVA_HOME=/workspace/toolchains/jdk21`; она дошла до проверки лицензии и завершилась `BUILD FAILED`: `Failed to install ... as some licences have not been accepted`. [Точный журнал](../evidence/android-build-blocked.log).

Команда повторной проверки после разрешённой установки SDK:

```sh
JAVA_HOME=/workspace/toolchains/jdk21 \
ANDROID_HOME=/workspace/toolchains/android-sdk \
ANDROID_USER_HOME=/workspace/toolchains/android-user \
GRADLE_USER_HOME=/workspace/toolchains/gradle-home \
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Исходный manifest статически разобран: allowBackup=false, fullBackupContent=false, нет uses-permission. [Свидетельство](../evidence/source-manifest-check.txt). Проверка merged manifest и APK ещё не выполнена. Политика системного backup задана, но её работа на телефоне не проверена.

`adb`/эмулятор изначально отсутствовали. На этапе первой сборки навык android-emulator-qa по переданному package дал `skill package is not available`; тогда его не было в cloud/executor-каталоге. При последующей приёмке навык появился в обновлённом каталоге; это не установило SDK или adb-target. Подключение плагина в родительском чате не доказало наличие Android-target в этой среде. Никаких замеров физического телефона, батареи или performance не проводилось.

## GitHub

В доступном GitHub-коннекторе нет операции создания репозитория. Уже настроенный CLI `gh auth status` сообщает недействительный GH_TOKEN; значение токена не читалось/не выводилось, credentials не менялись. Новый удалённый репозиторий не создан; публичная публикация не выполнялась. Для завершения удалённого шага нужен отдельно авторизованный способ создать именно **private** `not-ai-time-tracker` и отправить локальный commit.

Модель/effort runtime не раскрыты; исполнитель их не менял. Дополнительные агенты не запускались.

## Технические источники версий

- [Совместимость AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes): Gradle 8.13, JDK >=17, build tools 35.
- [Room release notes](https://developer.android.com/jetpack/androidx/releases/room): Kotlin 2.x/KSP и закреплённая ветка Room 2.7.
- [Android SDK License](https://developer.android.com/studio/terms): внешнее условие установки SDK.
