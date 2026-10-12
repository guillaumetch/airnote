import crypto from "node:crypto";

const seed = Buffer.from(Array.from({ length: 32 }, (_, index) => index + 1));
const pkcs8Prefix = Buffer.from("302e020100300506032b657004220420", "hex");
const privateKey = crypto.createPrivateKey({
  key: Buffer.concat([pkcs8Prefix, seed]),
  format: "der",
  type: "pkcs8",
});
const publicKey = Buffer.from(
  crypto.createPublicKey(privateKey).export({ format: "der", type: "spki" })
).subarray(-32);

const commitment = Buffer.from(
  "d6dfd317a4c51dde7d2b824fc09dbb8b6f9721efb9d6c9044f6a527c854ff650",
  "hex"
);
const slot = Buffer.alloc(8);
slot.writeBigUInt64LE(250_000_000n);
const txSignature = Buffer.from(Array.from({ length: 64 }, (_, index) => index));
const keyId = Buffer.alloc(4);
keyId.writeUInt32LE(7);

const payload = Buffer.concat([
  Buffer.from("AIRNOTE_MINT_CERT_V1", "ascii"),
  commitment,
  slot,
  txSignature,
  keyId,
]);
const signature = crypto.sign(null, payload, privateKey);

const expectedPublic =
  "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664";
const expectedSignature =
  "af9ce7a59779d62228178371cfc2225efc9a31fa4b54032cb2d04f6223f8888b165c5bf380843b29bd27cc3756c547bce62b459a7117d55b7c2cf4188f24a70e";

if (payload.length !== 128) throw new Error("certificate payload length mismatch");
if (publicKey.toString("hex") !== expectedPublic) throw new Error("Node Ed25519 public key does not match Rust/Android vector");
if (signature.toString("hex") !== expectedSignature) throw new Error("Node Ed25519 signature does not match Rust/Android vector");

console.log("AIRNOTE Node attestor parity PASS");
