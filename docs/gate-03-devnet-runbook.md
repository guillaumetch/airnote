# AIRNOTE Gate 03 — Devnet Runbook

This runbook creates the first **real finalized devnet Airnotes**. Private deployment keys, wallet keys, StrongBox private keys, and the mint-attestor seed stay local.

## Constants

- cluster: Solana Devnet
- RPC default: `https://api.devnet.solana.com`
- network code: `1`
- Circle Solana Devnet USDC mint: `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU`
- USDC decimals: `6`
- Gate 03 demo denominations: `1` and `5` USDC
- demo risk tier: `1` (provisional devnet policy; not a permanent public risk-rating definition)

## 0. Prerequisites

Required locally:

```bash
node --version       # Node 20+
npm --version
python3 --version
rustc --version
solana --version     # AIRNOTE CI pins 4.1.2
anchor --version     # AIRNOTE CI pins 1.2.0
```

Work from the Gate 03 branch:

```bash
git checkout gate-03-mint-airnotes
git pull origin gate-03-mint-airnotes
npm install --prefix tools/devnet
```

## 1. Create two Seeker note-owner keys

Build/install the Android app, then on the Seeker tap:

`CREATE GATE 03 NOTE OWNER`

Do this **twice**: once for the $1 Airnote and once for the $5 Airnote.

Each report must say:

```text
ATTESTATION_SECURITY_LEVEL=STRONGBOX
KEYMINT_SECURITY_LEVEL=STRONGBOX
PRIVATE_KEY_EXPORTABLE=false
MAX_USAGE_COUNT_REQUESTED=false
```

Record these fields for each note:

```text
NOTE_ID_HEX
KEY_ALIAS
OWNER_P256_COMPRESSED_HEX
DEVICE_ATTESTATION_DIGEST_HEX
```

Do not use a Gate 00 alias. Gate 00 keys requested `maxUsageCount=1` and were intentionally disposable.

The Gate 03 key is one persistent StrongBox P-256 key per note. The private key never leaves Android Keystore. Keep its alias until the note is redeemed or refunded.

## 2. Generate the real program identity and deploy

Set the wallet path if needed:

```bash
export AIRNOTE_WALLET="$HOME/.config/solana/id.json"
export AIRNOTE_RPC="https://api.devnet.solana.com"
```

Fund the wallet with devnet SOL if necessary:

```bash
solana -u devnet airdrop 2
solana -u devnet balance
```

Then:

```bash
bash tools/devnet/bootstrap-program.sh
```

The script:

1. creates `target/deploy/airnote_vault-keypair.json` locally if absent
2. runs `anchor keys sync`
3. rebuilds the program with the real program id
4. deploys to Devnet
5. prints `AIRNOTE_PROGRAM_ID=...`

The program keypair is private local deployment material and must never be committed.

After deployment, inspect the public-id diff:

```bash
git diff -- Anchor.toml programs/airnote_vault/src/lib.rs
```

Only the public program id should have changed. Commit those public changes before treating the deployment as canonical.

## 3. Fund the wallet with Circle Devnet USDC

Use Circle's public testnet faucet and select **Solana Devnet / USDC**. The wallet used by the AIRNOTE devnet client needs at least 6 USDC for the $1 + $5 pair.

Verify the token account after funding:

```bash
spl-token balance 4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU \
  --owner "$AIRNOTE_WALLET" \
  --url devnet
```

## 4. Initialize AIRNOTE config

```bash
export AIRNOTE_PROGRAM_ID="<PROGRAM_ID_FROM_STEP_2>"

node tools/devnet/airnote-devnet.mjs init \
  --program "$AIRNOTE_PROGRAM_ID" \
  --wallet "$AIRNOTE_WALLET" \
  --rpc "$AIRNOTE_RPC"
```

This binds the deployed AIRNOTE program to network code `1` and Circle's 6-decimal Devnet USDC mint.

The command is idempotent: if a matching Config PDA already exists it validates it and returns `already_initialized`.

## 5. Mint the finalized $1 Airnote

Paste the **first** Seeker report values:

```bash
export NOTE1_ID="<NOTE_ID_HEX>"
export NOTE1_OWNER="<OWNER_P256_COMPRESSED_HEX>"
export NOTE1_ATTESTATION="<DEVICE_ATTESTATION_DIGEST_HEX>"

mkdir -p .airnote
node tools/devnet/airnote-devnet.mjs mint \
  --program "$AIRNOTE_PROGRAM_ID" \
  --wallet "$AIRNOTE_WALLET" \
  --note-id "$NOTE1_ID" \
  --owner-p256 "$NOTE1_OWNER" \
  --attestation-digest "$NOTE1_ATTESTATION" \
  --amount-usdc 1 \
  --lifetime-seconds 3600 \
  --risk-tier 1 \
  --rpc "$AIRNOTE_RPC" | tee .airnote/mint-1.json
```

