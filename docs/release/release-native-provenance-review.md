# Tracker native dependency: release-specific provenance update

Reviewed 2026-10-15. This supplements `native-source-license-review.json`; it preserves that file and the underlying native evidence unchanged.

## New release-specific source evidence

The official AndroidX Graphics release notes say `androidx.graphics:graphics-path:1.0.1` was released on May 1, 2024, that version 1.0.1 “contains these commits,” and list “Improvements to compiler flags.” The linked official source-history range is:

`4fcd99eacd92d7c73fb1d3580fd423ed7704a98a..8a05a22af450d589ef911d772a001a49dcb05b71`, scoped to `graphics/graphics-path`.

Source: [AndroidX Graphics release notes](https://developer.android.com/jetpack/androidx/releases/graphics#graphics-path). The release-page link was followed once to its Android Gitiles history URL; the fetch returned 403 Forbidden. I did not retry, use a mirror, or try to route around that access denial.

This materially improves provenance: 1.0.1 has an authoritative source-history range, unlike the older GitHub mirror snapshot `794e3806700833665f48f56f7dd3581642a6057f`. It does not prove the published AAR's native objects were built reproducibly from the range. Existing AAR/module/source metadata still has no VCS binding or native source archive; the published sources JAR has only Kotlin sources, per the preserved review.

## What can and cannot be said about static runtime code

The ELF evidence remains: all four ABI libraries list only `libm.so`, `libdl.so`, and `libc.so` in `DT_NEEDED`; `libc++_shared.so` is not dynamically required. This cannot exclude archive objects statically incorporated into the `.so`.

Google's [NDK C++ library guide](https://developer.android.com/ndk/guides/cpp-support) documents that CMake defaults to `c++_static`, describes `libc++_static.a` and `libc++_shared.so`, and identifies LLVM's license family as Apache-2.0 with LLVM Exceptions. This is a relevant risk signal, not proof of the historical AndroidX build setting: no exact release CMake/Gradle configuration, link command, runtime archive-member list, or build attestation was obtained. The `NDK r25c` and LLVM compiler commit strings identify the compiler toolchain, not whether any libc++, libc++abi, libunwind, or compiler-rt code entered the final binary.

The [official LLVM source commit](https://android.googlesource.com/toolchain/llvm-project/%2B/4c603efb0cca074e9238af8b4106c30add4418f6) corroborates the LLVM compiler source revision noted in the ELF. It does not identify AndroidX's source or runtime objects.

## Remaining boundary

- Resolved: official release notes tie 1.0.1 to the `graphics-path` source change range above.
- Still open: inspect that range's actual native/build files; obtain a publisher build attestation or otherwise bind the AAR's four `.so` files to source/build inputs; establish whether and which static runtime objects were retained; check exact historical and any third-party notices applicable to those objects.
- The previous direct requests for the exact LLVM `LICENSE.TXT` files returned 403. They were not retried here and were not replaced with a newer license snapshot. The actual runtime obligations therefore remain undetermined.

This is a provenance narrowing, not a legal opinion or release clearance. In particular, neither the POM's Apache-2.0 label nor the absence of a shared STL dependency demonstrates that no additional native notice is needed.

## Release-era source corroboration, 15 October 2026 follow-up

The official [AndroidX mirror commit 94d93d1](https://github.com/androidx/androidx/commit/94d93d113d20483317b9b8ef83e113d6a400459d) removes redundant stack-protector flags from graphics-path while retaining `-nostdlib++`. Its diff was read in this follow-up. The parent additionally reports reading the exact CMake, three cpp files and headers at that commit, with Apache-2.0 headers; those file contents were not independently reread here.

This corroborates release-era source configuration. It does not establish that the commit belongs to the release-notes range or produced the published AAR. The parent reports Gitiles403 and GitHub404 for that range/endpoints; this pass did not retry them. Exact source/build/link/runtime notice binding stays open, including compiler-rt. Historical artifact hashes and the earlier source review remain unchanged.
