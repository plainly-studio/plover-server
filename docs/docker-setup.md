# Setting up the sync server with Docker

For any computer with Docker that stays on at home: a NAS, a home server or a spare PC. For OpenMediaVault's web interface, see [omv-setup.md](omv-setup.md) instead.

## 1. A data folder

The server keeps its database and TLS certificate here:

```sh
mkdir -p ~/plover-data
```

It must be writable by the user the container runs as. `compose.yaml` runs it as `1000:100`; check yours with `id`, and set `PUID` and `PGID` if they differ.

## 2. Start it

Copy [`compose.yaml`](../compose.yaml) next to the folder, replace `CHANGE_TO_YOUR_DATA_FOLDER/plover` with the folder's full path, then:

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

- **Stop new pairings** once every device is paired: set `PLOVER_PAIRING_ENABLED: "false"` in `compose.yaml` and run `docker compose up -d` again.
- **Update:** `docker compose pull && docker compose up -d`.
- **Back up** the data folder. It's already encrypted.
- **More** (restoring, a new pairing code, a forgotten passphrase, a new certificate): see the maintenance table in [omv-setup.md](omv-setup.md#maintenance); the `docker exec` commands are the same.
