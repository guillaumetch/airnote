# AIRNOTE Gate 06 — Sender-controlled note lifetime

Status: **implementation / live acceptance pending**

## Goal

Let the sender choose how long a funded Airnote remains redeemable, while keeping the receiver-request freshness window separate.

The two clocks now have distinct jobs:

- **Airnote lifetime** — chosen by the sender when creating the note. It controls how long the funded note may settle on Solana.
- **Receiver-request TTL** — short receiver-generated freshness window used only during the offline handoff. Gate 05 allows an already accepted transfer to settle after this request TTL, provided the Airnote itself is still live.

## Sender policy

The Seeker creation UI offers:

- 15 minutes — LOW OFFLINE EXPOSURE
- 1 hour — STANDARD (default)
- 6 hours — EXTENDED
- 24 hours — MAXIMUM
- custom duration from 5 minutes through 24 hours

The UI intentionally sets a stricter 5-minute minimum than the low-level Solana program's current 60-second minimum. The program maximum remains 24 hours.

Longer lifetime does not weaken signature or replay checks, but it increases the time during which conflicting offline authorizations could exist before Solana arbitrates them. AIRNOTE therefore displays the exposure label alongside the duration.

## Seeker mint request

Creating an Airnote on Seeker now produces a persistent StrongBox note-owner key plus a sender mint request containing at least:

```text
AMOUNT_USDC=5
LIFETIME_SECONDS=21600
LIFETIME_LABEL=6 hours
OFFLINE_EXPOSURE=EXTENDED
NOTE_ID_HEX=...
OWNER_P256_COMPRESSED_HEX=...
DEVICE_ATTESTATION_DIGEST_HEX=...
```

The local `ESTIMATED_EXPIRES_AT` is presentation-only. The authoritative note expiry is derived from finalized Solana chain time when funding occurs.

The request is automatically written under the AIRNOTE app external-files directory as:

```text
airnote-mint-request-<note-id>.txt
```

## Devnet mint from sender request

The existing explicit `mint` command still accepts `--lifetime-seconds`.

Gate 06 additionally provides:

```bash
node tools/devnet/airnote-devnet.mjs mint-request \
  --program "$AIRNOTE_PROGRAM_ID" \
  --wallet "$AIRNOTE_WALLET" \
  --request .airnote/airnote-mint-request.txt \
  --rpc "$AIRNOTE_RPC"
```

`mint-request` takes amount, lifetime, note id, StrongBox owner public key and attestation digest from the Seeker-generated request. Those sender-selected fields are authoritative for the mint operation rather than being re-entered manually on the Mac.

## Security invariants

Sender-controlled lifetime does not change the canonical Airnote wire format or certificate format.

The funded note still commits to:

- issue timestamp derived from Solana Clock
- note expiry
- fixed denomination
- StrongBox P-256 owner public key
- device-attestation digest
- program, note account and USDC mint

Redemption remains first-valid-settlement-wins. Refund remains available only to the original funder after note expiry.

## Gate 06 acceptance

Gate 06 passes when:

- Android unit tests cover presets, custom bounds, labels and exposure tiers
- APK builds successfully
- Seeker sender can choose $1 / $5 / $10
- Seeker sender can choose 15m / 1h / 6h / 24h or a valid custom duration
- StrongBox note creation produces a mint request containing the chosen lifetime
- `mint-request` funds a fresh Devnet Airnote using that exact sender-selected lifetime
- finalized on-chain Note expiry matches chain issue time + chosen lifetime within normal transaction timing tolerance
- certificate reflects the same on-chain expiry
- the note remains compatible with Gate 05 delayed settlement

No new claim of trustless offline finality is introduced.
