# Parent review pass2: interim source-bound evidence

Baselineb64e4fd; the parent is still completing personal review2. This is not
the complete second packet or pass2 acceptance. Raw original XML, source files
and hashes are bound to their exact commits in each verification.json.

-5b06c2b reproduces4core/4Room/4VM failures forP2-001…003.
-414ce40 passes4core/19app (4Room+15ViewModel), including prior stale guards,
  report oracle parity, empty backfill and later empty/uncertain history sources.

Full raw Gradle logs stay with task artifacts. No device/model attestation.

Further exact checkpoints:

- a5b4a06 reproduces4 technical lifecycle +2 post-commit STORAGE failures;
  58ce0f8 passes8app including prior chooser/export lifecycle tests.
- fad946c reproduces6 surface publication races +4 wrong-segment/note VM starts.
- 84d9389 partial run has1core fixture failure (START never promised Undo) and
  an explicitly interrupted ActivityScenarioRule cleanup; no stale app XML claimed.
- cd963af passes1core/1Room exact-segment scenario,6surface races and19VM checks.
  Its4 UiDraft failures include2 real P2-009 overwrites and2 selector fixtures.
- 6e46613 reproduces2 P2-008 missing forms AFTER actual controller recreation.
- 48e460e passes38/40 app including9surface/21VM checks; the2 final Card-ID
  assertions exposed an ambiguous test click on underlying home text; assertions
  were retained and the fixture was corrected.
  53480c2's2 search-selector failures matched the search TextField itself; corrected
  by a partial query and unique card result, with an added no-domain-write assertion.
- 68f4d7c passes all4 actual Activity draft/form recreation tests. Its active chooser
  manual cancellation also reproduces a duplicate chooser. The other preparation
  test initially blocked the paused main launcher;51e5f2b corrects that fixture and
  reproduces the actual CANCELLED/resultReceived=false before any chooser.
- 9f30a79 passes8app: all6 technical lifecycle/cancellation cases, prior primary UI
  export lifecycle and private-copy chooser protection. Active writers/foreign
  references remain protected; cancelled prepared bytes can register normally.

All model/device/linguistic/third-person-review claims remain excluded. The parent
independently accepted the first three findings'414ce40/fb881c7 source-bound evidence;
this is not general pass2 acceptance. Full raw Gradle logs stay in the artifact.

Final aggregate on5289806d3cf5a373f5cbaa5889e64c32783777bc executes194core +216app,
zero failures/errors/skips,3m17s. Fingerprint Python3/3 passes.
The preceding ff01ee6 aggregate passed194core but5 of216app checks reached a host
JNI classloader collision (same ELF path already owned by another SDK sandbox).
5289806 changes only that test fixture: a unique path with byte/hash-identical ELF.
All216app now pass together; no production native/Android binding was changed.
F-Droid disabled actual-source recipe lint/scanner exit0 with1 retained gzip warning.
The source/runtime is not an independent physical-device/model/reproducibility claim.

Unsigned build34351d1 completes3m19s; lint0errors/0fatal/69warnings. The one new
warning is SetTextI18n in the existing technical error status, without suppression.
Both APKs pass CRC/unsigned/zipalign-P16/min26/target36, embed all24 locale assets
byte-identical to baselineb64, and contain all6 newly added backend API symbols.
All65 external runtime hashes match prior declarations (66 artifacts incl owncore).
Tests5289806 and build34351d1 have290 identical critical source/build/schema files;
their Git diff contains only documentation/evidence/recipe metadata. APK hashes,
actual commands and all9 finding results are in final-result.json.
