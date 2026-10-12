#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
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
const SECP256R1_PROGRAM_ID = new PublicKey("Secp256r1SigVerify1111111111111111111111111");
const INSTRUCTIONS_SYSVAR = new PublicKey("Sysvar1nstructions1111111111111111111111111");
const NOTE_SEED = Buffer.from("note");
const VAULT_SEED = Buffer.from("vault");
const TRANSFER_PREFIX = "airnote:t1:";
const TRANSFER_BUNDLE_BYTES = 597;
const TRANSFER_DOMAIN = Buffer.from("AIRNOTE_TRANSFER_V1", "ascii");
const MINT_DOMAIN = Buffer.from("AIRNOTE_MINT_CORE_V1", "ascii");
const CERT_DOMAIN = Buffer.from("AIRNOTE_MINT_CERT_V1", "ascii");
const RECIPIENT_DOMAIN = Buffer.from("AIRNOTE_RECIPIENT_V1", "ascii");
const VERSION = 1;

function usage(message) {
  if (message) console.error(`error: ${message}\n`);
  console.error(`AIRNOTE Gate 05 Devnet redemption

Usage:
  node redeem-airnote.mjs \\
    --program <AIRNOTE_PROGRAM_ID> \\
    --transfer <FILE_CONTAINING_airnote:t1_PAYLOAD> \\
    --recipient-wallet <RECIPIENT_SOLANA_KEYPAIR_JSON> \\
    [--payer-wallet <FEE_PAYER_KEYPAIR_JSON>] \\
    [--rpc <DEVNET_RPC>]

The recipient request expiry is checked during the offline handoff, not as a
post-acceptance settlement deadline. Settlement remains valid until the Airnote
itself expires. If --payer-wallet is omitted, the recipient pays transaction/ATA
rent. The tool never prints secret key material.`);
  process.exit(message ? 2 : 0);
}

function parseOptions(args) {
  if (args.includes("--help") || args.includes("-h")) usage();
  const out = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i];
    const value = args[i + 1];
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

function u16le(value) {
  const out = Buffer.alloc(2);
  out.writeUInt16LE(value);
  return out;
}

function u64le(value) {
  const out = Buffer.alloc(8);
  out.writeBigUInt64LE(BigInt(value));
  return out;
}

function same(a, b) {
  return Buffer.from(a).equals(Buffer.from(b));
}

function discriminator(label) {
  return createHash("sha256").update(label).digest().subarray(0, 8);
}

function derive(programId, seed, noteId) {
  return PublicKey.findProgramAddressSync([seed, noteId], programId)[0];
}

