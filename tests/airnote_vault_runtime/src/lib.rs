use anchor_lang::{prelude::Pubkey, AccountDeserialize};
use litesvm::{
    types::{FailedTransactionMetadata, TransactionResult},
    LiteSVM,
};
use litesvm_token::{
    get_spl_account, spl_token::state::Account as TokenAccount,
    CreateAssociatedTokenAccount, CreateMint, MintTo,
};
use solana_address::Address;
pub use solana_instruction::Instruction;
use solana_keypair::Keypair as SolanaKeypair;
use solana_signer::Signer as SolanaSigner;
use solana_transaction::Transaction;
use std::ops::{Deref, DerefMut};

fn to_address(pubkey: &Pubkey) -> Address {
    Address::new_from_array(pubkey.to_bytes())
}

fn to_pubkey(address: Address) -> Pubkey {
    Pubkey::new_from_array(address.to_bytes())
}

pub struct Keypair(SolanaKeypair);

impl Keypair {
    pub fn pubkey(&self) -> Pubkey {
        to_pubkey(self.0.pubkey())
    }
}

pub struct MintAddress(Pubkey);

impl MintAddress {
    pub fn pubkey(&self) -> Pubkey {
        self.0
    }
}

pub struct RuntimeSvm {
    inner: LiteSVM,
}

impl Deref for RuntimeSvm {
    type Target = LiteSVM;

    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl DerefMut for RuntimeSvm {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl RuntimeSvm {
    pub fn create_funded_account(
        &mut self,
        lamports: u64,
    ) -> Result<Keypair, FailedTransactionMetadata> {
        let keypair = SolanaKeypair::new();
        self.inner.airdrop(&keypair.pubkey(), lamports)?;
        Ok(Keypair(keypair))
    }

    pub fn create_token_mint(
        &mut self,
        authority: &Keypair,
        decimals: u8,
    ) -> Result<MintAddress, FailedTransactionMetadata> {
        let mint = CreateMint::new(&mut self.inner, &authority.0)
            .decimals(decimals)
            .send()?;
        Ok(MintAddress(to_pubkey(mint)))
    }

    pub fn create_associated_token_account(
        &mut self,
        mint: &Pubkey,
        owner: &Keypair,
    ) -> Result<Pubkey, FailedTransactionMetadata> {
        let mint = to_address(mint);
        let owner_address = owner.0.pubkey();
        let ata = CreateAssociatedTokenAccount::new(&mut self.inner, &owner.0, &mint)
            .owner(&owner_address)
            .send()?;
        Ok(to_pubkey(ata))
    }

    pub fn mint_to(
        &mut self,
        mint: &Pubkey,
        destination: &Pubkey,
        authority: &Keypair,
        amount: u64,
    ) -> Result<(), FailedTransactionMetadata> {
        let mint = to_address(mint);
        let destination = to_address(destination);
        MintTo::new(
            &mut self.inner,
            &authority.0,
            &mint,
            &destination,
            amount,
        )
        .owner(&authority.0)
        .send()
    }

    pub fn assert_token_balance(&self, account: &Pubkey, expected: u64) {
        let account = to_address(account);
        let balance = get_spl_account::<TokenAccount>(&self.inner, &account)
            .unwrap_or_else(|err| panic!("failed to read token account {account}: {err:?}"))
            .amount;
        assert_eq!(balance, expected, "unexpected token balance for {account}");
    }
}

pub struct AnchorContext {
    pub svm: RuntimeSvm,
}

pub struct AnchorLiteSVM;

impl AnchorLiteSVM {
    pub fn build_with_program(program_id: Pubkey, program_bytes: &[u8]) -> AnchorContext {
        let mut svm = LiteSVM::new();
        svm.add_program(to_address(&program_id), program_bytes)
            .unwrap_or_else(|err| panic!("failed to load AIRNOTE SBF program: {err:?}"));
        AnchorContext {
            svm: RuntimeSvm { inner: svm },
        }
    }
}

pub struct TxOutcome(TransactionResult);

impl TxOutcome {
    pub fn assert_success(self) {
        if let Err(err) = self.0 {
            panic!(
                "expected transaction success, got {:?}\n{}",
                err.err,
                err.meta.pretty_logs()
            );
        }
    }

    pub fn assert_anchor_error(self, expected_code: &str) {
        match self.0 {
            Ok(meta) => panic!(
                "expected Anchor error {expected_code}, transaction succeeded\n{}",
                meta.pretty_logs()
            ),
            Err(err) => {
                let needle = format!("Error Code: {expected_code}");
                assert!(
                    err.meta.logs.iter().any(|line| line.contains(&needle)),
                    "expected Anchor error {expected_code}, got {:?}\n{}",
                    err.err,
                    err.meta.pretty_logs()
                );
            }
        }
    }
}

impl AnchorContext {
    pub fn execute_instruction(
        &mut self,
        instruction: Instruction,
        signers: &[&Keypair],
    ) -> Result<TxOutcome, &'static str> {
        self.execute_instructions(vec![instruction], signers)
    }

    pub fn execute_instructions(
        &mut self,
        instructions: Vec<Instruction>,
        signers: &[&Keypair],
    ) -> Result<TxOutcome, &'static str> {
        let payer = signers.first().ok_or("at least one signer is required")?;
        let payer_address = payer.0.pubkey();
        let signer_refs: Vec<&SolanaKeypair> = signers.iter().map(|signer| &signer.0).collect();
        let tx = Transaction::new_signed_with_payer(
            &instructions,
            Some(&payer_address),
            &signer_refs,
            self.svm.inner.latest_blockhash(),
        );
        Ok(TxOutcome(self.svm.inner.send_transaction(tx)))
    }

    pub fn get_account<T: AccountDeserialize>(&self, pubkey: &Pubkey) -> Option<T> {
        let account = self.svm.inner.get_account(&to_address(pubkey))?;
        let mut data = account.data.as_slice();
        T::try_deserialize(&mut data).ok()
    }
}
