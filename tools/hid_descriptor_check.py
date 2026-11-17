"""Разбор HID-дескриптора из Descriptors.kt и сверка с тем, что шлёт код.

HID short item: байт-префикс = bSize (bits 0-1) | bType (bits 2-3) | bTag (bits 4-7).
bType: 0 main, 1 global, 2 local. bSize: 0->0, 1->1, 2->2, 3->4 байта.
"""
import re
import sys

KT = r"C:/Users/User98/pocket-input/android/app/src/main/kotlin/dev/pocketinput/hid/Descriptors.kt"
src = open(KT, encoding="utf-8").read()

start = src.find("byteArrayOf(", src.find("HID_DESCRIPTOR"))
if start < 0:
    sys.exit("не нашёл HID_DESCRIPTOR в Descriptors.kt")

lines_before = src[:start].count(chr(10))
body = []
for ln in src.splitlines()[lines_before:]:
    if ln.strip() == ")":
        break
    body.append(ln)
raw = chr(10).join(body)
raw = raw[raw.find("byteArrayOf(") + len("byteArrayOf("):]

tokens = re.findall(r"0x[0-9A-Fa-f]{2}|(?<![\w.])-?\d+", raw)
data = [int(t, 16) if t.lower().startswith("0x") else (int(t) & 0xFF) for t in tokens]
print("длина дескриптора: %d байт" % len(data))
print()

TAGS_MAIN = {0x8: "Input", 0x9: "Output", 0xB: "Feature", 0xA: "Collection", 0xC: "EndCollection"}
TAGS_GLOBAL = {0x0: "UsagePage", 0x1: "LogicalMin", 0x2: "LogicalMax",
               0x7: "ReportSize", 0x8: "ReportID", 0x9: "ReportCount"}
TAGS_LOCAL = {0x0: "Usage", 0x1: "UsageMin", 0x2: "UsageMax"}

reports = {}
state = {}
i = 0
while i < len(data):
    prefix = data[i]
    size = {0: 0, 1: 1, 2: 2, 3: 4}[prefix & 0x3]
    typ = (prefix >> 2) & 0x3
    tag = (prefix >> 4) & 0xF
    val = 0
    for k in range(size):
        val |= data[i + 1 + k] << (8 * k)
    i += 1 + size

    if typ == 1:
        name = TAGS_GLOBAL.get(tag, "global0x%X" % tag)
        state[name] = val
        if name == "ReportID":
            reports.setdefault(val, {"bits": 0, "fields": []})
        continue
    if typ == 2:
        if TAGS_LOCAL.get(tag, "") == "Usage":
            state["last_usage"] = val
        continue

    name = TAGS_MAIN.get(tag, "main0x%X" % tag)
    if name in ("Input", "Output", "Feature"):
        bits = state.get("ReportSize", 0) * state.get("ReportCount", 0)
        rid = state.get("ReportID", 0)
        rep = reports.setdefault(rid, {"bits": 0, "fields": []})
        rep["bits"] += bits
        rep["fields"].append({
            "kind": name,
            "usage_page": state.get("UsagePage"),
            "usage": state.get("last_usage"),
            "size": state.get("ReportSize"),
            "count": state.get("ReportCount"),
            "bits": bits,
        })

for rid in sorted(reports):
    r = reports[rid]
    print("Report ID %d: %d бит = %.1f байт, полей %d" % (rid, r["bits"], r["bits"] / 8, len(r["fields"])))
    for f in r["fields"]:
        print("   %-6s usage_page=0x%02X usage=%s size=%s count=%s -> %d бит" % (
            f["kind"], f["usage_page"] or 0, f["usage"], f["size"], f["count"], f["bits"]))

ok = True
print()
print("сверка с отправителем (keyboardReport = 8 байт, mouseReport = 4 байта):")
if reports.get(1, {}).get("bits") == 64:
    print("  OK: report ID 1 = 8 байт")
else:
    print("  FAIL: report ID 1 = %s бит" % reports.get(1, {}).get("bits"))
    ok = False
if reports.get(2, {}).get("bits") == 32:
    print("  OK: report ID 2 = 4 байта")
else:
    print("  FAIL: report ID 2 = %s бит" % reports.get(2, {}).get("bits"))
    ok = False
has_gd = any((f["usage_page"] == 0x01) for rid in reports for f in reports[rid]["fields"])
print("  %s: Usage Page Generic Desktop (0x01) %s" % ("OK" if has_gd else "FAIL",
                                                      "найден" if has_gd else "отсутствует"))
print()
print("ИТОГ:", "дескриптор согласован с репортами" if ok and has_gd else "ЕСТЬ РАСХОЖДЕНИЯ")
