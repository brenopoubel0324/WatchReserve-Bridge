#Requires -Version 7.4
<#
.SYNOPSIS
Valida a ponte Wear no AVD circular reservado e restaura a configuração de ensaio.
.DESCRIPTION
Requer APKs pré-compilados dos dois projetos. Não altera relógio físico, não desinstala
outros gerenciadores e não concede acesso por comandos ADB. A autorização usa a UI real.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory)][string]$LabRepository,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$AdbPath = 'adb',
    [switch]$IncludeLocalSessionChecks
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$adb = (Get-Command $AdbPath -CommandType Application).Source
$bridgeRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$LabRepository = (Resolve-Path -LiteralPath $LabRepository).Path
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$expectedAvd = 'WatchReserve_Wear5_Small_Round'
$bridgePackage = 'dev.brenopoubel.watchreserve.bridge'
$labPackage = 'dev.brenopoubel.watchreserve.advancedlab'

function Invoke-Adb([string[]]$Arguments) {
    $lines = @(& $adb -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB falhou: $($Arguments[0])" }
    ($lines | Out-String).Trim()
}

function Invoke-Instrumentation([string]$Runner, [string]$Class, [string]$LogFile) {
    $arguments = @('shell','am','instrument','-w','-e','reservedAvd',$expectedAvd)
    if ($Class) { $arguments += @('-e','class',$Class) }
    $processInfo = [Diagnostics.ProcessStartInfo]::new($adb)
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    foreach ($argument in (@('-s',$Serial) + $arguments + $Runner)) { $processInfo.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $processInfo
    try {
        $process.Start() | Out-Null
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(240000)) {
            $process.Kill($true)
            throw 'Tempo limite da instrumentação; a limpeza encerrará os processos de ensaio.'
        }
        $result = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'O processo ADB da instrumentação falhou.' }
    } finally { $process.Dispose() }
    [IO.File]::WriteAllText($LogFile, $result, [Text.UTF8Encoding]::new($false))
    if ($result -notmatch 'OK \([1-9][0-9]* tests?\)' -or
        $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Instrumentação reprovada; confira $LogFile"
    }
}

function Copy-Screenshot([string]$Package, [string]$Name, [string]$Directory) {
    & $adb -s $Serial exec-out run-as $Package cat "files/review-screenshots/$Name.png" > (Join-Path $Directory "$Name.png")
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao copiar captura privada.' }
}

function Start-TestBridge([string]$Library, [string]$Apk) {
    Invoke-Adb @('shell','am','start','-W','-f','0x10008000','-n',"$bridgePackage/.BridgeActivity") | Out-Null
    Invoke-Adb @('shell',"$Library/x86_64/libshizuku.so","--apk=$Apk") | Out-Null
    Invoke-Adb @('shell','am','start','-W','-n',"$bridgePackage/.BridgeActivity") | Out-Null
    $uiFile = '/data/local/tmp/watchreserve-bridge-ready.xml'
    try {
        foreach ($attempt in 1..5) {
            Invoke-Adb @('shell','uiautomator','dump',$uiFile) | Out-Null
            if ((Invoke-Adb @('shell','cat',$uiFile)).Contains('Ativa · shell UID 2000')) { return }
            Start-Sleep -Milliseconds 200
        }
        throw 'A ponte não confirmou UID 2000 antes da instrumentação.'
    } finally { Invoke-Adb @('shell','rm','-f',$uiFile) | Out-Null }
}

function Get-ReadRecipe {
    $raw = Invoke-Adb @('shell','run-as',$labPackage,'cat','no_backup/read-session.properties')
    $phase = [regex]::Match($raw,'(?m)^phase=([A-Z_]+)\s*$').Groups[1].Value
    $id = [regex]::Match($raw,'(?m)^id=([a-f0-9-]{36})\s*$').Groups[1].Value
    if (-not $phase -or $id -notmatch '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$') {
        throw 'Receita privada não reconhecida.'
    }
    return @{ Phase = $phase; Id = $id; Raw = $raw }
}

function Set-TestWifi([bool]$Enabled) {
    Invoke-Adb @('shell','settings','put','system','clockwork_wifi_setting',$(if ($Enabled) { 'on' } else { 'off' })) | Out-Null
    Invoke-Adb @('shell','svc','wifi',$(if ($Enabled) { 'enable' } else { 'disable' })) | Out-Null
    Wait-TestWifi $Enabled
}

