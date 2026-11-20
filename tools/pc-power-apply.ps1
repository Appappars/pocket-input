# Windows power settings for Bluetooth HID: stop the radio/USB port from sleeping.
# ASCII only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI, and Cyrillic text
# inside an UTF-8 file breaks its parser (that is exactly what failed the first run).
# Revert script: pc-power-revert.ps1
$ErrorActionPreference = 'Stop'
$log = @()

# 1. USB selective suspend -> Disabled (0) on AC and DC.
#    The Bluetooth dongle sits behind a USB hub, so an idle port can be suspended.
$usbSub = '2a737441-1930-4402-8d77-b2bebba308a3'
$usbSet = '48e6b7a6-50f5-4782-a5d4-53bb8f07e226'
powercfg /setacvalueindex SCHEME_CURRENT $usbSub $usbSet 0 | Out-Null
powercfg /setdcvalueindex SCHEME_CURRENT $usbSub $usbSet 0 | Out-Null
powercfg /setactive SCHEME_CURRENT | Out-Null
$log += 'usb selective suspend: 1 (allowed) -> 0 (disabled), ac+dc'

# 2. Clear the per-device "Allow the computer to turn this device off to save power"
#    flag for the phone's HID device (Bluetooth HID profile 0x1124).
$targets = @()
foreach ($d in Get-CimInstance -Namespace root\wmi -ClassName MSPower_DeviceEnable) {
    if ($d.InstanceName -match '00001124-0000-1000-8000-00805f9b34fb') { $targets += $d }
}
foreach ($d in $targets) {
    if ($d.Enable) {
        Set-CimInstance -InputObject $d -Property @{ Enable = $false }
        $log += ('power management off: ' + $d.InstanceName)
    } else {
        $log += ('already off: ' + $d.InstanceName)
    }
}
if ($targets.Count -eq 0) { $log += 'WARN: phone HID device not found (phone not paired?)' }

# 3. Raw dump for verification (parsed outside, in UTF-8).
$log += ''
$log += '=== powercfg dump: USB ==='
$log += (powercfg /q SCHEME_CURRENT $usbSub $usbSet | Out-String)
$log += '=== MSPower state ==='
foreach ($d in Get-CimInstance -Namespace root\wmi -ClassName MSPower_DeviceEnable) {
    if ($d.InstanceName -match '00001124-0000-1000-8000-00805f9b34fb') {
        $log += ('phone as HID: Enable=' + $d.Enable + ' :: ' + $d.InstanceName)
    }
}
Set-Content -Path (Join-Path $PSScriptRoot 'pc-power-after.txt') -Value $log -Encoding UTF8
$log | ForEach-Object { Write-Output $_ }