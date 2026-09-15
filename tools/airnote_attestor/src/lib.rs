use airnote_vault::protocol::{encode_mint_certificate_payload, MintCertificateFields};
use ed25519_dalek::{Signer, SigningKey, VerifyingKey};

pub struct AttestorKey {
    signing_key: SigningKey,
}

impl AttestorKey {
    pub fn from_seed(seed: [u8; 32]) -> Self {
        Self {
            signing_key: SigningKey::from_bytes(&seed),
        }
    }

    pub fn public_key_bytes(&self) -> [u8; 32] {
        self.signing_key.verifying_key().to_bytes()
    }

    pub fn sign_certificate(&self, fields: &MintCertificateFields) -> [u8; 64] {
        self.signing_key
            .sign(&encode_mint_certificate_payload(fields))
            .to_bytes()
    }

    pub fn verifying_key(&self) -> VerifyingKey {
        self.signing_key.verifying_key()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signature, Verifier};

    fn deterministic_fields() -> MintCertificateFields {
        let mut tx_signature = [0u8; 64];
        for (index, byte) in tx_signature.iter_mut().enumerate() {
            *byte = index as u8;
        }
        MintCertificateFields {
            note_commitment: hex::decode(
                "d6dfd317a4c51dde7d2b824fc09dbb8b6f9721efb9d6c9044f6a527c854ff650",
            )
            .unwrap()
            .try_into()
            .unwrap(),
            finalized_slot: 250_000_000,
            mint_tx_signature: tx_signature,
            attestor_key_id: 7,
        }
    }

    #[test]
    fn matches_android_gate03_attestor_vector() {
        let mut seed = [0u8; 32];
        for (index, byte) in seed.iter_mut().enumerate() {
            *byte = (index + 1) as u8;
        }
        let attestor = AttestorKey::from_seed(seed);
        assert_eq!(
            hex::encode(attestor.public_key_bytes()),
            "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
        );

        let fields = deterministic_fields();
        let payload = encode_mint_certificate_payload(&fields);
        assert_eq!(payload.len(), 128);
        let signature = attestor.sign_certificate(&fields);
        assert_eq!(
            hex::encode(signature),
            "af9ce7a59779d62228178371cfc2225efc9a31fa4b54032cb2d04f6223f8888b165c5bf380843b29bd27cc3756c547bce62b459a7117d55b7c2cf4188f24a70e"
        );
        attestor
            .verifying_key()
            .verify(&payload, &Signature::from_bytes(&signature))
            .unwrap();
    }

    #[test]
    fn mutation_breaks_signature() {
        let attestor = AttestorKey::from_seed([7u8; 32]);
        let fields = deterministic_fields();
        let signature = Signature::from_bytes(&attestor.sign_certificate(&fields));
        let mut payload = encode_mint_certificate_payload(&fields);
        payload[40] ^= 1;
        assert!(attestor.verifying_key().verify(&payload, &signature).is_err());
    }
}
