param(
    [ValidateSet('Inventory', 'Provider', 'MediaStore')]
    [string]$Stage = 'Inventory',
    [string]$EvidenceDir = (Join-Path $PSScriptRoot '../evidence')
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $EvidenceDir | Out-Null
$records = [System.Collections.Generic.List[string]]::new()

function Add-AdbEvidence {
    param([string[]]$Arguments, [string]$Filter, [int]$Context = 0)

    $records.Add('$ adb ' + ($Arguments -join ' '))
    $lines = @(& adb @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $exitCode = $LASTEXITCODE
    if ($Filter) {
        $mask = [bool[]]::new($lines.Count)
        for ($index = 0; $index -lt $lines.Count; $index++) {
            if ($lines[$index] -match $Filter) {
                for ($offset = [Math]::Max(0, $index - $Context); $offset -le $index; $offset++) {
                    $mask[$offset] = $true
                }
            }
        }
        $lines = for ($index = 0; $index -lt $lines.Count; $index++) {
            if ($mask[$index]) { $lines[$index] }
        }
    }
    foreach ($line in $lines) {
        $records.Add(($line -replace '^\S+(\s+device\s+product:)', '[serial omitted]$1'))
    }
    $records.Add('[exit ' + $exitCode + ']')
    $records.Add('')
}

if ($Stage -eq 'Inventory') {
    Add-AdbEvidence -Arguments @('version')
    Add-AdbEvidence -Arguments @('devices', '-l') -Filter '^(List of devices attached|\S+\s+device\s+product:PD2502 model:V2502A\b)'
    $model = @(& adb -d shell getprop ro.product.model)
    if ($LASTEXITCODE -ne 0 -or (($model -join '').Trim() -ne 'V2502A')) { throw 'Expected exactly one USB device, model V2502A.' }
    $properties = 'ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.build.fingerprint ro.vivo.os.build.display.id ro.vivo.os.name ro.vivo.os.version ro.vivo.product.version ro.build.version.incremental'
    Add-AdbEvidence -Arguments @('-d','shell',('for k in ' + $properties + '; do echo "$k=$(getprop "$k")"; done'))
    Add-AdbEvidence -Arguments @('-d','shell','cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.APP_GALLERY')
    Add-AdbEvidence -Arguments @('-d','shell','cmd package resolve-activity --brief -a android.intent.action.VIEW -t image/jpeg')
    Add-AdbEvidence -Arguments @('-d','shell','cmd package list packages --apex-only') -Filter '^package:com\.android\.media'
    Add-AdbEvidence -Arguments @('-d','shell','cmd package list packages -s') -Filter '^package:(com\.(?:vivo\.(?:base\.)?gallery|vivo\.widget\.gallery|android\.(?:photopicker|providers\.media(?:\.module)?|location\.fused)|amap\.android\.location|baidu\.map\.location))$'
    foreach ($package in 'com.vivo.gallery','com.vivo.base.gallery','com.android.photopicker','com.android.providers.media.module','com.vivo.widget.gallery') {
        Add-AdbEvidence -Arguments @('-d','shell','pm','path',$package)
        Add-AdbEvidence -Arguments @('-d','shell','dumpsys','package',$package) -Filter '^\s*(versionCode=|versionName=|codePath=|pkgFlags=|sharedUser=|splits=|\[com\.vivo\.gallery,com\.vivo\.base\.gallery,com\.vivo\.widget\.gallery\]:)|^\s*Permission \[.*(GALLERY|MEDIA)' -Context 0
    }
    Add-AdbEvidence -Arguments @('-d','shell','dumpsys package providers') -Filter '^\s+Provider\{\w+ (com\.vivo\.(?:base\.)?gallery|com\.vivo\.widget\.gallery|com\.android\.(?:providers\.media\.module|photopicker))/' -Context 1
    Add-AdbEvidence -Arguments @('-d','shell','service list') -Filter '^\s*\d+\s+(location|location_time_zone_manager|media\.[^:]+|media_[^:]+|vivomediametrics|[^\s:]*(?:gallery|photoalbum|footprint)[^\s:]*):'
    $logName = 'A-adb-inventory.txt'
} elseif ($Stage -eq 'Provider') {
    $model = @(& adb -d shell getprop ro.product.model)
    if ($LASTEXITCODE -ne 0 -or (($model -join '').Trim() -ne 'V2502A')) { throw 'Expected exactly one USB device, model V2502A.' }
    Add-AdbEvidence -Arguments @('-d','shell','dumpsys package com.vivo.gallery') -Filter '^\s+perm=PermissionInfo\{.*(?:com\.android\.gallery3d\.permission\.GalleryDataProvider|com\.vivo\.gallery3d\.permission\.GALLERY_MEDIA_PROVIDER)\}' -Context 3
    Add-AdbEvidence -Arguments @('-d','shell','dumpsys package com.vivo.base.gallery') -Filter '^\s+perm=PermissionInfo\{.*com\.vivo\.gallery3d\.permission\.MediaExtraProvider\}' -Context 3
    # The exported, normal-permission provider is probed only with an empty result predicate.
    Add-AdbEvidence -Arguments @('-d','shell','content query --uri content://com.android.gallery3d.dataprovider/mediaextra --projection _id --where 0=1')
    $logName = 'C-adb-provider.txt'
} else {
    $model = @(& adb -d shell getprop ro.product.model)
    if ($LASTEXITCODE -ne 0 -or (($model -join '').Trim() -ne 'V2502A')) { throw 'Expected exactly one USB device, model V2502A.' }
    Add-AdbEvidence -Arguments @('-d','shell','content gettype --uri content://media/external/images/media')
    Add-AdbEvidence -Arguments @('-d','shell','content query --uri content://media/external/images/media --projection _id:datetaken:generation_added:generation_modified --where 0=1')
    Add-AdbEvidence -Arguments @('-d','shell','content query --uri content://media/external/images/media --projection latitude:longitude --where 0=1')
    Add-AdbEvidence -Arguments @('-d','shell','content call --uri content://media --method get_generation --extra android.intent.extra.TEXT:s:external_primary')
    $logName = 'C-adb-mediastore.txt'
}

[IO.File]::WriteAllText((Join-Path (Resolve-Path $EvidenceDir) $logName), ($records -join "`n") + "`n")
