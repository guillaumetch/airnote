import crypto from "node:crypto";

const AIRNOTE_PROGRAM_ID = "DyT3zjATxr4Fg28Ne3DjjV5Q6rd6JWx2Vo7agZZxZFx9";
const DEFAULT_RPC = "https://api.devnet.solana.com";
const EXPECTED_ATTESTOR_PUBLIC_KEY_HEX = "57d04f590d2d5f62a32bf6c98421c0aa75c394cad648ae46b3c7ecc97d1a55c6";
const ATTESTOR_KEY_ID = 1;
const NOTE_ACCOUNT_LEN = 238;
const STATUS_FUNDED = 0;
const SUPPORTED_DENOMINATIONS = new Set([1_000_000, 5_000_000, 10_000_000]);
const BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const BASE58_INDEX = new Map([...BASE58_ALPHABET].map((c, i) => [c, i]));
const NOTE_DISCRIMINATOR = sha256(Buffer.from("account:Note")).subarray(0, 8);
const CREATE_NOTE_DISCRIMINATOR = sha256(Buffer.from("global:create_note")).subarray(0, 8);
const MINT_DOMAIN = Buffer.from("AIRNOTE_MINT_CORE_V1", "ascii");
const CERT_DOMAIN = Buffer.from("AIRNOTE_MINT_CERT_V1", "ascii");
const VERSION = 1;

function sha256(data) {
  return crypto.createHash("sha256").update(data).digest();
}

function u64le(value) {
  const out = Buffer.alloc(8);
  out.writeBigUInt64LE(BigInt(value));
  return out;
}

function u32le(value) {
  const out = Buffer.alloc(4);
  out.writeUInt32LE(Number(value));
  return out;
}

function b58decode(value) {
  let n = 0n;
  for (const c of value) {
    const digit = BASE58_INDEX.get(c);
    if (digit === undefined) throw new Error("invalid base58");
    n = n * 58n + BigInt(digit);
  }
  let body = Buffer.alloc(0);
  if (n > 0n) {
    let hex = n.toString(16);
    if (hex.length % 2) hex = "0" + hex;
    body = Buffer.from(hex, "hex");
  }
  let zeros = 0;
  while (zeros < value.length && value[zeros] === "1") zeros += 1;
  return Buffer.concat([Buffer.alloc(zeros), body]);
}

function b58encode(bytes) {
  const value = Buffer.from(bytes);
  let n = value.length ? BigInt("0x" + value.toString("hex")) : 0n;
  let body = "";
  while (n > 0n) {
    const rem = Number(n % 58n);
    n /= 58n;
    body = BASE58_ALPHABET[rem] + body;
  }
  let zeros = 0;
  while (zeros < value.length && value[zeros] === 0) zeros += 1;
  return "1".repeat(zeros) + body;
}

function fixedB58(value, bytes, label) {
  const raw = b58decode(value);
  if (raw.length !== bytes) throw new Error(label + " must decode to " + bytes + " bytes");
  return raw;
}

async function rpcCall(rpcUrl, method, params) {
  const response = await fetch(rpcUrl, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
  });
  if (!response.ok) throw new Error("RPC " + method + " HTTP " + response.status);
  const payload = await response.json();
  if (payload.error) throw new Error("RPC " + method + " error: " + JSON.stringify(payload.error));
  return payload.result;
}

function parseNote(data) {
  if (data.length !== NOTE_ACCOUNT_LEN) throw new Error("unexpected Note account length " + data.length);
  if (!data.subarray(0, 8).equals(NOTE_DISCRIMINATOR)) throw new Error("invalid AIRNOTE Note discriminator");

  let o = 8;
  const take = (size) => {
    const value = data.subarray(o, o + size);
    if (value.length !== size) throw new Error("truncated Note account");
    o += size;
    return value;
  };

  const funder = take(32);
  const network = data[o++];
  const noteId = take(32);
  const usdcMint = take(32);
  const amount = Number(data.readBigUInt64LE(o)); o += 8;
  const ownerP256 = take(33);
  const issuedAt = Number(data.readBigUInt64LE(o)); o += 8;
  const expiresAt = Number(data.readBigUInt64LE(o)); o += 8;
  const attestationDigest = take(32);
  const riskTier = data[o++];
  const status = data[o++];
  const redeemedBy = take(32);
  const redeemedAt = Number(data.readBigUInt64LE(o)); o += 8;
  const bump = data[o++];
  const vaultBump = data[o++];

  if (o !== data.length) throw new Error("unexpected trailing Note bytes");
  if (network !== 1) throw new Error("attestor only certifies AIRNOTE devnet notes");
  if (!SUPPORTED_DENOMINATIONS.has(amount)) throw new Error("unsupported AIRNOTE denomination");
  if (ownerP256[0] !== 2 && ownerP256[0] !== 3) throw new Error("invalid compressed P-256 owner");
  if (expiresAt <= issuedAt) throw new Error("invalid note lifetime");
  if (status !== STATUS_FUNDED) throw new Error("Note is not FUNDED");
  if (!redeemedBy.equals(Buffer.alloc(32)) || redeemedAt !== 0) throw new Error("FUNDED Note contains redemption state");

  return {
    funder,
    network,
    noteId,
    usdcMint,
    amount,
    ownerP256,
    issuedAt,
    expiresAt,
    attestationDigest,
    riskTier,
    bump,
    vaultBump,
  };
}

