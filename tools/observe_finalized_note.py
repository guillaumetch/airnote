#!/usr/bin/env python3
"""Observe one AIRNOTE create_note transaction at finalized commitment.

This tool does not sign anything. It emits only observation metadata suitable for
feeding into the isolated `airnote-attestor` signer after every invariant below
has been checked against a Solana RPC node.
"""

import argparse
import base64
import hashlib
import json
import struct
import sys
import urllib.request

ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
ALPHABET_INDEX = {c: i for i, c in enumerate(ALPHABET)}
NOTE_DISCRIMINATOR = hashlib.sha256(b"account:Note").digest()[:8]
CREATE_NOTE_DISCRIMINATOR = hashlib.sha256(b"global:create_note").digest()[:8]
MINT_DOMAIN = b"AIRNOTE_MINT_CORE_V1"
VERSION = 1
SUPPORTED_DENOMINATIONS = {1_000_000, 5_000_000, 10_000_000}
STATUS_FUNDED = 0
NOTE_ACCOUNT_LEN = 238


def fail(message: str) -> "NoReturn":
    print(f"AIRNOTE OBSERVER FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def b58decode(value: str) -> bytes:
    n = 0
    try:
        for char in value:
            n = n * 58 + ALPHABET_INDEX[char]
    except KeyError as exc:
        fail(f"invalid base58 character: {exc.args[0]}")
    raw = b"" if n == 0 else n.to_bytes((n.bit_length() + 7) // 8, "big")
    zeros = len(value) - len(value.lstrip("1"))
    return b"\x00" * zeros + raw


def b58encode(value: bytes) -> str:
    n = int.from_bytes(value, "big")
    chars = []
    while n:
        n, rem = divmod(n, 58)
        chars.append(ALPHABET[rem])
    zeros = len(value) - len(value.lstrip(b"\x00"))
    body = "".join(reversed(chars))
    return "1" * zeros + body


def fixed_b58(value: str, length: int, label: str) -> bytes:
    decoded = b58decode(value)
    if len(decoded) != length:
        fail(f"{label} must decode to {length} bytes, got {len(decoded)}")
    return decoded


class Rpc:
    def __init__(self, url: str):
        self.url = url
        self.next_id = 1

    def call(self, method: str, params):
        request_id = self.next_id
        self.next_id += 1
        body = json.dumps(
            {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
        ).encode()
        request = urllib.request.Request(
            self.url,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = json.load(response)
        except Exception as exc:
            fail(f"RPC {method} failed: {exc}")
        if payload.get("error") is not None:
            fail(f"RPC {method} error: {payload['error']}")
        return payload.get("result")


def take(data: bytes, offset: int, size: int, label: str):
    end = offset + size
    if end > len(data):
        fail(f"truncated Note account while reading {label}")
    return data[offset:end], end


def parse_note(data: bytes):
    if len(data) != NOTE_ACCOUNT_LEN:
        fail(f"Note account must be exactly {NOTE_ACCOUNT_LEN} bytes, got {len(data)}")
    if data[:8] != NOTE_DISCRIMINATOR:
        fail("account discriminator is not AIRNOTE Note")

    o = 8
    funder, o = take(data, o, 32, "funder")
    network = data[o]
    o += 1
    note_id, o = take(data, o, 32, "note_id")
    usdc_mint, o = take(data, o, 32, "usdc_mint")
    amount = struct.unpack_from("<Q", data, o)[0]
    o += 8
    owner_p256, o = take(data, o, 33, "owner_p256")
    issued_at = struct.unpack_from("<Q", data, o)[0]
    o += 8
    expires_at = struct.unpack_from("<Q", data, o)[0]
    o += 8
    attestation_digest, o = take(data, o, 32, "attestation_digest")
    risk_tier = data[o]
    o += 1
    status = data[o]
    o += 1
    redeemed_by, o = take(data, o, 32, "redeemed_by")
    redeemed_at = struct.unpack_from("<Q", data, o)[0]
    o += 8
    bump = data[o]
    o += 1
    vault_bump = data[o]
    o += 1
    if o != len(data):
        fail("unexpected trailing Note account bytes")

    if network not in (1, 2):
        fail(f"unsupported AIRNOTE network code {network}")
    if amount not in SUPPORTED_DENOMINATIONS:
        fail(f"unsupported denomination {amount}")
    if owner_p256[0] not in (2, 3):
        fail("owner P-256 key is not compressed SEC1")
    if expires_at <= issued_at:
        fail("Note expiry is not after issue time")
    if status != STATUS_FUNDED:
        fail(f"Note is not FUNDED; status={status}")
    if redeemed_by != bytes(32) or redeemed_at != 0:
        fail("FUNDED note unexpectedly contains redemption state")

    return {
        "funder": funder,
        "network": network,
        "note_id": note_id,
        "usdc_mint": usdc_mint,
        "amount": amount,
        "owner_p256": owner_p256,
        "issued_at": issued_at,
        "expires_at": expires_at,
        "attestation_digest": attestation_digest,
        "risk_tier": risk_tier,
        "bump": bump,
        "vault_bump": vault_bump,
    }


def mint_core(note, program_raw: bytes, note_raw: bytes) -> bytes:
    return b"".join(
        [
            MINT_DOMAIN,
            bytes([VERSION, note["network"]]),
            note["note_id"],
            program_raw,
            note_raw,
            note["usdc_mint"],
            struct.pack("<Q", note["amount"]),
            note["owner_p256"],
            struct.pack("<Q", note["issued_at"]),
            struct.pack("<Q", note["expires_at"]),
            note["attestation_digest"],
            bytes([note["risk_tier"]]),
        ]
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rpc", required=True)
    parser.add_argument("--program", required=True)
    parser.add_argument("--note", required=True)
    parser.add_argument("--vault", required=True)
    parser.add_argument("--signature", required=True)
    args = parser.parse_args()

    program_raw = fixed_b58(args.program, 32, "program id")
    note_raw = fixed_b58(args.note, 32, "note account")
    fixed_b58(args.vault, 32, "vault account")
    signature_raw = fixed_b58(args.signature, 64, "transaction signature")

    rpc = Rpc(args.rpc)

    statuses = rpc.call(
        "getSignatureStatuses",
        [[args.signature], {"searchTransactionHistory": True}],
    )
    if not statuses or len(statuses.get("value", [])) != 1:
        fail("missing signature status")
    status = statuses["value"][0]
    if status is None:
        fail("transaction signature was not found")
    if status.get("err") is not None:
        fail(f"mint transaction failed: {status['err']}")
    if status.get("confirmationStatus") != "finalized":
        fail(f"mint transaction is not finalized: {status.get('confirmationStatus')}")
    finalized_slot = status.get("slot")
    if not isinstance(finalized_slot, int) or finalized_slot < 0:
        fail("invalid finalized slot")

    tx = rpc.call(
        "getTransaction",
        [
            args.signature,
            {
                "encoding": "jsonParsed",
                "commitment": "finalized",
                "maxSupportedTransactionVersion": 0,
            },
        ],
    )
    if tx is None:
        fail("finalized transaction body not found")
    if tx.get("meta", {}).get("err") is not None:
        fail(f"transaction meta reports failure: {tx['meta']['err']}")
    if tx.get("slot") != finalized_slot:
        fail("signature status slot and transaction slot disagree")

    matching_create = False
    for ix in tx.get("transaction", {}).get("message", {}).get("instructions", []):
        if ix.get("programId") != args.program:
            continue
        accounts = ix.get("accounts", [])
        data_text = ix.get("data")
        if args.note not in accounts or args.vault not in accounts or not isinstance(data_text, str):
            continue
        data = b58decode(data_text)
        if data[:8] == CREATE_NOTE_DISCRIMINATOR:
            matching_create = True
            break
    if not matching_create:
        fail("finalized transaction does not contain matching AIRNOTE create_note instruction")

    note_info = rpc.call(
        "getAccountInfo",
        [args.note, {"encoding": "base64", "commitment": "finalized"}],
    )
    note_value = None if not note_info else note_info.get("value")
    if note_value is None:
        fail("finalized Note account not found")
    if note_value.get("owner") != args.program:
        fail("Note account is not owned by AIRNOTE program")
    if note_value.get("executable"):
        fail("Note account must not be executable")
    encoded_data = note_value.get("data")
    if not isinstance(encoded_data, list) or len(encoded_data) < 2 or encoded_data[1] != "base64":
        fail("unexpected Note account encoding")
    try:
        note_data = base64.b64decode(encoded_data[0], validate=True)
    except Exception as exc:
        fail(f"invalid Note base64: {exc}")
    note = parse_note(note_data)

    vault_info = rpc.call(
        "getAccountInfo",
        [args.vault, {"encoding": "jsonParsed", "commitment": "finalized"}],
    )
    vault_value = None if not vault_info else vault_info.get("value")
    if vault_value is None:
        fail("finalized vault token account not found")
    parsed = vault_value.get("data", {}).get("parsed", {})
    if parsed.get("type") != "account":
        fail("vault is not a parsed SPL token account")
    info = parsed.get("info", {})
    if info.get("mint") != b58encode(note["usdc_mint"]):
        fail("vault mint does not match Note usdc_mint")
    if info.get("owner") != args.vault:
        fail("vault token authority is not the vault PDA itself")
    token_amount = info.get("tokenAmount", {})
    if token_amount.get("decimals") != 6:
        fail("vault mint does not use 6 decimals")
    try:
        vault_amount = int(token_amount.get("amount", "-1"))
    except ValueError:
        fail("invalid vault token amount")
    if vault_amount != note["amount"]:
        fail(f"vault amount {vault_amount} != Note amount {note['amount']}")

    core = mint_core(note, program_raw, note_raw)
    if len(core) != 240:
        fail(f"internal mint-core length mismatch: {len(core)}")
    commitment = hashlib.sha256(core).digest()

    result = {
        "finalized_slot": finalized_slot,
        "mint_tx_signature_hex": signature_raw.hex(),
        "note_commitment_hex": commitment.hex(),
        "network": note["network"],
        "note_id_hex": note["note_id"].hex(),
        "note_account": args.note,
        "vault_account": args.vault,
        "usdc_mint": b58encode(note["usdc_mint"]),
        "amount_base_units": note["amount"],
        "owner_p256_compressed_hex": note["owner_p256"].hex(),
        "issued_at_unix_s": note["issued_at"],
        "expires_at_unix_s": note["expires_at"],
        "device_attestation_digest_hex": note["attestation_digest"].hex(),
        "risk_tier": note["risk_tier"],
    }
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
