use anchor_lang::{prelude::*, solana_program::instruction::Instruction};
use solana_sha256_hasher::hash;

use crate::{
    constants::{MINT_DOMAIN, SECP256R1_PROGRAM_ID, TRANSFER_DOMAIN, VERSION},
    errors::AirnoteError,
};

pub struct MintCoreFields {
    pub network: u8,
    pub note_id: [u8; 32],
    pub program_id: Pubkey,
    pub note_account: Pubkey,
    pub usdc_mint: Pubkey,
    pub amount_base_units: u64,
    pub owner_p256_compressed: [u8; 33],
    pub issued_at_unix_s: u64,
    pub expires_at_unix_s: u64,
    pub device_attestation_digest: [u8; 32],
    pub risk_tier: u8,
}

pub fn encode_mint_core(fields: &MintCoreFields) -> Vec<u8> {
    let mut out = Vec::with_capacity(240);
    out.extend_from_slice(MINT_DOMAIN);
    out.push(VERSION);
    out.push(fields.network);
    out.extend_from_slice(&fields.note_id);
    out.extend_from_slice(fields.program_id.as_ref());
    out.extend_from_slice(fields.note_account.as_ref());
    out.extend_from_slice(fields.usdc_mint.as_ref());
    out.extend_from_slice(&fields.amount_base_units.to_le_bytes());
    out.extend_from_slice(&fields.owner_p256_compressed);
    out.extend_from_slice(&fields.issued_at_unix_s.to_le_bytes());
    out.extend_from_slice(&fields.expires_at_unix_s.to_le_bytes());
    out.extend_from_slice(&fields.device_attestation_digest);
    out.push(fields.risk_tier);
    out
}

pub fn note_commitment(fields: &MintCoreFields) -> [u8; 32] {
    hash(&encode_mint_core(fields)).to_bytes()
}

pub fn encode_transfer_payload(
    note_commitment: &[u8; 32],
    recipient_wallet: &Pubkey,
    recipient_nonce: &[u8; 32],
    request_expires_at_unix_s: u64,
) -> Vec<u8> {
    let mut out = Vec::with_capacity(124);
    out.extend_from_slice(TRANSFER_DOMAIN);
    out.push(VERSION);
    out.extend_from_slice(note_commitment);
    out.extend_from_slice(recipient_wallet.as_ref());
    out.extend_from_slice(recipient_nonce);
    out.extend_from_slice(&request_expires_at_unix_s.to_le_bytes());
    out
}

pub fn assert_p256_verification(
    ix: &Instruction,
    expected_pubkey: &[u8; 33],
    expected_message: &[u8],
) -> Result<()> {
    require_keys_eq!(
        ix.program_id,
        SECP256R1_PROGRAM_ID,
        AirnoteError::MissingP256Verification
    );
    require!(ix.accounts.is_empty(), AirnoteError::UnsupportedP256Layout);

    let data = &ix.data;
    require!(data.len() >= 16, AirnoteError::MalformedP256Instruction);
    require!(data[0] == 1, AirnoteError::UnsupportedP256Layout);
    require!(data[1] == 0, AirnoteError::UnsupportedP256Layout);

    let signature_offset = read_u16(data, 2)?;
    let signature_instruction_index = read_u16(data, 4)?;
    let public_key_offset = read_u16(data, 6)?;
    let public_key_instruction_index = read_u16(data, 8)?;
    let message_data_offset = read_u16(data, 10)?;
    let message_data_size = read_u16(data, 12)?;
    let message_instruction_index = read_u16(data, 14)?;

    // AIRNOTE intentionally supports only a self-contained precompile instruction.
    // u16::MAX tells the precompile to read signature/key/message from its own data.
    require!(
        signature_instruction_index == u16::MAX
            && public_key_instruction_index == u16::MAX
            && message_instruction_index == u16::MAX,
        AirnoteError::UnsupportedP256Layout
    );

    let _signature = checked_slice(data, signature_offset as usize, 64)?;
    let pubkey = checked_slice(data, public_key_offset as usize, 33)?;
    let message = checked_slice(
        data,
        message_data_offset as usize,
        message_data_size as usize,
    )?;

    require!(pubkey == expected_pubkey, AirnoteError::P256OwnerMismatch);
    require!(
        message == expected_message,
        AirnoteError::TransferPayloadMismatch
    );

    Ok(())
}

fn read_u16(data: &[u8], offset: usize) -> Result<u16> {
    let bytes = checked_slice(data, offset, 2)?;
    Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
}

