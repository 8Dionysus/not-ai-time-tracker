# Repository guidance

- Keep the Kotlin domain core independent of Android UI and external services.
- Route every data mutation through Tracker and a StateStore transaction. UI must not compute domain durations or write database rows.
- Extend the three shared integration scenarios for concrete defects and changed boundaries. Keep Memory and Room behavior comparable.
- Distinguish JVM/Robolectric checks, APK build, lint and physical-device verification in reports.
- Keep report zones explicit. Backup restore requires preview, explicit confirmation, verified rollback and atomic replacement.
- Derive documentation claims from current code. Check local links and format versions; keep historical evidence clearly labeled and preserve raw sources/logs.
- Use a nonpersonal commit email. Do not commit credentials, SDK paths or local.properties.
- Do not modify sibling repositories or start additional agents without the user's explicit instruction.
