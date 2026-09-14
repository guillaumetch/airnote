# AIRNOTE Gate 00 — Seeker Result

Date: 2026-09-14
Device: Solana Mobile Seeker, Android 16 / API 36

## Result

**FAIL for the original hardware-enforced one-use-key thesis.**

The Seeker generated a P-256 key in AndroidKeyStore with `maxUsageCount = 1`. The private key was non-exportable and the key itself was backed by `TRUSTED_ENVIRONMENT`. The first signature succeeded and independently verified. A second signature attempt failed with `KeyPermanentlyInvalidatedException`, and the alias disappeared from AndroidKeyStore.

However, Android Key Attestation conclusively showed:

- `usageCountLimit softwareEnforced: 1`
- `usageCountLimit hardwareEnforced: ABSENT`
- `FEATURE_KEYSTORE_SINGLE_USE_KEY: false`
- `FEATURE_KEYSTORE_LIMITED_USE_KEY: false`

Therefore, the one-use counter is enforced by Android software rather than TEE/StrongBox. This does not satisfy AIRNOTE's original threat model, which required secure hardware itself to prevent a malicious payer from reusing the same offline credential.

## What remains valid

- P-256 private keys can be hardware-backed on Seeker.
- Hardware attestation works and reports `TRUSTED_ENVIRONMENT`.
- The attestation challenge matches.
- P-256 signatures can be produced in a Solana-compatible compact form.
- The stock Seeker/Android runtime does enforce a one-use key behavior under normal conditions.

## Security implication

AIRNOTE must not claim hardware-enforced offline double-spend prevention using Android `maxUsageCount` on Seeker.

The next design should either:

1. adopt a risk-bounded / hybrid offline model that explicitly assumes an uncompromised, verified Android OS and limits offline exposure, or
2. find another secure-state primitive that provides hardware-enforced anti-rollback / monotonic state suitable for double-spend prevention.

Gate 00 successfully falsified the original assumption before payment logic was built.