function readTransfer(filename) {
  const text = fs.readFileSync(expandHome(filename), "utf8").trim();
  if (!text.startsWith(TRANSFER_PREFIX)) throw new Error("transfer file is not an AIRNOTE Gate 04 transfer payload");
  let raw;
  try {
    raw = Buffer.from(text.slice(TRANSFER_PREFIX.length), "base64url");
  } catch (error) {
    throw new Error(`invalid AIRNOTE base64url transfer payload: ${error.message}`);
  }
  if (raw.length !== TRANSFER_BUNDLE_BYTES) {
    throw new Error(`transfer bundle must be ${TRANSFER_BUNDLE_BYTES} bytes, got ${raw.length}`);
  }

  const mint = raw.subarray(0, 240);
  const cert = raw.subarray(240, 368);
  const request = raw.subarray(432, 533);
  const transferSignature = raw.subarray(533, 597);

  if (!same(mint.subarray(0, 20), MINT_DOMAIN) || mint[20] !== VERSION) throw new Error("invalid mint core domain/version");
  if (!same(cert.subarray(0, 20), CERT_DOMAIN)) throw new Error("invalid mint certificate domain");
  if (!same(request.subarray(0, 20), RECIPIENT_DOMAIN) || request[20] !== VERSION) {
    throw new Error("invalid recipient request domain/version");
  }

  const noteId = mint.subarray(22, 54);
  const programId = new PublicKey(mint.subarray(54, 86));
  const noteAccount = new PublicKey(mint.subarray(86, 118));
  const usdcMint = new PublicKey(mint.subarray(118, 150));
  const amountBaseUnits = mint.readBigUInt64LE(150);
  const ownerP256 = mint.subarray(158, 191);
  const issuedAt = mint.readBigUInt64LE(191);
  const noteExpiresAt = mint.readBigUInt64LE(199);
  const riskTier = mint[239];

  const commitment = cert.subarray(20, 52);
  const recomputedCommitment = createHash("sha256").update(mint).digest();
  if (!same(commitment, recomputedCommitment)) throw new Error("mint certificate commitment does not match mint core");

  const recipient = new PublicKey(request.subarray(21, 53));
  const recipientNonce = request.subarray(53, 85);
  const requestCreatedAt = request.readBigUInt64LE(85);
  const requestExpiresAt = request.readBigUInt64LE(93);

  const message = Buffer.concat([
    TRANSFER_DOMAIN,
    Buffer.from([VERSION]),
    commitment,
    recipient.toBuffer(),
    recipientNonce,
    u64le(requestExpiresAt),
  ]);
  if (message.length !== 124) throw new Error(`unexpected transfer message length ${message.length}`);

  return {
    text,
    raw,
    mint,
    noteId,
    programId,
    noteAccount,
    usdcMint,
    amountBaseUnits,
    ownerP256,
    issuedAt,
    noteExpiresAt,
    riskTier,
    commitment,
    recipient,
    recipientNonce,
    requestCreatedAt,
    requestExpiresAt,
    transferSignature,
    message,
  };
}

