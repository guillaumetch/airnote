use airnote_vault::{
    accounts,
    constants::{
        CONFIG_SEED, NETWORK_DEVNET, NOTE_SEED, SECP256R1_PROGRAM_ID, STATUS_FUNDED,
        STATUS_REDEEMED, STATUS_REFUNDED, VAULT_SEED,
    },
    instruction,
    protocol::{encode_transfer_payload, note_commitment, MintCoreFields},
    state::Note,
    CreateNoteArgs, ID,
};
use airnote_vault_runtime::{AnchorLiteSVM, Instruction};
use anchor_lang::{
    prelude::{Clock, Pubkey},
    InstructionData, ToAccountMetas,
};
use p256::ecdsa::{signature::Signer as P256Signer, Signature, SigningKey};

const AIRNOTE_SO: &[u8] = include_bytes!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../target/deploy/airnote_vault.so"
));

struct Fixture {
    ctx: airnote_vault_runtime::AnchorContext,
    admin: airnote_vault_runtime::Keypair,
    funder: airnote_vault_runtime::Keypair,
    payer: airnote_vault_runtime::Keypair,
    recipient: airnote_vault_runtime::Keypair,
    mint: airnote_vault_runtime::MintAddress,
    funder_ata: Pubkey,
    config: Pubkey,
}

fn setup() -> Fixture {
    let mut ctx = AnchorLiteSVM::build_with_program(ID, AIRNOTE_SO);

    let admin = ctx.svm.create_funded_account(10_000_000_000).unwrap();
    let funder = ctx.svm.create_funded_account(10_000_000_000).unwrap();
    let payer = ctx.svm.create_funded_account(10_000_000_000).unwrap();
    let recipient = ctx.svm.create_funded_account(10_000_000_000).unwrap();

    let mint = ctx.svm.create_token_mint(&admin, 6).unwrap();
    let funder_ata = ctx
        .svm
        .create_associated_token_account(&mint.pubkey(), &funder)
        .unwrap();
    ctx.svm
        .mint_to(&mint.pubkey(), &funder_ata, &admin, 20_000_000)
        .unwrap();

    let (config, _) = Pubkey::find_program_address(&[CONFIG_SEED], &ID);
    let init_ix = Instruction {
        program_id: ID,
        accounts: accounts::InitializeConfig {
            admin: admin.pubkey(),
            config,
            usdc_mint: mint.pubkey(),
            system_program: anchor_lang::system_program::ID,
        }
        .to_account_metas(None),
        data: instruction::InitializeConfig {
            network: NETWORK_DEVNET,
        }
        .data(),
    };
    ctx.execute_instruction(init_ix, &[&admin])
        .unwrap()
        .assert_success();

    Fixture {
        ctx,
        admin,
        funder,
        payer,
        recipient,
        mint,
        funder_ata,
        config,
    }
}

fn p256_key(seed_byte: u8) -> (SigningKey, [u8; 33]) {
    let signing_key = SigningKey::from_bytes((&[seed_byte; 32]).into()).unwrap();
    let encoded = signing_key.verifying_key().to_encoded_point(true);
    let public_key: [u8; 33] = encoded.as_bytes().try_into().unwrap();
    (signing_key, public_key)
}

fn create_note(
    fixture: &mut Fixture,
    note_id: [u8; 32],
    amount: u64,
    owner_p256: [u8; 33],
    lifetime_seconds: u64,
) -> (Pubkey, Pubkey, Note) {
    let clock = fixture.ctx.svm.get_sysvar::<Clock>();
    let expires_at = u64::try_from(clock.unix_timestamp).unwrap() + lifetime_seconds;
    let (note, _) = Pubkey::find_program_address(&[NOTE_SEED, &note_id], &ID);
    let (vault, _) = Pubkey::find_program_address(&[VAULT_SEED, &note_id], &ID);

    let ix = Instruction {
        program_id: ID,
        accounts: accounts::CreateNote {
            funder: fixture.funder.pubkey(),
            config: fixture.config,
            usdc_mint: fixture.mint.pubkey(),
            funder_token: fixture.funder_ata,
            note,
            vault_token: vault,
            token_program: anchor_spl::token::ID,
            system_program: anchor_lang::system_program::ID,
        }
        .to_account_metas(None),
        data: instruction::CreateNote {
            args: CreateNoteArgs {
                note_id,
                amount_base_units: amount,
                owner_p256_compressed: owner_p256,
                expires_at_unix_s: expires_at,
                device_attestation_digest: [0xA5; 32],
                risk_tier: 1,
            },
        }
        .data(),
    };

    fixture
        .ctx
        .execute_instruction(ix, &[&fixture.funder])
        .unwrap()
        .assert_success();
    fixture.ctx.svm.assert_token_balance(&vault, amount);

    let note_state: Note = fixture.ctx.get_account(&note).unwrap();
    assert_eq!(note_state.status, STATUS_FUNDED);
    (note, vault, note_state)
}

