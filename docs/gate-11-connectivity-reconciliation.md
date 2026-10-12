# Gate 11 — Connectivity recovery and settlement reconciliation

## Goal

Make AIRNOTE recover cleanly when connectivity returns without weakening the Gate 09 wallet boundary.

Gate 11 does **not** background-sign or background-submit a fresh redemption. Mobile Wallet Adapter approval remains an explicit user action with the recipient-bound Solana wallet.

What Gate 11 may do automatically:

- wait for network connectivity after an offline receive
- tell the receiver that an unsigned Airnote is ready to settle
- reconcile a Solana transaction signature that the wallet already submitted
- mark the payment Settled only after finalized chain evidence
- detect a chain-rejected transaction
- detect a transaction whose recent blockhash has definitively expired without landing
- preserve idempotency across process death / app restart

## Connectivity scheduling

`AirnoteReconciliationScheduler` enqueues unique WorkManager work with a `CONNECTED` network constraint.

Scheduling happens when:

- a received Airnote is persisted after Scan 2
- a wallet-returned settlement signature is persisted
- a submitted transaction enters a retryable reconciliation state
- AIRNOTE home is opened/resumed, providing an upgrade/recovery sweep for existing local history

The job is persisted by WorkManager and therefore is not tied to an Activity remaining alive.

## Unsigned pending Airnotes

A received Airnote with:

- direction `RECEIVED`
- local status `PENDING` or `FAILED_RETRYABLE`
- no submitted Solana signature
- a still-live Airnote expiry

is eligible for a `ready to settle` notification once network connectivity is available.

The notification opens the payment detail screen. AIRNOTE does not invoke MWA from the worker.

The user still performs:

`Open payment -> Settle on Solana -> wallet approval`

## Submitted transaction reconciliation

After MWA returns a Solana signature, Gate 09/11 persist:

- transaction signature
- transaction `lastValidBlockHeight`

before waiting for finalization.

The background worker checks the exact stored signature with `getSignatureStatuses(searchTransactionHistory=true)`.

Outcomes:

### Finalized

`SETTLING / FAILED_RETRYABLE -> SETTLED`

A settlement notification is posted when permission is available.

Foreground and background finalization are idempotent for the same transaction signature, so the worker racing the payment screen cannot turn success into a local error.

### Explicit chain failure

The known signature is cleared only after Solana explicitly reports an error. The payment becomes retryable if the Airnote itself is still live.

### Signature still missing / processing

AIRNOTE keeps the known signature and does not submit a replacement.

If `lastValidBlockHeight` is known, AIRNOTE additionally checks finalized Solana block height.

A fresh submission is allowed only when:

1. the signature is still not observed,
2. this is not the worker's first observation, and
3. finalized block height is strictly greater than the transaction's stored `lastValidBlockHeight`.

At that point the old recent blockhash can no longer land a new transaction, so the dead submission can be cleared and the user may explicitly authorize a fresh MWA retry.

## Expiry correctness

Local Airnote expiry no longer wins over an unresolved known Solana signature.

Payments with a stored settlement signature are excluded from ordinary local `expireDue()` handling until the chain transaction is reconciled.

This prevents the app from displaying `Expired` while a transaction submitted before expiry is still progressing toward finality.

Gate 11 can also repair legacy Gate 09/10 local data where a payment was previously marked `EXPIRED` while retaining a transaction signature:

- if that signature finalized, local state is repaired to `SETTLED`
- if that signature definitively failed, it is reconciled as failed while preserving the real Airnote expiry outcome

Solana finality is authoritative over an earlier local expiry guess.

## Notifications

Android 13+ requires `POST_NOTIFICATIONS` permission.

AIRNOTE home exposes `ENABLE SETTLEMENT ALERTS` when permission is not granted.

Notification types:

- Airnote ready to settle
- Airnote settled
- settlement needs attention

Notifications deep-link to the corresponding payment detail screen.

Notifications are convenience only. Reconciliation and payment state correctness do not depend on notification permission.

## Protocol boundary

Gate 11 does not change:

- Scan 1 request QR format
- Scan 2 transfer QR format
- canonical protocol bytes
- StrongBox P-256 signing
- recipient binding
- request nonce / TTL rules
- Airnote lifetime semantics
- mint certificate verification
- vault / note PDA derivation
- P-256 precompile instruction
- `redeem_note`
- on-chain replay protection
- MWA requirement for fresh settlement authorization

## Gate proof

Gate 11 implementation proof requires:

- Android unit tests green
- debug APK build green
- Gate 01–10 Android regressions green
- Solana regression suite green

Physical proof remains separate.

The stacked release is **not** physically proven until the deferred Gate 05/06 device proof and the live Gate 09+ Seeker settlement proof are completed.

A useful Gate 11 physical scenario is:

1. Receive Airnote with both devices offline.
2. Close AIRNOTE on the receiver.
3. Restore connectivity.
4. Observe `ready to settle` behavior (when notification permission is enabled).
5. Open payment and authorize settlement via the bound Solana wallet.
6. Kill AIRNOTE immediately after wallet submission.
7. Let WorkManager reconcile the persisted signature.
8. Reopen AIRNOTE and verify `Settled` with the same transaction signature.
9. Re-open/check repeatedly and confirm no duplicate redemption is submitted.
