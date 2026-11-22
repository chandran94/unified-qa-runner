# Phase 3L — Controlled Gmail SMTP Restart Checklist

Status: **PLAN ONLY — NOT EXECUTED**

1. Confirm the operator has separately authorized one restart and one test email.
2. Confirm PID `18360` is still `com.example.unifiedqarunner.Main`, owns `127.0.0.1:8081`, and uses the disposable Phase 3E root.
3. Confirm no production `qa-runner.json`, database, account store, or credential source is selected.
4. Confirm `target/classes/com/example/unifiedqarunner/Main.class` and `target/runtime-classpath.txt` exist.
5. Confirm the single authorized personal Gmail recipient privately with the operator. Do not record it in source or reports.
6. Run the launcher privately. Enter only the authorized business sender and Gmail App Password at its masked prompts.
7. Verify the new listener is `127.0.0.1:8081` and the new PID uses the same disposable root. Do not continue on wildcard binding.
8. Register exactly one disposable synthetic account to the authorized recipient. Do not retry automatically.
9. Ask the operator to inspect their Gmail inbox and enter the OTP in the normal browser UI. Never request the OTP in chat and never read the inbox automatically.
10. Verify activation, login, logout, second login, CSRF, and the approved User Story smoke flow.
11. Record only statuses, sanitized error categories, PID, listener, storage-isolation evidence, and test counts.
12. Keep the process running only if healthy, isolated, and loopback-bound. Do not commit, push, deploy, or send any additional email.
