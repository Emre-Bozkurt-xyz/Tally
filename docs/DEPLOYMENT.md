# Tally server: deployment

`https://tally.ems-place.com`, on the same path as Vault and Mosaic:

```txt
Phone (Access service token) / Browser (Access login)
  -> Cloudflare DNS + Cloudflare Access (app "tally")
  -> DigitalOcean VPS: Caddy
  -> FRP tunnel
  -> mini-PC 127.0.0.1:18410
     -> tally-server (Ktor, port 8080 in the container)
     -> tally-postgres (internal network only)
```

The server checks the Access JWT (`Cf-Access-Jwt-Assertion`) on every request except
`/healthz`, so a request that reaches the VPS without going through Cloudflare gets 401.

## Ports

| What | Where |
|---|---|
| tally-server | mini-PC `127.0.0.1:18410` -> container `8080` |
| tally-postgres | internal Docker network only, never published |
| FRP remote port | your choice on the VPS (examples below use `18410`) |

## One-time setup

Do these in order. Steps 1 to 3 are on the mini-PC, 4 and 5 on the VPS, 6 and 7 in Cloudflare.

### 1. Runner

GitHub -> Emre-Bozkurt-xyz/Tally -> Settings -> Actions -> Runners -> New self-hosted runner
(Linux) gives the download URL and a registration token (valid about an hour). On the
mini-PC, as your normal user (the runner refuses to configure under sudo, and it must own its
folder):

```bash
sudo mkdir -p /opt/actions-runner/tally && sudo chown "$USER:$USER" /opt/actions-runner/tally
cd /opt/actions-runner/tally
curl -o runner.tar.gz -L PASTE_DOWNLOAD_URL_HERE   # from the GitHub page
tar xzf runner.tar.gz            # no sudo, or the files end up owned by root
./config.sh --url https://github.com/Emre-Bozkurt-xyz/Tally --token PASTE_TOKEN_HERE \
  --labels minipc,tally,prod --name minipc-tally
sudo ./svc.sh install "$USER"    # run as a service, survives logout and reboot
sudo ./svc.sh start
groups "$USER" | grep -q docker || sudo usermod -aG docker "$USER"   # deploy runs docker compose
```

If you already extracted it with sudo: `sudo chown -R "$USER:$USER" /opt/actions-runner/tally`.

The workflow (`.github/workflows/deploy.yml`) runs tests on GitHub's runners, then deploys on
`[self-hosted, minipc, tally, prod]` for pushes to `main` that touch `core/`, `server/`, Gradle
files, the Dockerfile, compose, or `scripts/`.

### 2. Repo

```bash
sudo mkdir -p /opt/apps/tally && sudo chown "$USER" /opt/apps/tally
git clone https://github.com/Emre-Bozkurt-xyz/Tally.git /opt/apps/tally/repo
```

(Use the same credential/deploy-key setup as `/opt/apps/vault/repo` if the repo is private.)

### 3. Environment

```bash
cd /opt/apps/tally/repo
cp .env.example .env.production
chmod 600 .env.production
openssl rand -base64 32   # use as POSTGRES_PASSWORD
```

Fill in `POSTGRES_PASSWORD` now; `ACCESS_TEAM_DOMAIN` and `ACCESS_AUD` come from step 7.
`ANTHROPIC_API_KEY` is optional (category suggestions for unknown merchants); add it any
time and redeploy (`bash scripts/deploy.sh`).
The server refuses to start without them (unless `ACCESS_DISABLED=true`, which is for local
development only).

### 4. FRP (mini-PC `frpc` config)

```toml
[[proxies]]
name = "tally-server"
type = "tcp"
localIP = "127.0.0.1"
localPort = 18410
remotePort = 18410
```

Restart `frpc`.

### 5. Caddy (VPS `/etc/caddy/Caddyfile`)

```caddy
tally.ems-place.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:18410
}
```

