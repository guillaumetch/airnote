use anchor_lang::prelude::*;
use anchor_spl::{
    associated_token::AssociatedToken,
    token::{self, Mint, Token, TokenAccount, TransferChecked},
};
use solana_instructions_sysvar::get_instruction_relative;

pub mod constants;
pub mod errors;
pub mod protocol;
pub mod state;

use constants::*;
use errors::AirnoteError;
use protocol::{assert_p256_verification, encode_transfer_payload, note_commitment, MintCoreFields};
use state::{Config, Note};

declare_id!("8pF7EZH5K1NK8KYmXLesHLMTWbYdD2FWQ9wR6xVLZL7C");

#[program]
pub mod airnote_vault {
    use super::*;

    pub fn initialize_config(ctx: Context<InitializeConfig>, network: u8) -> Result<()> {
        require!(
            network == NETWORK_DEVNET || network == NETWORK_MAINNET_BETA,
            AirnoteError::UnsupportedNetwork
        );
        require!(
            ctx.accounts.usdc_mint.decimals == USDC_DECIMALS,
            AirnoteError::InvalidUsdcDecimals
        );

        let config = &mut ctx.accounts.config;
        config.admin = ctx.accounts.admin.key();
        config.network = network;
        config.usdc_mint = ctx.accounts.usdc_mint.key();
        config.bump = ctx.bumps.config;

        emit!(ConfigInitialized {
            admin: config.admin,
            network,
            usdc_mint: config.usdc_mint,
        });
        Ok(())
    }

    pub fn create_note(ctx: Context<CreateNote>, args: CreateNoteArgs) -> Result<()> {
        require!(
            is_supported_denomination(args.amount_base_units),
            AirnoteError::UnsupportedDenomination
        );
        require!(
            args.owner_p256_compressed[0] == 0x02 || args.owner_p256_compressed[0] == 0x03,
            AirnoteError::InvalidP256PublicKey
        );
        require!(args.risk_tier <= 3, AirnoteError::InvalidRiskTier);
        require!(
            ctx.accounts.usdc_mint.decimals == USDC_DECIMALS,
            AirnoteError::InvalidUsdcDecimals
        );

        let issued_at = current_unix_seconds()?;
        let lifetime = args
            .expires_at_unix_s
            .checked_sub(issued_at)
            .ok_or_else(|| error!(AirnoteError::InvalidNoteExpiry))?;
        require!(
            (MIN_NOTE_LIFETIME_SECONDS..=MAX_NOTE_LIFETIME_SECONDS).contains(&lifetime),
            AirnoteError::InvalidNoteExpiry
        );

        let transfer_accounts = TransferChecked {
            from: ctx.accounts.funder_token.to_account_info(),
            mint: ctx.accounts.usdc_mint.to_account_info(),
            to: ctx.accounts.vault_token.to_account_info(),
            authority: ctx.accounts.funder.to_account_info(),
        };
        token::transfer_checked(
            CpiContext::new(ctx.accounts.token_program.key(), transfer_accounts),
            args.amount_base_units,
            USDC_DECIMALS,
        )?;

        let note_key = ctx.accounts.note.key();
        let note = &mut ctx.accounts.note;
        note.funder = ctx.accounts.funder.key();
        note.network = ctx.accounts.config.network;
        note.note_id = args.note_id;
        note.usdc_mint = ctx.accounts.usdc_mint.key();
        note.amount_base_units = args.amount_base_units;
        note.owner_p256_compressed = args.owner_p256_compressed;
        note.issued_at_unix_s = issued_at;
        note.expires_at_unix_s = args.expires_at_unix_s;
        note.device_attestation_digest = args.device_attestation_digest;
        note.risk_tier = args.risk_tier;
        note.status = STATUS_FUNDED;
        note.redeemed_by = Pubkey::default();
        note.redeemed_at_unix_s = 0;
        note.bump = ctx.bumps.note;
        note.vault_bump = ctx.bumps.vault_token;

        let commitment = commitment_for_note(note, note_key);
        emit!(NoteCreated {
            note_id: note.note_id,
            note_account: note_key,
            funder: note.funder,
            amount_base_units: note.amount_base_units,
            issued_at_unix_s: note.issued_at_unix_s,
            expires_at_unix_s: note.expires_at_unix_s,
            note_commitment: commitment,
        });
        Ok(())
    }

