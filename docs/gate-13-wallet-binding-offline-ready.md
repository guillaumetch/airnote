# Gate 13 — Wallet binding and offline readiness

## Goal

Remove manual recipient-address entry from the normal AIRNOTE receive flow while preserving the two-scan offline protocol exactly as-is.

The receiver chooses an MWA-compatible Solana wallet once. AIRNOTE persists only that wallet's 32-byte public key. Future receive requests can be created from the cached public key without reopening the wallet and without network access.

## Product flow

1. Open **Receive**.
2. Tap **Select Receive Wallet**.
3. MWA opens a compatible wallet and returns the authorized account public key.
4. AIRNOTE stores only the public key locally.
5. The Receive screen shows **Offline Receive Ready**.
6. The user may disconnect from the internet.
7. **Show Request QR — 1 of 2** uses the cached public key.
8. The sender scans Scan 1 and returns the signed Scan 2 QR.
9. The receiver verifies/accepts Scan 2 offline.
10. Later, settlement reopens MWA and still requires the approving account to equal the recipient embedded in the transfer.

## Security boundary

AIRNOTE does not persist through this gate:

- wallet private keys
- seed phrases
- Seed Vault material
- an MWA auth token

The cached public key is not authority to spend funds. It is only the recipient identity used to construct the offline request.

Settlement authority remains with the external wallet. Gate 09's wallet-mismatch guard remains authoritative: the wallet that approves redemption must match the recipient bound into the offline transfer.

## Offline boundary

Wallet selection is setup, not part of the two-scan handoff. AIRNOTE does not rely on wallet authorization being available offline.

Once a public key has been selected and cached, Scan 1 generation, Scan 2 verification, recipient binding verification, P-256 authorization verification, and local acceptance do not need a wallet session or internet connection.

## History correctness

When Scan 2 is accepted, the stored payment counterparty is derived from the recipient contained in the active request itself. Changing the cached receive wallet later cannot rewrite or relabel an already-created handoff.

## Protocol boundary

Gate 13 changes no:

- request or transfer QR wire format
- canonical protocol bytes
- request nonce/TTL
- sender lifetime
- StrongBox/P-256 signing
- mint certification
- note commitment
- PDA derivation
- P-256 precompile instruction
- `redeem_note`
- Solana replay protection
- Gate 11 reconciliation behavior
- Gate 12 proof rules

This gate is recipient setup and UX around the existing protocol.

## Physical acceptance

A useful Seeker proof is:

1. Select the receive wallet while connected.
2. Close/reopen AIRNOTE and verify **Offline Receive Ready** persists.
3. Disable validated internet.
4. Generate Scan 1 without opening the wallet.
5. Complete Scan 1 / Scan 2 offline.
6. Restore connectivity.
7. Settle and verify MWA requires the same bound wallet.
8. Confirm a different wallet is rejected by the existing wallet-mismatch guard.
