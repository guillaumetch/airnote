# AIRNOTE Gate 05 — Delayed Settlement After Offline Acceptance

Status: **implementation in progress**

## Goal

Separate two time boundaries that Gate 04 exposed as meaningfully different:

1. **recipient-request expiry** — a short freshness window for the offline handoff itself;
2. **Airnote expiry** — the final deadline for settlement on Solana.

A recipient who cryptographically accepts a valid Airnote while offline must not be forced to reconnect inside the short request window. Once the sender has signed the exact recipient-bound transfer during a fresh handoff, that authorization remains redeemable until the Airnote itself expires.

## Why Gate 05 exists

Gate 04 passed end-to-end on physical Seekers and Solana Devnet, but the first accepted transfer could not settle after the 10-minute receiver-request window elapsed. The note itself was still funded and live.

That behavior made the recipient-request TTL act as a settlement deadline. For an offline-payment product, this is the wrong boundary: a user may accept a payment with no connectivity and remain offline for much longer than a few minutes.

## Protocol semantics

The canonical payloads do not change.

The recipient request still contains:

```text
AIRNOTE_RECIPIENT_V1
version
recipient_solana_wallet
recipient_nonce
request_created_at_unix_s
request_expires_at_unix_s
```

The StrongBox owner still signs:

```text
AIRNOTE_TRANSFER_V1
version
note_commitment
recipient_solana_wallet
recipient_nonce
request_expires_at_unix_s
```

`request_expires_at_unix_s` therefore remains cryptographically bound to the transfer authorization.

### Offline handoff rule

The receiver and sender still enforce the short request freshness window before accepting/signing the handoff. Gate 04 Android policy currently caps a recipient request at 600 seconds.

A stale captured request cannot be used to start a new valid handoff.

### On-chain settlement rule

After a valid StrongBox transfer authorization exists, `redeem_note` no longer requires:

```text
current_chain_time <= request_expires_at_unix_s
```

Instead, Solana requires:

```text
request_expires_at_unix_s >= note.issued_at_unix_s
request_expires_at_unix_s <= note.expires_at_unix_s
current_chain_time < note.expires_at_unix_s
```

This means the request TTL proves that the authorization came from a bounded handoff envelope, while the Airnote lifetime controls how long an already accepted transfer may settle.

## Security properties that do not change

- the recipient Solana wallet is still bound into the P-256 signed payload;
- the recipient nonce is still bound into the signed payload;
- the request expiry is still bound into the signed payload;
- the recipient wallet must still sign the redemption transaction;
- the immediately preceding secp256r1 instruction must still verify the exact canonical transfer bytes;
- the note PDA must still be funded and unconsumed;
- only the first successful redemption wins;
- replay after redemption still returns `NoteConsumed`;
- redemption after Airnote expiry still fails;
- only the original funder may refund an expired funded note.

Gate 05 does not add trustless offline finality and does not change the Gate 00 StrongBox limitation. Offline acceptance remains bounded-risk and pending settlement.

## Runtime proof target

The LiteSVM proof must cover:

- mutated recipient nonce rejected;
- canonical redemption succeeds;
- replay rejected as `NoteConsumed`;
- a transfer whose short recipient request has expired **does settle successfully** while the Airnote remains live;
- a request expiry that extends beyond the Airnote lifetime is rejected;
- a valid transfer cannot redeem after Airnote expiry;
- unauthorized refund fails;
- original funder refund succeeds after expiry.

## Live Devnet acceptance

Gate 05 is PASS only after:

- [ ] program host compile passes;
- [ ] SBF build passes;
- [ ] LiteSVM delayed-settlement proof passes;
- [ ] Android Gate 04 regression tests remain green;
- [ ] upgraded program is deployed to Devnet under the existing AIRNOTE program id;
- [ ] a fresh physical Seeker Airnote is minted and certified;
- [ ] a fresh recipient request is signed during its short freshness window;
- [ ] the test intentionally waits until that request window has elapsed;
- [ ] the exact same signed transfer settles before the Airnote expires;
- [ ] replay of that transfer is rejected as consumed.

## Product consequence

The intended user experience becomes:

```text
fresh request -> offline cryptographic acceptance -> stay offline as needed -> reconnect -> settle
```

instead of:

```text
fresh request -> offline acceptance -> must reconnect before request TTL
```

The receiver can therefore treat the request TTL as a handshake anti-replay/freshness mechanism rather than a connectivity countdown.
