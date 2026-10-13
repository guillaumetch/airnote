# Gate 15 — SKR Reserve+

## Goal

Add a creative, meaningful SKR integration without changing AIRNOTE's proven payment protocol.

SKR acts as an **access entitlement** for a larger offline cash reserve:

- Standard AIRNOTE: up to **5 USDC / 6 hours**
- Verified liquid SKR holder: **Reserve+** unlocks **10 USDC / 24 hours**

USDC remains the payment asset. SKR is never transferred, spent, escrowed, or exposed to the AIRNOTE vault program.

## Official SKR asset

AIRNOTE checks the official SKR SPL token on Solana mainnet:

`SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3`

Source: Solana Mobile — SKR token information / SKR page.

## Product flow

1. User taps **VERIFY SKR ACCESS**.
2. Mobile Wallet Adapter authorizes a Solana wallet.
3. AIRNOTE queries Solana mainnet for token accounts owned by that wallet for the official SKR mint.
4. Any positive liquid SKR balance unlocks **SKR HOLDER ✓ / RESERVE+**.
5. 10 USDC and 24h controls become available.
6. When a Reserve+ Airnote is created, AIRNOTE stores only the verified wallet public key with the local note.
7. At funding time, the MWA wallet must match that same public key.
8. AIRNOTE re-checks the mainnet SKR balance before constructing/submitting the existing Devnet USDC `create_note` transaction.

This prevents the SKR gate from being only a visual client badge.

## Network boundary

AIRNOTE's hackathon payment rail remains on Solana Devnet:

- AIRNOTE vault program: `DyT3zjATxr4Fg28Ne3DjjV5Q6rd6JWx2Vo7agZZxZFx9`
- Circle Devnet USDC: `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU`

SKR holder verification is a read-only Solana **mainnet** query using the wallet public key returned through MWA.

No mainnet transaction is created.

## Protocol boundary

Gate 15 does **not** modify:

- Airnote mint-core encoding
- note commitment
- attestor certificate payload
- request QR
- transfer QR
- StrongBox P-256 transfer authorization
- recipient binding
- `redeem_note`
- replay protection
- delayed settlement / reconciliation

The physically proven Gate 14 path remains intact.

## Acceptance

Gate 15 passes when:

1. Android + Solana CI are green.
2. A wallet with no liquid SKR stays on Standard (5 USDC / 6h).
3. A wallet with liquid SKR shows **SKR HOLDER ✓ / RESERVE+ UNLOCKED**.
4. 10 USDC / 24h controls unlock only after holder verification.
5. A Reserve+ note refuses funding from a different wallet.
6. Funding re-checks SKR before submission.
7. A 10 USDC or 24h Reserve+ note still reaches READY OFFLINE and uses the unchanged two-scan / settlement path.
