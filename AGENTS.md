# not-ai-time-tracker
Keep the domain core independent of Android UI. KISS/Pareto/DRY; no servers or model APIs.
All mutations go through Tracker and StateStore transactions. UI must not calculate durations or write rows.
Use three integrated scenario tests; add tests only for concrete defects. Never claim a device check from JVM tests.
Report zone is explicit; Android automatic backup is disabled. Manual JSON restore is replacement with review and rollback.
No additional agents unless the user explicitly asks. Do not modify sibling repositories.
