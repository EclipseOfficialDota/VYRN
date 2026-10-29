//! Compatible primitives for the Rust backend migration.
//! This crate is not yet an HTTP server or a replacement for the Go backend.

use argon2::{Algorithm, Argon2, Params, Version};
use base64::{
    Engine,
    engine::general_purpose::{STANDARD_NO_PAD, URL_SAFE_NO_PAD},
};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

pub const MAX_PASSWORD_BYTES: usize = 1024;
pub const PASSWORD_MEMORY_KIB: u32 = 65_536;
const PREFIX: &str = "$argon2id$v=19$m=65536,t=3,p=1$";

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    InvalidPassword,
    RandomUnavailable,
    HashFailed,
}

pub fn valid_password(password: &str) -> bool {
    password.len() <= MAX_PASSWORD_BYTES && password.chars().count() >= 8
}

fn hasher() -> Result<Argon2<'static>, Error> {
    // Preserve existing accounts: Go uses Argon2id v19, 64 MiB, 3 passes, 1 lane.
    let params = Params::new(PASSWORD_MEMORY_KIB, 3, 1, Some(32)).map_err(|_| Error::HashFailed)?;
    Ok(Argon2::new(Algorithm::Argon2id, Version::V0x13, params))
}

pub fn hash_password(password: &str) -> Result<String, Error> {
    if !valid_password(password) {
        return Err(Error::InvalidPassword);
    }
    let mut salt = [0u8; 16];
    getrandom::fill(&mut salt).map_err(|_| Error::RandomUnavailable)?;
    let hash = derive(password, &salt)?;
    Ok(format!(
        "{PREFIX}{}${}",
        STANDARD_NO_PAD.encode(salt),
        STANDARD_NO_PAD.encode(*hash)
    ))
}

fn derive(password: &str, salt: &[u8]) -> Result<Zeroizing<[u8; 32]>, Error> {
    let mut hash = Zeroizing::new([0u8; 32]);
    hasher()?
        .hash_password_into(password.as_bytes(), salt, hash.as_mut())
        .map_err(|_| Error::HashFailed)?;
    Ok(hash)
}

pub fn verify_password(encoded: &str, password: &str) -> bool {
    // Reject attacker-controlled work factors BEFORE invoking Argon2. Accept only
    // the exact existing format, not arbitrary parameters from a PHC parser.
    if password.len() > MAX_PASSWORD_BYTES || encoded.len() != 97 {
        return false;
    }
    let Some(body) = encoded.strip_prefix(PREFIX) else {
        return false;
    };
    let Some((salt, expected)) = body.split_once('$') else {
        return false;
    };
    let (Ok(salt), Ok(expected)) = (
        STANDARD_NO_PAD.decode(salt),
        STANDARD_NO_PAD.decode(expected),
    ) else {
        return false;
    };
    if salt.len() != 16 || expected.len() != 32 {
        return false;
    }
    match derive(password, &salt) {
        Ok(actual) => bool::from(actual.as_slice().ct_eq(&expected)),
        Err(_) => false,
    }
}

pub fn token() -> Result<String, Error> {
    let mut bytes = Zeroizing::new([0u8; 32]);
    getrandom::fill(bytes.as_mut()).map_err(|_| Error::RandomUnavailable)?;
    Ok(URL_SAFE_NO_PAD.encode(*bytes))
}

pub fn digest(value: &str) -> [u8; 32] {
    Sha256::digest(value.as_bytes()).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn existing_password_policy_and_formats() {
        assert!(!valid_password("1234567"));
        assert!(valid_password("12345678"));
        assert!(!valid_password("ééééééé"));
        assert!(valid_password("éééééééé"));
        assert!(valid_password(&"x".repeat(1024)));
        assert!(!valid_password(&"x".repeat(1025)));
        let password = "درخت سبز قشنگ";
        let encoded = hash_password(password).unwrap();
        assert!(verify_password(&encoded, password));
        assert!(!verify_password(&encoded, "wrong password"));
        assert_ne!(encoded, hash_password(password).unwrap());
        assert!(!verify_password(&encoded, &"x".repeat(1025)));
        for hostile in [
            "",
            "$argon2id$v=19$m=999999999,t=3,p=1$x$y",
            "$argon2id$v=19$m=65536,t=3,p=1$x$y",
        ] {
            assert!(!verify_password(hostile, password));
        }
        assert!(!verify_password(&encoded.replace("p=1", "p=2"), password));
        assert!(!verify_password(&encoded.replace("v=19", "v=16"), password));
    }

    #[test]
    fn token_encoding_and_digest_contract() {
        let a = token().unwrap();
        let b = token().unwrap();
        assert_eq!(a.len(), 43);
        assert_ne!(a, b);
        assert_eq!(URL_SAFE_NO_PAD.decode(&a).unwrap().len(), 32);
        assert_eq!(
            digest("abc"),
            [
                186, 120, 22, 191, 143, 1, 207, 234, 65, 65, 64, 222, 93, 174, 34, 35, 176, 3, 97,
                163, 150, 23, 122, 156, 180, 16, 255, 97, 242, 0, 21, 173
            ]
        );
    }
}
