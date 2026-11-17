#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PocketInput, фаза 0 (спайк HID), задача 5.3.7.

hid_probe.py — перечисляет HID-устройства Windows через пакет `hid`
(`pip install hidapi`; модуль импортируется как `import hid`) и печатает
markdown-таблицу: device / vid:pid / usage_page / boot / collections / verdict.

boot-протокол: usage_page == 0x01 И в report-дескрипторе присутствует тег
Report ID. Дескриптор читается через hidapi (device.get_report_descriptor,
доступен в hidapi >= 0.14; на этой машине установлен hidapi 0.15.0).
Если дескриптор недоступен (устройство занято/нет прав), boot помечается
«неизвестно» — строка всё равно выводится, ничего не выдумывается.

Запуск:  python tools/hid_probe.py [--out docs/measurement/hid-probe-<дата>.md]
"""
import argparse
import sys

try:
    import hid  # pip install hidapi
except ImportError as e:
    print(f"UNPROVEN: модуль hid не установлен ({e}). Выполните: python -m pip install hidapi")
    sys.exit(1)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def read_report_descriptor(path: bytes):
    """Читает сырой HID report-дескриптор по пути устройства; None если недоступен."""
    dev = hid.device()
    try:
        dev.open_path(path)
        return bytes(dev.get_report_descriptor())
    except Exception:
        return None
    finally:
        try:
            dev.close()
        except Exception:
            pass


def parse_descriptor(desc: bytes):
    """Возвращает (has_report_id, top_level_collections)."""
    has_report_id = False
    top_collections = 0
    depth = 0
    i = 0
    n = len(desc)
    while i < n:
        b = desc[i]
        size = b & 0x03
        size = 4 if size == 3 else size
        tag = b & 0xFC
        if tag == 0x84:  # Report ID
            has_report_id = True
        elif tag == 0xA0:  # Collection
            if depth == 0:
                top_collections += 1
            depth += 1
        elif tag == 0xC0:  # End Collection
            depth = max(0, depth - 1)
        i += 1 + size
    return has_report_id, top_collections


def device_name(dev) -> str:
    name = (dev.get("product_string") or "").strip()
    mfg = (dev.get("manufacturer_string") or "").strip()
    if not name:
        name = mfg or "Unknown HID device"
    elif mfg and mfg not in name:
        name = f"{mfg} {name}"
    return name.replace("|", "\\|")


def probe():
    rows = []
    devices = hid.enumerate(0, 0)
    for dev in devices:
        path = dev["path"]  # bytes, как отдаёт hidapi
        up = dev.get("usage_page", 0)
        desc = read_report_descriptor(path)
        if desc:
            has_rid, collections = parse_descriptor(desc)
            if up == 0x01:
                boot = "yes" if has_rid else "no (нет Report ID)"
            else:
                boot = "no (usage_page != 0x01)"
            verdict = "HID OK"
        else:
            collections = None
            boot = "неизвестно (дескриптор недоступен)"
            verdict = "HID OK (виден системе, дескриптор не читается)"
        rows.append((
            device_name(dev),
            f"{dev.get('vendor_id', 0):04x}:{dev.get('product_id', 0):04x}",
            f"0x{up:04X}",
            boot,
            "-" if collections is None else str(collections),
            verdict,
        ))
    return rows


def render_table(rows):
    lines = [
        "| device | vid:pid | usage_page | boot | collections | verdict |",
        "|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append("| " + " | ".join(r) + " |")
    return lines


def main():
    ap = argparse.ArgumentParser(description="Перечисление HID-устройств Windows (фаза 0 PocketInput)")
    ap.add_argument("--out", help="путь к файлу markdown; без него — печать в stdout")
    args = ap.parse_args()

    rows = probe()
    out = ["# HID probe — Windows", "",
           f"Устройств (HID-интерфейсов) найдено: {len(rows)}", ""]
    out += render_table(rows)
    text = "\n".join(out) + "\n"

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"записано: {args.out} ({len(rows)} строк таблицы)")
    else:
        print(text)


if __name__ == "__main__":
    main()
