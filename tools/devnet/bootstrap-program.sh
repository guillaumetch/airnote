#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RPC="${AIRNOTE_RPC:-https://api.devnet.solana.com}"
WALLET="${AIRNOTE_WALLET:-$HOME/.config/solana/id.json}"
PROGRAM_KEYPAIR="target/deploy/airnote_vault-keypair.json"
PROGRAM_SO="target/deploy/airnote_vault.so"

for cmd in solana solana-keygen anchor; do
  command -v "$cmd" >/dev/null || { echo "missing required command: $cmd" >&2; exit 1; }
done

if [[ ! -f "$WALLET" ]]; then
  echo "wallet keypair not found: $WALLET" >&2
  exit 1
fi

mkdir -p target/deploy
if [[ ! -f "$PROGRAM_KEYPAIR" ]]; then
  echo "Generating local AIRNOTE program identity at $PROGRAM_KEYPAIR"
  solana-keygen new --no-bip39-passphrase --silent -o "$PROGRAM_KEYPAIR"
else
  echo "Reusing existing local AIRNOTE program identity at $PROGRAM_KEYPAIR"
fi

PROGRAM_ID="$(solana-keygen pubkey "$PROGRAM_KEYPAIR")"
echo "AIRNOTE program id: $PROGRAM_ID"

echo "Syncing the PUBLIC program id into Anchor.toml + declare_id!"
anchor keys sync

echo "Building AIRNOTE SBF with synchronized id"
anchor build --skip-lint
[[ -f "$PROGRAM_SO" ]] || { echo "SBF artifact missing: $PROGRAM_SO" >&2; exit 1; }

echo "Wallet: $(solana-keygen pubkey "$WALLET")"
echo "SOL balance: $(solana -u "$RPC" -k "$WALLET" balance 2>/dev/null || true)"

echo "Deploying AIRNOTE to devnet"
solana -u "$RPC" -k "$WALLET" program deploy \
  "$PROGRAM_SO" \
  --program-id "$PROGRAM_KEYPAIR"

echo
echo "Deployment proof:"
solana -u "$RPC" program show "$PROGRAM_ID"

echo
echo "AIRNOTE_PROGRAM_ID=$PROGRAM_ID"
echo "Only public id changes should be committed. target/deploy/*-keypair.json remains local/ignored."
echo "Review with: git diff -- Anchor.toml programs/airnote_vault/src/lib.rs"
