#!/usr/bin/env python3
"""Замер прихода HID-событий мыши/клавиатуры на Windows (RAWINPUT).

Зачем: у Bluetooth-HID нет подтверждений доставки, поэтому «задержка» видна только
со стороны ПК — по моментам прихода отчётов. Утилита ставит RAWINPUT-приёмник,
пишет время каждого события и считает:

  * интервалы между отчётами (медиана / p95 / максимум) — фактическая частота
    отчётов, когда телефон шлёт движение;
  * число «провалов» > 50 мс — это пробуждение из sniff-режима и забитый канал;
  * отдельно самый большой провал — «первое движение после простоя».

Часы: GetMessageTime() (мс от старта Windows, тот же счётчик у всех сообщений),
поэтому интервалы внутри запуска корректны. Склейка с логом телефона по
абсолютному времени требует общей базы — для этого есть --csv и колонка t_ms.

Примеры:
    python tools/hid_latency.py --seconds 20
    python tools/hid_latency.py --seconds 60 --csv latency.csv
    python tools/hid_latency.py --seconds 15 --only "VID_0000&PID_0000"

Вывод: таблица по каждому устройству + предупреждения по нормативам:
    медиана интервала ≤ 12 мс, p95 ≤ 35 мс, провал после простоя ≤ 60 мс.
"""

from __future__ import annotations

import argparse
import ctypes
import ctypes.wintypes as wintypes
import statistics
import sys
import time

WM_INPUT = 0x00FF
RID_INPUT = 0x10000003
RIDI_DEVICENAME = 0x20000007
RIM_TYPEMOUSE = 0
RIM_TYPEKEYBOARD = 1
RIDEV_INPUTSINK = 0x00000100

# Нормативы: что считаем «хорошо» для Bluetooth-мыши.
TARGET_MEDIAN_MS = 12.0
TARGET_P95_MS = 35.0
TARGET_WAKE_MS = 60.0
STALL_MS = 50
PAUSE_MS = 1000  # интервал больше этого — остановка руки, а не столл канала


class RAWINPUTDEVICE(ctypes.Structure):
    _fields_ = [
        ("usUsagePage", wintypes.USHORT),
        ("usUsage", wintypes.USHORT),
        ("dwFlags", wintypes.DWORD),
        ("hwndTarget", wintypes.HWND),
    ]


class RAWINPUTHEADER(ctypes.Structure):
    _fields_ = [
        ("dwType", wintypes.DWORD),
        ("dwSize", wintypes.DWORD),
        ("hDevice", wintypes.HANDLE),
        ("wParam", wintypes.WPARAM),
    ]


class RAWMOUSE_BUTTONS(ctypes.Structure):
    # USHORT usButtonFlags; USHORT usButtonData — читаем как одно поле
    _fields_ = [("usButtonFlags", wintypes.USHORT), ("usButtonData", wintypes.USHORT)]


class RAWMOUSE_BUTTONS_UNION(ctypes.Union):
    _fields_ = [("buttons", RAWMOUSE_BUTTONS), ("ulButtons", wintypes.ULONG)]