The client waits until the create-note transaction is **finalized** before returning.

## 6. Mint the finalized $5 Airnote

Use the **second** Seeker report:

```bash
export NOTE5_ID="<NOTE_ID_HEX>"
export NOTE5_OWNER="<OWNER_P256_COMPRESSED_HEX>"
export NOTE5_ATTESTATION="<DEVICE_ATTESTATION_DIGEST_HEX>"

node tools/devnet/airnote-devnet.mjs mint \
  --program "$AIRNOTE_PROGRAM_ID" \
  --wallet "$AIRNOTE_WALLET" \
  --note-id "$NOTE5_ID" \
  --owner-p256 "$NOTE5_OWNER" \
  --attestation-digest "$NOTE5_ATTESTATION" \
  --amount-usdc 5 \
  --lifetime-seconds 3600 \
  --risk-tier 1 \
  --rpc "$AIRNOTE_RPC" | tee .airnote/mint-5.json
```

## 7. Create the real devnet mint-attestor key

Generate this once, locally:

```bash
mkdir -p .airnote
umask 077
openssl rand -hex 32 > .airnote/attestor-1.seed
```

Print only its public key:

```bash
cargo run --quiet --manifest-path tools/airnote_attestor/Cargo.toml -- \
  pubkey .airnote/attestor-1.seed
```

The seed stays local. Before Gate 04's offline acceptance UI is considered trusted, this public key must be pinned in the app's devnet keyring under key id `1`.

## 8. Independently observe + certify each funded note

Helper to read the minter JSON:

```bash
json_field () { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$1" "$2"; }
```

### $1

```bash
MINT=.airnote/mint-1.json
NOTE="$(json_field "$MINT" note_account)"
VAULT="$(json_field "$MINT" vault_account)"
SIG="$(json_field "$MINT" signature)"

bash tools/devnet/certify-airnote.sh \
  "$AIRNOTE_PROGRAM_ID" "$NOTE" "$VAULT" "$SIG" \
  .airnote/attestor-1.seed 1 "$AIRNOTE_RPC"
```

### $5

```bash
MINT=.airnote/mint-5.json
NOTE="$(json_field "$MINT" note_account)"
VAULT="$(json_field "$MINT" vault_account)"
SIG="$(json_field "$MINT" signature)"

bash tools/devnet/certify-airnote.sh \
  "$AIRNOTE_PROGRAM_ID" "$NOTE" "$VAULT" "$SIG" \
  .airnote/attestor-1.seed 1 "$AIRNOTE_RPC"
```

The wrapper does **not** trust the minter output. `observe_finalized_note.py` independently checks:

- transaction status is finalized and successful
- the finalized transaction contains the matching AIRNOTE `create_note`
- Note PDA is owned by the deployed AIRNOTE program
- Note state is still `FUNDED`
- amount is one supported denomination
- vault mint matches the Note USDC mint
- vault token authority is the vault PDA itself
- vault uses 6 decimals
- vault balance exactly equals the Note amount
- canonical 240-byte mint core is reconstructed from finalized state
- note commitment is recomputed from that finalized state

Only after those checks does the isolated Ed25519 attestor sign the canonical 128-byte mint-certificate payload.

Certified packages are written under:

```text
.airnote/certificates/<NOTE_ID_HEX>.json
```

`.airnote/` is private/local and ignored by git.

## Gate 03 live acceptance

Gate 03 is complete when all are recorded:

```text
PROGRAM_DEPLOYED_DEVNET=PASS
CONFIG_USDC_BINDING=PASS
SEEKER_STRONGBOX_NOTE_KEY_1=PASS
SEEKER_STRONGBOX_NOTE_KEY_5=PASS
FINALIZED_1_USDC_NOTE=PASS
FINALIZED_1_USDC_CERTIFICATE=PASS
FINALIZED_5_USDC_NOTE=PASS
FINALIZED_5_USDC_CERTIFICATE=PASS
ATTESTOR_PRIVATE_SEED_COMMITTED=false
PROGRAM_PRIVATE_KEY_COMMITTED=false
```

Then proceed to **Gate 04 — two-phone offline QR transfer**.
