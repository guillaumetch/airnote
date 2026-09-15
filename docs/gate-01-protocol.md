# AIRNOTE Gate 01 — Canonical Offline Note Protocol

Status: **v1 draft locked for implementation**

## 1. Product invariant

AIRNOTE v1 is **one-hop offline USDC**:

1. Sender is online and locks USDC into the AIRNOTE Solana vault.
2. AIRNOTE issues a finalized mint certificate for a fixed-value note.
3. Sender may later go offline and transfer the entire note to one recipient.
4. Recipient verifies the certificate and transfer locally, with no network.
5. Recipient reconnects and redeems the note on Solana.
6. The recipient cannot re-spend the note offline in v1; redemption is required first.

Gate 00 proved that Seeker StrongBox can protect a non-exportable P-256 key, but `usageCountLimit=1` is software-enforced. Therefore v1 does **not** claim hardware prevention of double spending. The chain is the final arbiter and only the first valid redemption wins.

## 2. Risk model

AIRNOTE accepts bounded offline risk. The offline recipient checks cryptographic authenticity and policy, but cannot know whether a compromised sender has produced a conflicting transfer while both parties are offline.

Mandatory v1 controls:

- fixed denominations only (initially $1 / $5 / $10 USDC)
- one-hop offline transfer only
- short note expiry
- short recipient-request expiry
- StrongBox-backed sender key when available
- Android attestation evaluated while minting
- mint certificate issued only after finalized Solana funding
- per-device outstanding offline limit
- recipient-side local duplicate detection
- on-chain note consumption / replay protection
- post-reconnect conflict detection and sender risk scoring

## 3. Canonical encoding

All signed payloads use a fixed binary format. **No JSON, CBOR, protobuf, Java/Kotlin serialization or locale-dependent text is signed.**

Rules:

- integers: unsigned semantic values encoded little-endian
- timestamps: Unix seconds as 8-byte little-endian values
- Solana public keys: raw 32 bytes
- Solana transaction signatures: raw 64 bytes
- P-256 public keys: SEC1 compressed form, 33 bytes
- nonces: 32 cryptographically-random bytes
- hashes: SHA-256, 32 bytes
- P-256 signatures: compact `r || s`, 64 bytes, low-S normalized before transport
- all domain strings are literal ASCII bytes and are included in the bytes being hashed/signed

## 4. Mint core

The Solana note account and the offline certificate describe the same immutable mint core.

```text
AIRNOTE_MINT_CORE_V1                 ASCII domain
version                              u8 = 1
network                              u8 (1=devnet, 2=mainnet-beta)
note_id                              [32]
program_id                           [32]
note_account                         [32]
usdc_mint                            [32]
amount_base_units                    u64 LE
owner_p256_pubkey_compressed         [33]
issued_at_unix_s                     u64 LE
expires_at_unix_s                    u64 LE
device_attestation_digest            [32]
risk_tier                            u8
```

`note_commitment = SHA256(encode(mint_core))`

The Solana note account must contain enough fields to recompute this commitment deterministically.

## 5. Mint certificate

The mint certificate is the recipient's portable proof that AIRNOTE observed finalized on-chain backing before the sender went offline.

```text
AIRNOTE_MINT_CERT_V1                 ASCII domain
note_commitment                      [32]
finalized_slot                       u64 LE
mint_tx_signature                    [64]
attestor_key_id                      u32 LE
```

The AIRNOTE attestor signs the exact certificate payload. The transport object carries:

```text
mint_core
certificate_payload
attestor_signature                   [64]
```

For the hackathon MVP the attestor is an explicit trust assumption. It can certify funding but cannot move vault funds. A later version may use threshold attestation.

Offline verification MUST:

1. recompute `note_commitment` from `mint_core`
2. confirm it equals the certificate commitment
3. verify the attestor signature against a pinned/known attestor key
4. reject expired notes
5. reject unsupported network/program/USDC mint
6. enforce local denomination and risk limits

## 6. Recipient request

The recipient creates a fresh request while offline.

```text
AIRNOTE_RECIPIENT_V1                 ASCII domain
version                              u8 = 1
recipient_solana_wallet              [32]
recipient_nonce                      [32]
request_created_at_unix_s            u64 LE
request_expires_at_unix_s            u64 LE
```

The QR/NFC/BLE request contains these fields. The nonce MUST be generated with a CSPRNG for every request.

## 7. Transfer authorization

The sender signs exactly one canonical transfer payload with the note owner's P-256 key.

```text
AIRNOTE_TRANSFER_V1                  ASCII domain
version                              u8 = 1
note_commitment                      [32]
recipient_solana_wallet              [32]
recipient_nonce                      [32]
request_expires_at_unix_s            u64 LE
```

The P-256 signature is compact low-S `r || s` (64 bytes).

Why each field exists:

- `note_commitment`: binds the signature to one exact funded note
- `recipient_solana_wallet`: prevents redirection to another redeemer
- `recipient_nonce`: prevents precomputed / replayed recipient requests
- `request_expires_at_unix_s`: limits the lifetime of captured requests

The whole note value is transferred. There is no offline change-making in v1.

## 8. Offline transfer package

Sender -> recipient transport package:

```text
mint_core
certificate_payload
attestor_signature
recipient_request
transfer_signature
```

The recipient MUST verify locally before showing `Received`:

1. mint core parses and uses supported constants
2. note commitment matches certificate
3. certificate attestor signature is valid
4. note is not expired
5. recipient request is their own current request
6. request is not expired
7. transfer P-256 signature verifies against `owner_p256_pubkey_compressed`
8. transfer payload contains their wallet + nonce exactly
9. note ID / commitment is not already present in local accepted-note storage
10. amount is within local offline acceptance policy

A successful local verification means **accepted pending settlement**, not final settlement.

## 9. Redemption transaction

The online recipient submits one Solana transaction containing:

1. a top-level `Secp256r1SigVerify1111111111111111111111111` instruction verifying the transfer P-256 signature
2. the AIRNOTE `redeem_note` instruction

The AIRNOTE program reads the instructions sysvar and checks that the secp256r1 verification instruction covers the exact canonical transfer bytes required by the note account and redeemer.

`redeem_note` MUST then:

- verify note PDA / program state
- verify note is unspent
- verify note is not expired
- recompute `note_commitment`
- bind recipient wallet and request nonce/expiry to the verified transfer payload
- mark the note consumed before token transfer completes atomically
- transfer the full USDC amount from vault custody to the bound recipient
- emit a redemption event

If conflicting offline transfers exist, only the first valid on-chain redemption succeeds. Later attempts return an explicit consumed/conflict error.

## 10. State machine

```text
FUNDED -> CERTIFIED -> OFFLINE_TRANSFERRED -> REDEEMED
   |          |               |
   |          |               +-> CONFLICT_LOST (recipient-side outcome)
   |          +-> EXPIRED
   +-> REFUNDED (only under explicit expiry/refund rules)
```

There is no `OFFLINE_TRANSFERRED -> OFFLINE_TRANSFERRED` transition in v1.

## 11. Gate 01 acceptance

Gate 01 passes when:

- Kotlin canonical encoders exist for mint core, certificate, recipient request and transfer payload
- all field lengths are validated
- byte output is deterministic
- SHA-256 note commitment is deterministic
- golden vectors cover each payload
- mutation tests prove changing wallet, nonce, amount, expiry or owner key changes the signed commitment/payload
- protocol code has no Android UI dependency

Next: **Gate 02 — Solana vault + note PDA + redeem path using the secp256r1 precompile.**
