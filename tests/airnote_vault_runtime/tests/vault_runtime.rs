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
fn create_reject_mutation_redeem_replay_delayed_settlement_and_refund() {
    let mut f = setup();
    let recipient_ata = anchor_spl::associated_token::get_associated_token_address(
        &f.recipient.pubkey(),
        &f.mint.pubkey(),
    );

    // Note A: mutation must fail, canonical redemption must succeed, and replay must fail.
    let (signing_key_a, owner_a) = p256_key(7);
    let (note_a, vault_a, state_a) = create_note(&mut f, [0x11; 32], 1_000_000, owner_a, 3_600);
    let commitment_a = commitment(&state_a, note_a);
    let good_nonce_a = [0x22; 32];
    let bad_nonce_a = [0x23; 32];
    let request_expiry_a = state_a.issued_at_unix_s + 300;
    let payload_a = encode_transfer_payload(
        &commitment_a,
        &f.recipient.pubkey(),
        &good_nonce_a,
        request_expiry_a,
    );
    let p256_a = p256_instruction(&signing_key_a, &owner_a, &payload_a);

    let bad_redeem_a = redeem_instruction(
        &f,
        f.payer.pubkey(),
        note_a,
        vault_a,
        bad_nonce_a,
        request_expiry_a,
    );
    f.ctx
        .execute_instructions(vec![p256_a.clone(), bad_redeem_a], &[&f.payer, &f.recipient])
        .unwrap()
        .assert_anchor_error("TransferPayloadMismatch");
    let still_funded_a: Note = f.ctx.get_account(&note_a).unwrap();
    assert_eq!(still_funded_a.status, STATUS_FUNDED);
    f.ctx.svm.assert_token_balance(&vault_a, 1_000_000);

    let good_redeem_a = redeem_instruction(
        &f,
        f.payer.pubkey(),
        note_a,
        vault_a,
        good_nonce_a,
        request_expiry_a,
    );
    f.ctx
        .execute_instructions(vec![p256_a.clone(), good_redeem_a], &[&f.payer, &f.recipient])
        .unwrap()
        .assert_success();
    let redeemed_a: Note = f.ctx.get_account(&note_a).unwrap();
    assert_eq!(redeemed_a.status, STATUS_REDEEMED);
    assert_eq!(redeemed_a.redeemed_by, f.recipient.pubkey());
    f.ctx.svm.assert_token_balance(&vault_a, 0);
    f.ctx.svm.assert_token_balance(&recipient_ata, 1_000_000);

    let replay_payer = f.ctx.svm.create_funded_account(10_000_000_000).unwrap();
    let replay_redeem_a = redeem_instruction(
        &f,
        replay_payer.pubkey(),
        note_a,
        vault_a,
        good_nonce_a,
        request_expiry_a,
    );
    f.ctx
        .execute_instructions(
            vec![p256_a, replay_redeem_a],
            &[&replay_payer, &f.recipient],
        )
        .unwrap()
        .assert_anchor_error("NoteConsumed");
    f.ctx.svm.assert_token_balance(&recipient_ata, 1_000_000);

    // Note B: the offline request may expire after acceptance, but the signed transfer
    // can still settle later while the Airnote itself remains live.
    let (signing_key_b, owner_b) = p256_key(9);
    let (note_b, vault_b, state_b) = create_note(&mut f, [0x44; 32], 5_000_000, owner_b, 120);
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

    let delayed_redeem_b = redeem_instruction(
        &f,
        f.payer.pubkey(),
        note_b,
        vault_b,
        nonce_b,
        request_expiry_b,
    );
    f.ctx
        .execute_instructions(vec![p256_b, delayed_redeem_b], &[&f.payer, &f.recipient])
        .unwrap()
        .assert_success();
    let redeemed_b: Note = f.ctx.get_account(&note_b).unwrap();
    assert_eq!(redeemed_b.status, STATUS_REDEEMED);
    assert_eq!(redeemed_b.redeemed_by, f.recipient.pubkey());
    f.ctx.svm.assert_token_balance(&vault_b, 0);
    f.ctx.svm.assert_token_balance(&recipient_ata, 6_000_000);

    // Note C: a request expiry that outlives the Airnote envelope is still invalid.
    // The same funded note also proves that note expiry remains the final settlement deadline.
    let (signing_key_c, owner_c) = p256_key(11);
    let (note_c, vault_c, state_c) = create_note(&mut f, [0x66; 32], 5_000_000, owner_c, 120);
    let commitment_c = commitment(&state_c, note_c);
    let invalid_nonce_c = [0x77; 32];
    let invalid_request_expiry_c = state_c.expires_at_unix_s + 1;
    let invalid_payload_c = encode_transfer_payload(
        &commitment_c,
        &f.recipient.pubkey(),
        &invalid_nonce_c,
        invalid_request_expiry_c,
    );
    let invalid_p256_c = p256_instruction(&signing_key_c, &owner_c, &invalid_payload_c);
    let invalid_redeem_c = redeem_instruction(
        &f,
        f.payer.pubkey(),
        note_c,
        vault_c,
        invalid_nonce_c,
        invalid_request_expiry_c,
    );
    f.ctx
        .execute_instructions(
            vec![invalid_p256_c, invalid_redeem_c],
            &[&f.payer, &f.recipient],
        )
        .unwrap()
        .assert_anchor_error("InvalidRequestExpiry");
    let still_funded_c: Note = f.ctx.get_account(&note_c).unwrap();
    assert_eq!(still_funded_c.status, STATUS_FUNDED);
    f.ctx.svm.assert_token_balance(&vault_c, 5_000_000);

    let valid_nonce_c = [0x78; 32];
    let valid_request_expiry_c = state_c.issued_at_unix_s + 30;
    let valid_payload_c = encode_transfer_payload(
        &commitment_c,
        &f.recipient.pubkey(),
        &valid_nonce_c,
        valid_request_expiry_c,
    );
    let valid_p256_c = p256_instruction(&signing_key_c, &owner_c, &valid_payload_c);

    clock.unix_timestamp = i64::try_from(state_c.expires_at_unix_s + 1).unwrap();
    f.ctx.svm.set_sysvar::<Clock>(&clock);
    let expired_note_redeem_c = redeem_instruction(
        &f,
        f.payer.pubkey(),
        note_c,
        vault_c,
        valid_nonce_c,
        valid_request_expiry_c,
    );
    f.ctx
        .execute_instructions(
            vec![valid_p256_c, expired_note_redeem_c],
            &[&f.payer, &f.recipient],
        )
        .unwrap()
        .assert_anchor_error("NoteExpired");

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
            note: note_c,
            usdc_mint: f.mint.pubkey(),
            vault_token: vault_c,
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
    let after_attack_c: Note = f.ctx.get_account(&note_c).unwrap();
    assert_eq!(after_attack_c.status, STATUS_FUNDED);
    f.ctx.svm.assert_token_balance(&vault_c, 5_000_000);

    let refund_c = Instruction {
        program_id: ID,
        accounts: accounts::RefundNote {
            funder: f.funder.pubkey(),
            note: note_c,
            usdc_mint: f.mint.pubkey(),
            vault_token: vault_c,
            funder_token: f.funder_ata,
            token_program: anchor_spl::token::ID,
        }
        .to_account_metas(None),
        data: instruction::RefundNote {}.data(),
    };
    f.ctx
        .execute_instruction(refund_c, &[&f.funder])
        .unwrap()
        .assert_success();
    let refunded_c: Note = f.ctx.get_account(&note_c).unwrap();
    assert_eq!(refunded_c.status, STATUS_REFUNDED);
    f.ctx.svm.assert_token_balance(&vault_c, 0);

    // 20 USDC initial - 1 USDC redeemed - 5 USDC delayed redemption; Note C refunded.
    f.ctx.svm.assert_token_balance(&f.funder_ata, 14_000_000);
    f.ctx.svm.assert_token_balance(&recipient_ata, 6_000_000);

    assert_ne!(f.admin.pubkey(), Pubkey::default());
}
