# Фаза 1 PocketInput — общий контракт роя (читать целиком)

Источник требований: `spec/PocketInput-SPEC.md`, разделы **4.5–4.8** (экраны, жесты, клавиатура,
настройки) и **5.2** (фаза 1: трекпад с жестами + клавиатура с модификаторами, всё через HID).
Правило спеки: решения не обсуждаются, реализуется ровно записанное здесь.

**Что уже есть и не переписывается:** `hid/HidTransport.kt`, `hid/Descriptors.kt`,
`ui/connect/ConnectScreen.kt`, `service/PocketInputService.kt`, `res/values*/strings.xml`
(все нужные строки уже добавлены координатором).

## Контракты (менять нельзя — на них завязаны три параллельных исполнителя)

### `dev.pocketinput.data` (пишет исполнитель SHELL)

```kotlin
data class SettingsState(
    val sensitivity: Float = 1.0f,   // 0.2..4.0, множитель дельты трекпада
    val scrollGain: Float = 1.0f,    // 0.2..3.0, множитель скролла
    val vibration: Boolean = true,   // тактильный отклик на клик/захват
    val theme: String = "system",    // "system" | "dark" | "light"
    val layout: String = "en",       // "en" | "ru"
)

class SettingsStore(context: Context) {   // SharedPreferences, БЕЗ новых зависимостей
    fun load(): SettingsState
    fun save(s: SettingsState)
}
```

### Сигнатуры экранов (обязательны дословно)

```kotlin
// dev.pocketinput.ui.trackpad
@Composable fun TrackpadScreen(
    transport: HidTransport,
    settings: SettingsState,
    onOpenKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
)

// dev.pocketinput.ui.keyboard
@Composable fun KeyboardScreen(
    transport: HidTransport,
    settings: SettingsState,
    onLayoutChange: (String) -> Unit,   // "en" | "ru" — запоминается per-устройство
    onBack: () -> Unit,
)

// dev.pocketinput.ui.settings
@Composable fun SettingsScreen(
    state: SettingsState,
    onChange: (SettingsState) -> Unit,
    onBack: () -> Unit,
)

// dev.pocketinput.ui.theme
@Composable fun PocketInputTheme(theme: String = "system", content: @Composable () -> Unit)
```

### API транспорта, доступное экранам (уже реализовано)

```kotlin
fun tap(button: Int = 1, holdMs: Long = 40)      // клик: down → пауза → up
fun sendMotion(dx: Int, dy: Int)                 // движение с ТЕКУЩЕЙ маской кнопок
fun setButtons(mask: Int, wheel: Int = 0)        // новое состояние маски кнопок
fun pressButton(bit: Int)                        // bit: 1 = ЛКМ, 2 = ПКМ
fun releaseButton(bit: Int)
fun releaseAllButtons()
fun wheel(units: Int)                            // 1 единица = 1/120 щелчка, знак = знак
fun keyTap(usage: Int, mods: Int = 0, holdMs: Long = 20)
fun keyDown(usage: Int, mods: Int = 0)           // автоповтор делает ОС ПК
fun keyUp(mods: Int = 0)
```

## Нормативные числа фазы 1

| Величина | Значение | Где живёт |
|---|---|---|
| Порог тапа | сдвиг < 8 dp | `TrackpadMath.TAP_THRESHOLD_DP` |
| Порог drag | ≥ 8 dp | там же |
| Двойной тап | второе касание в пределах **350 мс**, оба касания без сдвига > 8 dp | `TrackpadMath.DOUBLE_TAP_MS` |
| Долгое нажатие | **500 мс** без сдвига > 8 dp → правая кнопка; левый клик при отпускании НЕ отправлять | `TrackpadMath.LONG_PRESS_MS` |
| Скролл двумя пальцами | **6 единиц колеса на 1 dp** движения; дробный остаток копится; отправка не чаще 60 Гц; за отчёт ±127 | `TrackpadMath.SCROLL_UNITS_PER_DP` |
| Три пальца | сдвиг > **60 dp**: вверх → `LGUI + 0x07` (Win+D); вправо → `LALT + 0x2B` (Alt+Tab); влево → `LALT\|LSHIFT + 0x2B` | `TrackpadMath.THREE_FINGER_DP` |
| Частота мышиных отчётов | не чаще 120 Гц (8 333 333 нс) | `TrackpadMath.MIN_REPORT_INTERVAL_NS` |
| Дельта за отчёт | не больше 127 px по каждой оси, остаток досылается | `TrackpadMath.splitDeltas` |
| Чувствительность | `dx_px = dx_dp · densityDpi / 160 · settings.sensitivity` | `TrackpadMath.pxPerDp` |
| Скролл-гейн | итог `units = dp · SCROLL_UNITS_PER_DP · settings.scrollGain` | `TrackpadMath` |
| Автоповтор | на HID-пути НЕ реализуется: `keyDown` держит клавишу, повторяет ОС ПК | — |

## Usage-коды HID (Usage Page 0x07) — обязательная таблица

| Клавиши | Коды |
|---|---|
| a…z | `0x04`…`0x1D` |
| 1…9, 0 | `0x1E`…`0x26`, `0x27` |
| Enter, Esc, Backspace, Tab, Space | `0x28`, `0x29`, `0x2A`, `0x2B`, `0x2C` |
| `- = [ ] \ ; ' \` , . /` | `0x2D 0x2E 0x2F 0x30 0x31 0x33 0x34 0x35 0x36 0x37 0x38` |
| Delete, Home, End | `0x4C`, `0x4A`, `0x4D` |
| Стрелки: вправо, влево, вниз, вверх | `0x4F`, `0x50`, `0x51`, `0x52` |

