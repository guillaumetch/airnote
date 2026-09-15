# Gate 09 — Native Seeker settlement

## Goal

Remove the Mac / Node redemption step from the normal AIRNOTE product flow without changing the proven AIRNOTE wire protocol or Solana program semantics.

Gate 09 takes a Gate 08 `PENDING` received Airnote and performs the existing `redeem_note` flow from Android using a Solana Mobile Wallet Adapter wallet for Ed25519 authorization and transaction submission.

## Security boundary

Gate 09 does **not** change:

- Gate 04 request QR or transfer QR formats
- canonical mint / certificate / recipient / transfer bytes
- StrongBox P-256 ownership signing
- recipient binding
- request TTL semantics
- sender-selected Airnote lifetime semantics
- note commitment derivation
- Solana `redeem_note` instruction data or account order
- first-redemption-wins / on-chain replay protection

AIRNOTE never imports, stores, or handles the recipient wallet private key. Mobile Wallet Adapter opens a compatible Solana wallet which owns that key.

## Native settlement flow

1. Receiver accepts a signed Airnote offline.
2. Gate 08 records it locally as `PENDING`.
3. User opens the payment and taps **SETTLE ON SOLANA**.
4. AIRNOTE opens a Mobile Wallet Adapter session on Solana devnet.
5. The authorized wallet public key must exactly equal the recipient wallet already bound into the offline transfer.
6. AIRNOTE validates the same Gate 05 policy used by `tools/devnet/redeem-airnote.mjs`:
   - devnet network
   - Circle devnet USDC mint
   - supported demo amount
   - risk tier
   - note commitment
   - note PDA
   - note account owner
   - finalized Solana chain time before note expiry
   - request-expiry bounds
7. AIRNOTE constructs the same two-instruction redemption transaction:
   - Secp256r1 verification instruction
   - AIRNOTE `redeem_note` instruction
8. The recipient wallet is also the fee payer, matching the Gate 05 CLI default.
9. Mobile Wallet Adapter signs and submits the transaction.
10. AIRNOTE persists the returned signature immediately.
11. AIRNOTE independently polls Solana until that signature reaches `finalized`.
12. Only then does local state become `SETTLED`.

## Idempotency / restart safety

A submitted signature is persisted **before** AIRNOTE waits for finalization.

If the app is closed, killed, or RPC times out after submission, the local payment retains the signature. The next settlement action becomes **CHECK SOLANA SETTLEMENT** and reconciles that exact signature instead of constructing and submitting a second redemption transaction.

A fresh transaction is allowed only after Solana explicitly reports the prior submitted transaction failed. Local idempotency therefore complements, but does not replace, the program's first-redemption-wins replay protection.

## Transaction parity with Gate 05

The Android builder mirrors `tools/devnet/redeem-airnote.mjs`:

- note PDA seeds: `note`, `note_id`
- vault PDA seeds: `vault`, `note_id`
- recipient ATA: SPL Token / Associated Token derivation
- P-256 instruction offsets: signature `16`, key `80`, message `113`
- Anchor discriminator: first 8 bytes of `sha256("global:redeem_note")`
- discriminator hex: `d61abce427f0195a`
- redeem data: discriminator + 32-byte recipient nonce + request expiry u64 LE
- account ordering remains identical
- MWA transaction parameters use confirmed preflight, `skipPreflight=false`, `maxRetries=5`

Byte-level unit tests lock the P-256 header and redeem discriminator/layout.

## UX

Payment detail now exposes:

- `SETTLE ON SOLANA` for a fresh pending Airnote
- `RETRY SETTLEMENT` when no transaction is known to be live and a retryable failure occurred
- `CHECK SOLANA SETTLEMENT` when a signature has already been submitted
- `Settling on Solana` while active
- the transaction signature while submitted
- `Settled` only after independent finalized confirmation

Wallet mismatch, expiry, RPC unavailability, finalization timeout, wallet absence, and wallet submission failures are surfaced without pretending the payment settled.

## Network scope

Gate 09 is intentionally devnet-only. It validates the Circle Solana Devnet USDC mint and configures Mobile Wallet Adapter for Solana devnet.

Mainnet configuration is a later release gate and must not be enabled by merely changing product copy.

## CI

The previous workflow branch allowlist stopped before Gates 08/09. Gate 09 changes Android and Solana workflow filters to `gate-*` so future stacked gate branches receive CI automatically.

Gate 09 acceptance requires:

- Android unit tests pass
- debug APK builds
- P-256 instruction byte regression passes
- redeem instruction byte regression passes
- existing Gate 00–08 tests remain green
- Solana regression workflow remains green
- no protocol or program changes

## Physical proof still required

Gate 09 implementation and CI do not constitute a live native settlement proof.

Before Gate 09 is called physically proven we still need a Seeker run showing:

1. receive an Airnote offline
2. reconnect
3. tap `SETTLE ON SOLANA`
4. authorize the bound recipient wallet through Mobile Wallet Adapter
5. transaction submits
6. AIRNOTE reaches `SETTLED` only after finalized confirmation
7. USDC is present in the recipient token account
8. repeating the settlement does not produce a second redemption

The deferred combined Gate 05/06 physical proof also remains required before the full stacked release is called complete.
