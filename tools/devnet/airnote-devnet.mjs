#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { createHash } from "node:crypto";
import {
  Connection,
  Keypair,
  PublicKey,
  SystemProgram,
  Transaction,
  TransactionInstruction,
} from "@solana/web3.js";
import {
  ASSOCIATED_TOKEN_PROGRAM_ID,
  TOKEN_PROGRAM_ID,
  getAssociatedTokenAddressSync,
} from "@solana/spl-token";

const DEFAULT_RPC = "https://api.devnet.solana.com";
const DEVNET_USDC = new PublicKey("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU");
const NETWORK_DEVNET = 1;
const CONFIG_SEED = Buffer.from("config");
const NOTE_SEED = Buffer.from("note");
const VAULT_SEED = Buffer.from("vault");
const SUPPORTED_USDC = new Set([1, 5, 10]);
const CONFIG_DISCRIMINATOR = discriminator("account:Config");

function usage(message) {
  if (message) console.error(`error: ${message}\n`);
  console.error(`AIRNOTE devnet tool

Usage:
  node airnote-devnet.mjs init \\
    --program <PROGRAM_ID> \\
    --wallet <SOLANA_KEYPAIR_JSON> [--rpc <URL>]

  node airnote-devnet.mjs mint \\
    --program <PROGRAM_ID> \\
    --wallet <SOLANA_KEYPAIR_JSON> \\
    --note-id <32_BYTE_HEX> \\
    --owner-p256 <33_BYTE_COMPRESSED_HEX> \\
    --attestation-digest <32_BYTE_HEX> \\
    --amount-usdc <1|5|10> \\
    [--lifetime-seconds <60..86400>] \\
    [--risk-tier <0..3>] \\
    [--rpc <URL>]

  node airnote-devnet.mjs mint-request \\
    --program <PROGRAM_ID> \\
    --wallet <SOLANA_KEYPAIR_JSON> \\
    --request <SENDER_MINT_REQUEST_TXT> \\
    [--risk-tier <0..3>] \\
    [--rpc <URL>]

mint-request takes amount, lifetime, note id, owner P-256 key and attestation digest
from the Seeker-generated sender request. The sender-selected lifetime is authoritative.

This tool reads a local Solana keypair file. It never prints the secret key.`);
  process.exit(message ? 2 : 0);
}

function parseOptions(values) {
  const out = {};
  for (let i = 0; i < values.length; i += 2) {
    const key = values[i];
    const value = values[i + 1];
    if (!key?.startsWith("--") || value === undefined) usage(`invalid option near ${key ?? "<end>"}`);
    out[key.slice(2)] = value;
  }
  return out;
}

function required(options, name) {
  const value = options[name];
  if (!value) usage(`missing --${name}`);
  return value;
}

function expandHome(value) {
  return value.startsWith("~/") ? path.join(os.homedir(), value.slice(2)) : value;
}

function readKeypair(filename) {
  const full = expandHome(filename);
  const secret = JSON.parse(fs.readFileSync(full, "utf8"));
  if (!Array.isArray(secret) || secret.length !== 64) {
    throw new Error(`wallet file must contain a 64-byte Solana secret key array: ${full}`);
  }
  return Keypair.fromSecretKey(Uint8Array.from(secret));
}

function readSenderMintRequest(filename) {
  const full = expandHome(filename);
  const text = fs.readFileSync(full, "utf8");
  if (!text.startsWith("AIRNOTE — SENDER MINT REQUEST")) {
    throw new Error("request file is not an AIRNOTE sender mint request");
  }
  const fields = {};
  for (const line of text.split(/\r?\n/)) {
    const split = line.indexOf("=");
    if (split <= 0) continue;
    const key = line.slice(0, split).trim();
    const value = line.slice(split + 1).trim();
    if (key && value) fields[key] = value;
  }
  for (const key of [
    "AMOUNT_USDC",
    "LIFETIME_SECONDS",
    "NOTE_ID_HEX",
    "OWNER_P256_COMPRESSED_HEX",
    "DEVICE_ATTESTATION_DIGEST_HEX",
  ]) {
    if (!fields[key]) throw new Error(`sender mint request missing ${key}`);
  }
  const lifetime = Number(fields.LIFETIME_SECONDS);
  if (!Number.isInteger(lifetime) || lifetime < 300 || lifetime > 86_400) {
    throw new Error("sender mint request lifetime must be between 300 and 86400 seconds");
  }
  return fields;
}

function discriminator(label) {
  return createHash("sha256").update(label).digest().subarray(0, 8);
}

function fixedHex(value, bytes, label) {
  if (!/^[0-9a-fA-F]+$/.test(value) || value.length !== bytes * 2) {
    throw new Error(`${label} must be exactly ${bytes} bytes of hex`);
  }
  return Buffer.from(value, "hex");
}

function u64le(value) {
  const out = Buffer.alloc(8);
  out.writeBigUInt64LE(BigInt(value));
  return out;
}

function sameBytes(a, b) {
  return Buffer.from(a).equals(Buffer.from(b));
}

