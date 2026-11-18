# PocketInput

Android-телефон как Bluetooth HID-клавиатура/мышь/указка для Windows-ПК:
телефон регистрируется как HID-композит, двигает курсор и передаёт клавиши
через системный Bluetooth Windows. ПК-агент запланирован как запасной транспорт.

Актуальная сборка — **0.3.4-hold-drag** в `dist/`. Порядок установки и сценарии
проверки — в [TESTING.md](TESTING.md), ожидаемое поведение — в
[спецификации устойчивости](docs/phase1-reliability.md).
Состояние проверок на оборудовании фиксируется в `docs/measurement/`.

## Структура репозитория (спека, раздел 5.1)

```text
pocket-input/
├── android/          # Android-приложение, Kotlin + Compose (модуль :app)
├── agent/            # ПК-агент, Python 3.11+ (транспорт, инъекция ввода)
├── proto/            # pocketinput_v1.proto + generated/ (не править руками)
├── spec/             # спецификация: бриф, ADR, дорожная карта, QA-план
├── docs/             # документация
│   └── measurement/  # протоколы замеров и спайков (фаза 0 — здесь)
└── tools/            # утилиты проверки на ПК
    └── hid_probe.py  # перечисление HID-устройств Windows (задача 5.3.7)
```

## Быстрый старт

### Сборка Android-модуля

Из корня `android/` (Gradle-версия зафиксирована wrapper'ом):

```bash
cd android
./gradlew :app:assembleDebug       # debug-APK
./gradlew :app:testDebugUnitTest   # JVM-юнит-тесты
```

### Проверка HID-стека на ПК (фаза 0)

Нужен Python 3.11+ и пакет `hidapi`:

```bash
python -m pip install hidapi          # модуль импортируется как `import hid`
python tools/hid_probe.py             # таблица в stdout
python tools/hid_probe.py --out docs/measurement/hid-probe-$(date +%F).md
```

Скрипт печатает markdown-таблицу `device / vid:pid / usage_page / boot /
collections / verdict`; `boot` = usage_page 0x01 и наличие Report ID в
дескрипторе. Артефакт прогона на этой машине (2026-11-17, 12 HID-интерфейсов):
`docs/measurement/hid-probe-2026-11-17.md`.

### Где что читать

| Вопрос | Файл |
|---|---|
| Что строим, скоуп, этапы | `spec/` |
| Дорожная карта, задачи фаз 0–2, матрица устройств, QA | `spec/05-roadmap-qa.md` (5.1 структура, 5.3 задачи фазы 0, 5.5 матрица) |
| Протокол спайка фазы 0 | `docs/measurement/phase0-report.md` |