function Wait-TestWifi([bool]$Enabled) {
    $value = if ($Enabled) { '1' } else { '0' }
    $expectedStatus = if ($Enabled) { 'Wifi is enabled' } else { 'Wifi is disabled' }
    foreach ($attempt in 1..20) {
        $status = Invoke-Adb @('shell','cmd','wifi','status')
        if ((Invoke-Adb @('shell','settings','get','global','wifi_on')) -eq $value -and $status.Contains($expectedStatus)) { return }
        Start-Sleep -Milliseconds 250
    }
    throw 'O estado de Wi-Fi não foi confirmado pelo serviço e pela configuração.'
}

function Restore-TestWifi {
    Set-TestWifi ($baseline['wifi_on'] -eq '1')
    if ($originalWifiPolicy -eq 'null') { Invoke-Adb @('shell','settings','delete','system','clockwork_wifi_setting') | Out-Null }
    else { Invoke-Adb @('shell','settings','put','system','clockwork_wifi_setting',$originalWifiPolicy) | Out-Null }
    Wait-TestWifi ($baseline['wifi_on'] -eq '1')
    if ((Invoke-Adb @('shell','settings','get','system','clockwork_wifi_setting')) -ne $originalWifiPolicy) {
        throw 'Preferência Wear de Wi-Fi não restaurada.'
    }
}

