# Deployment

## Default hosted server: kinframe.appmire.be

Runs on **appmire-hetz1** (Debian 13, the shared appmire box), deployed with Ansible from the private
`kinframe-deploy` repo (see below). It follows the same conventions as the other appmire sites on hetz1: a compose stack in
`/opt/<project>`, host nginx terminating TLS with certbot, and a per-site vhost proxying to `127.0.0.1:<port>`.

### Hostnames

| Hostname | Purpose | Upstream |
|---|---|---|
| `kinframe.appmire.be` | Web app (SPA), API `/v1`, websockets, `/.well-known/kinframe`, join links `/f/<code>`, `/i/<token>` | `127.0.0.1:8110` (kinframe server) |
| `auth.kinframe.appmire.be` | Logto OIDC endpoint and sign-in UI | `127.0.0.1:8111` (Logto core) |
| `admin.auth.kinframe.appmire.be` | Logto admin console | `127.0.0.1:8112`, **IP-restricted** using the same admin allow-list as appmire-logto (kept in the private `kinframe-deploy` repo). Fallback: `ssh -L 8112:127.0.0.1:8112 appmire-hetz1`. Basic auth can't be used because it collides with the console's bearer tokens. |

DNS: the `*.appmire.be` wildcard already resolves all three names to hetz1 (A `136.243.103.58`, AAAA
`2a01:4f8:171:c1f::2`; checked 2026-10-22). No records to add, and certbot's HTTP-01 challenge works as for the other
vhosts.

Ports 8101–8108 (sites) and 3001–3002 (appmire-logto) are already used on hetz1. **8110–8112** are reserved for kinframe; the playbook
checks they're free before the first deploy.

### Stack (`/opt/kinframe/docker-compose.yml`)

| Service | Image | Notes |
|---|---|---|
| `server` | `ghcr.io/jeroentrappers/kinframe-server:<tag>` | Go API, media worker (ffmpeg + libvips), serves the built web SPA. Binds `127.0.0.1:8110`. Media volume `/opt/kinframe/media`. |
| `logto` | `svhd/logto:1.43.0` (same pin as appmire-logto) | Binds `127.0.0.1:8111` (core) and `127.0.0.1:8112` (admin). `ENDPOINT=https://auth.kinframe.appmire.be`, `ADMIN_ENDPOINT=http://localhost:8112`, `TRUST_PROXY_HEADER=1`. |
| `postgres` | `postgres:17-alpine` | Databases `kinframe` and `logto`, each with its own role. Not exposed. |
| `logto-bootstrap` | `kinframe-server` image, `bootstrap-logto` command | One-shot job, idempotent (a re-run changes nothing). Uses Logto's Management API to create the API resource (`https://<domain>/v1`, 1 h tokens, scope `kinframe:operate`), the `kinframe-operator` role, the SPA app `kinframe web` (redirect `https://<domain>/callback`, post-logout `https://<domain>/`, CORS origin `https://<domain>`), one native app `kinframe app` for Android and iOS (redirects `app.kinframe:/callback` and `https://<domain>/app/callback`, post-logout `app.kinframe:/logout` and `https://<domain>/app/logout`), the SMTP email connector, the sign-in experience (email one-time codes, passkeys, name asked at sign-up, browser language) and custom access-token claims (`name`, `email`). It writes the client IDs to `/data/logto.json`, which the server reads at start and serves in `/.well-known/kinframe`. **On a fresh Logto it also creates the console admin account**: Logto grants admin to the first account registered in the console, so bootstrap must run before the console is reachable. See [First deploy](#first-deploy-logto-bootstrap). |

### First deploy: Logto bootstrap

`kinframe-server bootstrap-logto` talks to Logto only through its public HTTP APIs: the admin console's own sign-in
flow (OIDC + Experience API) and the Management API. It never writes to Logto's database.