fn commitment(note: &Note, note_key: Pubkey) -> [u8; 32] {
    note_commitment(&MintCoreFields {
        network: note.network,
        note_id: note.note_id,
        program_id: ID,
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

fn p256_instruction(signing_key: &SigningKey, public_key: &[u8; 33], message: &[u8]) -> Instruction {
    let signature: Signature = signing_key.sign(message);
    let signature = signature.normalize_s().unwrap_or(signature);
    let compact = signature.to_bytes();

    let signature_offset = 16u16;
    let public_key_offset = signature_offset + 64;
    let message_offset = public_key_offset + 33;
    let mut data = Vec::with_capacity(16 + 64 + 33 + message.len());
    data.extend_from_slice(&[1, 0]);
    data.extend_from_slice(&signature_offset.to_le_bytes());
    data.extend_from_slice(&u16::MAX.to_le_bytes());
    data.extend_from_slice(&public_key_offset.to_le_bytes());
    data.extend_from_slice(&u16::MAX.to_le_bytes());
    data.extend_from_slice(&message_offset.to_le_bytes());
    data.extend_from_slice(&(message.len() as u16).to_le_bytes());
    data.extend_from_slice(&u16::MAX.to_le_bytes());
    data.extend_from_slice(compact.as_slice());
    data.extend_from_slice(public_key);
    data.extend_from_slice(message);

    Instruction {
        program_id: SECP256R1_PROGRAM_ID,
        accounts: vec![],
        data,
    }
}

fn redeem_instruction(
    fixture: &Fixture,
    payer: Pubkey,
    note: Pubkey,
    vault: Pubkey,
    nonce: [u8; 32],
    request_expires_at: u64,
) -> Instruction {
    let recipient_ata = anchor_spl::associated_token::get_associated_token_address(
        &fixture.recipient.pubkey(),
        &fixture.mint.pubkey(),
    );
    Instruction {
        program_id: ID,
        accounts: accounts::RedeemNote {
            payer,
            recipient: fixture.recipient.pubkey(),
            note,
            usdc_mint: fixture.mint.pubkey(),
            vault_token: vault,
            recipient_token: recipient_ata,
            token_program: anchor_spl::token::ID,
            associated_token_program: anchor_spl::associated_token::ID,
            system_program: anchor_lang::system_program::ID,
            instructions: solana_instructions_sysvar::ID,
        }
        .to_account_metas(None),
        data: instruction::RedeemNote {
            recipient_nonce: nonce,
            request_expires_at_unix_s: request_expires_at,
        }
        .data(),
    }
}

#[test]
fn create_reject_mutation_redeem_replay_expiry_and_refund() {
    let mut f = setup();
    let (signing_key, owner_p256) = p256_key(7);

    // Note A: mutation must fail, canonical redemption must succeed, state replay must fail.
    let (note_a, vault_a, state_a) = create_note(&mut f, [0x11; 32], 1_000_000, owner_p256, 3_600);
    let commitment_a = commitment(&state_a, note_a);
    let good_nonce = [0x22; 32];
    let bad_nonce = [0x23; 32];
    let request_expiry = state_a.issued_at_unix_s + 300;
    let payload_a = encode_transfer_payload(
        &commitment_a,
        &f.recipient.pubkey(),
        &good_nonce,
        request_expiry,
    );
    let p256_a = p256_instruction(&signing_key, &owner_p256, &payload_a);

    // The precompile verifies a real signature, but redeem args ask for a different nonce.
    let bad_redeem = redeem_instruction(
        &f,
        f.payer.pubkey(),
        note_a,
        vault_a,
        bad_nonce,
        request_expiry,
    );
    f.ctx
        .execute_instructions(vec![p256_a.clone(), bad_redeem], &[&f.payer, &f.recipient])
        .unwrap()
        .assert_anchor_error("TransferPayloadMismatch");
    let still_funded: Note = f.ctx.get_account(&note_a).unwrap();
    assert_eq!(still_funded.status, STATUS_FUNDED);
    f.ctx.svm.assert_token_balance(&vault_a, 1_000_000);

    let good_redeem = redeem_instruction(
        &f,
        f.payer.pubkey(),
        note_a,
        vault_a,
        good_nonce,
        request_expiry,
    );
    f.ctx
        .execute_instructions(vec![p256_a.clone(), good_redeem], &[&f.payer, &f.recipient])
        .unwrap()
        .assert_success();
    let redeemed: Note = f.ctx.get_account(&note_a).unwrap();
    assert_eq!(redeemed.status, STATUS_REDEEMED);
    assert_eq!(redeemed.redeemed_by, f.recipient.pubkey());
    f.ctx.svm.assert_token_balance(&vault_a, 0);
    let recipient_ata = anchor_spl::associated_token::get_associated_token_address(
        &f.recipient.pubkey(),
        &f.mint.pubkey(),
    );
    f.ctx.svm.assert_token_balance(&recipient_ata, 1_000_000);

    // Use a fresh fee payer so this is a distinct transaction and must fail on AIRNOTE state,
    // not merely LiteSVM duplicate-transaction detection.
    let replay_payer = f.ctx.svm.create_funded_account(10_000_000_000).unwrap();
    let replay_redeem = redeem_instruction(
        &f,
        replay_payer.pubkey(),
        note_a,
        vault_a,
        good_nonce,
        request_expiry,
    );
    f.ctx
        .execute_instructions(
            vec![p256_a, replay_redeem],
            &[&replay_payer, &f.recipient],
        )
        .unwrap()
        .assert_anchor_error("NoteConsumed");
    let replay_state: Note = f.ctx.get_account(&note_a).unwrap();
    assert_eq!(replay_state.status, STATUS_REDEEMED);
    f.ctx.svm.assert_token_balance(&recipient_ata, 1_000_000);

    // Note B: a signed recipient request expires before the note itself.
    let (signing_key_b, owner_b) = p256_key(9);
    let (note_b, vault_b, state_b) = create_note(&mut f, [0x44; 32], 5_000_000, owner_b, 60);
    let commitment_b = commitment(&state_b, note_b);
    let nonce_b = [0x55; 32];
    let request_expiry_b = state_b.issued_at_unix_s + 30;
    let payload_b = encode_transfer_payload(
        &commitment_b,
        &f.recipient.pubkey(),
        &nonce_b,
        request_expiry_b,
    );
    let p256_b = p256_instruction(&signing_key_b, &owner_b, &payload_b);

    let mut clock = f.ctx.svm.get_sysvar::<Clock>();
    clock.unix_timestamp = i64::try_from(request_expiry_b + 1).unwrap();
    f.ctx.svm.set_sysvar::<Clock>(&clock);
    assert!(request_expiry_b + 1 < state_b.expires_at_unix_s);

    let expired_request_redeem = redeem_instruction(
        &f,
        f.payer.pubkey(),
        note_b,
        vault_b,
        nonce_b,
        request_expiry_b,
    );
    f.ctx
        .execute_instructions(
            vec![p256_b, expired_request_redeem],
            &[&f.payer, &f.recipient],
        )
        .unwrap()
        .assert_anchor_error("InvalidRequestExpiry");
    let request_expired_state: Note = f.ctx.get_account(&note_b).unwrap();
    assert_eq!(request_expired_state.status, STATUS_FUNDED);
    f.ctx.svm.assert_token_balance(&vault_b, 5_000_000);

    // Once the note expires, a different wallet still cannot take the refund.
    clock.unix_timestamp = i64::try_from(state_b.expires_at_unix_s + 1).unwrap();
    f.ctx.svm.set_sysvar::<Clock>(&clock);
    let attacker = f.ctx.svm.create_funded_account(10_000_000_000).unwrap();
    let attacker_ata = f
        .ctx
        .svm
        .create_associated_token_account(&f.mint.pubkey(), &attacker)
        .unwrap();
    let unauthorized_refund = Instruction {
        program_id: ID,
        accounts: accounts::RefundNote {
            funder: attacker.pubkey(),
            note: note_b,
            usdc_mint: f.mint.pubkey(),
            vault_token: vault_b,
            funder_token: attacker_ata,
            token_program: anchor_spl::token::ID,
        }
        .to_account_metas(None),
        data: instruction::RefundNote {}.data(),
    };
    f.ctx
        .execute_instruction(unauthorized_refund, &[&attacker])
        .unwrap()
        .assert_anchor_error("UnauthorizedRefund");
    let after_attack: Note = f.ctx.get_account(&note_b).unwrap();
    assert_eq!(after_attack.status, STATUS_FUNDED);
    f.ctx.svm.assert_token_balance(&vault_b, 5_000_000);

    let refund_ix = Instruction {
        program_id: ID,
        accounts: accounts::RefundNote {
            funder: f.funder.pubkey(),
            note: note_b,
            usdc_mint: f.mint.pubkey(),
            vault_token: vault_b,
            funder_token: f.funder_ata,
            token_program: anchor_spl::token::ID,
        }
        .to_account_metas(None),
        data: instruction::RefundNote {}.data(),
    };
    f.ctx
        .execute_instruction(refund_ix, &[&f.funder])
        .unwrap()
        .assert_success();
    let refunded: Note = f.ctx.get_account(&note_b).unwrap();
    assert_eq!(refunded.status, STATUS_REFUNDED);
    f.ctx.svm.assert_token_balance(&vault_b, 0);

    // 20 USDC initial - 1 USDC redeemed; the 5 USDC note was returned.
    f.ctx.svm.assert_token_balance(&f.funder_ata, 19_000_000);

    // Keep admin alive in the fixture so future extensions can mutate config without rebuilding setup.
    assert_ne!(f.admin.pubkey(), Pubkey::default());
}