function mintCore(note, programRaw, noteRaw) {
  const core = Buffer.concat([
    MINT_DOMAIN,
    Buffer.from([VERSION, note.network]),
    note.noteId,
    programRaw,
    noteRaw,
    note.usdcMint,
    u64le(note.amount),
    note.ownerP256,
    u64le(note.issuedAt),
    u64le(note.expiresAt),
    note.attestationDigest,
    Buffer.from([note.riskTier]),
  ]);
  if (core.length !== 240) throw new Error("internal mint core length mismatch");
  return core;
}

function attestorPrivateKey() {
  const seedHex = process.env.AIRNOTE_ATTESTOR_SEED_HEX || "";
  if (!/^[0-9a-fA-F]{64}$/.test(seedHex)) {
    throw new Error("AIRNOTE_ATTESTOR_SEED_HEX must be a 32-byte hex secret");
  }
  const seed = Buffer.from(seedHex, "hex");
  const pkcs8Prefix = Buffer.from("302e020100300506032b657004220420", "hex");
  const privateKey = crypto.createPrivateKey({
    key: Buffer.concat([pkcs8Prefix, seed]),
    format: "der",
    type: "pkcs8",
  });
  const publicDer = crypto.createPublicKey(privateKey).export({ format: "der", type: "spki" });
  const publicRaw = Buffer.from(publicDer).subarray(-32);
  if (publicRaw.toString("hex") !== EXPECTED_ATTESTOR_PUBLIC_KEY_HEX) {
    throw new Error("configured attestor seed does not match the public key pinned in AIRNOTE");
  }
  return privateKey;
}