Compose wiring (the job shares the server's `/data` volume; the server starts after it):

```yaml
  logto-bootstrap:
    image: ghcr.io/jeroentrappers/kinframe-server:<tag>
    command: ["bootstrap-logto"]
    restart: "no"
    depends_on:
      logto: { condition: service_started }   # bootstrap waits for /api/status itself
    env_file: ./kinframe.env                    # KINFRAME_PUBLIC_URL, KINFRAME_SMTP_*, …
    environment:
      KINFRAME_LOGTO_ENDPOINT: https://auth.kinframe.appmire.be   # = Logto ENDPOINT
      KINFRAME_LOGTO_URL: http://logto:3001
      KINFRAME_LOGTO_ADMIN_ENDPOINT: <Logto ADMIN_ENDPOINT, exactly>
      KINFRAME_LOGTO_ADMIN_URL: http://logto:3002
    volumes:
      - media:/data
  server:
    depends_on:
      logto-bootstrap: { condition: service_completed_successfully }
```

The `*_ENDPOINT` values must equal Logto's `ENDPOINT`/`ADMIN_ENDPOINT`: bootstrap connects to the `*_URL` addresses but
sends the public host in `Host`/`X-Forwarded-*` (Logto has `TRUST_PROXY_HEADER=1`), because Logto picks the tenant and
issuer from it.

The Postgres role Logto connects as needs `CREATEROLE`: `db seed` creates per-tenant roles and fails with
"permission denied to create role" otherwise (`CREATE ROLE logto LOGIN CREATEROLE PASSWORD '…'`).

Procedure:

1. Put a console admin username and a strong password (Logto requires at least 8 characters) in the vault as
   `KINFRAME_LOGTO_ADMIN_USERNAME` / `KINFRAME_LOGTO_ADMIN_PASSWORD`, plus `KINFRAME_SMTP_HOST/PORT/USER/PASSWORD/FROM`
   and optionally `KINFRAME_OPERATOR_EMAILS`.
2. Bring the stack up **without the admin vhost** (or with it IP-restricted, as on hetz1). The first bootstrap run:
   creates the console admin account (which closes console registration), creates a machine-to-machine app
   `kinframe bootstrap` with the "Logto Management API access" role, configures everything listed above and writes
   `/data/logto.json`. It prints the M2M credentials **once** on stdout:
   `docker compose logs logto-bootstrap | grep KINFRAME_LOGTO_M2M`.
3. Store `KINFRAME_LOGTO_M2M_ID` and `KINFRAME_LOGTO_M2M_SECRET` in the vault and set them for the job. Later runs use
   them instead of the admin password, which can then be removed from the job's environment (keep it in the vault: it
   is the console login).
4. Operators: list their emails in `KINFRAME_OPERATOR_EMAILS`. Users must have signed up before the role can be
   assigned; re-run the job after they did (`docker compose run --rm logto-bootstrap`). Their clients must request the
   `kinframe:operate` scope to get it in the token.

If the M2M secret is lost: delete the `kinframe bootstrap` app in the console and run the job once with the admin
credentials; it creates a new app and prints the new secret. If Logto's ENDPOINT or kinframe's domain changes, re-run
the job: it updates redirect URIs and creates the new API resource (the old one is left in place).

No manual console steps are needed. The console is only for looking around and for changes beyond this setup.

### nginx vhost specifics

These differ from the WordPress vhosts:
- `client_max_body_size 0` and `proxy_request_buffering off` on `/v1/uploads`, because tus chunks stream through.
- Websocket upgrade headers on `/v1/ws` and `/v1/device/ws`, with `proxy_read_timeout 120s` (the server pings every
  25 s).
- `proxy_buffering off` for media `Range` responses.

### Ansible: public role, private deployment repo

The kinframe repo is public, so **nothing hosting-specific or secret lives in it**, not even in encrypted form.
A committed Ansible Vault can be downloaded by anyone and its passphrase attacked offline indefinitely.

| Where | What |
|---|---|
| **kinframe** (public, GitHub) | Generic, reusable deployment pieces: `deploy/compose/` (compose files and `.env.example` with placeholders) and `deploy/ansible/roles/kinframe` (a role taking every host-specific value and secret as variables). |
| **kinframe-deploy** (private, gitlab.com/jeroentrappers, like the other appmire repos) | `inventory.ini` (appmire-hetz1), `group_vars/all.yml` (domains, ports 8110–8112, image tag, admin allow-list) and `group_vars/all/vault.yml` (Ansible Vault). Uses the public role (pulled as a git submodule or `ansible-galaxy` requirement pinned to a tag), plus the nginx and certbot pattern from `easyappointments/deploy/ansible/roles/nginx`. |

**Secrets** go only in the private repo's vault, with the vault passphrase in its git-ignored `.vault-pass`:
- Postgres passwords (`kinframe` and `logto` roles)
- Logto machine-to-machine app secret
- device-token signing key
- **SMTP username and password** for Logto's sign-in emails. The hosted server sends through the operator's own
  mail server, using an SMTP-only app password rather than a mailbox password. `logto-bootstrap` creates the matching
  connector via the Management API.

```
cd kinframe-deploy
ansible-playbook site.yml --tags app     # deploy or upgrade the stack
ansible-playbook site.yml --tags nginx   # vhosts + TLS
```

**Guardrails:**
- The public repo runs **gitleaks** in CI and as a pre-commit hook.
- `.gitignore` excludes `.env*` (except `.env.example`), `.vault-pass`, keystores and build output.

Operational notes from the appmire infra:
- Deploy from a machine whose IP is in appmire-infra `trusted_ips`, or go through the home Tailscale exit node. Bursts
  of SSH from untrusted IPs trip fail2ban on hetz1.
- `docker compose exec` inside an `ssh … bash -s <<EOF` script needs `</dev/null`.

## Self-hosting (everyone else)

`deploy/compose/` holds a standalone variant of the same stack with **Caddy** for automatic TLS instead of host nginx.
One `.env` with two hostnames and an email address, then `docker compose up -d`. The same `logto-bootstrap` job
configures Logto, so self-hosters never open the Logto console unless they want to.
