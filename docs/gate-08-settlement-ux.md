# Gate 08 — Settlement UX + Airnote History

## Scope

Gate 08 adds a product-level payment lifecycle and persistent local history without changing AIRNOTE cryptography, QR wire formats, P-256 signing, recipient binding, sender lifetime semantics, replay protection, or Solana program semantics.

## Product lifecycle

The Android product layer now models:

`CREATED -> ACCEPTED_OFFLINE -> PENDING -> SETTLING -> SETTLED`

with explicit terminal/alternate states:

- `FAILED_RETRYABLE`
- `REJECTED`
- `EXPIRED`

A settled payment cannot regress. Expired and rejected payments remain visible in history.

## Persistence

`AirnotePaymentStore` persists payment records in a dedicated Android `SharedPreferences` namespace as versioned JSON-shaped records. The protocol payload is Base64-encoded inside the private preference value and is not rendered in the activity list.

The record contains:

- commitment-derived local ID
- sent/received direction
- amount
- creation and expiry times
- product status
- recipient/counterparty display value where available
- settlement signature when available
- last settlement attempt
- retry count
- diagnostic error code
- protocol payload needed to resume settlement

## Duplicate protection

Received Airnotes use the verified note commitment as their local identity. `recordReceived` is idempotent: if that commitment already exists, the existing record is returned rather than creating another payment.

The older Gate 04 duplicate marker remains in place as a compatibility guard. Solana first-redemption-wins remains the protocol-level replay authority.

## UX

A first-class Activity screen lists Airnotes by direction, amount, status, and time. A payment detail screen shows the lifecycle, expiry, Solana transaction signature when known, and technical details separately.

The receive flow writes an accepted Airnote to persistent history immediately after the existing offline verification succeeds and surfaces it as `Pending` settlement.

## Settlement boundary

The repository's current real redemption implementation remains `tools/devnet/redeem-airnote.mjs`. That tool requires a Solana recipient keypair (and optionally a separate fee payer) to construct and sign the redemption transaction.

Gate 08 deliberately does **not** fabricate an Android transaction submission path or fake a `Settled` result. The Android lifecycle/store includes `SETTLING`, `SETTLED`, retry metadata, and transaction-signature storage so an on-device signer/RPC adapter can be connected next without changing history semantics.

Until that adapter exists, the payment detail surface retains access to the settlement payload for the existing real redemption path.

## Tests

`PaymentLifecycleTest` covers:

- accepted -> pending -> settling -> settled
- settled-state non-regression
- invalid transition rejection
- retry counter semantics
- terminal expired/rejected behavior

Existing Android protocol tests and Solana regression CI remain authoritative for cryptographic/program regression.

## Deferred proof

Gate 08 DOES NOT replace deferred Gate 05/06 physical validation.

Gate 08 MUST NOT be interpreted as proof that the full two-Seeker settlement path has passed physical validation.

The stacked release still requires the deferred live proof covering sender lifetime, post-request-TTL settlement, and replay rejection.

## Intentional deferral

Automatic/background Android settlement is deferred until AIRNOTE has an on-device Solana transaction signer/fee-payer strategy. WorkManager or connectivity-triggered retry should be added only after that boundary exists and is physically proven.
