use airnote_attestor::AttestorKey;
use airnote_vault::protocol::{encode_mint_certificate_payload, MintCertificateFields};
use std::{env, fs, process};

fn usage() -> ! {
    eprintln!(
        "usage: airnote-attestor issue <seed-hex-file> <note-commitment-hex> <finalized-slot> <mint-tx-signature-hex> <attestor-key-id>"
    );
    process::exit(2)
}

fn decode_fixed<const N: usize>(value: &str, label: &str) -> [u8; N] {
    let bytes = hex::decode(value.trim()).unwrap_or_else(|err| {
        eprintln!("invalid {label} hex: {err}");
        process::exit(2)
    });
    bytes.try_into().unwrap_or_else(|bytes: Vec<u8>| {
        eprintln!("{label} must be exactly {N} bytes, got {}", bytes.len());
        process::exit(2)
    })
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() != 7 || args[1] != "issue" {
        usage();
    }

    let seed_text = fs::read_to_string(&args[2]).unwrap_or_else(|err| {
        eprintln!("failed to read attestor seed file: {err}");
        process::exit(2)
    });
    let seed = decode_fixed::<32>(&seed_text, "attestor seed");
    let note_commitment = decode_fixed::<32>(&args[3], "note commitment");
    let finalized_slot = args[4].parse::<u64>().unwrap_or_else(|err| {
        eprintln!("invalid finalized slot: {err}");
        process::exit(2)
    });
    let mint_tx_signature = decode_fixed::<64>(&args[5], "mint transaction signature");
    let attestor_key_id = args[6].parse::<u32>().unwrap_or_else(|err| {
        eprintln!("invalid attestor key id: {err}");
        process::exit(2)
    });

    let fields = MintCertificateFields {
        note_commitment,
        finalized_slot,
        mint_tx_signature,
        attestor_key_id,
    };
    let attestor = AttestorKey::from_seed(seed);
    let payload = encode_mint_certificate_payload(&fields);
    let signature = attestor.sign_certificate(&fields);

    println!("attestor_public_key={}", hex::encode(attestor.public_key_bytes()));
    println!("certificate_payload={}", hex::encode(payload));
    println!("attestor_signature={}", hex::encode(signature));
}
