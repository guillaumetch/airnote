# AIRNOTE Gate 03 — Finalized Mint Certificates

Status: **local implementation + CI PASS; live devnet deployment/certification pending**

## Goal

Turn a funded Gate 02 note into a portable Airnote that an offline recipient can authenticate without querying Solana.

Gate 03 does not change custody. USDC remains in the Gate 02 per-note vault. Gate 03 adds an explicit attestor signature proving that AIRNOTE independently observed the note as finalized and fully backed on Solana before the sender went offline.

## Seeker note-owner key

Gate 00 proved that `maxUsageCount=1` is not StrongBox-enforced on Seeker. Gate 03 therefore does **not** reuse the disposable Gate 00 key pattern.

For each Airnote the Seeker creates a fresh persistent P-256 key with:

- Android Keystore / `secp256r1`
- `setIsStrongBoxBacked(true)`
- no `maxUsageCount`
- non-exportable private key
- fresh note id
- attestation challenge `SHA256("AIRNOTE_NOTE_ATTEST_CHALLENGE_V1" || note_id)`
- attestation parsed locally and required to report both attestation and KeyMint security level `STRONGBOX`

The canonical device-attestation digest is:

```text
SHA256(
  "AIRNOTE_ANDROID_ATTESTATION_CHAIN_V1" ||
  chain_count_u32_le ||
  for each certificate in chain order: der_length_u32_le || der_bytes
)
```

That digest is committed into the on-chain Note and mint core. The key alias remains on the Seeker until redemption/refund and can later sign Gate 04 transfer payloads.

This does **not** restore hardware-enforced one-use semantics. StrongBox protects key custody/identity; Solana remains the settlement arbiter.

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

## Finalization observer

`tools/observe_finalized_note.py` is the trust boundary between Solana RPC data and the isolated signer. Given program id, Note PDA, vault PDA and create-note transaction signature, it requires:

- signature status `finalized`
- successful finalized transaction body at the same slot
- a matching AIRNOTE `create_note` top-level instruction containing the supplied Note + vault accounts
- Note account owned by the AIRNOTE program
- exact Anchor `Note` discriminator/layout
- state `FUNDED` with no redemption state
- supported fixed denomination
- isolated SPL vault with matching mint
- vault authority equal to the vault PDA
- 6-decimal token amount
- vault balance exactly equal to the Note amount

It then reconstructs the canonical 240-byte mint core from finalized account state and recomputes `note_commitment`. It emits observation metadata only; it never signs.

The observer's account-layout fixture is tested against the Gate 01 canonical commitment, and a redeemed Note fixture is explicitly rejected.

## Attestor core

`tools/airnote_attestor` is an isolated Rust crate. It:

- consumes the canonical Rust `MintCertificateFields`
- signs the exact Gate 01 payload with Ed25519
- exposes only the public key and signature
- includes deterministic signer/verifier parity tests
- provides `pubkey <seed-file>` so the deployer can pin a real attestor public key without exposing the seed

Real private material is ignored by git (`.airnote/`, `*.attestor-seed`, `attestor-seed.hex`).

The attestor is intentionally separate from the RPC observer. Production flow is:

```text
finalized Solana state -> observer PASS -> canonical observation -> isolated signer -> certificate
```

## Devnet tooling

`tools/devnet/` contains the local-only live path:

- `bootstrap-program.sh`: create/reuse the local program keypair, `anchor keys sync`, build and deploy
- `airnote-devnet.mjs init`: bind Config to Circle Solana Devnet USDC
- `airnote-devnet.mjs mint`: create a $1/$5/$10 note from Seeker key material and wait for finalization
- `certify-airnote.sh`: independently observe finalized state and produce the portable certificate package

See `docs/gate-03-devnet-runbook.md`.

## CI state

At the pre-devnet checkpoint, both repository workflows are green:

- Android unit tests + debug APK build: PASS
- Rust host/program tests: PASS
- Kotlin/Rust certificate parity: PASS
- mint attestor tests: PASS
- finalized-observer tests: PASS
- isolated LiteSVM harness compile: PASS
- SBF build: PASS
- Gate 02 LiteSVM adversarial runtime proof: PASS

## Gate 03 acceptance

- [x] persistent per-note Seeker StrongBox owner-key path implemented
- [x] Rust mint-certificate encoder matches the Gate 01 Kotlin golden vector
- [x] Ed25519 attestor core signs canonical certificate bytes
- [x] Android verifies the same deterministic attestor vector
- [x] mutations of signed certificate fields are rejected
- [x] unknown attestor key ids are rejected
- [x] finalized Solana RPC observer validates Note state + isolated vault balance
- [x] real private/deployment paths are git-ignored
- [x] Gate 02 runtime proves $1 and $5 funding paths
- [ ] Gate 02 program deployed with a real synchronized program id on Devnet
- [ ] real devnet attestor public key pinned for offline acceptance
- [ ] first finalized devnet $1 Airnote certified end-to-end
- [ ] first finalized devnet $5 Airnote certified end-to-end

Until the last four items pass, outputs may be called **test/local certificates**, not finalized AIRNOTE devnet demo assets.

Next after Gate 03: **Gate 04 — offline QR transfer between two phones.**
