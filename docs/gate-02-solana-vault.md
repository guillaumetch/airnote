# AIRNOTE Gate 02 — Solana Vault + Redemption

Status: **host compile + protocol parity green; SBF/local execution pending**

## Goal

Turn a Gate 01 Airnote into actual isolated USDC custody on Solana and make the chain the final arbiter of conflicting offline transfers.

## On-chain state

### Config PDA

Seeds: `['config']`

Stores:
- AIRNOTE network code
- configured USDC mint
- admin

### Note PDA

Seeds: `['note', note_id]`

Stores the immutable mint-core fields needed to recompute the Gate 01 `note_commitment`, plus settlement state:
- original funder
- network
- note id
- USDC mint
- amount
- compressed P-256 owner public key
- chain-derived issue timestamp
- note expiry
- device-attestation digest
- risk tier
- status
- eventual redeemer + redemption time

### Per-note token vault

Seeds: `['vault', note_id]`

Each Airnote has an isolated SPL Token account. The vault PDA is its own token authority and the AIRNOTE program signs for it with PDA seeds.

## Instructions

### `initialize_config(network)`

Creates the singleton config and binds AIRNOTE to one 6-decimal USDC mint for the selected cluster.

### `create_note(args)`

Requires the funder to sign and transfers one supported fixed denomination into the per-note vault.

Current v1 denominations:
- 1 USDC
- 5 USDC
- 10 USDC

Issue time comes from Solana `Clock`, not the Android client. Expiry must be between 60 seconds and 24 hours after chain issue time.

After funding, the program emits the canonical note commitment. A separate AIRNOTE mint-attestor service will only certify the note after observing finalized funding.

### `redeem_note(recipient_nonce, request_expires_at)`

The transaction must be ordered:

1. top-level Solana secp256r1 verification instruction
2. AIRNOTE `redeem_note`

AIRNOTE reads the immediately preceding instruction through the instructions sysvar and accepts only a strict self-contained P-256 layout:
- exactly one signature
- signature, public key and message are all stored in the same P-256 instruction (`instruction_index = u16::MAX`)
- compressed public key must equal the note owner key
- verified message must byte-equal the canonical Gate 01 `AIRNOTE_TRANSFER_V1` payload for the actual recipient wallet, nonce and request expiry

The recipient wallet must sign the AIRNOTE redemption. A separate `payer` signer funds ATA creation / transaction fees so fee sponsorship can be added without changing transfer ownership.

If valid, AIRNOTE marks the note redeemed and transfers the full vault amount atomically. A second redemption sees a consumed note and fails.

### `refund_note()`

Only the original funder can refund a still-funded note, and only after note expiry. Redemption requires `now < note expiry`; refund requires `now >= note expiry`, so the two success domains do not overlap.

## Security boundary

Gate 00 proved that Seeker StrongBox protects the P-256 private key but does not hardware-enforce the one-use counter. Gate 02 therefore does not attempt to infer offline uniqueness from Android state.

The chain guarantees only:
- funded note state is unique by PDA
- the canonical owner key and note data are immutable after creation
- a redemption must correspond to a valid P-256 verification instruction bound to the actual recipient
- the writable Note PDA serializes competing settlements
- only the first valid redemption succeeds
- expired funded notes can be refunded instead of redeemed

Offline acceptance remains **pending settlement** until the recipient reconnects.

## Cross-language protocol lock

Rust currently reproduces the Gate 01 Kotlin golden vectors:

- mint core length: 240 bytes
- mint-core SHA-256: `d6dfd317a4c51dde7d2b824fc09dbb8b6f9721efb9d6c9044f6a527c854ff650`
- transfer payload length: 124 bytes
- transfer SHA-256: `badd1898e2fb123b79c877a0d03374a6f229f5e2dab3d8bb0363d033fe815c4e`

CI runs both `cargo check` and these Rust protocol tests.

## Program ID note

The program id currently present in `declare_id!` and `Anchor.toml` is a scaffold id only. No deployment keypair is committed and none should be committed.

Before the first local/devnet deployment:
1. build/generate the actual program keypair locally
2. run the Anchor key sync flow
3. commit only the resulting public program id changes
4. never commit `target/deploy/*-keypair.json`

## Gate 02 acceptance

Gate 02 is PASS only after all of these are true:

- [x] host Rust compilation passes
- [x] Rust/Kotlin canonical payload golden vectors match
- [x] strict secp256r1 instruction introspection is unit-tested
- [ ] Solana SBF `anchor build` succeeds
- [ ] local execution: config -> create $1 test note -> redeem succeeds
- [ ] exact replay / second redemption fails
- [ ] altered recipient / nonce / transfer bytes fail
- [ ] expired note cannot redeem
- [ ] expired funded note refunds only to original funder
- [ ] actual deployment program id is synchronized

Next after Gate 02: finalized mint certificate / attestor service and the Android mint + offline-send UI.
