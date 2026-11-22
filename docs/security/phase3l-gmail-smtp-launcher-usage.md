# Phase 3L — Secure Gmail SMTP Launcher Usage

Status: **PREPARED ONLY — NOT EXECUTED**

Script: [`scripts/phase3l-gmail-smtp-launcher.ps1`](../../scripts/phase3l-gmail-smtp-launcher.ps1)

The launcher uses the existing `com.example.unifiedqarunner.Main` entry point, the existing `target/runtime-classpath.txt`, the current disposable storage root, and `--port=8081`. It refuses to start if port 8081 is occupied and never stops an existing process.

Run only during an explicitly approved restart window from PowerShell:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\phase3l-gmail-smtp-launcher.ps1
```

The launcher prompts for the business sender address and masked Gmail App Password. It sets SMTP values only in the launcher process environment, starts the intended Java child, and removes those environment variables from the launcher process afterward. The password is not an argument, file, log entry, or report value.

Required Gmail settings are `smtp.gmail.com`, port `587`, STARTTLS `true`, SSL `false`, and matching sender/from address. Use a Google App Password, never the normal Google account password.

The OTP recipient is entered only through the normal future registration flow. It is not hardcoded in this script.

Limitations: process environment variables can be observed by sufficiently privileged local tools while the child is running; this launcher does not provide OS-level secret isolation. Run it only on a trusted workstation, close unrelated diagnostic tools, and do not paste secrets into chat or source control.
