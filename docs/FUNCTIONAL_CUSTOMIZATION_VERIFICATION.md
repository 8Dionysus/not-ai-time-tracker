# Functional customization verification, 15 October 2026

Runtime/build source: `fe0e490a932f3a789e1a7b78de575634ec6fbbef`.
The subsequent delivery commit changes documentation, evidence and the disabled
F-Droid recipe only. APK embedded VCS identifies that exact runtime source.
Requested model/effort: gpt-6.1-sol/high; no independent runtime attestation.

The [requirements matrix](CUSTOMIZATION_REQUIREMENTS_MATRIX.md) and
[Opus API contract](FUNCTIONAL_CUSTOMIZATION_HANDOFF.md) describe the actual
existing and new APIs. Own categories/subcategories and named Cards preserve
history. Standalone catalog roots now use the existing selective engine, including
closure, conflict/COPY, origin retention, exact re-export, interruption/resume and
Undo. Functional schedule day/home-zone/quiet-shade preferences persist through
Room8/JSON10. Full restore previews expose immutable incoming settings/schedule.
The complete prepared notice projection is rechecked before submission.
Colors, themes and their model remain deferred to the user and Opus.

## Exact checks

```sh
/workspace/tracker-tools/run-gradle :core:test :app:testDebugUnitTest :core:scheduleDeliveryProfile :app:assembleDebug :app:assembleRelease :app:lintRelease :app:releaseInventory -PtrackerUnsigned=true -PtrackerCleanupLibrary=/workspace/tracker-tools/libtracker-copy-graph-final.so -Pkotlin.compiler.execution.strategy=in-process
```

BUILD SUCCESSFUL in6m58s. Core83/app67, failures/errors/skips0. Room and Memory
run the same new contracts; app checks are JVM/Robolectric, not a phone. The extra
unit cases pin compiled-v6 hash vectors, canonical root IDs, duplicate/missing
references, v1/v2 admission, typed errors, DST boundary instants, immutable previews,
legacy-field rejection and a2000-node own hierarchy. Real Room7→8 fixtures cover
every SessionState and rollback of falsely versioned future metadata. Earlier
failed setup/old-version assertions were corrected before this exact-source run.

Release lint:0errors,60warnings,1hint. Unsigned APKs:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| debug | 13222863 | `34c781ad9e3384663499b1bed3542322b659e9231ad6bc84c3d75167dc2e8dd3` |
| release | 8850560 | `e6c344170f375ad1db6040191d08d505a3d7e47409dd3d714d006c8d084d31a9` |

Both pass zipalign with16KiB alignment. apksigner verify returns1 as expected for
unsigned APKs; no release key was created. Compile/target36, min26. Merged manifest
has no INTERNET permission. All8ELF/fourABI hashes and readelf outputs are recorded.
The own-C source blob is unchanged from the historical0-diagnostic analysis at
5e43515; this is not a new Android JNI execution or a new analyzer run.

Resolved release graph:66 including own core,65 external POM declarations,
0unknown;27 supplied license/notice members. Generated notices match the current
APK bytes. Native release/static-runtime rights binding remains open. F-Droid2.4.5
contextual lint exits0 for the current disabled recipe pinned to runtime source,
and an enabled isolated fixture. No independent F-Droid build/reproducibility claim.

Raw XML, full/isolated logs, profiles, APK verification, native inspection and the
fresh device-target recheck are in
[evidence/functional-customization](../evidence/functional-customization/artifact-verification.json).
The full delivery ZIP additionally retains prior runtime evidence and current APKs.

## Receipt capacity remains an explicit limit

A separate profile-only run succeeded in20s with no competing full build. Its
0/1000/10000/47392-receipt median sync times are2.251/120.286/301.978/1042.419ms.
The last fixture is exactly16777216UTF-8bytes; the count is fixture-dependent,
not a guaranteed capacity for arbitrary IDs. Three unchanged syncs write no state.
The next reserve refuses before post, keeps all receipt bytes and disarms hooks.
The full-build-contended measurement is preserved separately. These are host JVM
measurements with1GiB max heap, not phone PSS, retained heap, battery or latency.
The O(n) decode/index/full-file write architecture is unchanged. An indexed durable
journal requires exact identities, compatible migration and crash-safe semantics;
no receipts were discarded and no retention policy was invented.

## Remaining acceptance

- Production deletion remains disabled pending trusted Android root/device QA,
  all-writer/lifecycle durable-reference adoption and legacy/final-seal binding.
  Native descriptor anchoring does not prove atomic compare-and-rename/unlink
  against a noncooperating same-UID writer.
- Opus owns UI catalog-root/dependency/conflict flows and functional settings,
  schedule renderer/channel/startup/three surfaces, typed Focus outcome handling,
  atomic switchDraft caller and explicit CSV sourceLocale. No protected UI source
  changed from f14fa3c; colors/themes remain outside this pass.
- Catalog translation editorial review/UI resource migration remain open. The two
  new CoreError reason drafts are not new CSV issues or reviewed translations.
- No agreed usable emulator/system image/AVD or KVM exists in the selected workspace;
  no device/OEM/TalkBack, overlayP2/goal-edit or phone resource acceptance is claimed.
  Independent F-Droid reproduction and native release rights binding remain open.

The prior A–F implementation/limitations remain in
[BACKEND_AUDIT_HANDOFF.md](BACKEND_AUDIT_HANDOFF.md); this checkpoint adds the
functional interfaces and migrations rather than accepting the entire product.
