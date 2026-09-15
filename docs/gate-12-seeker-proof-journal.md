# Gate 12 — Seeker physical proof journal

## Goal

Turn AIRNOTE's intentionally deferred physical validation into observable, exportable device evidence without changing payment protocol semantics.

Gate 12 does **not** fake a PASS and does not add manual checkboxes for cryptographic claims. Evidence is written only by the code paths that actually perform the relevant action.

## Evidence captured

The local proof journal can record:

- Gate 00 StrongBox probe PASS / non-PASS
- receiver request QR ready (Scan 1 of 2)
- sender signed transfer QR ready (Scan 2 of 2)
- receiver offline-transfer verification accepted
- MWA wallet matched the recipient bound into the Airnote
- Solana redemption signature returned and persisted
- the exact submitted signature finalized in the foreground
- the exact submitted signature finalized by WorkManager in the background
- definitive chain/blockhash failure
- connectivity recovery surfaced an unsigned Airnote as ready to settle

Each event stores only a timestamp, event type, optional handoff-session id, Airnote commitment/payment id, public Solana transaction signature, validated-internet state, and a short non-secret diagnostic.

The journal does **not** store raw transfer payloads, wallet private keys, seed phrases, StrongBox private key material, or certificate private material.

## Offline evidence

`ACCESS_NETWORK_STATE` is used only to observe whether Android has an active network with both `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED` at the moment an event is recorded.

The receiver-side checklist marks **Two-scan handoff observed offline** only when:

1. a receiver request QR event exists,
2. an accepted transfer event exists for the same local handoff-session id,
3. both observations report `validated_internet=false`, and
4. the request event occurred before acceptance.

This is stronger evidence than UI copy saying the device was offline.

## Settlement evidence

The checklist does not treat wallet submission as settlement. It requires:

- an MWA recipient authorization observation,
- a persisted `SETTLEMENT_SUBMITTED` event, and
- a finalized event carrying the **same Solana transaction signature**.

A WorkManager-finalized event is shown separately so the crash/recovery test can demonstrate background reconciliation.

## Physical proof recipe

Recommended two-device run:

1. Reset Gate 12 proof journal on both devices.
2. On sender Seeker, run the StrongBox hardware probe.
3. Disable validated internet on both devices.
4. Receiver creates Scan 1.
5. Sender scans Scan 1 and produces signed Scan 2.
6. Receiver scans Scan 2 and reaches `OFFLINE HANDOFF COMPLETE`.
7. Export receiver proof journal if you want an immediate offline checkpoint.
8. Restore receiver connectivity.
9. Open payment and settle through MWA using the bound recipient wallet.
10. For the Gate 11 recovery proof, kill AIRNOTE immediately after wallet submission.
11. Let WorkManager observe the persisted signature reach finalized.
12. Reopen AIRNOTE and verify the payment is `Settled`.
13. Open **Developer Tools → Seeker Physical Proof** and export the journal.
14. Optionally export the sender journal too to show the StrongBox/signed-Scan-2 side of the handoff.

## Interpretation boundary

A `SETTLEMENT_FINALIZED_BACKGROUND` event proves AIRNOTE's WorkManager path observed finality. Process death itself is an external device action, so pair the journal with a screen recording or live judge demo if you want to prove the app was actually killed between submission and reconciliation.

The `Single submission in this proof run` check is local evidence for the demo path. Global replay/double-redemption protection remains the Solana program's responsibility and is already covered by the protocol/runtime test stack.

## Protocol boundary

Gate 12 changes no:

- QR wire format
- canonical protocol bytes
- StrongBox/P-256 signature semantics
- recipient binding
- request nonce or TTL
- sender lifetime
- certification
- PDA derivation
- P-256 precompile instruction
- `redeem_note`
- Solana replay protection
- Gate 11 reconciliation policy

Gate 12 is instrumentation and proof UX around already-existing behavior.