async function observeAndCertify(body) {
  const programId = String(body.programId || "");
  const noteAccount = String(body.noteAccount || "");
  const vaultAccount = String(body.vaultAccount || "");
  const mintSignature = String(body.mintSignature || "");
  if (programId !== AIRNOTE_PROGRAM_ID) throw new Error("wrong AIRNOTE program id");

  const programRaw = fixedB58(programId, 32, "program id");
  const noteRaw = fixedB58(noteAccount, 32, "note account");
  fixedB58(vaultAccount, 32, "vault account");
  const signatureRaw = fixedB58(mintSignature, 64, "mint signature");
  const rpcUrl = process.env.AIRNOTE_RPC || DEFAULT_RPC;

  const statuses = await rpcCall(
    rpcUrl,
    "getSignatureStatuses",
    [[mintSignature], { searchTransactionHistory: true }]
  );
  const status = statuses && statuses.value && statuses.value[0];
  if (!status) throw new Error("mint transaction signature was not found");
  if (status.err) throw new Error("mint transaction failed");
  if (status.confirmationStatus !== "finalized") throw new Error("mint transaction is not finalized");
  const finalizedSlot = Number(status.slot);
  if (!Number.isSafeInteger(finalizedSlot) || finalizedSlot < 0) throw new Error("invalid finalized slot");

  const tx = await rpcCall(
    rpcUrl,
    "getTransaction",
    [
      mintSignature,
      {
        encoding: "jsonParsed",
        commitment: "finalized",
        maxSupportedTransactionVersion: 0,
      },
    ]
  );
  if (!tx) throw new Error("finalized transaction body not found");
  if (tx.meta && tx.meta.err) throw new Error("transaction meta reports failure");
  if (Number(tx.slot) !== finalizedSlot) throw new Error("signature status slot and transaction slot disagree");

  const instructions = (((tx || {}).transaction || {}).message || {}).instructions || [];
  let matchingCreate = false;
  for (const ix of instructions) {
    if (ix.programId !== programId || !Array.isArray(ix.accounts) || typeof ix.data !== "string") continue;
    if (!ix.accounts.includes(noteAccount) || !ix.accounts.includes(vaultAccount)) continue;
    const data = b58decode(ix.data);
    if (data.length >= 8 && data.subarray(0, 8).equals(CREATE_NOTE_DISCRIMINATOR)) {
      matchingCreate = true;
      break;
    }
  }
  if (!matchingCreate) throw new Error("finalized transaction lacks matching AIRNOTE create_note");

  const noteInfo = await rpcCall(
    rpcUrl,
    "getAccountInfo",
    [noteAccount, { encoding: "base64", commitment: "finalized" }]
  );
  const noteValue = noteInfo && noteInfo.value;
  if (!noteValue) throw new Error("finalized Note account not found");
  if (noteValue.owner !== programId) throw new Error("Note account is owned by the wrong program");
  if (noteValue.executable) throw new Error("Note account must not be executable");
  if (!Array.isArray(noteValue.data) || noteValue.data[1] !== "base64") throw new Error("unexpected Note account encoding");
  const note = parseNote(Buffer.from(noteValue.data[0], "base64"));

  const vaultInfo = await rpcCall(
    rpcUrl,
    "getAccountInfo",
    [vaultAccount, { encoding: "jsonParsed", commitment: "finalized" }]
  );
  const vaultValue = vaultInfo && vaultInfo.value;
  if (!vaultValue) throw new Error("finalized vault token account not found");
  const parsed = vaultValue.data && vaultValue.data.parsed;
  if (!parsed || parsed.type !== "account") throw new Error("vault is not an SPL token account");
  const info = parsed.info || {};
  if (info.mint !== b58encode(note.usdcMint)) throw new Error("vault mint does not match Note USDC mint");
  if (info.owner !== vaultAccount) throw new Error("vault token authority is not the vault PDA");
  const tokenAmount = info.tokenAmount || {};
  if (Number(tokenAmount.decimals) !== 6) throw new Error("vault mint does not use 6 decimals");
  if (BigInt(tokenAmount.amount || "-1") !== BigInt(note.amount)) throw new Error("vault balance does not equal Note amount");

  const core = mintCore(note, programRaw, noteRaw);
  const commitment = sha256(core);
  const certificatePayload = Buffer.concat([
    CERT_DOMAIN,
    commitment,
    u64le(finalizedSlot),
    signatureRaw,
    u32le(ATTESTOR_KEY_ID),
  ]);
  if (certificatePayload.length !== 128) throw new Error("internal certificate payload length mismatch");

  const privateKey = attestorPrivateKey();
  const attestorSignature = crypto.sign(null, certificatePayload, privateKey);
  if (attestorSignature.length !== 64) throw new Error("invalid Ed25519 signature length");

  return {
    version: 1,
    mint_core: {
      network: note.network,
      note_id_hex: note.noteId.toString("hex"),
      program_id: programId,
      note_account: noteAccount,
      usdc_mint: b58encode(note.usdcMint),
      amount_base_units: note.amount,
      owner_p256_compressed_hex: note.ownerP256.toString("hex"),
      issued_at_unix_s: note.issuedAt,
      expires_at_unix_s: note.expiresAt,
      device_attestation_digest_hex: note.attestationDigest.toString("hex"),
      risk_tier: note.riskTier,
    },
    certificate: {
      note_commitment_hex: commitment.toString("hex"),
      finalized_slot: finalizedSlot,
      mint_tx_signature_hex: signatureRaw.toString("hex"),
      attestor_key_id: ATTESTOR_KEY_ID,
      attestor_public_key_hex: EXPECTED_ATTESTOR_PUBLIC_KEY_HEX,
      certificate_payload_hex: certificatePayload.toString("hex"),
      attestor_signature_hex: attestorSignature.toString("hex"),
    },
  };
}

export default async function handler(req, res) {
  res.setHeader("cache-control", "no-store");
  if (req.method !== "POST") {
    res.setHeader("allow", "POST");
    return res.status(405).json({ error: "method_not_allowed" });
  }

  try {
    const body = typeof req.body === "string" ? JSON.parse(req.body) : (req.body || {});
    const certificate = await observeAndCertify(body);
    return res.status(200).json(certificate);
  } catch (error) {
    return res.status(400).json({
      error: "certification_failed",
      message: error instanceof Error ? error.message : String(error),
    });
  }
}
