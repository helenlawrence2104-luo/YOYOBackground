$ErrorActionPreference = 'Stop'
$yoyoProcessIds = adb shell ps -A -o PID,NAME | ForEach-Object {
    if ($_ -match '^\s*(\d+)\s+com\.hihonor\.magicvoice(?::\S+)?\s*$') { $Matches[1] }
}
if ($LASTEXITCODE -ne 0) { throw 'ADB device query failed.' }
foreach ($yoyoProcessId in $yoyoProcessIds) { adb shell su -c "kill $yoyoProcessId" }
Write-Output 'YOYO processes stopped; Android will restart its bound assistant service.'
