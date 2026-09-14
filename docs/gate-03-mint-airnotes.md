# AIRNOTE Gate 03 — Finalized Mint Certificates

Status: **certificate protocol + signer/verifier implemented; finalized devnet observation pending**

## Goal

Turn a funded Gate 02 note into a portable Airnote that an offline recipient can authenticate without querying Solana.

Gate 03 does not change custody. USDC remains in the Gate 02 per-note vault. Gate 03 adds an explicit attestor signature proving that AIRNOTE observed the note as finalized on Solana before the sender went offline.

## Certificate contract

The signed bytes are exactly the Gate 01 canonical payload:

```text
AIRNOTE_MINT_CERT_V1
note_commitment          [32]
finalized_slot           u64 LE
mint_tx_signature        [64]
attestor_key_id          u32 LE
```

Total payload length: **128 bytes**.

The attestor signs those exact bytes with Ed25519. The private attestor key cannot move vault funds; it only certifies an observation.

## Cross-language lock

Kotlin and Rust share the same certificate golden vector:

- note commitment: `d6dfd317a4c51dde7d2b824fc09dbb8b6f9721efb9d6c9044f6a527c854ff650`
- finalized slot: `250000000`
- tx signature: bytes `00..3f`
- attestor key id: `7`
- payload length: `128`
- payload SHA-256: `31da2d818a0230418cbddda05a788452d8555dde8c94154bb4b323838f615272`

A deterministic **test-only** Ed25519 seed is used in unit tests to lock signer/verifier parity. It is not a deployable attestor credential.

Expected test public key:

```text
79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664
```

Expected signature over the golden certificate payload:

```text
af9ce7a59779d62228178371cfc2225efc9a31fa4b54032cb2d04f6223f8888b165c5bf380843b29bd27cc3756c547bce62b459a7117d55b7c2cf4188f24a70e
```

## Android verification

`MintCertificateVerifier`:

1. recomputes the note commitment from the received mint core
2. requires exact equality with the certificate commitment
3. resolves `attestor_key_id` through a pinned keyring
4. verifies the 64-byte Ed25519 signature over the canonical 128-byte payload
5. rejects unknown keys or any mutated signed field

Android provides the Ed25519 `Signature` implementation from API 33. Seeker is above this floor.

This verifier intentionally handles only cryptographic certificate authenticity. The offline acceptance layer must additionally enforce supported network, program id, USDC mint, denomination, expiry, risk tier, device limits, and local duplicate detection.

## Attestor core

`tools/airnote_attestor` is an isolated Rust crate. It:

- consumes the canonical Rust `MintCertificateFields`
- signs the exact Gate 01 payload with Ed25519
- exposes only the public key and signature
- includes deterministic signer/verifier parity tests

Real private seed files are ignored by git (`*.attestor-seed`, `attestor-seed.hex`).

### Local issuer CLI

After an external observer has established finalized backing:

```bash
cargo run --manifest-path tools/airnote_attestor/Cargo.toml -- \
  issue \
  ./attestor-seed.hex \
  <NOTE_COMMITMENT_HEX> \
  <FINALIZED_SLOT> \
  <64_BYTE_MINT_TX_SIGNATURE_HEX> \
  <ATTESTOR_KEY_ID>
```

It outputs:

```text
attestor_public_key=...
certificate_payload=...
attestor_signature=...
```

The CLI **does not itself prove finalization**. Supplying fabricated slot/transaction metadata would produce a cryptographically valid but dishonest certificate. Production issuance therefore requires the next component: a Solana RPC finalization observer that reconstructs the funded note and vault before invoking the signer.

## Gate 03 acceptance

- [x] Rust mint-certificate encoder matches the Gate 01 Kotlin golden vector
- [x] Ed25519 attestor core signs canonical certificate bytes
- [x] Android verifies the same deterministic attestor vector
- [x] mutations of signed certificate fields are rejected
- [x] unknown attestor key ids are rejected
- [x] real attestor seed paths are git-ignored
- [x] Gate 02 runtime already proves $1 and $5 note funding paths
- [ ] finalized Solana RPC observer validates note account + isolated vault balance
- [ ] Gate 02 program deployed with a real synchronized program id
- [ ] first finalized devnet $1 Airnote certified end-to-end
- [ ] first finalized devnet $5 Airnote certified end-to-end

Until the last four items pass, AIRNOTE may call these **test certificates**, not finalized devnet Airnotes.

Next after Gate 03: **Gate 04 — offline QR transfer between two phones.**