class RAWMOUSE(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [
        ("usFlags", wintypes.USHORT),
        ("u", RAWMOUSE_BUTTONS_UNION),
        ("ulRawButtons", wintypes.ULONG),
        ("lLastX", wintypes.LONG),
        ("lLastY", wintypes.LONG),
        ("ulExtraInformation", wintypes.ULONG),
    ]


class KEYBOARD_BODY(ctypes.Structure):
    _fields_ = [
        ("MakeCode", wintypes.USHORT),
        ("Flags", wintypes.USHORT),
        ("Reserved", wintypes.USHORT),
        ("VKey", wintypes.USHORT),
        ("Message", wintypes.UINT),
        ("ExtraInformation", wintypes.ULONG),
    ]


class RAWINPUT_BODY(ctypes.Union):
    _fields_ = [("mouse", RAWMOUSE), ("keyboard", KEYBOARD_BODY)]


class RAWINPUT(ctypes.Structure):
    _fields_ = [("header", RAWINPUTHEADER), ("body", RAWINPUT_BODY)]


class WNDCLASSW(ctypes.Structure):
    _fields_ = [
        ("style", wintypes.UINT),
        ("lpfnWndProc", ctypes.c_void_p),
        ("cbClsExtra", ctypes.c_int),
        ("cbWndExtra", ctypes.c_int),
        ("hInstance", wintypes.HINSTANCE),
        ("hIcon", wintypes.HICON),
        ("hCursor", wintypes.HANDLE),
        ("hbrBackground", wintypes.HBRUSH),
        ("lpszMenuName", wintypes.LPCWSTR),
        ("lpszClassName", wintypes.LPCWSTR),
    ]


class MSG(ctypes.Structure):
    _fields_ = [
        ("hwnd", wintypes.HWND),
        ("message", wintypes.UINT),
        ("wParam", wintypes.WPARAM),
        ("lParam", wintypes.LPARAM),
        ("time", wintypes.DWORD),
        ("pt", wintypes.POINT),
    ]


user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

# Прототипы обязательны: без них ctypes ждёт int и падает на 64-битных
# LPARAM/HANDLE («OverflowError: int too long to convert»), а окно молча не работает.
user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.DefWindowProcW.restype = ctypes.c_ssize_t
user32.GetRawInputData.argtypes = [
    wintypes.HANDLE, wintypes.UINT, ctypes.c_void_p,
    ctypes.POINTER(wintypes.UINT), wintypes.UINT,
]
user32.GetRawInputData.restype = wintypes.UINT
user32.GetRawInputDeviceInfoW.argtypes = [
    wintypes.HANDLE, wintypes.UINT, ctypes.c_void_p, ctypes.POINTER(wintypes.UINT),
]
user32.GetRawInputDeviceInfoW.restype = wintypes.UINT
user32.RegisterRawInputDevices.argtypes = [
    ctypes.POINTER(RAWINPUTDEVICE), wintypes.UINT, wintypes.UINT,
]
user32.RegisterRawInputDevices.restype = wintypes.BOOL
user32.RegisterClassW.argtypes = [ctypes.POINTER(WNDCLASSW)]
user32.RegisterClassW.restype = wintypes.ATOM
user32.CreateWindowExW.argtypes = [
    wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
    ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, ctypes.c_void_p,
]
user32.CreateWindowExW.restype = wintypes.HWND
user32.PeekMessageW.argtypes = [
    ctypes.POINTER(MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT, wintypes.UINT,
]
user32.PeekMessageW.restype = wintypes.BOOL
user32.TranslateMessage.argtypes = [ctypes.POINTER(MSG)]
user32.DispatchMessageW.argtypes = [ctypes.POINTER(MSG)]
user32.DispatchMessageW.restype = ctypes.c_ssize_t
user32.GetMessageTime.restype = wintypes.LONG
kernel32.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
kernel32.GetModuleHandleW.restype = wintypes.HINSTANCE


class Frame:
    """Одно пришедшее событие."""

    __slots__ = ("t_ms", "kind", "dx", "dy", "flags", "device")

    def __init__(self, t_ms: int, kind: str, dx: int, dy: int, flags: int, device: str):
        self.t_ms = t_ms
        self.kind = kind
        self.dx = dx
        self.dy = dy
        self.flags = flags
        self.device = device


def device_name(handle) -> str:
    """Полное имя устройства RAWINPUT (VID/PID и интерфейс)."""
    if not handle:
        return "синтетический ввод (SendInput, без устройства)"
    size = wintypes.UINT(0)
    user32.GetRawInputDeviceInfoW(handle, RIDI_DEVICENAME, None, ctypes.byref(size))
    if size.value == 0:
        return f"handle:{handle}"
    buf = ctypes.create_unicode_buffer(size.value)
    got = user32.GetRawInputDeviceInfoW(handle, RIDI_DEVICENAME, buf, ctypes.byref(size))
    if got <= 0:
        return f"handle:{handle}"
    return buf.value


def register(hwnd) -> None:
    devices = (RAWINPUTDEVICE * 2)(
        RAWINPUTDEVICE(0x01, 0x02, RIDEV_INPUTSINK, hwnd),  # Generic Desktop / Mouse
        RAWINPUTDEVICE(0x01, 0x06, RIDEV_INPUTSINK, hwnd),  # Generic Desktop / Keyboard
    )
    if not user32.RegisterRawInputDevices(devices, 2, ctypes.sizeof(RAWINPUTDEVICE)):
        raise OSError(ctypes.get_last_error(), "RegisterRawInputDevices failed")


def parse_raw(buf) -> tuple[str, int, int, int] | None:
    """Разбор одного RAWINPUT: (вид, dx, dy, flags). Отдельно от WinAPI — проверяется --selftest."""
    raw = RAWINPUT.from_buffer_copy(buf)
    if raw.header.dwType == RIM_TYPEMOUSE:
        m = raw.body.mouse
        return ("mouse", m.lLastX, m.lLastY, int(m.usFlags))
    if raw.header.dwType == RIM_TYPEKEYBOARD:
        return ("keyboard", 0, 0, 0)
    return None


def read_input(lparam) -> tuple[str, int, int, int, str] | None:
    size = wintypes.UINT(0)
    got = user32.GetRawInputData(
        wintypes.HANDLE(lparam), RID_INPUT, None, ctypes.byref(size), ctypes.sizeof(RAWINPUTHEADER)
    )
    if got == 0xFFFFFFFF or size.value == 0:
        return None
    # Клавиатурный пакет RAWINPUT занимает 40 байт, мышиный — 48 (24 header + 24 mouse).
    # Буфер выделяем под максимум из двух, иначе from_buffer_copy падает на клавиатуре:
    # исключение внутри WNDPROC глушится и событие просто теряется.
    alloc = max(int(size.value), ctypes.sizeof(RAWINPUT))
    buf = ctypes.create_string_buffer(alloc)
    if (
        user32.GetRawInputData(
            wintypes.HANDLE(lparam), RID_INPUT, buf, ctypes.byref(size), ctypes.sizeof(RAWINPUTHEADER)
        )
        == 0xFFFFFFFF
    ):
        return None
    raw = RAWINPUT.from_buffer_copy(buf)
    parsed = parse_raw(buf)
    if parsed is None:
        return None
    kind, dx, dy, flags = parsed
    return (kind, dx, dy, flags, device_name(raw.header.hDevice))


def selftest() -> int:
    """Проверка раскладки структур без живой мыши: собираем пакет и разбираем обратно."""
    print(f"RAWINPUTHEADER = {ctypes.sizeof(RAWINPUTHEADER)} байт (ожидается 24)")
    print(f"RAWMOUSE = {ctypes.sizeof(RAWMOUSE)} байт (ожидается 24)")
    print(f"RAWINPUT = {ctypes.sizeof(RAWINPUT)} байт (ожидается 48)")
    raw = RAWINPUT()
    raw.header.dwType = RIM_TYPEMOUSE
    raw.header.dwSize = ctypes.sizeof(RAWINPUT)
    raw.body.mouse.lLastX = 17
    raw.body.mouse.lLastY = -9
    raw.body.mouse.usFlags = 0
    raw.body.mouse.ulButtons = 0
    buf = ctypes.string_at(ctypes.byref(raw), ctypes.sizeof(RAWINPUT))
    parsed = parse_raw(buf)
    print(f"разбор синтетического пакета: {parsed}")
    ok = parsed == ("mouse", 17, -9, 0)

    # Клавиатурный пакет короче мышиного (40 байт) — на нём парсер раньше падал.
    kb_size = ctypes.sizeof(RAWINPUTHEADER) + ctypes.sizeof(KEYBOARD_BODY)
    raw2 = RAWINPUT()
    raw2.header.dwType = RIM_TYPEKEYBOARD
    raw2.header.dwSize = kb_size
    raw2.body.keyboard.VKey = 0x41
    buf2 = ctypes.string_at(ctypes.byref(raw2), kb_size)
    parsed2 = parse_raw(ctypes.create_string_buffer(buf2, ctypes.sizeof(RAWINPUT)))
    print(f"клавиатурный пакет {kb_size} байт: {parsed2}")
    ok = ok and parsed2 == ("keyboard", 0, 0, 0)
    print("selftest:", "ок" if ok else "ПРОВАЛ")
    return 0 if ok else 1


def percentiles(values: list[int], qs=(50, 95, 99)) -> dict[int, float]:
    if not values:
        return {q: 0.0 for q in qs}
    ordered = sorted(values)
    out = {}
    for q in qs:
        idx = min(len(ordered) - 1, max(0, int(round((q / 100) * (len(ordered) - 1)))))
        out[q] = float(ordered[idx])
    return out


def summarize(device: str, frames: list[Frame], longest_idle_gap: int | None) -> list[str]:
    mouse = [f for f in frames if f.kind == "mouse"]
    keys = [f for f in frames if f.kind == "keyboard"]
    lines = [f"\n{device}", f"  событий: мышь {len(mouse)}, клавиатура {len(keys)}"]
    if not mouse:
        return lines

    # Движение: кадры, где реально пришли ненулевые дельты.
    moves = [f for f in mouse if f.dx or f.dy]
    times = [f.t_ms for f in moves]
    gaps = [b - a for a, b in zip(times, times[1:]) if b > a]
    # Паузы > 1 с — это рука остановилась, а не канал: в статистику провалов они не идут,
    # иначе тест «поводи, сделай паузу, поводи» всегда «доказывает» пробуждение из сна.
    pauses = [g for g in gaps if g > PAUSE_MS]
    stalls = [g for g in gaps if STALL_MS < g <= PAUSE_MS]
    active = [g for g in gaps if g <= PAUSE_MS]

    p = percentiles(active)
    span_s = (times[-1] - times[0]) / 1000 if len(times) > 1 else 0
    rate = len(times) / span_s if span_s > 0 else 0.0

    lines.append(f"  частота отчётов движения: {rate:.0f} Гц (потолок Bluetooth-HID ≈ 125 Гц)")
    lines.append(
        f"  интервал: медиана {p[50]:.0f} мс, p95 {p[95]:.0f} мс, p99 {p[99]:.0f} мс"
    )
    lines.append(
        f"  провалов {STALL_MS}–{PAUSE_MS} мс: {len(stalls)} из {len(active)} активных интервалов"
        + (f", самый длинный {max(stalls)} мс" if stalls else "")
    )
    if pauses:
        lines.append(
            f"  пауз > {PAUSE_MS} мс: {len(pauses)} (рука остановилась; для пробуждения из сна "
            "нужен тест с ПОСТОЯННЫМ движением — см. ниже)"
        )

    verdict = []
    if p[50] > TARGET_MEDIAN_MS:
        verdict.append(f"медиана {p[50]:.0f} мс выше нормы {TARGET_MEDIAN_MS:.0f} мс — телефон шлёт реже, чем может линия")
    if p[95] > TARGET_P95_MS:
        verdict.append(f"p95 {p[95]:.0f} мс выше нормы {TARGET_P95_MS:.0f} мс — сетка отчётов рвётся")
    if len(stalls) > max(3, len(gaps) // 20):
        verdict.append("провалы чаще 5 % — канал забит или устройство уходит в сон")
    # Пауза между движениями ничего не говорит о пробуждении: палец мог просто стоять.
    # Пробуждение видно только в непрерывном движении (методика в конце вывода).
    if verdict:
        lines.extend(f"  ! {v}" for v in verdict)
    else:
        lines.append("  ок: и частота, и провалы в норме")
    return lines


def run(seconds: float, only: str | None, csv_path: str | None) -> int:
    frames: list[Frame] = []
    bad = [0]
    last_by_device: dict[str, int] = {}
    idle_gaps: dict[str, int] = {}

    wndproc_type = ctypes.WINFUNCTYPE(
        ctypes.c_ssize_t, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM
    )

    def wndproc(hwnd, msg, wparam, lparam):
        if msg == WM_INPUT:
            try:
                parsed = read_input(lparam)
            except Exception as exc:  # noqa: BLE001 - диагностика важнее падения цикла
                bad[0] += 1
                if bad[0] == 1:
                    print(f"! ошибка разбора RAWINPUT: {exc}")
                return 0
            t = int(user32.GetMessageTime())
            if parsed:
                kind, dx, dy, flags, name = parsed
                if only and only.lower() not in name.lower():
                    return 0
                frames.append(Frame(t, kind, dx, dy, flags, name))
                prev = last_by_device.get(name)
                if prev is not None and kind == "mouse":
                    # Простой > 1 с: следующее событие после него — проверка пробуждения.
                    if t - prev > 1000:
                        idle_gaps[name] = max(idle_gaps.get(name, 0), t - prev)
                last_by_device[name] = t
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

    proc = wndproc_type(wndproc)
    hinst = kernel32.GetModuleHandleW(None)
    class_name = "PocketInputLatencySink"
    wc = WNDCLASSW(0, ctypes.cast(proc, ctypes.c_void_p), 0, 0, hinst, None, None, None, None, class_name)
    if not user32.RegisterClassW(ctypes.byref(wc)):
        err = ctypes.get_last_error()
        if err not in (0, 1410):  # 1410 = класс уже зарегистрирован
            raise OSError(err, "RegisterClassW failed")
    hwnd = user32.CreateWindowExW(0, class_name, "latency sink", 0, 0, 0, 0, 0, None, None, hinst, None)
    if not hwnd:
        raise OSError(ctypes.get_last_error(), "CreateWindowExW failed")

    register(hwnd)
    print(f"Слушаю HID {seconds:.0f} с. Подвигай курсор телефоном, нажми пару кликов,")
    print("сделай паузу 3–5 с и снова подвигай — это и есть проверка пробуждения.\n")

    msg = MSG()
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        # Ждём сообщение не дольше остатка времени.
        if user32.PeekMessageW(ctypes.byref(msg), None, 0, 0, 1):  # PM_REMOVE
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))
        else:
            time.sleep(0.001)

    by_device: dict[str, list[Frame]] = {}
    for f in frames:
        by_device.setdefault(f.device, []).append(f)

    if bad[0]:
        print(f"! не удалось разобрать {bad[0]} пакетов RAWINPUT")
    if not by_device:
        print("Событий не пришло. Проверь, что телефон подключён как HID и курсор двигался.")
        return 2

    print("=" * 72)
    for name, items in sorted(by_device.items()):
        for line in summarize(name, items, idle_gaps.get(name)):
            print(line)

    if csv_path:
        with open(csv_path, "w", encoding="utf-8") as fh:
            fh.write("t_ms,device,kind,dx,dy,flags\n")
            for f in sorted(frames, key=lambda x: x.t_ms):
                fh.write(f'{f.t_ms},"{f.device}",{f.kind},{f.dx},{f.dy},{f.flags}\n')
        print(f"\nСырые события: {csv_path}")

    print("\nНормативы: медиана ≤ 12 мс, p95 ≤ 35 мс, провалов 50–1000 мс ≤ 5 %.")
    print("Пробуждение из sniff: веди палец ПОСТОЯННО по кругу 20 с без остановок —")
    print("тогда каждый интервал > 50 мс принадлежит каналу, а не руке.")
    print("Частота выше ~125 Гц недостижима: столько отчётов Windows забирает у HID-канала.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Замер прихода HID-событий на Windows")
    ap.add_argument("--seconds", type=float, default=20.0, help="сколько слушать")
    ap.add_argument("--only", default=None, help="фильтр по подстроке в имени устройства")
    ap.add_argument("--csv", default=None, help="куда сохранить сырые события")
    ap.add_argument("--selftest", action="store_true", help="проверить раскладку структур и выйти")
    args = ap.parse_args(argv)
    if sys.platform != "win32":
        print("Утилита только для Windows: используется RAWINPUT.")
        return 2
    if args.selftest:
        return selftest()
    return run(args.seconds, args.only, args.csv)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))