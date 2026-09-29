# Setting up the sync server with Docker

For any computer with Docker that stays on at home: a NAS, a home server or a spare PC. For OpenMediaVault's web interface, see [omv-setup.md](omv-setup.md) instead.

## 1. A folder for Plover

Paste these lines into a terminal. They make a `plover` folder in your home folder, with the data folder inside it, and download `compose.yaml` into it:

```sh
mkdir -p ~/plover/data
cd ~/plover
curl -fsSLO https://raw.githubusercontent.com/plainly-studio/plover-server/main/compose.yaml
sed -i 's|- CHANGE_TO_YOUR_DATA_FOLDER:/data|- ./data:/data|' compose.yaml
printf 'PUID=%s\nPGID=%s\n' "$(id -u)" "$(id -g)" > .env
```

What they do:
- **`mkdir`** creates the data folder, where the server keeps its database and TLS certificate. It has to exist **before** the container first starts: otherwise Docker creates it owned by root, and the server can't write to it.
- **`curl`** downloads [`compose.yaml`](../compose.yaml). No `curl`? Install it (`sudo apt install curl` on Ubuntu or Debian), or run `nano compose.yaml`, paste the file's contents from GitHub, and save with Ctrl+O, Enter, Ctrl+X.
- **`sed`** points `compose.yaml` at the data folder: its `volumes:` line becomes `- ./data:/data`.
- **`printf`** writes a `.env` file, so the container runs as you and can write to the folder.

## 2. Start it

From the `plover` folder:

```sh
docker compose up -d
```

Port **8443** must be free. If it isn't, change the left side of `"8443:8443"`, e.g. `"9443:8443"`, and use that port in the app.

## 3. Read the log

```sh
docker logs plover
```

It shows the address to enter in the app, a pairing code, and the certificate's fingerprint. `docker exec plover /opt/plover/bin/plover-server show-pairing` prints them again later.

Give the computer a fixed address in your router, so the app can always find it.

## 4. Pair your phone

In the app: **Settings → Cloud backup → Back up to your NAS**.

1. **Server address:** the computer's IP address on your network, e.g. `192.168.1.20` (port 8443 is assumed).
2. **Check the fingerprint:** it must match the log, line by line. From then on, the app only talks to a server with exactly this certificate.
3. **Pairing code:** from the log.
4. **Create your vault** on your first device, with a passphrase of at least 10 characters. **Write it down:** nobody can recover it, and without it the synced data can't be read.

On your other devices, repeat steps 1–3 and unlock the vault with the same passphrase.

## Afterwards

Run the `docker compose` commands from the `plover` folder (`cd ~/plover`).

- **Stop new pairings** once every device is paired: set `PLOVER_PAIRING_ENABLED: "false"` in `compose.yaml` and run `docker compose up -d` again.
- **Update:** `docker compose pull && docker compose up -d`.
- **Back up** `~/plover/data`. It's already encrypted.
- **More** (restoring, a new pairing code, a forgotten passphrase, a new certificate): see the maintenance table in [omv-setup.md](omv-setup.md#maintenance); the `docker exec` commands are the same.

## Troubleshooting

- **The log says "Plover can't write to its data folder"** (and the container keeps restarting): the folder belongs to another user, often root because Docker created it. Give it to yourself with `sudo chown -R "$(id -u):$(id -g)" ~/plover/data`, then `docker compose up -d`.
