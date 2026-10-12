# Gate 14.1 — Receiver State + Stable Development Signing

## Goal

Polish the physical Gate 14 path without changing the AIRNOTE protocol.

Gate 14.1 addresses two issues discovered during real Seeker acceptance:

1. an active receiver request could be visually re-enabled after RECEIVE was restored, making it too easy to accidentally replace the nonce before Scan 2;
2. GitHub Actions debug APKs were signed by ephemeral runner debug keys, so installing a later CI artifact could require uninstalling AIRNOTE and deleting local StrongBox-backed note state.

## Receiver-state hardening

An active receiver request remains locked until it is either accepted or expires.

While a handoff is active:

- CHANGE RECEIVE WALLET is disabled;
- SHOW REQUEST QR is disabled;
- the exact persisted request/nonce remains visible;
- SCAN PAYMENT — 2 OF 2 remains enabled.

After a successful offline handoff, the request/session is cleared and the request action becomes an explicit **RECEIVE ANOTHER AIRNOTE** action rather than ambiguously showing the original Scan 1 label.

No protocol bytes, signatures, recipient binding rules, or settlement behavior change.

## Stable development signing

The Android build optionally accepts a stable keystore through environment variables:

- `AIRNOTE_ANDROID_KEYSTORE_PATH`
- `AIRNOTE_ANDROID_KEYSTORE_PASSWORD`
- `AIRNOTE_ANDROID_KEY_ALIAS`
- `AIRNOTE_ANDROID_KEY_PASSWORD`

The build fails if stable signing is only partially configured.

GitHub Actions optionally consumes these repository secrets:

- `AIRNOTE_ANDROID_KEYSTORE_B64`
- `AIRNOTE_ANDROID_KEYSTORE_PASSWORD`
- `AIRNOTE_ANDROID_KEY_ALIAS`
- `AIRNOTE_ANDROID_KEY_PASSWORD`

If no keystore secret exists, CI remains green and falls back to the runner debug key. Once all four secrets are configured, CI debug artifacts use the stable key.

For hackathon/devnet development, the simplest way to make local and CI APKs update-compatible is to use the developer machine's existing Android debug keystore as the CI development signing key. This is **not** production release signing.

Typical local debug-keystore values are:

- file: `~/.android/debug.keystore`
- alias: `androiddebugkey`
- store password: `android`
- key password: `android`

The keystore itself must never be committed. `*.jks` and `*.keystore` are ignored by Git.

## Acceptance

Gate 14.1 passes when:

1. Android + Solana CI are green.
2. RECEIVE → request → leave activity → return preserves the same request and keeps Scan 1 controls locked.
3. Successful Scan 2 relabels the next action to RECEIVE ANOTHER AIRNOTE.
4. After the stable CI signing secrets are configured from the same development key, a CI APK installs over the local build with `adb install -r` without signature mismatch.
