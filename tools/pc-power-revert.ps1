# Revert of the Bluetooth-HID power tweaks. ASCII only (PowerShell 5.1 = ANSI parser).
# Restores exactly what was changed: USB selective suspend -> allowed,
# power management back on for the phone HID device and the two hubs.
$ErrorActionPreference = 'Continue'
$log = @()

$usbSub = '2a737441-1930-4402-8d77-b2bebba308a3'
$usbSet = '48e6b7a6-50f5-4782-a5d4-53bb8f07e226'
powercfg /setacvalueindex SCHEME_CURRENT $usbSub $usbSet 1 | Out-Null
powercfg /setdcvalueindex SCHEME_CURRENT $usbSub $usbSet 1 | Out-Null
powercfg /setactive SCHEME_CURRENT | Out-Null
$log += 'usb selective suspend -> 1 (allowed)'

$restore = @(
    '00001124-0000-1000-8000-00805f9b34fb',   # phone as HID
    'USB\\VID_05E3&PID_0620',                 # external USB hub
    'USB\\ROOT_HUB30\\5&26e72d53'             # root hub that was enabled
)
foreach ($d in Get-CimInstance -Namespace root\wmi -ClassName MSPower_DeviceEnable) {
    foreach ($p in $restore) {
        if ($d.InstanceName -match $p -and -not $d.Enable) {
            try {
                Set-CimInstance -InputObject $d -Property @{ Enable = $true }
                $log += ('back on: ' + $d.InstanceName)
            } catch {
                $log += ('FAILED: ' + $d.InstanceName + ' -> ' + $_.Exception.Message)
            }
        }
    }
}
$log | ForEach-Object { Write-Output $_ }