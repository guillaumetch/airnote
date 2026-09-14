# AIRNOTE

AIRNOTE is a Solana Mobile experiment for hardware-backed, USDC-funded offline payments using single-use payment credentials on Seeker.

## Gate 00 — Seeker hardware feasibility

Gate 00 exists to validate the project's foundational assumption before any payment UI, Solana program, USDC flow, QR transport, or networking is built.

The probe checks whether a physical Solana Seeker can:

- expose Android's single-use keystore capability;
- generate a P-256 key inside `AndroidKeyStore`;
- report a hardware security level (TEE or StrongBox);
- sign one deterministic AIRNOTE test message;
- convert ECDSA DER output to Solana-compatible compact `r || s` format;
- normalize `s` to low-S form and verify independently;
- reject a second signature attempt with the exact same key;
- keep the private key non-exportable.

The current implementation intentionally returns **INCONCLUSIVE** until the Android Key Attestation extension is parsed and `usageCountLimit = 1` is shown in the hardware-enforced authorization list. Runtime single-use behavior alone is not enough to claim AIRNOTE's security assumption is proven.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Android build tools / Gradle environment

From the repository root:

```bash
gradle test
gradle assembleDebug
```

The debug APK is expected at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on the physical Seeker:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open **AIRNOTE Probe** and run **RUN COMPLETE PROBE**.

Do not treat emulator results as evidence of AIRNOTE feasibility.

## Security posture

Gate 00 must never silently fall back to an ordinary software-generated private key. It must never export or persist private-key material, and it must never fake single-use behavior with an application-level boolean. A PASS is only valid when the device itself enforces the security property.

## Status

- Repository initialized
- Native Android Gate 00 probe implemented
- P-256 signature conversion + low-S tests implemented
- Physical Seeker run pending
- Full Android Key Attestation parsing pending