fn checked_slice(data: &[u8], offset: usize, len: usize) -> Result<&[u8]> {
    let end = offset
        .checked_add(len)
        .ok_or_else(|| error!(AirnoteError::MalformedP256Instruction))?;
    require!(end <= data.len(), AirnoteError::MalformedP256Instruction);
    Ok(&data[offset..end])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn range(start: u8, count: usize) -> Vec<u8> {
        (0..count).map(|i| start.wrapping_add(i as u8)).collect()
    }

    fn array32(start: u8) -> [u8; 32] {
        range(start, 32).try_into().unwrap()
    }

    fn fixture_core() -> MintCoreFields {
        let mut owner = [0u8; 33];
        owner[0] = 0x02;
        owner[1..].copy_from_slice(&range(0x80, 32));
        MintCoreFields {
            network: 1,
            note_id: array32(0x00),
            program_id: Pubkey::new_from_array(array32(0x20)),
            note_account: Pubkey::new_from_array(array32(0x40)),
            usdc_mint: Pubkey::new_from_array(array32(0x60)),
            amount_base_units: 5_000_000,
            owner_p256_compressed: owner,
            issued_at_unix_s: 1_700_000_000,
            expires_at_unix_s: 1_700_003_600,
            device_attestation_digest: array32(0xa0),
            risk_tier: 1,
        }
    }

    #[test]
    fn mint_core_matches_kotlin_golden_vector() {
        let encoded = encode_mint_core(&fixture_core());
        assert_eq!(encoded.len(), 240);
        assert_eq!(
            hex(&hash(&encoded).to_bytes()),
            "d6dfd317a4c51dde7d2b824fc09dbb8b6f9721efb9d6c9044f6a527c854ff650"
        );
    }

    #[test]
    fn transfer_matches_kotlin_golden_vector() {
        let commitment = note_commitment(&fixture_core());
        let wallet = Pubkey::new_from_array(array32(0xc0));
        let nonce = array32(0xe0);
        let encoded = encode_transfer_payload(&commitment, &wallet, &nonce, 1_700_000_300);
        assert_eq!(encoded.len(), 124);
        assert_eq!(
            hex(&hash(&encoded).to_bytes()),
            "badd1898e2fb123b79c877a0d03374a6f229f5e2dab3d8bb0363d033fe815c4e"
        );
    }

    #[test]
    fn strict_self_contained_p256_layout_is_accepted() {
        let owner = fixture_core().owner_p256_compressed;
        let commitment = note_commitment(&fixture_core());
        let wallet = Pubkey::new_from_array(array32(0xc0));
        let nonce = array32(0xe0);
        let message = encode_transfer_payload(&commitment, &wallet, &nonce, 1_700_000_300);
        let ix = synthetic_p256_ix(&owner, &message);
        assert!(assert_p256_verification(&ix, &owner, &message).is_ok());
    }

    #[test]
    fn changed_message_is_rejected() {
        let owner = fixture_core().owner_p256_compressed;
        let commitment = note_commitment(&fixture_core());
        let wallet = Pubkey::new_from_array(array32(0xc0));
        let nonce = array32(0xe0);
        let message = encode_transfer_payload(&commitment, &wallet, &nonce, 1_700_000_300);
        let ix = synthetic_p256_ix(&owner, &message);
        let mut changed = message.clone();
        *changed.last_mut().unwrap() ^= 1;
        assert!(assert_p256_verification(&ix, &owner, &changed).is_err());
    }

    fn synthetic_p256_ix(owner: &[u8; 33], message: &[u8]) -> Instruction {
        // Header = count/padding + 7 little-endian u16 offsets.
        // Data is laid out as header | signature | compressed pubkey | message.
        let signature_offset = 16u16;
        let public_key_offset = signature_offset + 64;
        let message_offset = public_key_offset + 33;
        let mut data = Vec::new();
        data.extend_from_slice(&[1, 0]);
        data.extend_from_slice(&signature_offset.to_le_bytes());
        data.extend_from_slice(&u16::MAX.to_le_bytes());
        data.extend_from_slice(&public_key_offset.to_le_bytes());
        data.extend_from_slice(&u16::MAX.to_le_bytes());
        data.extend_from_slice(&message_offset.to_le_bytes());
        data.extend_from_slice(&(message.len() as u16).to_le_bytes());
        data.extend_from_slice(&u16::MAX.to_le_bytes());
        data.extend_from_slice(&[0u8; 64]);
        data.extend_from_slice(owner);
        data.extend_from_slice(message);
        Instruction {
            program_id: SECP256R1_PROGRAM_ID,
            accounts: vec![],
            data,
        }
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }
}
