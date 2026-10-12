# Gate 14 — Create & Fund → Ready Offline

## Goal

Turn AIRNOTE's developer-facing mint/certificate workflow into one mobile product action:

Create & Fund
→ StrongBox P-256 note owner
→ MWA wallet authorization
→ Devnet USDC locked in the per-note vault
→ Solana finalized
→ AIRNOTE attestor independently revalidates backing
→ signed mint certificate cached locally
→ READY OFFLINE

No mint-request file or certificate file is part of the normal payment UX.

## Trust boundary

Gate 14 does not let the sender self-certify a note.

The attestor endpoint holds the existing Ed25519 attestor key and signs only after independently checking finalized Solana state:

- mint transaction exists, succeeded and is finalized
- transaction contains the expected AIRNOTE create_note
- Note account belongs to the pinned AIRNOTE program
- Note status is still FUNDED
- denomination is supported
- vault is the matching SPL token account
- vault mint matches the Note's USDC mint
- vault token authority equals the vault PDA itself
- vault balance equals the Note amount exactly
- canonical mint core / commitment are reconstructed from finalized state

The Android app then verifies the returned certificate against the already-pinned attestor public key before marking a note READY OFFLINE.

## Failure semantics

Funding submission is persisted before waiting for finality.

- wallet/RPC failure before a signature: note remains PREPARED and can retry
- known submitted signature: retry reconciles that signature, never blindly funds again
- finalized funding + attestor unavailable: note remains FUNDED_FINALIZED and retry performs certification only
- READY_OFFLINE: sender can select the note locally without a file picker

## Product flow

1. Choose $1 / $5 / $10 and lifetime.
2. Tap CREATE & FUND AIRNOTE.
3. AIRNOTE creates the StrongBox owner key.
4. MWA opens the Solana wallet.
5. User approves one Devnet USDC funding transaction.
6. AIRNOTE waits for finalized.
7. Attestor certifies the finalized backing.
8. UI shows READY OFFLINE.
9. SEND AIRNOTE shows the local note directly.

The existing two-scan offline protocol, P-256 transfer signature, recipient binding, settlement transaction, replay protection, and reconciliation rules are unchanged.


## Physical acceptance — PASS

Validated on a physical Solana Mobile Seeker.

Observed end-to-end path:

- CREATE & FUND 1 USDC through MWA
- funding transaction finalized on Solana Devnet
- attestor at `https://api.airnote.cash/certify` certified the finalized note
- Android verified the pinned attestor signature and reached READY OFFLINE
- SEND listed the locally prepared note without file import
- receiver request QR was created from the cached receive-wallet binding
- sender scanned Scan 1 and created the StrongBox-backed P-256 transfer authorization
- receiver scanned Scan 2 with internet unavailable
- receiver reached OFFLINE HANDOFF COMPLETE / AIRNOTE RECEIVED
- backing certificate, recipient binding, StrongBox P-256 authorization, and duplicate guard all verified offline
- connectivity was restored
- receiver opened settlement, authorized the exact bound wallet through MWA, submitted `redeem_note`, and reached SETTLED

This proves the intended product sequence on real hardware:

`online preparation -> offline ownership transfer -> online Solana settlement`

During the one-device Seeker test, an activity-switch lifecycle issue was found: the receiver request was previously held only in memory, so reopening RECEIVE could create a new nonce and correctly reject the already-signed transfer as not bound to the current request. Gate 14 now persists the exact active receiver request/session locally and restores it byte-for-byte across activity/process switches. No private key material is persisted.

Gate 14 acceptance status: **PASS**.
