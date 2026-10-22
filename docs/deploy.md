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
| `logto-bootstrap` | `kinframe-server` image, `bootstrap-logto` command | One-shot job, idempotent. Uses Logto's Management API (machine-to-machine app) to create the API resource, the SPA app (web), the native app (mobile, redirect `app.kinframe:/callback` plus `https://kinframe.appmire.be/app/callback`), the `kinframe-operator` role and the sign-in experience (email one-time code and passkeys). It writes the resulting client IDs into the server's config volume, which `/.well-known/kinframe` serves. **It also creates the console admin account at once.** Logto grants admin to the first visitor of a fresh console (see appmire-logto's README), so the console must never be reachable before bootstrap has run. |

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
- **SMTP username and password.** Use the same mail settings as the existing **auth.appmire.be** Logto
  (`~/Developer/appmire/appmire-logto`). Its SMTP email connector is configured in that instance's console and stored
  in its database, not in the repo. `logto-bootstrap` creates the matching connector via the Management API.

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