```bash
sudo caddy fmt --overwrite /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

### 6. DNS

Cloudflare -> ems-place.com -> DNS: `A`, name `tally`, content `174.138.112.215`, **proxied**
(orange cloud; Access only works on proxied records).

### 7. Cloudflare Access

Zero Trust dashboard:

1. **Service token for the phone.** Access -> Service auth -> Service Tokens -> Create
   `tally-phone`. Copy the Client ID and Client Secret now (the secret is shown once). Pick a
   long duration and note when it expires.
2. **Application.** Access -> Applications -> Add -> Self-hosted. Name `tally`, domain
   `tally.ems-place.com` (whole domain). Policies:
   - `me`: Action **Allow**, Include **Emails**: your email.
   - `phone`: Action **Service Auth**, Include **Service Token**: `tally-phone`.
3. **AUD tag.** Applications -> tally -> Overview: copy "Application Audience (AUD) Tag" into
   `ACCESS_AUD` in `.env.production`.
4. **Team domain.** Settings -> Custom Pages (or the URL of your Access login page):
   `yourteam.cloudflareaccess.com` goes into `ACCESS_TEAM_DOMAIN`.

## First deploy

Push to `main` (or run the workflow by hand: Actions -> Deploy Tally server -> Run workflow).
Or on the mini-PC directly:

```bash
bash /opt/apps/tally/repo/scripts/deploy.sh
```

## Check it

```bash
# On the mini-PC: liveness, no login needed.
curl http://127.0.0.1:18410/healthz                     # ok

# Anywhere, as the phone will: through Access with the service token.
curl https://tally.ems-place.com/api/me \
  -H "CF-Access-Client-Id: <client id>" \
  -H "CF-Access-Client-Secret: <client secret>"           # {"name":"...","kind":"SERVICE_TOKEN"}

# Skipping Cloudflare (straight to the VPS) must fail.
curl -sk -o /dev/null -w '%{http_code}\n' \
  --resolve tally.ems-place.com:443:174.138.112.215 https://tally.ems-place.com/api/me   # 401
```

In a browser, `https://tally.ems-place.com` shows the Access login, then "signed in as <you>".
`/api/health` reports the database.

## Phone

Tally, menu, **Sync**: keep the server URL, paste the `tally-phone` Client ID and Client
Secret, then **Test connection** (should say "Connected as ... (service token)") and
**Sync now**. The first sync sends everything; after that it runs hourly, when the app opens,
and 30 seconds after changes.

## Backups

`scripts/backup-db.sh` writes `backups/tally_<timestamp>.sql` and keeps the last 30. Nightly on
the mini-PC (crontab -e):

```cron
15 3 * * * cd /opt/apps/tally/repo && bash scripts/backup-db.sh >> backups/backup.log 2>&1
```

Copy `backups/` off the machine the same way as Vault's. Restore into an empty database with
`scripts/restore-db.sh <file>`.

## Local development

```bash
# Postgres only, from the compose file:
POSTGRES_PASSWORD=dev docker compose -f docker-compose.production.yml up -d postgres
```

Postgres isn't published to the host in that compose file; for running the server with
`./gradlew :server:run`, add a local override that maps `127.0.0.1:5432:5432`, then:

```bash
POSTGRES_PASSWORD=dev ACCESS_DISABLED=true DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/tally \
  ./gradlew :server:run
```

## Failure modes

- **525 SSL handshake failed (Cloudflare)**: Caddy has no certificate for the name. A record
  that was proxied from the start blocks Let's Encrypt's check (it goes through Cloudflare,
  which can't reach an origin without a certificate yet). Set the record to DNS only,
  `sudo systemctl reload caddy`, wait for "certificate obtained successfully" in
  `journalctl -u caddy`, then set it back to Proxied. Renewals work proxied.
- **502 from Caddy**: FRP down, wrong `remotePort`, or the container isn't running
  (`docker compose --env-file .env.production -f docker-compose.production.yml ps`).
- **401 everywhere, even logged in**: `ACCESS_AUD` doesn't match the app's AUD tag, or
  `ACCESS_TEAM_DOMAIN` is wrong. Server logs show the rejected request.
- **Phone gets an HTML login page instead of JSON**: the service token headers are missing or
  the `phone` policy isn't Service Auth.
- **Server exits on start**: a required env var is missing; the log line names it.
