# Pass 2: clear power management on the USB hubs the Bluetooth dongle hangs off.
# ASCII only: PowerShell 5.1 parses .ps1 as ANSI and chokes on UTF-8 Cyrillic.
$ErrorActionPreference = 'Stop'
$out = @()

$hubPatterns = @(
    'USB\\VID_05E3&PID_0620',      # external hub
    'USB\\ROOT_HUB30'              # root hubs
)

foreach ($d in Get-CimInstance -Namespace root\wmi -ClassName MSPower_DeviceEnable) {
    foreach ($p in $hubPatterns) {
        if ($d.InstanceName -match $p -and $d.Enable) {
            try {
                Set-CimInstance -InputObject $d -Property @{ Enable = $false }
                $out += ('off: ' + $d.InstanceName)
            } catch {
                $out += ('FAILED: ' + $d.InstanceName + ' -> ' + $_.Exception.Message)
            }
        }
    }
}

$out += ''
$out += '=== state after pass 2 ==='
foreach ($d in Get-CimInstance -Namespace root\wmi -ClassName MSPower_DeviceEnable) {
    if ($d.InstanceName -match 'USB\\|00001124') {
        $out += ('Enable=' + $d.Enable + '  ' + $d.InstanceName)
    }
}
Set-Content -Path (Join-Path $PSScriptRoot 'pc-power-pass2.txt') -Value $out -Encoding UTF8
$out | ForEach-Object { Write-Output $_ }