Маски модификаторов: **LCTRL 0x01, LSHIFT 0x02, LALT 0x04, LGUI 0x08, RCTRL 0x10, RSHIFT 0x20,
RALT 0x40, RGUI 0x80**.

Символы с Shift (`! @ # $ % ^ & * ( ) _ + { } : " < > ?`) отправляются тем же usage-кодом
базовой клавиши с `LSHIFT` в маске. Пример: `!` = usage `0x1E` (клавиша «1») + модификатор `0x02`.

## Жесты трекпада (ровно этот набор)

| Жест | Действие |
|---|---|
| движение одним пальцем | курсор: `transport.sendMotion(dx, dy)` |
| тап < 8 dp, < 350 мс | левый клик: `transport.tap(1)` |
| два тапа подряд в пределах 350 мс | двойной клик: `tap(1)` дважды (второй с задержкой 40 мс) |
| удержание ≥ 500 мс без сдвига | правая кнопка: `tap(2)` |
| два пальца вверх/вниз | скролл: накопление → `transport.wheel(units)` |
| три пальца вверх | `keyTap(0x07, LGUI)` |
| три пальца вправо / влево | `keyTap(0x2B, LALT)` / `keyTap(0x2B, LALT or LSHIFT)` |
| кнопка «ЛКМ» на панели | latch: первое нажатие `pressButton(1)`, второе `releaseButton(1)`; состояние показывается текстом `btn_lmb_latched` |
| кнопка «ПКМ» на панели | `tap(2)` |
| уход экрана в фон | `releaseAllButtons()` |

Панель трекпада: две кнопки («ЛКМ», «ПКМ») и две кнопки навигации («Клавиатура», «Настройки»).
Подсказка — строка `trackpad_hint_full`.

## Клавиатура (ровно этот набор)

- Собственная Compose-клавиатура, **не системная IME**.
- Слои: `EN` (QWERTY), `RU` (ЙЦУКЕН), `SYM` (цифры и пунктуация), `SYM2` (второй символьный).
- Переключение слоя — клавишей на самой клавиатуре; выбранный `layout` приходит из
  `settings.layout` и сообщается наружу через `onLayoutChange`.
- **RU-слой отправляет те же usage-коды, что и EN** (HID передаёт позиции клавиш, а не буквы),
  поэтому при активном RU-слое показывается постоянная подсказка `keyboard_cyrillic_hint`.
- Обязательные клавиши в любом слое: Backspace `0x2A`, Enter `0x28`, Space `0x2C`, Tab `0x2B`,
  Esc `0x29`, стрелки `0x4F/0x50/0x51/0x52`, Del `0x4C`.
- Модификаторы Ctrl / Shift / Alt / Win — sticky: одиночное нажатие «взводит» модификатор на одну
  следующую клавишу, второе нажатие по тому же модификатору «залипает» до повторного нажатия.
  Взведённый/залипший модификатор визуально выделен, залипший сопровождается текстом
  `keyboard_sticky_hint`.
- Нажатие клавиши = `keyTap(usage, mods)`; удержание Backspace — `keyDown(0x2A)` и `keyUp()` на отпускании.
- Клавиатура должна помещаться на экран телефона в портретной ориентации без прокрутки.

## Владение файлами (нарушать нельзя)

| Исполнитель | Пишет только эти файлы |
|---|---|
| TRACKPAD | `android/app/src/main/kotlin/dev/pocketinput/ui/trackpad/TrackpadScreen.kt`, `TrackpadMath.kt`, `android/app/src/test/kotlin/dev/pocketinput/ui/trackpad/TrackpadMathTest.kt` |
| KEYBOARD | `android/app/src/main/kotlin/dev/pocketinput/ui/keyboard/KeyboardScreen.kt`, `KeyMap.kt`, `android/app/src/test/kotlin/dev/pocketinput/ui/keyboard/KeyMapTest.kt` |
| SHELL | `android/app/src/main/kotlin/dev/pocketinput/MainActivity.kt`, `ui/theme/Theme.kt`, `ui/settings/SettingsScreen.kt`, `data/SettingsStore.kt` |

Запрещено: менять `build.gradle.kts`, `AndroidManifest.xml`, `res/values*/strings.xml`,
`hid/*`, `ui/connect/*`, `service/*`, спеке и чужим файлам из таблицы.

## Правила кода

1. Kotlin + Compose Material 3, без новых зависимостей (нет навигации compose, нет ViewModel —
   состояние в `remember`/`rememberSaveable`).
2. Все размеры в `dp`, работа с пикселями только через `TrackpadMath`.
3. Чистая логика (пороги, накопление скролла, раскладки, sticky-модификаторы) — в объектах без
   Android-зависимостей, чтобы её покрывали JVM-тесты.
4. Никаких `Thread.sleep` в UI-потоке; задержки — через `coroutineScope`/`delay` внутри
   `pointerInput` или на потоке отправки транспорта.
5. Строки — из `R.string.*`; если нужной строки нет, используется литерал с комментарием
   `// TODO(i18n)` (добавлять строки в ресурсы нельзя — файл чужой).
6. Всё видимое пользователю — по-русски через существующие ресурсы (в `values-ru` есть перевод).

## Проверка

- `gradle assembleDebug` может падать из-за незавершённых файлов соседей — смотри только ошибки,
  указывающие на ТВОИ файлы; чужие ошибки игнорируй и не «исправляй» чужие файлы.
- Свои JVM-тесты: `gradle testDebugUnitTest --tests "dev.pocketinput.*ТвойПакет*"`.
- В отчёте на доске: список файлов, число строк, что покрыто тестами, что осталось незакрытым.