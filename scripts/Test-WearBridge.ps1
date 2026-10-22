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
    [string]$AdbPath = 'adb'
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
if ($font -ne 'null' -and $font -notmatch '^\d+(\.\d+)?$') { throw 'Escala de fonte original inválida.' }
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'baseline.json'), ($baseline | ConvertTo-Json))
try {
    foreach ($apk in $apks) { Invoke-Adb @('install','-r',$apk) | Out-Null }
    $packageDump = Invoke-Adb @('shell','dumpsys','package',$bridgePackage)
    $lib = [regex]::Match($packageDump,'legacyNativeLibraryDir=(/data/app/[A-Za-z0-9_+./=~-]+)').Groups[1].Value
    $apkPath = (Invoke-Adb @('shell','pm','path',$bridgePackage)) -replace '^package:',''
    $abi = Invoke-Adb @('shell','getprop','ro.product.cpu.abi')
    if (-not $lib -or $apkPath -notmatch '^/data/app/[A-Za-z0-9_+./=~-]+\.apk$' -or $abi -ne 'x86_64') {
        throw 'Instalação do AVD não reconhecida.'
    }
    # O instrumentador encerra o processo-alvo. Não iniciar junto da criação
    # inicial do provider pelo servidor: essa corrida deixa o AOSP aguardando PID.
    Invoke-Adb @('shell','am','start','-W','-n',"$bridgePackage/.BridgeActivity") | Out-Null
    Invoke-Adb @('shell',"$lib/x86_64/libshizuku.so","--apk=$apkPath") | Out-Null
    Invoke-Adb @('shell','am','start','-W','-n',"$bridgePackage/.BridgeActivity") | Out-Null
    foreach ($scale in @('1.0','1.3')) {
        $directory = Join-Path $OutputDirectory "font-$scale"
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        Invoke-Adb @('shell','settings','put','system','font_scale',$scale) | Out-Null
        Invoke-Instrumentation "$bridgePackage.test/androidx.test.runner.AndroidJUnitRunner" `
            "$bridgePackage.BridgeWearTest#denyOnceAlwaysRevokeAndReadOnRoundScreen" (Join-Path $directory 'authorization.txt')
        foreach ($name in @('authorization-top','authorization-actions','bridge-status')) { Copy-Screenshot $bridgePackage $name $directory }
        Invoke-Instrumentation "$labPackage.test/androidx.test.runner.AndroidJUnitRunner" '' (Join-Path $directory 'diagnostics.txt')
        foreach ($name in @('home','report')) { Copy-Screenshot $labPackage $name $directory }
        Write-Output "PASS: autorização real, revogação, leitura shell e segurança; fonte $scale."
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
            if ((Invoke-Adb @('shell','settings','get','global',$key)) -ne $baseline[$key]) {
                if ($baseline[$key] -eq 'null') { Invoke-Adb @('shell','settings','delete','global',$key) | Out-Null }
                else { Invoke-Adb @('shell','settings','put','global',$key,$baseline[$key]) | Out-Null }
            }
            if ((Invoke-Adb @('shell','settings','get','global',$key)) -ne $baseline[$key]) { throw 'Valor não restaurado.' }
        } catch { $cleanupErrors.Add("Configuração $key`: $($_.Exception.Message)") }
    }
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'cleanup.json'), (ConvertTo-Json -InputObject @($cleanupErrors)))
    if ($cleanupErrors.Count) { throw ($cleanupErrors -join "`n") }
}
