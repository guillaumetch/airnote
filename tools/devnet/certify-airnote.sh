#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [[ $# -lt 5 || $# -gt 7 ]]; then
  cat >&2 <<'EOF'
usage: tools/devnet/certify-airnote.sh \
  <PROGRAM_ID> <NOTE_ACCOUNT> <VAULT_ACCOUNT> <MINT_TX_SIGNATURE> <ATTESTOR_SEED_FILE> \
  [ATTESTOR_KEY_ID=1] [RPC=https://api.devnet.solana.com]
EOF
  exit 2
fi

PROGRAM_ID="$1"
NOTE_ACCOUNT="$2"
VAULT_ACCOUNT="$3"
MINT_TX_SIGNATURE="$4"
SEED_FILE="$5"
ATTESTOR_KEY_ID="${6:-1}"
RPC="${7:-https://api.devnet.solana.com}"

if [[ ! -f "$SEED_FILE" ]]; then
  echo "attestor seed file not found: $SEED_FILE" >&2
  exit 1
fi

mkdir -p .airnote/certificates
OBSERVATION="$(mktemp)"
ATTESTATION="$(mktemp)"
trap 'rm -f "$OBSERVATION" "$ATTESTATION"' EXIT

python3 tools/observe_finalized_note.py \
  --rpc "$RPC" \
  --program "$PROGRAM_ID" \
  --note "$NOTE_ACCOUNT" \
  --vault "$VAULT_ACCOUNT" \
  --signature "$MINT_TX_SIGNATURE" \
  > "$OBSERVATION"

read_json() {
  python3 - "$OBSERVATION" "$1" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    value = json.load(handle)[sys.argv[2]]
print(value)
PY
}

NOTE_COMMITMENT="$(read_json note_commitment_hex)"
FINALIZED_SLOT="$(read_json finalized_slot)"
MINT_TX_SIGNATURE_HEX="$(read_json mint_tx_signature_hex)"
NOTE_ID_HEX="$(read_json note_id_hex)"

cargo run --quiet --manifest-path tools/airnote_attestor/Cargo.toml -- \
  issue \
  "$SEED_FILE" \
  "$NOTE_COMMITMENT" \
  "$FINALIZED_SLOT" \
  "$MINT_TX_SIGNATURE_HEX" \
  "$ATTESTOR_KEY_ID" \
  > "$ATTESTATION"

OUT=".airnote/certificates/${NOTE_ID_HEX}.json"
python3 - "$OBSERVATION" "$ATTESTATION" "$PROGRAM_ID" "$ATTESTOR_KEY_ID" "$OUT" <<'PY'
import json, sys

observation_path, attestation_path, program_id, key_id, out_path = sys.argv[1:]
with open(observation_path, "r", encoding="utf-8") as handle:
    obs = json.load(handle)

kv = {}
with open(attestation_path, "r", encoding="utf-8") as handle:
    for line in handle:
        line = line.strip()
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        kv[key] = value

required = {"attestor_public_key", "certificate_payload", "attestor_signature"}
missing = required - kv.keys()
if missing:
    raise SystemExit(f"attestor output missing fields: {sorted(missing)}")

package = {
    "version": 1,
    "mint_core": {
        "network": obs["network"],
        "note_id_hex": obs["note_id_hex"],
        "program_id": program_id,
        "note_account": obs["note_account"],
        "usdc_mint": obs["usdc_mint"],
        "amount_base_units": obs["amount_base_units"],
        "owner_p256_compressed_hex": obs["owner_p256_compressed_hex"],
        "issued_at_unix_s": obs["issued_at_unix_s"],
        "expires_at_unix_s": obs["expires_at_unix_s"],
        "device_attestation_digest_hex": obs["device_attestation_digest_hex"],
        "risk_tier": obs["risk_tier"],
    },
    "certificate": {
        "note_commitment_hex": obs["note_commitment_hex"],
        "finalized_slot": obs["finalized_slot"],
        "mint_tx_signature_hex": obs["mint_tx_signature_hex"],
        "attestor_key_id": int(key_id),
        "attestor_public_key_hex": kv["attestor_public_key"],
        "certificate_payload_hex": kv["certificate_payload"],
        "attestor_signature_hex": kv["attestor_signature"],
    },
}

with open(out_path, "w", encoding="utf-8") as handle:
    json.dump(package, handle, indent=2, sort_keys=True)
    handle.write("\n")
print(json.dumps(package, indent=2, sort_keys=True))
PY

echo >&2
echo "Certified Airnote written to: $OUT" >&2
