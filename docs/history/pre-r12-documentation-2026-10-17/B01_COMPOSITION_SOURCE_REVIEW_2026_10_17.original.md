# B01 — личный source-разбор production composition

Основание: независимый адресный разбор текущего diff после v11, пока B02 находится на согласовании. Проверяемый source HEAD `16ae515a67013df8c95cc45c4a035f490eac674f`, последний product commit `1c97d72f2516e8b9b480c4abd635804db6695668`.

**В этом дополнении нет изменения production-кода, сборки/тестов, emulator startup, signing/install или grants. V11 и её APK остаются неизменными.** Draft patch отдельно помечен как неприменённый и непроверенный. Это уточнение B01 и source-рисков; новые native/OS verdicts не выдаются.

## 1. Исправление классификации B01

Формулировка v11 «нужны approved factory/resource/importance inputs» была слишком широкой: отсутствие нового channel-title resource само по себе не блокирует backend. Минимальную production composition можно сделать из существующих ресурсов и уже используемого стандартного Android Notification.Builder. Это обычная обратимая работа backend в composition root.

Необязательны для минимального backend пути: новая иконка, брендовая композиция, custom RemoteViews, новый Compose-экран, localized catalog title lookup, Start/Snooze buttons и contextual occurrence screen. Они не должны становиться предпосылкой холодной production delivery.

В v11 минимальная composition пока **не реализована**: hook действительно возвращает null. Новый вывод — это runnable-sized backend wiring work, а не неизбежная внешняя блокировка на Opus. Пока существующий кандидат проходит приёмку, ниже предлагается конкретный отдельный patch без подмены его байтов.

## 2. Что уже есть в production

| Назначение | Точный существующий source/resource | Что можно переиспользовать |
|---|---|---|
| Нейтральное имя приложения/канала | `app/src/main/res/values/strings.xml`: `R.string.app_name` = `(not)AI Time Tracker` | Для первого нейтрального канала/заголовка отдельная строка не обязательна; позднее app-owned label можно заменить. Description optional, null допустим. |
| Status icon | `app/src/main/res/drawable/ic_stat_notai.xml` | Настоящая существующая monochrome NotAI mark. Не test icon `android.R.drawable.ic_menu_today`. |
| Цвет | `values/colors.xml` и `values-night/colors.xml`: `R.color.widget_amber` | Уже используется production notification renderer. |
| Системная карточка | `FocusSignals.notificationFactory`, `TimerSurfaces.overlayNotification` | Production precedent обычного Notification.Builder, PRIVATE visibility, direct app-open intent. Их сообщения/CHANNEL/смысл не подходят расписанию. |
| App-open action | `TimerSurfaces.open(context)` | Explicit immutable Activity PendingIntent в MainActivity; нет Start extras, receiver trampoline, новых разрешений или навигационного экрана. |
| Stable schedule authority | `AndroidScheduleDeliveryPlatform.CHANNEL="schedule_reminders"`, NOTICE84, `schedule:<ticket.identity>` | Канал/tag/ticket/notify/cancel принадлежат backend. Нельзя переиспользовать timer_controls/focus_completion/floating_indicator. |
| Lifecycle | `TrackerApplication.scheduleNoticeConfiguration`, `ScheduleNoticeBinding.initialize/refreshChannelMetadata` | Один hook до runtime/receivers, обновление channel text с сохранением фактической пользовательской policy, точный registration guard при post. |
| Данные сообщения | `ScheduleProjection.instant/date/localTime/zone` | Показывать уже resolved planned instant с датой/годом и offset/zone. Не подставлять время доставки или самостоятельно разрешать DST. |
| Renderer текущего таймера | `SurfaceViews.notification`, `notification_timer.xml`, `notification_timer_big.xml` | Готовые layout/assets, но не готовый schedule renderer: им нужен TrackerView, они показывают live timer и timer controls. Подмена на них меняет смысл. |
| Каталогные подписи | `Dataset.label` в `ui/Presentation.kt` | Требуется Dataset. В текущем factory signature его нет; Classification может содержать только type ID. Минимальный notice использует app_name, не делает lookup/Room bootstrap ради подписи и не показывает opaque ID. |

В `strings.xml` сейчас9 строк; schedule/reminder-specific string отсутствует. `Format` — существующие RU helpers; часть day captions относительна и не содержит года. Для потенциально поздней доставки надёжнее стандартное абсолютное date-time представление из уже resolved instant. Одиннадцать catalog locale packs не являются Android UI translations.

## 3. Минимальный рекомендуемый production contract

