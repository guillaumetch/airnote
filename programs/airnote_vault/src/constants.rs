use anchor_lang::prelude::*;

pub const CONFIG_SEED: &[u8] = b"config";
pub const NOTE_SEED: &[u8] = b"note";
pub const VAULT_SEED: &[u8] = b"vault";

pub const VERSION: u8 = 1;
pub const NETWORK_DEVNET: u8 = 1;
pub const NETWORK_MAINNET_BETA: u8 = 2;
pub const USDC_DECIMALS: u8 = 6;

pub const DENOMINATION_1_USDC: u64 = 1_000_000;
pub const DENOMINATION_5_USDC: u64 = 5_000_000;
pub const DENOMINATION_10_USDC: u64 = 10_000_000;

pub const MIN_NOTE_LIFETIME_SECONDS: u64 = 60;
pub const MAX_NOTE_LIFETIME_SECONDS: u64 = 24 * 60 * 60;

pub const STATUS_FUNDED: u8 = 0;
pub const STATUS_REDEEMED: u8 = 1;
pub const STATUS_REFUNDED: u8 = 2;

pub const MINT_DOMAIN: &[u8] = b"AIRNOTE_MINT_CORE_V1";
pub const CERT_DOMAIN: &[u8] = b"AIRNOTE_MINT_CERT_V1";
pub const TRANSFER_DOMAIN: &[u8] = b"AIRNOTE_TRANSFER_V1";

pub const SECP256R1_PROGRAM_ID: Pubkey =
    pubkey!("Secp256r1SigVerify1111111111111111111111111");