    pub fn redeem_note(
        ctx: Context<RedeemNote>,
        recipient_nonce: [u8; 32],
        request_expires_at_unix_s: u64,
    ) -> Result<()> {
        let now = current_unix_seconds()?;
        let note_key = ctx.accounts.note.key();
        let note = &mut ctx.accounts.note;

        require!(note.status == STATUS_FUNDED, AirnoteError::NoteConsumed);
        require!(now < note.expires_at_unix_s, AirnoteError::NoteExpired);
        require!(
            request_expires_at_unix_s >= note.issued_at_unix_s
                && request_expires_at_unix_s <= note.expires_at_unix_s
                && now <= request_expires_at_unix_s,
            AirnoteError::InvalidRequestExpiry
        );
        require!(
            ctx.accounts.vault_token.amount >= note.amount_base_units,
            AirnoteError::InsufficientVaultBalance
        );

        let commitment = commitment_for_note(note, note_key);
        let transfer_payload = encode_transfer_payload(
            &commitment,
            &ctx.accounts.recipient.key(),
            &recipient_nonce,
            request_expires_at_unix_s,
        );

        // The secp256r1 precompile cannot be CPI-called. AIRNOTE requires it to be
        // the immediately preceding top-level instruction, then introspects it here.
        let verification_ix = get_instruction_relative(
            -1,
            &ctx.accounts.instructions.to_account_info(),
        )
        .map_err(|_| error!(AirnoteError::MissingP256Verification))?;
        assert_p256_verification(
            &verification_ix,
            &note.owner_p256_compressed,
            &transfer_payload,
        )?;

        // State is changed before the token CPI. Solana transaction atomicity rolls
        // this back if the transfer fails, while concurrent redemptions serialize on
        // this writable note account so only the first successful redemption wins.
        note.status = STATUS_REDEEMED;
        note.redeemed_by = ctx.accounts.recipient.key();
        note.redeemed_at_unix_s = now;

        let note_id = note.note_id;
        let vault_bump = note.vault_bump;
        let signer_seeds: &[&[u8]] = &[VAULT_SEED, note_id.as_ref(), &[vault_bump]];
        let signer = &[signer_seeds];
        let transfer_accounts = TransferChecked {
            from: ctx.accounts.vault_token.to_account_info(),
            mint: ctx.accounts.usdc_mint.to_account_info(),
            to: ctx.accounts.recipient_token.to_account_info(),
            authority: ctx.accounts.vault_token.to_account_info(),
        };
        token::transfer_checked(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.key(),
                transfer_accounts,
                signer,
            ),
            note.amount_base_units,
            USDC_DECIMALS,
        )?;

        emit!(NoteRedeemed {
            note_id,
            note_commitment: commitment,
            recipient: ctx.accounts.recipient.key(),
            amount_base_units: note.amount_base_units,
            redeemed_at_unix_s: now,
        });
        Ok(())
    }

    pub fn refund_note(ctx: Context<RefundNote>) -> Result<()> {
        let now = current_unix_seconds()?;
        let note = &mut ctx.accounts.note;
        require!(note.status == STATUS_FUNDED, AirnoteError::NoteConsumed);
        require!(now >= note.expires_at_unix_s, AirnoteError::NoteNotExpired);
        require_keys_eq!(
            ctx.accounts.funder.key(),
            note.funder,
            AirnoteError::UnauthorizedRefund
        );
        require!(
            ctx.accounts.vault_token.amount >= note.amount_base_units,
            AirnoteError::InsufficientVaultBalance
        );

        note.status = STATUS_REFUNDED;

        let note_id = note.note_id;
        let vault_bump = note.vault_bump;
        let signer_seeds: &[&[u8]] = &[VAULT_SEED, note_id.as_ref(), &[vault_bump]];
        let signer = &[signer_seeds];
        let transfer_accounts = TransferChecked {
            from: ctx.accounts.vault_token.to_account_info(),
            mint: ctx.accounts.usdc_mint.to_account_info(),
            to: ctx.accounts.funder_token.to_account_info(),
            authority: ctx.accounts.vault_token.to_account_info(),
        };
        token::transfer_checked(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.key(),
                transfer_accounts,
                signer,
            ),
            note.amount_base_units,
            USDC_DECIMALS,
        )?;

        emit!(NoteRefunded {
            note_id,
            funder: note.funder,
            amount_base_units: note.amount_base_units,
            refunded_at_unix_s: now,
        });
        Ok(())
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone)]
pub struct CreateNoteArgs {
    pub note_id: [u8; 32],
    pub amount_base_units: u64,
    pub owner_p256_compressed: [u8; 33],
    pub expires_at_unix_s: u64,
    pub device_attestation_digest: [u8; 32],
    pub risk_tier: u8,
}

#[derive(Accounts)]
pub struct InitializeConfig<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(
        init,
        payer = admin,
        seeds = [CONFIG_SEED],
        bump,
        space = 8 + Config::INIT_SPACE,
    )]
    pub config: Account<'info, Config>,
    pub usdc_mint: Account<'info, Mint>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(args: CreateNoteArgs)]