if ((Invoke-Adb @('shell','getprop','ro.kernel.qemu')) -ne '1' -or
    ((Invoke-Adb @('emu','avd','name')) -split '\r?\n')[0].Trim() -ne $expectedAvd) {
    throw 'Use somente o AVD reservado do WatchReserve.'
}
$configuration = Invoke-Adb @('shell','am','get-config')
if ($configuration -notmatch '-round-' -or $configuration -notmatch '-watch-') { throw 'AVD deve ser Wear circular.' }
if ((Invoke-Adb @('shell','wm','size')) -ne 'Physical size: 384x384' -or
    (Invoke-Adb @('shell','wm','density')) -ne 'Physical density: 320') {
    throw 'O teste exige 192 dp nativos, sem ampliação da tela.'
}
foreach ($repo in @($bridgeRoot, $LabRepository)) {
    if ($OutputDirectory.Equals($repo,[StringComparison]::OrdinalIgnoreCase) -or
        $OutputDirectory.StartsWith($repo + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Resultados devem ficar fora dos repositórios.'
    }
}
foreach ($other in @('com.hamondev.shevery','moe.shizuku.privileged.api')) {
    if (Invoke-Adb @('shell','pm','list','packages',$other)) {
        throw 'Outro gerenciador está instalado. Prepare um AVD isolado; o roteiro não o removerá.'
    }
}
$apks = @(
    (Join-Path $bridgeRoot 'wear/build/outputs/apk/debug/wear-debug.apk'),
    (Join-Path $bridgeRoot 'wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk'),
    (Join-Path $LabRepository 'advancedLab/build/outputs/apk/debug/advancedLab-debug.apk'),
    (Join-Path $LabRepository 'advancedLab/build/outputs/apk/androidTest/debug/advancedLab-debug-androidTest.apk')
)
foreach ($apk in $apks) { if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Compile os APKs dos dois projetos primeiro.' } }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$baseline = [ordered]@{}
foreach ($key in @('adb_enabled','adb_wifi_enabled','adb_allowed_connection_time','wifi_on','airplane_mode_on','bluetooth_on','low_power')) {
    $baseline[$key] = Invoke-Adb @('shell','settings','get','global',$key)
}
$font = Invoke-Adb @('shell','settings','get','system','font_scale')
$originalWifiPolicy = Invoke-Adb @('shell','settings','get','system','clockwork_wifi_setting')
if ($originalWifiPolicy -notin @('null','on','off','off_airplane')) { throw 'Preferência Wear de Wi-Fi não reconhecida.' }
$wifiTouched = $false
if ($font -ne 'null' -and $font -notmatch '^\d+(\.\d+)?$') { throw 'Escala de fonte original inválida.' }
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'baseline.json'), ($baseline | ConvertTo-Json))
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'wifi-policy-original.txt'), $originalWifiPolicy)
try {
    foreach ($apk in $apks) { Invoke-Adb @('install','-r',$apk) | Out-Null }
    $packageDump = Invoke-Adb @('shell','dumpsys','package',$bridgePackage)
    $lib = [regex]::Match($packageDump,'legacyNativeLibraryDir=(/data/app/[A-Za-z0-9_+./=~-]+)').Groups[1].Value
    $apkPath = (Invoke-Adb @('shell','pm','path',$bridgePackage)) -replace '^package:',''
    $abi = Invoke-Adb @('shell','getprop','ro.product.cpu.abi')
    if (-not $lib -or $apkPath -notmatch '^/data/app/[A-Za-z0-9_+./=~-]+\.apk$' -or $abi -ne 'x86_64') {
        throw 'Instalação do AVD não reconhecida.'
    }
    Invoke-Instrumentation "$bridgePackage.test/androidx.test.runner.AndroidJUnitRunner" `
        "$bridgePackage.BridgeProviderTest" (Join-Path $OutputDirectory 'provider-readiness.txt')
    Start-TestBridge $lib $apkPath
    foreach ($scale in @('1.0','1.3')) {
        $directory = Join-Path $OutputDirectory "font-$scale"
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        Invoke-Adb @('shell','settings','put','system','font_scale',$scale) | Out-Null
        Invoke-Instrumentation "$bridgePackage.test/androidx.test.runner.AndroidJUnitRunner" `
            "$bridgePackage.BridgeWearTest#denyOnceAlwaysRevokeAndReadOnRoundScreen" (Join-Path $directory 'authorization.txt')
        foreach ($name in @('authorization-top','authorization-actions','bridge-status')) { Copy-Screenshot $bridgePackage $name $directory }
        # O servidor shell continua ativo; o próximo helper precisa iniciar o provider a frio.
        Invoke-Adb @('shell','am','force-stop',$bridgePackage) | Out-Null
        Invoke-Instrumentation "$labPackage.test/androidx.test.runner.AndroidJUnitRunner" '' (Join-Path $directory 'diagnostics.txt')
        foreach ($name in @('home','report','session-prepared')) { Copy-Screenshot $labPackage $name $directory }
        Write-Output "PASS: autorização real, revogação, leitura shell e segurança; fonte $scale."
    }
    if ($IncludeLocalSessionChecks) {
        $directory = Join-Path $OutputDirectory 'local-session'
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        if ($baseline['wifi_on'] -notin @('0','1')) { throw 'Estado original de Wi-Fi não estável.' }
        $wifiTouched = $true
        Set-TestWifi $false
        Invoke-Instrumentation "$bridgePackage.test/androidx.test.runner.AndroidJUnitRunner" `
            "$bridgePackage.BridgeWearTest#prepareBoundLocalSessionForTransportHandoff" (Join-Path $directory 'handoff.txt')
        $preparedRecipe = Get-ReadRecipe
        if ($preparedRecipe.Phase -ne 'DELAYED') { throw 'Handoff perdeu a janela anterior às consultas.' }
        $deviceSeconds = Invoke-Adb @('shell','date','+%s')
        if ($deviceSeconds -notmatch '^\d+$') { throw 'Horário do AVD não disponível.' }
        $transportStart = [long]$deviceSeconds * 1000
        $transport = Invoke-Adb @('reconnect')
        if ($transport -notmatch 'reconnecting') { throw 'A queda do transporte não foi confirmada pelo ADB.' }
        [IO.File]::WriteAllText((Join-Path $directory 'transport-private.txt'), $transport)
        Start-Sleep -Seconds 8
        $recipe = $null
        $waitUntil = [DateTimeOffset]::UtcNow.AddSeconds(170)
        do {
            if ((& $adb -s $Serial get-state 2>$null) -eq 'device') {
                $recipe = Get-ReadRecipe
                if ($recipe.Phase -in @('COMPLETED','FAILED','TIMED_OUT','CANCELLED','INTERRUPTED','INVALID')) { break }
            }
            Start-Sleep -Seconds 1
        } while ([DateTimeOffset]::UtcNow -lt $waitUntil)
        if (-not $recipe -or $recipe.Id -ne $preparedRecipe.Id -or $recipe.Phase -ne 'COMPLETED') {
            throw 'A sessão não terminou localmente após a reconexão ADB.'
        }
        $archive = Join-Path $directory 'report-private.zip'
        & $adb -s $Serial exec-out run-as $labPackage cat "no_backup/diagnostics/report-$($recipe.Id).zip" > $archive
        if ($LASTEXITCODE -ne 0) { throw 'ZIP da sessão local não recuperado.' }
        $zip = [IO.Compression.ZipFile]::OpenRead($archive)
        try {
            $reader = [IO.StreamReader]::new($zip.GetEntry('summary.json').Open())
            try { $summary = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            if ($summary.schema -ne 2 -or $summary.sessionId -ne $recipe.Id -or $summary.uid -ne 2000 -or
                $summary.queries.Count -ne 13 -or $summary.startedAtEpochMillis -lt $transportStart) {
                throw 'Resumo não comprova a sessão após o handoff de transporte.'
            }
            foreach ($query in @('POWER','PROCESSES','WIFI')) {
                $result = @($summary.queries | Where-Object query -eq $query)
                if ($result.Count -ne 1 -or $result[0].status -ne 'ok') { throw 'Consulta principal não concluída sem falha.' }
            }
            foreach ($entry in @(@('power.txt','mWakefulness='),@('processes.txt','ProcessRecord{'),@('wifi.txt','Wifi is disabled'))) {
                $reader = [IO.StreamReader]::new($zip.GetEntry($entry[0]).Open())
                try { if (-not $reader.ReadToEnd().Contains($entry[1])) { throw 'Conteúdo real de leitura ausente.' } }
                finally { $reader.Dispose() }
            }
        } finally { $zip.Dispose() }
        Invoke-Instrumentation "$bridgePackage.test/androidx.test.runner.AndroidJUnitRunner" `
            "$bridgePackage.BridgeWearTest#clientDeathRevocationAndBridgeDeathReconcileLocally" (Join-Path $directory 'interruptions.txt')
        Start-TestBridge $lib $apkPath
        Invoke-Instrumentation "$bridgePackage.test/androidx.test.runner.AndroidJUnitRunner" `
            "$bridgePackage.BridgeWearTest#restartedBridgeAllowsNewReadWithoutClearingData" (Join-Path $directory 'restart.txt')
        Write-Output 'PASS: Wi-Fi desligado, queda momentânea de transporte, sessão local e reconciliação de interrupções.'
        Restore-TestWifi
        $wifiTouched = $false
    }
    Invoke-Instrumentation "$bridgePackage.test/androidx.test.runner.AndroidJUnitRunner" `
        "$bridgePackage.BridgeWearTest#stopButtonEndsServerAndHelpers" (Join-Path $OutputDirectory 'stop.txt')
    foreach ($key in $baseline.Keys) {
        if ((Invoke-Adb @('shell','settings','get','global',$key)) -ne $baseline[$key]) {
            throw "A ponte alterou uma configuração não autorizada: $key"
        }
    }
    Invoke-Adb @('shell','dumpsys','jobscheduler') | Set-Content -LiteralPath (Join-Path $OutputDirectory 'jobs-private.txt')
} finally {
    # Limpeza só depois das guardas de AVD; não abrange nenhum dispositivo físico.
    $cleanupErrors = [Collections.Generic.List[string]]::new()
    try {
        $processes = Invoke-Adb @('shell','ps','-A','-o','USER,PID,NAME')
        foreach ($line in ($processes -split '\r?\n')) {
            if ($line -match '^shell\s+(\d+)\s+watchreserve_bridge$') { Invoke-Adb @('shell','kill',$Matches[1]) | Out-Null }
        }
    } catch { $cleanupErrors.Add("Ponte: $($_.Exception.Message)") }
    foreach ($package in @($bridgePackage,$labPackage,"$bridgePackage.test","$labPackage.test")) {
        try { Invoke-Adb @('shell','am','force-stop',$package) | Out-Null }
        catch { $cleanupErrors.Add("Processo $package`: $($_.Exception.Message)") }
    }
    try {
        if ($font -eq 'null') { Invoke-Adb @('shell','settings','delete','system','font_scale') | Out-Null }
        else { Invoke-Adb @('shell','settings','put','system','font_scale',$font) | Out-Null }
        if ((Invoke-Adb @('shell','settings','get','system','font_scale')) -ne $font) { throw 'Escala não restaurada.' }
    } catch { $cleanupErrors.Add("Fonte: $($_.Exception.Message)") }
    foreach ($key in $baseline.Keys) {
        try {
            if ($key -eq 'wifi_on' -and $wifiTouched) {
                Restore-TestWifi
            }
            if ((Invoke-Adb @('shell','settings','get','global',$key)) -ne $baseline[$key]) {
                if ($baseline[$key] -eq 'null') { Invoke-Adb @('shell','settings','delete','global',$key) | Out-Null }
                else { Invoke-Adb @('shell','settings','put','global',$key,$baseline[$key]) | Out-Null }
            }
            if ((Invoke-Adb @('shell','settings','get','global',$key)) -ne $baseline[$key]) { throw 'Valor não restaurado.' }
        } catch { $cleanupErrors.Add("Configuração $key`: $($_.Exception.Message)") }
    }
    try {
        if ((Invoke-Adb @('shell','settings','get','system','clockwork_wifi_setting')) -ne $originalWifiPolicy) { Restore-TestWifi }
    } catch { $cleanupErrors.Add("Preferência Wear de Wi-Fi: $($_.Exception.Message)") }
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'cleanup.json'), (ConvertTo-Json -InputObject @($cleanupErrors)))
    if ($cleanupErrors.Count) { throw ($cleanupErrors -join "`n") }
}
