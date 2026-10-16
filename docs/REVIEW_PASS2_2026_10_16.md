# Parent personal review pass2 — initial findings

Reviewed baseline: `b64e4fd68ba97a78ee4b773cce9f5a3d391107f7`.
The parent accepted pass1 and continues a full personal pass2. P2-001…003 are
an initial partial packet; neither completion of pass2 nor the final review packet
is claimed here. Work remains on `fix/review-pass1-2026-10-16`; main is unchanged.

| Finding | Required correction | Focused evidence |
| --- | --- | --- |
| P2-001 report index | A certain empty identity has no interval and must not enter the closed-interval binary-search index. Its end may sit inside a longer fact, violating the assumed end monotonicity. Keep source facts/IDs/notes unchanged. | Shared Memory/Room exact `[1000,11000]` + empty`[6000,6000]`, window`[7000,10000]`=3000ms; more bounds, filters, source ordering, cached/reopened state and full Report/calendar oracle parity. |
| P2-002 start backfill | The overlap guard ignores a closed empty identity, while real positive/uncertain obstacles still refuse OVERLAP/RECOVERY_REQUIRED. No automatic backfill and no marker removal. | Shared Memory/Room empty6000, active start10000, proposed5000; preview no writes, explicit apply, running anchor/marker/note preservation, Undo, reopen and full restore. Negative positive/uncertain obstacles retained. |
| P2-003 history choice | When a classification has a positive certain closed occurrence, its canonical history representative uses the same latest-recorded ordering as suggestions. Empty/uncertain-only variants remain searchable with their literal source. Strict ViewModel token/reference/entry guards remain. | Shared Memory/Room recent/quick/search/reference round-trip after later empty/uncertain records, reopen and read purity. Four real ViewModel quick/recent starts verify original source note, successful guarded start and exact prior history preservation. |

No Room/archive/selective format change, origin rewrite, user data operation,
frontend redesign or additional model executor is required. Exact failure/success
XML and actual source SHA will be recorded separately after execution. Physical
Android/OEM/TalkBack/RTL and the parent's further findings remain separate.
