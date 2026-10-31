$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

mvn -DskipTests package
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$app = Join-Path $projectRoot "target\qa-runner-app\Unified QA Runner\Unified QA Runner.exe"
if (-not (Test-Path -LiteralPath $app)) {
    throw "The Unified QA Runner app image was not created: $app"
}

$process = Start-Process -FilePath $app -PassThru -Wait
exit $process.ExitCode
