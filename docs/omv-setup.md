# Setting up the sync server on OpenMediaVault

The server is one small container (about 150 MB of RAM with the image's default 64 MB heap). It stores only encrypted data: even with full access to the NAS, nobody can read your subscriptions without your passphrase.

## 1. Install the Compose plugin

If you haven't already: **System → Plugins**, install **openmediavault-compose** (from omv-extras). Then, under **Services → Compose → Settings**, pick a shared folder for compose files and one for app data.

## 2. Create a data folder

The server keeps its database and TLS certificate here. For example, on your data drive:

```
/srv/dev-disk-by-uuid-XXXX/appdata/subtrack
```

It must be writable by the user the container runs as. The default in `compose.yaml` is `1000:100`, which is OMV's first user and the `users` group. Check yours with `id <your user>` over SSH, and fix ownership if needed:

```sh
sudo chown 1000:100 /srv/dev-disk-by-uuid-XXXX/appdata/subtrack
```

## 3. Add the service

**Services → Compose → Files → Add**, name it `subtrack`, and paste [`compose.yaml`](../compose.yaml) from this repository. Replace `CHANGE_TO_YOUR_DATA_FOLDER/subtrack` with your data folder from step 2. Save, then press **Up**.

> **The image** is `ghcr.io/plainly-studio/subtrack-sync-server`, built by this repository's CI for `linux/amd64` and `linux/arm64`: `latest` from `main`, and a tag for each release.
>
> **Building it yourself** instead: replace the `image:` line with `build: .` in a clone of this repository.

Port **8443** must be free. If it isn't, change the left side of `"8443:8443"`, e.g. `"9443:8443"`, and use that port in the app.

## 4. Read the log

**Services → Compose → Containers → subtrack → Logs**. You'll see:

```
  Subtrack sync server 1.0.0 on port 8443 (HTTPS)

  In the app, enter:   https://<your NAS IP address>:8443
  Pairing code:        Y9UZ-8TVS-F4VF
  Certificate SHA-256 fingerprint (check it matches what the app shows):
    5D:1B:7C:63:B4:74:94:46
    F3:58:F6:FC:1D:1A:EC:5A
    54:19:3C:8A:6D:39:41:56
    D5:11:BF:06:1C:62:76:68

  Vault: not created yet
  Paired devices: 0
```

Your NAS's IP address is on the OMV dashboard (**Dashboard → Network interfaces**). It helps to give the NAS a fixed address in your router.

## 5. Pair your phone

In the app: **Settings → Cloud backup → Back up to your NAS**.

1. **Server address**: the NAS IP, e.g. `192.168.1.20` (port 8443 is assumed).
2. **Check the fingerprint**: it must match the log line by line (the app shows the same four lines of eight bytes). This is what makes the connection trustworthy: the app will only ever talk to a server with exactly this certificate.
3. **Pairing code**: from the log.
4. **Create your vault** (first device) with a passphrase of at least 10 characters. **Write it down.** It can't be recovered; without it the synced data is unreadable.

On your other devices, repeat steps 1–3 and **unlock the vault** with the same passphrase.

Sync runs by itself about once an hour on Wi-Fi, shortly after every change, and when you tap **Sync now**. Away from home it simply waits until you're back.

## Hardening (optional)

Once all your devices are paired, stop new pairings: add `SUBTRACK_PAIRING_ENABLED: "false"` under `environment:` and redeploy. Lost a phone? Remove it under **Settings → Cloud backup → Back up to your NAS → Devices** on any other device.

## Maintenance

| Task | How |
|---|---|
| Update | Compose → Files → subtrack → **Pull**, then **Up**. |
| Health | The container reports *healthy* once HTTPS answers (Compose → Containers shows it). |
| Back up | Back up the data folder (it's already encrypted), or use **Settings → Export backup** in the app. |
| Restore the data folder | Compose → Containers → subtrack → **Stop**; replace the data folder's contents with the backup; **Up**. Nothing else is needed: at its next sync each phone notices the server is missing changes it had seen, and uploads what the backup lacks (the log says so once per phone). Keep the backup's `tls.p12`/`tls.pass`, or phones must pair again. |
| Pairing details again | `docker exec subtrack /opt/subtrack/bin/subtrack-server show-pairing` prints the pairing code and fingerprint. |
| New pairing code | `docker exec subtrack /opt/subtrack/bin/subtrack-server rotate-pairing-code`, then restart. |
| Forgot the passphrase | `docker exec subtrack /opt/subtrack/bin/subtrack-server reset-vault --yes`. This deletes the synced data (each phone keeps its own copy); then disconnect and set up sync again from one phone. |
| New certificate | Delete `tls.p12` and `tls.pass` from the data folder and restart; every device must disconnect and pair again. |

## Troubleshooting

- **"Can't reach the server"**: phone not on the home Wi-Fi, wrong IP or port, or the container isn't running. From a computer on the LAN, `curl -k https://<nas-ip>:8443/v1/info` should answer with JSON.
- **"The server's certificate changed"**: the data folder was wiped or the certificate was regenerated. Disconnect in the app and pair again, comparing the new fingerprint.
- **Container restarts with "permission denied"**: the data folder isn't writable by `PUID:PGID`; see step 2.
