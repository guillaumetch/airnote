use anchor_lang::prelude::*;

#[error_code]
pub enum AirnoteError {
    #[msg("Unsupported AIRNOTE network")]
    UnsupportedNetwork,
    #[msg("Configured mint must use 6 decimals")]
    InvalidUsdcDecimals,
    #[msg("Unsupported AIRNOTE denomination")]
    UnsupportedDenomination,
    #[msg("Compressed P-256 key must be 33 bytes starting with 0x02 or 0x03")]
    InvalidP256PublicKey,
    #[msg("Note expiry is outside the allowed lifetime")]
    InvalidNoteExpiry,
    #[msg("Risk tier is unsupported")]
    InvalidRiskTier,
    #[msg("Note has already been consumed or refunded")]
    NoteConsumed,
    #[msg("Note has expired")]
    NoteExpired,
    #[msg("Note has not expired yet")]
    NoteNotExpired,
    #[msg("Recipient request expiry is inconsistent with the note")]
    InvalidRequestExpiry,
    #[msg("Expected secp256r1 verification instruction immediately before redeem_note")]
    MissingP256Verification,
    #[msg("Malformed secp256r1 verification instruction")]
    MalformedP256Instruction,
    #[msg("secp256r1 instruction must contain exactly one self-contained signature")]
    UnsupportedP256Layout,
    #[msg("P-256 public key does not match the note owner")]
    P256OwnerMismatch,
    #[msg("Verified P-256 message does not match the canonical AIRNOTE transfer payload")]
    TransferPayloadMismatch,
    #[msg("Vault does not contain enough USDC")]
    InsufficientVaultBalance,
    #[msg("Only the original funder may refund an expired note")]
    UnauthorizedRefund,
    #[msg("Clock returned an invalid timestamp")]
    InvalidClock,
}