function derive(programId, seed, noteId) {
  const seeds = noteId ? [seed, noteId] : [seed];
  return PublicKey.findProgramAddressSync(seeds, programId)[0];
}

function decodeConfig(account, expectedProgram, expectedMint) {
  if (!account.owner.equals(expectedProgram)) throw new Error("config account is not owned by AIRNOTE program");
  const data = Buffer.from(account.data);
  if (data.length !== 74) throw new Error(`unexpected Config account length ${data.length}`);
  if (!sameBytes(data.subarray(0, 8), CONFIG_DISCRIMINATOR)) throw new Error("invalid AIRNOTE Config discriminator");
  const network = data[40];
  const mint = new PublicKey(data.subarray(41, 73));
  if (network !== NETWORK_DEVNET) throw new Error(`AIRNOTE Config network=${network}, expected devnet=1`);
  if (!mint.equals(expectedMint)) throw new Error(`AIRNOTE Config USDC=${mint}, expected ${expectedMint}`);
  return { network, mint: mint.toBase58() };
}

async function waitFinalized(connection, signature, timeoutMs = 120_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const result = await connection.getSignatureStatuses([signature], { searchTransactionHistory: true });
    const status = result.value[0];
    if (status?.err) throw new Error(`transaction failed: ${JSON.stringify(status.err)}`);
    if (status?.confirmationStatus === "finalized") return status.slot;
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`transaction did not reach finalized within ${timeoutMs / 1000}s: ${signature}`);
}

async function sendAndFinalize(connection, payer, instruction) {
  const latest = await connection.getLatestBlockhash("confirmed");
  const tx = new Transaction({
    feePayer: payer.publicKey,
    recentBlockhash: latest.blockhash,
  }).add(instruction);
  tx.sign(payer);
  const signature = await connection.sendRawTransaction(tx.serialize(), {
    skipPreflight: false,
    preflightCommitment: "confirmed",
    maxRetries: 5,
  });
  const confirmation = await connection.confirmTransaction(
    { signature, blockhash: latest.blockhash, lastValidBlockHeight: latest.lastValidBlockHeight },
    "confirmed",
  );
  if (confirmation.value.err) throw new Error(`transaction confirmation failed: ${JSON.stringify(confirmation.value.err)}`);
  const finalizedSlot = await waitFinalized(connection, signature);
  return { signature, finalizedSlot };
}

async function ensureConfig(connection, programId, usdcMint) {
  const config = derive(programId, CONFIG_SEED);
  const account = await connection.getAccountInfo(config, "confirmed");
  if (!account) throw new Error(`AIRNOTE config does not exist at ${config}; run the init command first`);
  decodeConfig(account, programId, usdcMint);
  return config;
}

async function commandInit(options) {
  const rpc = options.rpc ?? DEFAULT_RPC;
  const programId = new PublicKey(required(options, "program"));
  const payer = readKeypair(required(options, "wallet"));
  const connection = new Connection(rpc, "confirmed");
  const config = derive(programId, CONFIG_SEED);
  const existing = await connection.getAccountInfo(config, "confirmed");

  if (existing) {
    decodeConfig(existing, programId, DEVNET_USDC);
    console.log(JSON.stringify({
      status: "already_initialized",
      rpc,
      program_id: programId.toBase58(),
      config: config.toBase58(),
      admin: payer.publicKey.toBase58(),
      network: NETWORK_DEVNET,
      usdc_mint: DEVNET_USDC.toBase58(),
    }, null, 2));
    return;
  }

  const data = Buffer.concat([discriminator("global:initialize_config"), Buffer.from([NETWORK_DEVNET])]);
  const instruction = new TransactionInstruction({
    programId,
    keys: [
      { pubkey: payer.publicKey, isSigner: true, isWritable: true },
      { pubkey: config, isSigner: false, isWritable: true },
      { pubkey: DEVNET_USDC, isSigner: false, isWritable: false },
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ],
    data,
  });
  const result = await sendAndFinalize(connection, payer, instruction);
  console.log(JSON.stringify({
    status: "initialized",
    rpc,
    program_id: programId.toBase58(),
    config: config.toBase58(),
    admin: payer.publicKey.toBase58(),
    network: NETWORK_DEVNET,
    usdc_mint: DEVNET_USDC.toBase58(),
    signature: result.signature,
    finalized_slot: result.finalizedSlot,
  }, null, 2));
}

