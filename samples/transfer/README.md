# Synthetic CSV examples

These files contain synthetic data and exercise documented input schemas; they are not personal exports.

- [schema-a.csv](schema-a.csv): comma delimiter and ISO timestamps with offsets.
- [schema-b.csv](schema-b.csv): semicolon delimiter, explicit local timestamp pattern and duration in seconds.
- [toggl-supported.csv](toggl-supported.csv): columns supported by the compatibility preset described in [HistoryTransfer](../../docs/BACKEND_V10.md).

For the Toggl fixture call `HistoryTransfer.parseToggl(text, "example:workspace", "Europe/Berlin")`. The interval is `2026-10-09T08:00:00Z..08:20:00Z`, 1,200,000 ms. Choose an explicit Classification for `SourceActivity("Language", "Personal")` before preview/apply.

The general [inspect → mapping → preview → apply](../../docs/FILE_IMPORT.md) path does not require a vendor name. A synthetic fixture does not establish compatibility with every localization/export version of an external tracker.