**Минимум V0:** stateless application-owned `ProductionScheduleNotices`, стандартный системный notification template, icon `ic_stat_notai`, существующий amber, title `app_name`, body = resolved planned date/time с годом и offset, PRIVATE + нейтральный publicVersion, `setAutoCancel(true)`, direct `TimerSurfaces.open(context)`. Никаких domain labels, дополнительных command actions, Activity capture, Runtime/DB lookups, permission prompts или notify внутри factory.

Initial importance предлагаю **LOW2** для консервативного тихого первого подключения. Это обратимый инженерный default, не выдаваемый за утверждённый продуктовый sound design. Backend уже сохраняет пользовательскую policy созданного канала: последующая смена constant на DEFAULT3 не должна рассматриваться как способ повысить существующий пользовательский канал. Реальный канал и поведение проверяются отдельно.

**Альтернатива DEFAULT3** имеет production precedent в Focus и уместна, если напоминание должно быть обычным audible alert. `alertsEnabled=true` доказывает opt-in доставки, но само по себе не задаёт sound/vibration semantics. Если требуется именно audible поведение, это конкретный продуктовый выбор, который стоит подтвердить; он не препятствует подготовке и проверке тихого минимального wiring.

Отдельно приложен `evidence/b01-source-review/minimal-production-composition.DRAFT.patch`. Он:

- добавляет production object с реальными app resources;
- меняет один application hook с null на configuration;
- не использует technical renderer/fixture, не создаёт fake TrackerView;
- сохраняет existing receipt-first platform/coordinator/factory freshness guards;
- **не применён, не собран и не протестирован**; не является частью APK7940ed51, вынесенной на B02.

Для принятия такого изменения нужны адресные проверки новой границы, а не повтор всего прежнего QA ради отчёта: default Application действительно BOUND на каждом старте, real production factory/channel/privacy/open intent, timed projection formatting (fixed zone/DST/late date), date-only не получает midnight, пользовательский blocked channel сохраняется, повторный/старый alarm не создаёт новой попытки. Существующие452 tests в v11 не являются проверкой этого неприменённого patch.

## 4. Точный путь подключения существующего UI

1. В существующем `TrackerApplication.scheduleNoticeConfiguration()` вернуть `ProductionScheduleNotices.configuration()`. Manifest уже указывает на этот Application; нового Activity/subclass/manifest provider не требуется.
2. Application binding создаёт канал и публикует factory до Runtime. `AndroidScheduleDeliveryPlatform` использует эту registration по умолчанию. `TrackerRuntime` уже обновляет ScheduleDelivery на изменениях планов/barrier/settings, foreground/platform events. Отдельный observer, polling loop или scheduler не нужен.
3. По alarm существующий `ScheduleAlarmReceiver` получает сохранённый batch hash на IO; core повторно проверяет occurrence/opt-in/revisions/barrier/time, делает durable RESERVED и вызывает короткий terminal post под существующими gates. Factory рисует только контент; receipt/tag/ticket extras добавляет backend.
4. Нажатие notice идёт через уже существующий `TimerSurfaces.open` прямо в MainActivity. Оно открывает имеющийся app task/default UI и не запускает таймер. Сохранённый tab может быть восстановлен; перехода к конкретному occurrence этот intent не обещает.
5. **Schedule screen сейчас отсутствует.** Source поиск не нашёл schedule UI/route в MainActivity, TrackerApp, SettingsScreen или TechnicalActivity. Backend `ScheduleWorkspace`/`ScheduleViewState` существует, но не является экраном. Для contextual occurrence review нужен настоящий будущий screen и route с повторным разрешением актуального occurrence. Это уже UI работа; generic open-app не надо называть этим review.

Создание channel в новом v11 initialize происходит на startup при nonnull configuration. Старый исторический handoff описывал contextual opt-in creation. Это надо явно согласовать как timing-контракт при подключении: metadata channel creation не выдаёт permission, не включает alerts и не ставит alarm; core всё равно требует explicit eligible reminder. Если нужен строго lazy channel после opt-in, это отдельная backend wiring-поправка, а не задача дизайна/названия.

## 5. Что действительно требует решения UI/product

