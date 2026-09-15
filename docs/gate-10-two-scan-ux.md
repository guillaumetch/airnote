# Gate 10 — Two-scan payment choreography

## Goal

Make the existing offline AIRNOTE handshake immediately understandable in the product UI without changing any protocol, cryptographic, QR, settlement, replay, or lifetime semantics.

The normal offline exchange remains exactly two QR scans:

1. Receiver shows a recipient-bound request QR. Sender scans it.
2. Sender returns the StrongBox P-256-authorized Airnote transfer QR. Receiver scans it.

After scan 2, the receiver has locally accepted the Airnote without network access. Settlement remains a separate online action and becomes final only after Solana finalization.

## Product changes

### Receiver

- labels the request as `SCAN 1 OF 2`
- labels the signed Airnote as `SCAN 2 OF 2`
- explicitly says that neither scan requires internet
- shows `OFFLINE HANDOFF COMPLETE` only after the existing Gate 04 verification succeeds
- exposes `OPEN PAYMENT / SETTLE` immediately after acceptance
- keeps Activity available as the persistent history surface
- leaves raw settlement payload copy as a technical fallback rather than the normal path

### Sender

- labels the receiver request as `SCAN 1 OF 2`
- labels the returned Airnote as `SCAN 2 OF 2`
- keeps the signed QR visible for receiver rescans
- once a transfer is signed, disables rebinding that loaded Airnote to another recipient from the same screen
- a new recipient handoff requires loading a fresh funded Airnote

The sender lock is a local product guardrail. On-chain replay / redemption protection remains the protocol source of truth.

### Home

- Send / Receive / Activity are first-class actions
- copy explains the two-scan offline model
- local status now counts Gate 08 payment lifecycle records rather than only legacy Gate 04 preference keys

## Invariants

Gate 10 does not change:

- request QR encoding
- transfer QR encoding
- canonical note or transfer bytes
- StrongBox P-256 key generation or signing
- recipient binding
- request nonce or request TTL rules
- sender lifetime / note expiry rules
- mint certificate verification
- Gate 09 Mobile Wallet Adapter settlement
- `redeem_note`
- on-chain replay protection

## Demo flow

Receiver:

`Receive Airnote -> Show request QR (1/2)`

Sender:

`Send Airnote -> Load funded Airnote -> Scan receiver request (1/2) -> Show signed payment QR (2/2)`

Receiver:

`Scan signed payment (2/2) -> Offline handoff complete -> Open payment / settle`

When connectivity is available:

`Settle on Solana -> wallet approval -> submitted -> finalized -> Settled`

## Gate status

Gate 10 is an implementation / UX gate. It does not replace the deferred physical proof requirements from Gates 05/06 or the live Seeker settlement proof required by Gate 09.

Do not call the stacked release physically proven until those device tests pass.