function p256Instruction(ownerP256, signature, message) {
  if (ownerP256.length !== 33) throw new Error("P-256 owner key must be 33 bytes");
  if (ownerP256[0] !== 0x02 && ownerP256[0] !== 0x03) throw new Error("P-256 owner key is not compressed SEC1");
  if (signature.length !== 64) throw new Error("P-256 compact signature must be 64 bytes");
  if (message.length > 0xffff) throw new Error("P-256 message is too large");

  const signatureOffset = 16;
  const publicKeyOffset = 80;
  const messageOffset = 113;
  const currentInstruction = 0xffff;
  const header = Buffer.concat([
    Buffer.from([1, 0]),
    u16le(signatureOffset),
    u16le(currentInstruction),
    u16le(publicKeyOffset),
    u16le(currentInstruction),
    u16le(messageOffset),
    u16le(message.length),
    u16le(currentInstruction),
  ]);
  const data = Buffer.concat([header, signature, ownerP256, message]);
  if (data.length !== 113 + message.length) throw new Error("internal P-256 instruction layout mismatch");
  return new TransactionInstruction({ programId: SECP256R1_PROGRAM_ID, keys: [], data });
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

async function chainTime(connection) {
  const slot = await connection.getSlot("finalized");
  const time = await connection.getBlockTime(slot);
  if (time === null) throw new Error(`could not read block time for finalized slot ${slot}`);
  return { slot, unixSeconds: BigInt(time) };
}

async function main() {
  const options = parseOptions(process.argv.slice(2));
  const rpc = options.rpc ?? DEFAULT_RPC;
  const expectedProgram = new PublicKey(required(options, "program"));
  const transfer = readTransfer(required(options, "transfer"));
  const recipient = readKeypair(required(options, "recipient-wallet"));
  const payer = options["payer-wallet"] ? readKeypair(options["payer-wallet"]) : recipient;

  if (!transfer.programId.equals(expectedProgram)) {
    throw new Error(`transfer program ${transfer.programId} does not match --program ${expectedProgram}`);
  }
  if (!transfer.usdcMint.equals(DEVNET_USDC)) {
    throw new Error(`transfer USDC mint ${transfer.usdcMint} is not Circle Solana Devnet USDC`);
  }
  if (!transfer.recipient.equals(recipient.publicKey)) {
    throw new Error(`transfer recipient ${transfer.recipient} does not match recipient keypair ${recipient.publicKey}`);
  }
  if (![1_000_000n, 5_000_000n, 10_000_000n].includes(transfer.amountBaseUnits)) {
    throw new Error(`unsupported AIRNOTE amount ${transfer.amountBaseUnits}`);
  }
  if (transfer.riskTier > 1) throw new Error(`risk tier ${transfer.riskTier} exceeds Gate 05 policy`);

  const note = derive(expectedProgram, NOTE_SEED, transfer.noteId);
  const vault = derive(expectedProgram, VAULT_SEED, transfer.noteId);
  if (!note.equals(transfer.noteAccount)) {
    throw new Error(`transfer note account ${transfer.noteAccount} does not match PDA ${note}`);
  }

  const connection = new Connection(rpc, "confirmed");
  const noteInfo = await connection.getAccountInfo(note, "confirmed");
  if (!noteInfo) throw new Error(`AIRNOTE note account does not exist: ${note}`);
  if (!noteInfo.owner.equals(expectedProgram)) throw new Error("AIRNOTE note account is owned by the wrong program");

  const clock = await chainTime(connection);
  if (clock.unixSeconds >= transfer.noteExpiresAt) throw new Error("Airnote expired before redemption");
  if (transfer.requestExpiresAt < transfer.issuedAt) throw new Error("receiver request expiry predates note issuance");
  if (transfer.requestExpiresAt > transfer.noteExpiresAt) throw new Error("receiver request expiry outlives Airnote");

  const recipientToken = getAssociatedTokenAddressSync(
    DEVNET_USDC,
    recipient.publicKey,
    false,
    TOKEN_PROGRAM_ID,
    ASSOCIATED_TOKEN_PROGRAM_ID,
  );

  const verifyIx = p256Instruction(transfer.ownerP256, transfer.transferSignature, transfer.message);
  const redeemData = Buffer.concat([
    discriminator("global:redeem_note"),
    transfer.recipientNonce,
    u64le(transfer.requestExpiresAt),
  ]);
  const redeemIx = new TransactionInstruction({
    programId: expectedProgram,
    keys: [
      { pubkey: payer.publicKey, isSigner: true, isWritable: true },
      { pubkey: recipient.publicKey, isSigner: true, isWritable: false },
      { pubkey: note, isSigner: false, isWritable: true },
      { pubkey: DEVNET_USDC, isSigner: false, isWritable: false },
      { pubkey: vault, isSigner: false, isWritable: true },
      { pubkey: recipientToken, isSigner: false, isWritable: true },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: ASSOCIATED_TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
      { pubkey: INSTRUCTIONS_SYSVAR, isSigner: false, isWritable: false },
    ],
    data: redeemData,
  });

  const latest = await connection.getLatestBlockhash("confirmed");
  const transaction = new Transaction({
    feePayer: payer.publicKey,
    recentBlockhash: latest.blockhash,
  }).add(verifyIx, redeemIx);
  const signers = new Map();
  signers.set(payer.publicKey.toBase58(), payer);
  signers.set(recipient.publicKey.toBase58(), recipient);
  transaction.sign(...signers.values());

  const signature = await connection.sendRawTransaction(transaction.serialize(), {
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
  const balance = await connection.getTokenAccountBalance(recipientToken, "confirmed");

  console.log(JSON.stringify({
    status: "redeemed_finalized",
    program_id: expectedProgram.toBase58(),
    note_account: note.toBase58(),
    vault_account: vault.toBase58(),
    recipient: recipient.publicKey.toBase58(),
    recipient_token: recipientToken.toBase58(),
    amount_base_units: transfer.amountBaseUnits.toString(),
    note_commitment_hex: transfer.commitment.toString("hex"),
    signature,
    finalized_slot: finalizedSlot,
    recipient_usdc_balance_base_units: balance.value.amount,
  }, null, 2));
}

main().catch((error) => {
  console.error(`AIRNOTE GATE 05 REDEEM FAIL: ${error?.stack ?? error}`);
  process.exit(1);
});