| Значение/работа | Нужен Opus/Дионис для минимального V0? |
|---|---|
| Новый channel title, icon, description | Нет: app_name/ic_stat_notai/optional null уже есть. Более точные копирайт/локализация позже. |
| Application/receiver/channel lifecycle и immutable factory registration | Нет: backend. В v11 большая часть уже реализована. |
| Минимальный system card и безопасный open-app intent | Новый дизайн не требуется; это ограниченное production wiring. |
| Audibility/initial channel importance | LOW можно выбрать как консервативный временный default. Обязательный audible alert/default vibration — конкретное продуктовое решение, не путать с runtime grant. |
| Показывать private activity name/details | Не требуется для V0. Rich labeling/privacy content нужно определить явно; не угадывать значение по type ID или читать live DB в renderer. |
| Открывать конкретный план, Start/Snooze actions, новый schedule home/widget/quiet-shade layout | Да, отсутствуют соответствующие UI/route/consumer. Backend data/commands уже есть; production actions нельзя заменять fabricated shortcuts. |
| DND automatic-rule configuration | Да: нужен настоящий config Activity и отдельные policy rights. MainActivity не является его заменой; эти grants сейчас запрещены. |

## 6. Независимые незакрытые backend границы и приёмочный diff

### 6.1 Ordinary long-record reminder — отдельный production composition gap

`LongRecordNoticeBinding.factory` в production main sources остаётся null; assignments найдены только в тестах. У `ordinary_timer_checkin` нет production renderer/channel creation wiring. Поэтому успешные core/controller/adapter тесты не закрывают реальную production delivery этого направления. Минимальный notification shell можно переиспользовать концептуально, но канал/NOTICE85/ticket/контроллер остаются отдельными; factory нельзя включением подменять user preference/activation/confirm после restore.

`AndroidLongRecordPlatform.prepare` также не проверяет точную factory identity при terminal post. Source последовательность допускает: prepare под F_old → concurrent replacement на F_new → availability видит nonnull F_new → post старого notice. Это **source-выявленный незащищённый контракт**, аналогичный исправленному Schedule; нового red/green воспроизведения или OS-дефекта здесь не заявляется. Следующий конкретный backend шаг — адресная regression и process-wide binding/final-post guard при включении production composition, без новых UI/grants.

### 6.2 Shared maintenance-gate contention остаётся после v11

V11 корректно ставит **собственную** terminal registration после durable receipt/chooser/writer release. Однако queued worker вызывает `PrivateCopyBackend.catalog`, который держит тот же общий maintenance gate через `PrivateCopyCatalog.registerExport` и hashing/registration. Следующий export callback или иной синхронный maintenance caller способен ждать уже работающий optional worker. Process IO queue также общая с lifetime/release-preview работами.

Это ограничивает вывод о latency: собственный registration больше не исполняется в callback, но полное отсутствие main-thread stall не доказано. Новый source вывод не означает измеренную длительность/ANR. Адресная следующая проверка — blocked worker от operation A и durable result operation B. Безопасное исправление требует отдельной snapshot/hash/revalidation либо durable process-owned handoff конструкции; просто убрать gate или сделать commit→apply нельзя. Data-loss в этом diff source-разбор не обнаружил.

### 6.3 Прочие точные границы

- `ScheduleProjectionBinding.consumers` пуст; cold receiver без real background consumer disarms refresh. Регистрация настоящих application-scoped consumers — wiring backend, их home/widget/quiet-shade rendering отсутствует и требует реального UI. Fake consumer для зелёной availability не подходит.
- `FocusEnvironmentBinding.rule/localFile` отсутствуют без реального DND config Activity/выбранного private local audio source. Здесь внешний выбор и права действительно существенны. Foreground signal уже подключает AttentionRuntime; выдавать новые права или выбирать файл за пользователя нельзя.
- Новый `ScheduleNoticeBinding.initialize` вызывает `TrackerRuntime.refreshExistingSchedule` из Application, даже при unconfigured startup; это не создаёт Runtime/DB (v11 tests), но class-loading/Binder cost нового candidate ещё не измерялся. Channel setup на main startup также входит в будущий bounded cold trace. Старый7-sample timing это изменение не принимал.
- Mandatory durable receipt/reference I/O остаётся синхронным. Упрощать его без сохранённого process-death договора не рекомендую. Других конкретных data-correctness дефектов в проверенном приёмочном diff не установлено.
- B02 остаётся **pending human answer / not attempted** для signing/install/grants. Физический/OEM/power-loss/TalkBack и реальные N16 OS failures не закрываются этой source-работой.

## 7. Сохранность проверяемого кандидата

HEAD16ae515 остаётся clean; candidate unsigned APK7940ed51 и принятый v11 ZIP af97e228 остаются неизменными. Source witnesses/line anchors/SHA и reviewed diff сохранены в `evidence/b01-source-review/`. V11452 tests и native existing-APK receipts не перезапускались и не приписываются draft. Это отдельное локальное дополнение для текущей приёмки; Library v11 не заменялась во время её независимого чтения.
