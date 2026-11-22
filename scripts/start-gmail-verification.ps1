[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $ProjectRoot,
    [Parameter(Mandatory)]
    [string] $StorageRoot,
    [ValidateRange(1, 65535)]
    [int] $Port = 8081
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath
$storage = (Resolve-Path -LiteralPath $StorageRoot).ProviderPath
$artifact = Join-Path $project 'target/unified-qa-runner-1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath (Join-Path $project 'pom.xml') -PathType Leaf) -or
    -not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
    throw 'Select the Maven project with the packaged Unified QA Runner JAR.'
}
if (-not (Test-Path -LiteralPath (Join-Path $storage 'qa-runner.json') -PathType Leaf)) {
    throw 'Select the existing data directory containing qa-runner.json.'
}
if ($project -ne $storage -and (
        (Test-Path -LiteralPath (Join-Path $project 'qa-runner.json') -PathType Leaf) -or
        (Test-Path -LiteralPath (Join-Path $project '.qa-organizations.json') -PathType Leaf) -or
        (Test-Path -LiteralPath (Join-Path $project '.qa-attachments') -PathType Container))) {
    throw 'The project already contains application data. Confirm the original data directory before starting.'
}
$portCheck = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
$portCheck.ExclusiveAddressUse = $true
try {
    $portCheck.Start()
} catch [Net.Sockets.SocketException] {
    throw "Port $Port is already in use or cannot be bound."
} finally {
    $portCheck.Stop()
}

$java = Join-Path $project '.jdk25/jdk-25.0.2/bin/java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    $java = (Get-Command java.exe -CommandType Application -ErrorAction Stop).Source
}
$sender = (Read-Host 'Gmail sender address').Trim()
if ($sender -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') {
    throw 'Enter a valid email address.'
}

$launch = [Diagnostics.ProcessStartInfo]::new()
$launch.FileName = $java
$launch.WorkingDirectory = $project
$launch.UseShellExecute = $false
$launch.Arguments = '-Djava.awt.headless=true -jar "{0}" "{1}" --port={2}' -f $artifact, $project, $Port
$launch.EnvironmentVariables['QA_RUNNER_DATA_DIR'] = $storage
$launch.EnvironmentVariables['QA_RUNNER_LOCAL_ONLY'] = 'true'
$launch.EnvironmentVariables['QA_RUNNER_AUTH_DEV_VERIFICATION'] = 'false'
$launch.EnvironmentVariables['QA_RUNNER_SMTP_HOST'] = 'smtp.gmail.com'
$launch.EnvironmentVariables['QA_RUNNER_SMTP_PORT'] = '587'
$launch.EnvironmentVariables['QA_RUNNER_SMTP_STARTTLS'] = 'true'
$launch.EnvironmentVariables['QA_RUNNER_SMTP_SSL'] = 'false'
$launch.EnvironmentVariables['QA_RUNNER_SMTP_USERNAME'] = $sender
$launch.EnvironmentVariables['QA_RUNNER_SMTP_FROM'] = $sender

$securePassword = Read-Host 'Gmail App Password' -AsSecureString
$passwordPointer = [IntPtr]::Zero
try {
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer) -replace '\s', ''
    if ([string]::IsNullOrEmpty($password)) {
        throw 'The Gmail App Password is required.'
    }
    $launch.EnvironmentVariables['QA_RUNNER_SMTP_PASSWORD'] = $password
    $process = [Diagnostics.Process]::Start($launch)
} finally {
    $launch.EnvironmentVariables.Remove('QA_RUNNER_SMTP_PASSWORD')
    $password = $null
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $securePassword.Dispose()
}

try {
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Unified QA Runner exited with code $($process.ExitCode)."
    }
} finally {
    $process.Dispose()
}
