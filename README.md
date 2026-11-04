# Sparrow-Scrypt

Sparrow-Scrypt is a desktop wallet for the Scrypt coins: Litecoin, Dogecoin, Pepecoin, Junkcoin, Luckycoin, Bellscoin and Dingocoin, in one app. Each wallet belongs to one coin. It is built on [Sparrow-LTC](https://github.com/sparrow-ltc/sparrow), which is built on [Sparrow Wallet](https://github.com/sparrowwallet/sparrow), and keeps their focus on PSBTs, coin control and transparency.

## Status: beta

Sparrow-Scrypt is in public testing. Expect bugs. Use test coins where you can and small amounts where you can't, and never put a seed that holds real savings into a beta build.

| Coin | Ticker | Test mode |
| --- | --- | --- |
| Litecoin | LTC | Public testnet |
| Pepecoin | PEP | Public testnet |
| Junkcoin | JKC | Public testnet |
| Dogecoin | DOGE | Your own node, or mainnet with small amounts |
| Luckycoin | LKY | Your own node, or mainnet with small amounts |
| Bellscoin | BELLS | Your own node, or mainnet with small amounts |
| Dingocoin | DINGO | Your own node, or mainnet with small amounts |

## What it adds

- Seven Scrypt coins in one app, with servers set per coin and test mode for every coin.
- Litecoin MWEB: a private wallet next to the public one, one-click moves between them, and Electrum-LTC view-key import and export.
- Litecoin inscriptions and runes: view, protect, send, mint and etch. Inscriptions on Dogecoin, Pepecoin, Bellscoin and Junkcoin: view and send.
- Seed imports from each coin's own wallet app (MyDoge, Peppool, Dingo, JunkieWally, luckycoinwallet, Nintondo) and from Cake Wallet.
- An encrypted seed backup file (Argon2id and AES-GCM, with its own password).
- LitVM on testnet: zkLTC, ERC-20 tokens, token creation and bridges, linked to a Litecoin wallet.
- An Android companion app, paired by QR, that can also act as an air-gapped signer.

## Downloads

Builds are on the [Releases](../../releases) page, with SHA256 checksums. Check the file before you run it.

| System | File |
| --- | --- |
| Windows 10 and 11, x64 | `Sparrow-Scrypt-<version>.zip` |
| Linux x64 | `sparrowscryptwallet-<version>-x86_64.tar.gz` |
| macOS 11 or later, Apple Silicon | `Sparrow-Scrypt-<version>-macos-aarch64.zip` |
| macOS 11 or later, Intel | `Sparrow-Scrypt-<version>-macos-x86_64.zip` |

The builds are not code-signed yet:

- Windows: unzip and run `Sparrow-Scrypt.exe`. If SmartScreen stops it, choose More info, then Run anyway.
- macOS: unzip and move `Sparrow-Scrypt.app` to Applications. The app is not notarized, so run `xattr -dr com.apple.quarantine /Applications/Sparrow-Scrypt.app` once in Terminal, then open it.
- Linux: extract and run `Sparrow-Scrypt/bin/Sparrow-Scrypt`.

## Building

Clone with the drongo and lark submodules:

`git clone --recursive https://github.com/scryptofoundation/sparrow-scrypt.git`

Building needs Java 25 or higher; the release builds use [Eclipse Temurin](https://adoptium.net/) 25. Build the app with

`./gradlew jpackageImage`

and find it in `build/jpackage/Sparrow-Scrypt`. To run from source instead, use `./sparrow` in the project folder.

`-Pcoin=ltc|doge|pep|lky|bells|dingo` builds a single-coin app (for example Sparrow-DOGE) instead of the multi-coin one.

## Configuration

`./sparrow -h` lists the command line options. The most useful are `--dir` for another home folder and `--network` for `testnet` or `regtest`.

The config file, log and wallets live in:

| Platform | Location |
| --- | --- |
| Windows | `%APPDATA%\Sparrow-Scrypt` |
| macOS and Linux | `~/.sparrow-scrypt` |

Test networks keep their own config and wallets in subfolders.

## Reporting problems

Open an issue in this repository with the coin, the build, your system and what happened. The `sparrow.log` file in the home folder usually helps. Never post a seed, a private key or a wallet file.

## License

Sparrow-Scrypt is licensed under the Apache 2 software licence, like Sparrow Wallet and Sparrow-LTC.

## Credit

Sparrow Wallet is by Craig Raw. Sparrow-LTC, the Litecoin port this builds on, is by Hector Chu.
