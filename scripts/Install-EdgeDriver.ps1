# Install-EdgeDriver.ps1
# This script downloads and installs Microsoft Edge WebDriver (msedgedriver.exe)
# Run this script as Administrator if you want to add it to system PATH

param(
    [string]$InstallPath = "C:\Users\elayachandran\IdeaProjects\Sample_project\drivers",
    [switch]$AddToPath
)

Write-Output "=== Microsoft Edge WebDriver Installer ==="

# Get Edge browser version
$edgePaths = @(
    "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
)

$edgeExe = $edgePaths | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edgeExe) {
    Write-Error "Microsoft Edge not found. Please install Edge first."
    exit 1
}

$edgeVersion = (Get-Item $edgeExe).VersionInfo.ProductVersion
Write-Output "Detected Edge version: $edgeVersion"

# Extract major.minor.build (e.g., 156.0.4314.50)
$versionParts = $edgeVersion.Split('.')
$driverVersion = "$($versionParts[0]).$($versionParts[1]).$($versionParts[2]).$($versionParts[3])"
Write-Output "Looking for msedgedriver version: $driverVersion"

# Create install directory
if (-not (Test-Path $InstallPath)) {
    New-Item -ItemType Directory -Force -Path $InstallPath | Out-Null
}

# Download URLs to try (in order of preference)
$downloadUrls = @(
    "https://msedgedriver.azureedge.net/$driverVersion/edgedriver_win64.zip",
    "https://msedgedriver.azureedge.net/LATEST_RELEASE_156_WINDOWS"
)

$zipPath = Join-Path $InstallPath "edgedriver_win64.zip"
$downloaded = $false

foreach ($url in $downloadUrls) {
    Write-Output "Trying: $url"
    try {
        Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing -TimeoutSec 30
        $size = (Get-Item $zipPath).Length
        if ($size -gt 1000000) {
            Write-Output "Successfully downloaded ($size bytes)"
            $downloaded = $true
            break
        } else {
            Write-Output "File too small, trying next URL..."
            Remove-Item $zipPath -Force
        }
    } catch {
        Write-Output "Failed: $($_.Exception.Message)"
        if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    }
}

if ($downloaded) {
    # Extract msedgedriver.exe
    Expand-Archive -Path $zipPath -DestinationPath $InstallPath -Force
    $driverExe = Join-Path $InstallPath "msedgedriver.exe"
    
    if (Test-Path $driverExe) {
        Write-Output ""
        Write-Output "=== Installation Successful ==="
        Write-Output "msedgedriver.exe installed to: $driverExe"
        Write-Output ""
        
        # Verify version
        Write-Output "Driver version:"
        & $driverExe --version
        
        if ($AddToPath) {
            Write-Output ""
            Write-Output "Adding to system PATH..."
            $currentPath = [Environment]::GetEnvironmentVariable("Path", "Machine")
            if ($currentPath -notlike "*$InstallPath*") {
                [Environment]::SetEnvironmentVariable("Path", "$currentPath;$InstallPath", "Machine")
                Write-Output "Added to system PATH. Restart your terminal to apply."
            }
        }
        
        Write-Output ""
        Write-Output "You can now run Edge tests from the Unified QA Runner UI."
    } else {
        Write-Error "msedgedriver.exe not found after extraction."
        exit 1
    }
} else {
    Write-Output ""
    Write-Output "=== Automatic Download Failed ==="
    Write-Output ""
    Write-Output "The Azure CDN is blocked on your network. Please download manually:"
    Write-Output ""
    Write-Output "1. Open this URL in your browser:"
    Write-Output "   https://developer.microsoft.com/en-us/microsoft-edge/tools/webdriver/"
    Write-Output ""
    Write-Output "2. Find version $driverVersion (or closest matching version)"
    Write-Output "3. Click 'x64' to download edgedriver_win64.zip"
    Write-Output ""
    Write-Output "4. Extract msedgedriver.exe and place it in:"
    Write-Output "   $InstallPath\msedgedriver.exe"
    Write-Output ""
    Write-Output "5. Then run your Edge tests from the Unified QA Runner UI."
    exit 1
}