async function commandMint(options) {
  const rpc = options.rpc ?? DEFAULT_RPC;
  const programId = new PublicKey(required(options, "program"));
  const payer = readKeypair(required(options, "wallet"));
  const noteId = fixedHex(required(options, "note-id"), 32, "note id");
  const ownerP256 = fixedHex(required(options, "owner-p256"), 33, "owner P-256 key");
  const attestationDigest = fixedHex(required(options, "attestation-digest"), 32, "attestation digest");
  if (ownerP256[0] !== 0x02 && ownerP256[0] !== 0x03) {
    throw new Error("owner P-256 key must be compressed SEC1 and start with 02 or 03");
  }

  const amountUsdc = Number(required(options, "amount-usdc"));
  if (!Number.isInteger(amountUsdc) || !SUPPORTED_USDC.has(amountUsdc)) {
    throw new Error("--amount-usdc must be exactly 1, 5 or 10");
  }
  const amountBaseUnits = BigInt(amountUsdc) * 1_000_000n;

  const lifetime = Number(options["lifetime-seconds"] ?? "3600");
  if (!Number.isInteger(lifetime) || lifetime < 60 || lifetime > 86_400) {
    throw new Error("--lifetime-seconds must be an integer from 60 through 86400");
  }
  const riskTier = Number(options["risk-tier"] ?? "1");
  if (!Number.isInteger(riskTier) || riskTier < 0 || riskTier > 3) {
    throw new Error("--risk-tier must be an integer from 0 through 3");
  }

  const connection = new Connection(rpc, "confirmed");
  const config = await ensureConfig(connection, programId, DEVNET_USDC);
  const note = derive(programId, NOTE_SEED, noteId);
  const vault = derive(programId, VAULT_SEED, noteId);
  if (await connection.getAccountInfo(note, "confirmed")) {
    throw new Error(`note PDA already exists: ${note}`);
  }

  const funderToken = getAssociatedTokenAddressSync(
    DEVNET_USDC,
    payer.publicKey,
    false,
    TOKEN_PROGRAM_ID,
    ASSOCIATED_TOKEN_PROGRAM_ID,
  );
  let tokenBalance;
  try {
    tokenBalance = await connection.getTokenAccountBalance(funderToken, "confirmed");
  } catch {
    throw new Error(`USDC token account not found: ${funderToken}. Fund ${payer.publicKey} with Circle Solana Devnet USDC first.`);
  }
  const available = BigInt(tokenBalance.value.amount);
  if (available < amountBaseUnits) {
    throw new Error(`insufficient devnet USDC: have ${available} base units, need ${amountBaseUnits}`);
  }

  const slot = await connection.getSlot("finalized");
  const blockTime = await connection.getBlockTime(slot);
  if (blockTime === null) throw new Error(`could not read finalized block time for slot ${slot}`);
  const expiresAt = BigInt(blockTime + lifetime);

  const data = Buffer.concat([
    discriminator("global:create_note"),
    noteId,
    u64le(amountBaseUnits),
    ownerP256,
    u64le(expiresAt),
    attestationDigest,
    Buffer.from([riskTier]),
  ]);
  const instruction = new TransactionInstruction({
    programId,
    keys: [
      { pubkey: payer.publicKey, isSigner: true, isWritable: true },
      { pubkey: config, isSigner: false, isWritable: false },
      { pubkey: DEVNET_USDC, isSigner: false, isWritable: false },
      { pubkey: funderToken, isSigner: false, isWritable: true },
      { pubkey: note, isSigner: false, isWritable: true },
      { pubkey: vault, isSigner: false, isWritable: true },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ],
    data,
  });

  const result = await sendAndFinalize(connection, payer, instruction);
  console.log(JSON.stringify({
    status: "funded_finalized",
    rpc,
    program_id: programId.toBase58(),
    config: config.toBase58(),
    funder: payer.publicKey.toBase58(),
    funder_token: funderToken.toBase58(),
    usdc_mint: DEVNET_USDC.toBase58(),
    amount_usdc: amountUsdc,
    amount_base_units: amountBaseUnits.toString(),
    requested_lifetime_seconds: lifetime,
    note_id_hex: noteId.toString("hex"),
    note_account: note.toBase58(),
    vault_account: vault.toBase58(),
    owner_p256_compressed_hex: ownerP256.toString("hex"),
    device_attestation_digest_hex: attestationDigest.toString("hex"),
    risk_tier: riskTier,
    requested_expires_at_unix_s: expiresAt.toString(),
    signature: result.signature,
    finalized_slot: result.finalizedSlot,
  }, null, 2));
}

async function commandMintRequest(options) {
  const fields = readSenderMintRequest(required(options, "request"));
  return commandMint({
    program: required(options, "program"),
    wallet: required(options, "wallet"),
    rpc: options.rpc,
    "risk-tier": options["risk-tier"] ?? "1",
    "note-id": fields.NOTE_ID_HEX,
    "owner-p256": fields.OWNER_P256_COMPRESSED_HEX,
    "attestation-digest": fields.DEVICE_ATTESTATION_DIGEST_HEX,
    "amount-usdc": fields.AMOUNT_USDC,
    "lifetime-seconds": fields.LIFETIME_SECONDS,
  });
}

async function main() {
  const command = process.argv[2];
  if (!command || command === "help" || command === "--help" || command === "-h") usage();
  const options = parseOptions(process.argv.slice(3));
  if (command === "init") return commandInit(options);
  if (command === "mint") return commandMint(options);
  if (command === "mint-request") return commandMintRequest(options);
  usage(`unknown command ${command}`);
}

main().catch((error) => {
  console.error(`AIRNOTE DEVNET FAIL: ${error?.stack ?? error}`);
  process.exit(1);
});
