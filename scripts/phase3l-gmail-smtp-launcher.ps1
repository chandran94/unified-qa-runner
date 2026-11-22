[CmdletBinding()]
param(
    [string] $ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string] $StorageRoot = 'C:\Users\elayachandran\AppData\Local\Temp\vscode-agent-codex-sandbox-EfA3Uy\qa-runner-phase3e-32733901852845dfa3d62916084bc0e1',
    [ValidateRange(1, 65535)]
    [int] $Port = 8081
)

$ErrorActionPreference = 'Stop'

function Fail-Safely([string] $Message) {
    throw $Message
}

$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$storage = [IO.Path]::GetFullPath($StorageRoot)
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())

if (-not (Test-Path -LiteralPath (Join-Path $storage 'qa-runner.json'))) {
    Fail-Safely 'Storage root must contain the existing disposable qa-runner.json.'
}
if (-not $storage.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $storage -notmatch 'vscode-agent-codex-sandbox') {
    Fail-Safely 'Refusing storage outside the task-owned temporary sandbox.'
}
if ($storage -match '(^|[\\/])unified-qa-runner([\\/]|$)' -and $storage -notmatch 'Temp') {
    Fail-Safely 'Refusing a project-root or non-temporary storage path.'
}

$listener = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
if ($null -ne $listener) {
    Fail-Safely "Port $Port is occupied. This launcher never stops an existing process."
}

$java = (Get-Command java -ErrorAction Stop).Source
$classpathFile = Join-Path $project 'target/runtime-classpath.txt'
$classes = Join-Path $project 'target/classes'
if (-not (Test-Path -LiteralPath $classpathFile) -or -not (Test-Path -LiteralPath (Join-Path $classes 'com/example/unifiedqarunner/Main.class'))) {
    Fail-Safely 'Latest compiled classes and runtime-classpath.txt are required.'
}

$sender = Read-Host 'Business Gmail sender address'
if ($sender -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') {
    Fail-Safely 'Sender must be a valid email address.'
}

$securePassword = Read-Host 'Gmail App Password (input is masked)' -AsSecureString
$passwordBstr = [IntPtr]::Zero
$plainPassword = $null
$process = $null
try {
    $passwordBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordBstr)
    if ([string]::IsNullOrWhiteSpace($plainPassword)) {
        Fail-Safely 'Gmail App Password must not be empty.'
    }

    # These values exist only in this launcher process and are inherited by the
    # intended child. They are never included in Java arguments or written to disk.
    $env:QA_RUNNER_SMTP_HOST = 'smtp.gmail.com'
    $env:QA_RUNNER_SMTP_PORT = '587'
    $env:QA_RUNNER_SMTP_STARTTLS = 'true'
    $env:QA_RUNNER_SMTP_SSL = 'false'
    $env:QA_RUNNER_SMTP_USERNAME = $sender
    $env:QA_RUNNER_SMTP_FROM = $sender
    $env:QA_RUNNER_SMTP_PASSWORD = $plainPassword
    $env:QA_RUNNER_LOCAL_ONLY = 'true'
    $env:QA_RUNNER_AUTH_DEV_VERIFICATION = 'false'

    $runtimeClasspath = (Get-Content -LiteralPath $classpathFile -Raw).Trim() + ';' + (Resolve-Path $classes).Path
    $arguments = @(
        '-Xmx512m',
        '-XX:MaxMetaspaceSize=192m',
        '-Djava.awt.headless=true',
        '-cp', $runtimeClasspath,
        'com.example.unifiedqarunner.Main',
        $storage,
        "--port=$Port"
    )
    $stdout = Join-Path $storage 'phase3l-startup.out.log'
    $stderr = Join-Path $storage 'phase3l-startup.err.log'
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $storage `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    Write-Output ("Started isolated Unified QA Runner PID {0}. Logs are task-owned paths." -f $process.Id)
}
finally {
    if ($passwordBstr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordBstr)
    }
    $plainPassword = $null
    $securePassword.Dispose()
    Remove-Item Env:QA_RUNNER_SMTP_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:QA_RUNNER_SMTP_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:QA_RUNNER_SMTP_FROM -ErrorAction SilentlyContinue
    Remove-Item Env:QA_RUNNER_SMTP_HOST -ErrorAction SilentlyContinue
    Remove-Item Env:QA_RUNNER_SMTP_PORT -ErrorAction SilentlyContinue
    Remove-Item Env:QA_RUNNER_SMTP_STARTTLS -ErrorAction SilentlyContinue
    Remove-Item Env:QA_RUNNER_SMTP_SSL -ErrorAction SilentlyContinue
    Remove-Item Env:QA_RUNNER_AUTH_DEV_VERIFICATION -ErrorAction SilentlyContinue
    Remove-Item Env:QA_RUNNER_LOCAL_ONLY -ErrorAction SilentlyContinue
}
