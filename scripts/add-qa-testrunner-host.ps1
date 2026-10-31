# Add QaTestRunner to Windows hosts file
# Run this script as Administrator

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry = "127.0.0.1 QaTestRunner"

# Check if entry already exists
if (Select-String -Path $hostsFile -Pattern "QaTestRunner" -Quiet) {
    Write-Host "QaTestRunner is already in the hosts file."
} else {
    Add-Content -Path $hostsFile -Value "`n$entry" -Force
    Write-Host "Added '$entry' to $hostsFile"
}

Write-Host ""
Write-Host "You can now access the QA Runner at: http://QaTestRunner:51076/"
Write-Host "Press any key to close..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