pub struct CreateNote<'info> {
    #[account(mut)]
    pub funder: Signer<'info>,
    #[account(
        seeds = [CONFIG_SEED],
        bump = config.bump,
        has_one = usdc_mint,
    )]
    pub config: Account<'info, Config>,
    pub usdc_mint: Account<'info, Mint>,
    #[account(
        mut,
        token::mint = usdc_mint,
        token::authority = funder,
    )]
    pub funder_token: Account<'info, TokenAccount>,
    #[account(
        init,
        payer = funder,
        seeds = [NOTE_SEED, args.note_id.as_ref()],
        bump,
        space = 8 + Note::INIT_SPACE,
    )]
    pub note: Account<'info, Note>,
    #[account(
        init,
        payer = funder,
        seeds = [VAULT_SEED, args.note_id.as_ref()],
        bump,
        token::mint = usdc_mint,
        token::authority = vault_token,
    )]
    pub vault_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct RedeemNote<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    pub recipient: Signer<'info>,
    #[account(
        mut,
        seeds = [NOTE_SEED, note.note_id.as_ref()],
        bump = note.bump,
        has_one = usdc_mint,
    )]
    pub note: Account<'info, Note>,
    pub usdc_mint: Account<'info, Mint>,
    #[account(
        mut,
        seeds = [VAULT_SEED, note.note_id.as_ref()],
        bump = note.vault_bump,
        token::mint = usdc_mint,
        token::authority = vault_token,
    )]
    pub vault_token: Account<'info, TokenAccount>,
    #[account(
        init_if_needed,
        payer = payer,
        associated_token::mint = usdc_mint,
        associated_token::authority = recipient,
    )]
    pub recipient_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
    #[account(address = solana_instructions_sysvar::ID)]
    /// CHECK: address constraint pins the canonical instructions sysvar.
    pub instructions: UncheckedAccount<'info>,
}

#[derive(Accounts)]
pub struct RefundNote<'info> {
    pub funder: Signer<'info>,
    #[account(
        mut,
        seeds = [NOTE_SEED, note.note_id.as_ref()],
        bump = note.bump,
        has_one = usdc_mint,
    )]
    pub note: Account<'info, Note>,
    pub usdc_mint: Account<'info, Mint>,
    #[account(
        mut,
        seeds = [VAULT_SEED, note.note_id.as_ref()],
        bump = note.vault_bump,
        token::mint = usdc_mint,
        token::authority = vault_token,
    )]
    pub vault_token: Account<'info, TokenAccount>,
    #[account(
        mut,
        token::mint = usdc_mint,
        token::authority = funder,
    )]
    pub funder_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[event]
pub struct ConfigInitialized {
    pub admin: Pubkey,
    pub network: u8,
    pub usdc_mint: Pubkey,
}

#[event]
pub struct NoteCreated {
    pub note_id: [u8; 32],
    pub note_account: Pubkey,
    pub funder: Pubkey,
    pub amount_base_units: u64,
    pub issued_at_unix_s: u64,
    pub expires_at_unix_s: u64,
    pub note_commitment: [u8; 32],
}

#[event]
pub struct NoteRedeemed {
    pub note_id: [u8; 32],
    pub note_commitment: [u8; 32],
    pub recipient: Pubkey,
    pub amount_base_units: u64,
    pub redeemed_at_unix_s: u64,
}

#[event]
pub struct NoteRefunded {
    pub note_id: [u8; 32],
    pub funder: Pubkey,
    pub amount_base_units: u64,
    pub refunded_at_unix_s: u64,
}

fn current_unix_seconds() -> Result<u64> {
    u64::try_from(Clock::get()?.unix_timestamp).map_err(|_| error!(AirnoteError::InvalidClock))
}

fn is_supported_denomination(amount: u64) -> bool {
    matches!(
        amount,
        DENOMINATION_1_USDC | DENOMINATION_5_USDC | DENOMINATION_10_USDC
    )
}

fn commitment_for_note(note: &Note, note_key: Pubkey) -> [u8; 32] {
    note_commitment(&MintCoreFields {
        network: note.network,
        note_id: note.note_id,
        program_id: crate::ID,
        note_account: note_key,
        usdc_mint: note.usdc_mint,
        amount_base_units: note.amount_base_units,
        owner_p256_compressed: note.owner_p256_compressed,
        issued_at_unix_s: note.issued_at_unix_s,
        expires_at_unix_s: note.expires_at_unix_s,
        device_attestation_digest: note.device_attestation_digest,
        risk_tier: note.risk_tier,
    })
}
