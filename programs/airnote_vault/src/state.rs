use anchor_lang::prelude::*;

#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    pub network: u8,
    pub usdc_mint: Pubkey,
    pub bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct Note {
    pub funder: Pubkey,
    pub network: u8,
    pub note_id: [u8; 32],
    pub usdc_mint: Pubkey,
    pub amount_base_units: u64,
    pub owner_p256_compressed: [u8; 33],
    pub issued_at_unix_s: u64,
    pub expires_at_unix_s: u64,
    pub device_attestation_digest: [u8; 32],
    pub risk_tier: u8,
    pub status: u8,
    pub redeemed_by: Pubkey,
    pub redeemed_at_unix_s: u64,
    pub bump: u8,
    pub vault_bump: u8,
}
