#Requires -Version 7.4
<#
.SYNOPSIS
Testa diários e desativação reversível exclusivamente no APK fixture do AVD reservado.
.DESCRIPTION
Exige o APK :lab do WatchReserve já instalado e APKs Wear compilados. Não inicia a
ponte shell, não concede permissões e não admite pacote/usuário de destino externos.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$AdbPath = 'adb'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$adb = (Get-Command $AdbPath -CommandType Application).Source
$bridgeRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$package = 'dev.brenopoubel.watchreserve.bridge'
$fixture = 'dev.brenopoubel.watchreserve.lab'
$testRoot = "$package.journal"
$avd = 'WatchReserve_Wear5_Small_Round'

function Invoke-Adb([string[]]$Arguments) {
    $result = @(& $adb -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB falhou: $($Arguments[0])" }
    ($result | Out-String).Trim()
}
function Get-FixtureState {
    $dump = Invoke-Adb @('shell','dumpsys','package',$fixture)
    $users = @($dump -split '\r?\n' | Where-Object { $_ -match '^\s*User 0:.*\benabled=[0-4]\b' })
    if ($users.Count -ne 1 -or $users[0] -notmatch '\benabled=([0-4])\b') { throw 'Estado nativo da fixture não reconhecido.' }
    $Matches[1]
}
function Invoke-Probe([string]$Class, [string]$Log, [bool]$ExpectCrash = $false, [bool]$RequireCrashEvidence = $false) {
    $info = [Diagnostics.ProcessStartInfo]::new($adb)
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $arguments = @('-s',$Serial,'shell','am','instrument','-w','-e','reservedAvd',$avd,'-e','class',$Class)
    if ($RequireCrashEvidence) { $arguments += @('-e','requireCrashEvidence','true') }
    $arguments += "$package.test/androidx.test.runner.AndroidJUnitRunner"
    foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    try {
        $process.Start() | Out-Null
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(180000)) { $process.Kill($true); throw 'Prazo da instrumentação de diários excedido.' }
        $result = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText($Log, $result, [Text.UTF8Encoding]::new($false))
        if ($ExpectCrash) {
            if ($result -notmatch 'Process crashed') { throw 'A morte real de processo não foi comprovada.' }
        } elseif ($process.ExitCode -ne 0 -or $result -notmatch 'OK \([1-9][0-9]* tests?\)' -or
            $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "Prova reprovada: $Log" }
    } finally { $process.Dispose() }
}

if ((Invoke-Adb @('get-state')) -ne 'device' -or (Invoke-Adb @('shell','getprop','ro.kernel.qemu')) -ne '1' -or
    ((Invoke-Adb @('emu','avd','name')) -split '\r?\n')[0].Trim() -ne $avd) { throw 'Use somente o AVD reservado.' }
$configuration = Invoke-Adb @('shell','am','get-config')
if ($configuration -notmatch '-round-' -or $configuration -notmatch '-watch-' -or
    (Invoke-Adb @('shell','wm','size')) -ne 'Physical size: 384x384' -or
    (Invoke-Adb @('shell','wm','density')) -ne 'Physical density: 320') { throw 'AVD Wear circular de 192 dp obrigatório.' }
