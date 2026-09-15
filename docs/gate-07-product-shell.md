# AIRNOTE Gate 07 — Product shell

Gate 07 begins the transition from a protocol lab into a judge-ready AIRNOTE application without changing the underlying payment cryptography.

## Scope

- production-facing AIRNOTE home screen
- primary actions: Send Airnote / Receive Airnote / Create Airnote
- sender-configurable amount and lifetime remain available
- local summary for prepared notes and pending offline acceptances
- Gate 00 / raw StrongBox tooling moved behind a dedicated Developer Tools screen
- send/receive copy rewritten as a deliberate two-step offline handshake rather than Seeker A/B lab instructions
- no protocol, wire-format, P-256, certificate, settlement, or replay-rule changes

## UX model

AIRNOTE intentionally keeps the two-QR offline handshake:

1. receiver shows a fresh request QR containing wallet + nonce + request TTL;
2. sender StrongBox-signs that exact request for a certified Airnote;
3. receiver scans the signed payment and accepts it offline;
4. Solana remains final settlement authority after reconnection.

The second scan is not treated as accidental friction. It is the step that delivers the receiver-bound hardware-signed payment.

## Product states

Current UI exposes the meaningful states already proven by the protocol:

- ready to fund
- Airnote ready
- signed offline
- accepted offline
- pending Solana settlement
- rejected / duplicate

A later product pass can add a richer note history and automatic online settlement without changing the Gate 07 security model.

## Deferred acceptance

The combined Gate 05 + Gate 06 physical proof is intentionally deferred. Gate 07 may proceed in parallel because it does not change those protocol semantics.

Before calling the stacked protocol/product release complete, still prove on physical hardware:

- sender-selected non-default note lifetime (target: 37 minutes)
- request TTL expires after a valid offline handoff
- the same signed transfer settles while the Airnote itself is still live
- recipient receives USDC
- replay rejects with `NoteConsumed`

## Acceptance for Gate 07 code

- Android unit tests green
- debug APK builds
- Solana regression suite remains green
- no change to canonical QR payloads or settlement authorization
- developer-only diagnostics are not on the normal payment path
