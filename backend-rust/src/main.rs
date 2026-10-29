use std::{
    env,
    io::{self, IsTerminal, Read, Write},
    process::ExitCode,
};
use vyrn_core::{MAX_PASSWORD_BYTES, digest, hash_password, token, verify_password};
use zeroize::Zeroizing;

fn password() -> Result<Zeroizing<String>, ()> {
    // Never echo passwords or accept them in shell history/process arguments.
    if io::stdin().is_terminal() {
        return Err(());
    }
    let mut value = Zeroizing::new(String::new());
    io::stdin()
        .take((MAX_PASSWORD_BYTES + 1) as u64)
        .read_to_string(&mut value)
        .map_err(|_| ())?;
    if value.len() > MAX_PASSWORD_BYTES {
        return Err(());
    }
    // Input is exact bytes: do not trim spaces or silently change a password.
    Ok(value)
}

fn run(args: &[String]) -> Result<String, ()> {
    match args {
        [command] if command == "token" => token().map_err(|_| ()),
        [command] if command == "hash-password" => hash_password(&password()?).map_err(|_| ()),
        [command, encoded] if command == "verify-password" => {
            if verify_password(encoded, &password()?) {
                Ok("verified".into())
            } else {
                Err(())
            }
        }
        [command] if command == "digest" => {
            let value = password()?;
            let mut result = String::with_capacity(64);
            use std::fmt::Write;
            for byte in digest(&value) {
                write!(&mut result, "{byte:02x}").map_err(|_| ())?
            }
            Ok(result)
        }
        _ => Err(()),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    match run(&args) {
        Ok(output) => {
            if writeln!(io::stdout().lock(), "{output}").is_ok() {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Err(()) => {
            let _ = writeln!(
                io::stderr(),
                "Command failed. Use token, hash-password, verify-password HASH, or digest. Password/input bytes must come from redirected stdin (no added newline). This is a migration utility, not the VYRN server."
            );
            ExitCode::FAILURE
        }
    }
}