$ancestor = $OutputDirectory
while ($ancestor) {
    if (Test-Path -LiteralPath (Join-Path $ancestor '.git')) { throw 'Evidências devem ficar fora de qualquer repositório.' }
    $parent = [IO.Directory]::GetParent($ancestor)
    $ancestor = if ($null -eq $parent) { $null } else { $parent.FullName }
}
if ((Invoke-Adb @('shell','pm','path',$fixture)) -notmatch '^package:/data/app/.+\.apk$') {
    throw 'Instale primeiro o APK :lab conhecido do WatchReserve no AVD.'
}
$processes = Invoke-Adb @('shell','ps','-A','-o','NAME')
foreach ($name in @('watchreserve_bridge',"$package`.*",'dev.brenopoubel.watchreserve.advancedlab.*',"$fixture`.*")) {
    if ($processes -match "(?m)^$name`$") { throw 'Encerre os processos de ensaio anteriores antes deste lote.' }
}
$apks = @('wear/build/outputs/apk/debug/wear-debug.apk','wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk')
foreach ($path in $apks) {
    if (-not (Test-Path -LiteralPath (Join-Path $bridgeRoot $path) -PathType Leaf)) { throw 'Compile os APKs Wear antes do ensaio.' }
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'initial-fixture-state.txt'), (Get-FixtureState))
$installed = $false; $baseline = $null; $primaryError = $null
$cleanupErrors = [Collections.Generic.List[string]]::new()
try {
    foreach ($path in $apks) { Invoke-Adb @('install','-r',(Join-Path $bridgeRoot $path)) | Out-Null }
    $installed = $true
    Invoke-Probe "$testRoot.JournalCrashProbe#recoverPendingFixtures" (Join-Path $OutputDirectory 'initial-recovery.txt')
    $baseline = Get-FixtureState
    if ($baseline -notin @('0','1')) { throw 'A fixture deve começar habilitada, com DEFAULT ou ENABLED.' }
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'baseline-enabled.txt'), $baseline)
    Invoke-Probe "$testRoot.JournalDeviceTest,$testRoot.AtomicJournalStoreTest" (Join-Path $OutputDirectory 'journals.txt')
    if ((Get-FixtureState) -ne $baseline) { throw 'A prova normal não restaurou o inteiro original.' }
    Invoke-Probe "$testRoot.JournalCrashProbe#interruptAfterFixtureWrite" (Join-Path $OutputDirectory 'expected-death.txt') $true
    $privateRecord = Join-Path $OutputDirectory 'crash-journal-private.properties'
    & $adb -s $Serial exec-out run-as $package cat no_backup/journal-fixtures/crash/restoration/profile.properties > $privateRecord
    if ($LASTEXITCODE -ne 0 -or (Get-Content -LiteralPath $privateRecord -Raw) -notmatch '(?m)^entry\.0\.state=INTENDED\s*$' -or
        (Get-FixtureState) -ne '3') { throw 'Intenção durável e efeito anterior à morte não comprovados.' }
    Invoke-Probe "$testRoot.JournalCrashProbe#recoverPendingFixtures" (Join-Path $OutputDirectory 'after-death-recovery.txt') $false $true
    if ((Get-FixtureState) -ne $baseline) { throw 'Recuperação após morte não restaurou DEFAULT/ENABLED exato.' }
} catch { $primaryError = $_ }
finally {
    if ($installed) {
        try {
            Invoke-Adb @('shell','am','force-stop',"$package.test") | Out-Null
            Invoke-Adb @('shell','am','force-stop',$package) | Out-Null
            Invoke-Probe "$testRoot.JournalCrashProbe#recoverPendingFixtures" (Join-Path $OutputDirectory 'final-recovery.txt')
        } catch { $cleanupErrors.Add("Diários: $($_.Exception.Message)") }
        foreach ($target in @("$package.test",$package)) {
            try { Invoke-Adb @('shell','am','force-stop',$target) | Out-Null }
            catch { $cleanupErrors.Add("Processo: $($_.Exception.Message)") }
        }
    }
    try {
        if ($null -ne $baseline -and (Get-FixtureState) -ne $baseline) { throw 'Inteiro original ainda não restaurado.' }
    } catch { $cleanupErrors.Add("Fixture: $($_.Exception.Message)") }
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'cleanup.json'), (ConvertTo-Json -InputObject @($cleanupErrors)))
}
if ($null -ne $primaryError) { throw $primaryError }
if ($cleanupErrors.Count) { throw ($cleanupErrors -join "`n") }
Write-Output 'PASS: três diários, pacote fixture, morte real e restauração exata; sem controles físicos habilitados.'
