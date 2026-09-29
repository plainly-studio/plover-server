# Plover sync server

Keeps the [Plover](https://plainly.au/plover/server/) Android app in sync between your devices, through a server you run at home. (The app was called Subtrack at first. The code still uses that name in places, like its Kotlin packages and the encryption format, and settings named `SUBTRACK_*` still work.)

- **End-to-end encrypted.** Your subscriptions are encrypted on the phone with a key made from your passphrase (Argon2id, then AES-256-GCM) before they're sent. The server stores only data it can't read. The code that does it is here: [`sync/`](sync/src/main/kotlin/app/subtrack/core/crypto/VaultCrypto.kt).
- **On your home network.** HTTPS with a self-signed certificate that the app pins at pairing. Phones sync when they're on the same network, and wait while they're away.
- **Small.** One Docker container, about 150 MB of memory, SQLite for storage.

## Running it

- **With Docker Compose:** copy [`compose.yaml`](compose.yaml), set your data folder, and run `docker compose up -d`. The image is built for `linux/amd64` and `linux/arm64`.
- **Guides:** [plain Docker](docs/docker-setup.md) and [OpenMediaVault](docs/omv-setup.md).
- **Then, in the app:** Settings → Cloud backup → Back up to your NAS. Enter the server's address, check the fingerprint against the server's log, and enter the pairing code from the log.

## What's here

| | |
|---|---|
| [`server/`](server/) | The Ktor server: pairing, devices, and storing encrypted records. |
| [`sync/`](sync/) | What the app and the server share: the sync protocol, the phone's sync engine, and the vault's encryption. |
| [`Dockerfile`](Dockerfile) | The image, built by CI and published to the GitHub Container Registry. |

Build and test with JDK 21: `./gradlew :sync:check :server:test`.

## Reporting problems

Issues here are for the server. For the app, email [support@plainly.au](mailto:support@plainly.au).

A security problem? Please email [support@plainly.au](mailto:support@plainly.au) rather than opening a public issue.

## Licence

Apache License 2.0: see [LICENSE](LICENSE) and [NOTICE](NOTICE). Contributions are accepted under the same licence (section 5).

The Plover app itself is not open source; this repository holds only the server and the code it shares with the app.
