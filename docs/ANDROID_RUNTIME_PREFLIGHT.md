# One authorized Android runtime preflight, 15 October 2026

One official AOSP launch in the existing Dionysus environment, with isolated SDK,
AVD and service directories. No new executor/environment, Google Play/account,
personal data, APK installation, release key or host permission/security change.
The user explicitly authorized the official SDK terms for this limited attempt.

Official sources: [SDK terms](https://developer.android.com/studio/terms),
[command-line emulator](https://developer.android.com/studio/run/emulator-commandline).
The documented unsupported/slow software mode was actually tried with
ANDROID_I_WANT_MY_TCG=yes and -accel off; lack of KVM was not treated as proof that
software emulation cannot run.

| Component | Exact selected package |
|---|---|
| Emulator | stable37.2.12.0, build16428233, official Linux x64 archive |
| Image | system-images;android-36;default;x86_64, revision2, extension17; AOSP/default, no Play |
| adb | existing official platform-tools37.0.0, copied into the isolated SDK |
| License | android-sdk-license; full selected package URLs/checksums/license hashes in evidence |

Both downloaded archives matched the official repository byte count/SHA-1; SHA-256
and selected package/source properties were recorded. SDK/image binaries are not
included in the source/release delivery. The AVD used2cores,2048MiB,480x800,
SwiftShader, no window/audio/snapshots/boot animation/metrics. Exact argv and process
environment are in [command.json](../evidence/android-runtime-preflight/command.json).
HOME and read-only/root-owned directories were not changed or bypassed.

## Observed result

- QEMU/TCG executed the Android kernel, init, APEX setup, zygote and system_server.
  The root serial console answered getprop. sys.boot_completed remained empty
  through the600s budget; the probe initiated shutdown at the deadline. Recorded
  process exit-6 is after that termination, not a separately diagnosed boot crash.
- The official host adb aborted trying to mkdir /home/agent/.android on a read-only
  filesystem, despite supported Android service-directory variables and a writable
  log path. It was not made usable by changing HOME, host permissions or security.
- A standard direct ADB protocol probe to the existing empty guest returned CNXN
  and executed shell commands without an authentication challenge being answered.
  This proves the guest transport responded; it does not make the official host
  adb client usable or establish a fully booted target.
- ART -version was an incorrect parameter and was rejected. The corrected
  -showversion command returned ART version2.1.0 x86_64/exit0. That flag exits before
  main. A tiny own Dex fixture was staged only into the empty test guest; its Java
  main did not run. The later main-only probe reached the already stopped target
  and got connection refused. The initial helper's overly broad “fixture executed”
  label is retained as explicitly unverified historical metadata and corrected in
  the final verification; raw output and javaMainRan=false are authoritative.

No second image/launch or app/JNI/UI/device scenario followed. No phone/OEM,
notification, cleanup-root or Java-main acceptance is claimed. Kernel/init/guest ADB
progress is a partial result, not successful end-to-end Android QA and not proof
that a longer software boot would be impossible. Further device scenarios require
the separate authorization requested by the coordinator.

Raw logs, metadata, commands, own fixture source and hashes are in
[verification.json](../evidence/android-runtime-preflight/verification.json).
The exact backend checkpoint saved before launch was
1d37545586268e594d90f317b007a117be5b06ff; backend work continued independently.
