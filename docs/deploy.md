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
| `minio` (optional, `kinframe_minio: true`) | `pgsty/silo:RELEASE.2026-10-18T00-00-00Z` | S3-compatible object store for media, volume `miniodata`, only on the compose network. Console on `127.0.0.1:<kinframe_minio_console_port>` when that is set (default: not exposed). See [Media storage](#media-storage-s3). |
| `minio-bootstrap` | same image, `minio-bootstrap.sh` | One-shot, idempotent: private bucket, a policy limited to that bucket, and the `kinframe_s3_access_key` user with it. The server only gets that user, never the root credentials. |
| `logto-bootstrap` | `kinframe-server` image, `bootstrap-logto` command | One-shot job, idempotent (a re-run changes nothing). Uses Logto's Management API to create the API resource (`https://<domain>/v1`, 1 h tokens, scope `kinframe:operate`), the `kinframe-operator` role, the SPA app `kinframe web` (redirect `https://<domain>/callback`, post-logout `https://<domain>/`, CORS origin `https://<domain>`), one native app `kinframe app` for Android and iOS (redirects `app.kinframe:/callback` and `https://<domain>/app/callback`, post-logout `app.kinframe:/logout` and `https://<domain>/app/logout`), the SMTP email connector, the sign-in experience (email one-time codes, passkeys, name asked at sign-up, browser language, social sign-in buttons for the configured providers, kinframe branding — see [Social sign-in](#social-sign-in)) and custom access-token claims (`name`, `email`). It writes the client IDs to `/data/logto.json`, which the server reads at start and serves in `/.well-known/kinframe`. **On a fresh Logto it also creates the console admin account**: Logto grants admin to the first account registered in the console, so bootstrap must run before the console is reachable. See [First deploy](#first-deploy-logto-bootstrap). |

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

### Social sign-in

Next to email codes and passkeys, Logto's sign-in page can offer **Apple, Google, Microsoft, Facebook and GitHub**, in
that order. Each provider is off until its credentials are in the vault; `bootstrap-logto` then creates the Logto
social connector and adds the button. Nothing is configured in the Logto console.

| Provider | Ansible variables (vault) | Logto connector | Callback / redirect URL to register |
|---|---|---|---|
| Apple | `kinframe_social_apple_client_id` (Services ID) | `apple-universal` | `https://auth.kinframe.appmire.be/callback/apple` |
| Google | `kinframe_social_google_client_id` / `_secret` | `google-universal` | `https://auth.kinframe.appmire.be/callback/google` |
| Microsoft | `kinframe_social_microsoft_client_id` / `_secret` (`_tenant`, default `common`) | `azuread-universal` | `https://auth.kinframe.appmire.be/callback/microsoft` |
| Facebook (Meta) | `kinframe_social_facebook_client_id` / `_secret` | `facebook-universal` | `https://auth.kinframe.appmire.be/callback/facebook` |
| GitHub | `kinframe_social_github_client_id` / `_secret` | `github-universal` | `https://auth.kinframe.appmire.be/callback/github` |

For another deployment replace `auth.kinframe.appmire.be` with its `logto_domain`. The pattern is Logto's:
`<Logto ENDPOINT>/callback/<connector instance ID>`; the bootstrap gives each connector a fixed ID (`apple`, `google`,
`microsoft`, `facebook`, `github`) so the URL is known before the first run (it is also logged when the connector is
created). A connector added by hand in the console has a random ID and therefore a different callback URL; the
bootstrap adopts such a connector and logs its URL.

The variables reach the bootstrap job only (`bootstrap.env`, mode 0600) and the play fails if a provider has an ID
without a secret. Re-running the play updates a connector whose values changed. Emptying a provider's variables
leaves its connector and button in place; set `kinframe_social_remove_unconfigured: true` to have the next run delete
the connectors of unset providers. Only identity scopes are requested: Google `openid profile email`, Apple
`name email`, Microsoft Graph `User.Read` (Logto's connector fixes this one), Facebook `email,public_profile`, GitHub
`read:user user:email`.

**Accounts and email linking.** A first social sign-in whose provider reports a *verified* email that already has a
kinframe (Logto) account is linked to that account and signs in, so someone who signed up with an email code and later
taps "Continue with Google" with the same address lands in the same account. This is Logto's
`socialSignIn.automaticAccountLinking` (OSS 1.43 has it; `kinframe_social_link_by_email: false` makes Logto ask
"link to the existing account?" instead). What counts as verified is decided by each connector: Google only passes
`email_verified` addresses, Apple the address in its signed ID token, GitHub the public or primary verified address,
Facebook the address Meta has confirmed. **Microsoft's email is not used**: Graph's `mail` attribute is not verified
(any Entra tenant admin can set it, the "nOAuth" problem), so the bootstrap sets the connector's `disableEmailSync`.
Microsoft users, like anyone whose provider gives no email (GitHub without a verified address, Apple after the first
authorization), are asked for an email and confirm it with a code before the account is created. If that address
already has an account, Logto 1.43 does not merge it (not tested end-to-end, it needs a real Microsoft account); such
users keep signing in with the email code. Apple "Hide My Email" addresses (`…@privaterelay.appleid.com`) never match
an existing account, so they create a separate one.

The name asked at sign-up comes from the provider when it has one (Logto copies it into the new account, so the
"Tell us about yourself" step is skipped); otherwise that step asks for it. The access-token customizer reads the
account's `name` and primary email, so social accounts carry both claims like email-code accounts.

**App Store guideline 4.8 (Login Services).** An iOS app that offers third-party or social login must also offer an
equivalent option that limits data to name and email, lets people keep their email private and does no ad tracking;
Sign in with Apple is the accepted way to meet it. Because the family app signs in through this Logto page (in an
`ASWebAuthenticationSession`), configure Apple whenever any other social provider is on for an iOS release. Apple is
listed first on purpose.

#### Registering the providers

**Apple** (Apple Developer account of the **Appmire** team, by an Account Holder or Admin):
1. Certificates, Identifiers & Profiles → Identifiers → the kinframe iOS App ID: enable the **Sign in with Apple**
   capability (as primary App ID).
2. Identifiers → **+** → **Services IDs** → description "kinframe", identifier e.g. `app.kinframe.signin`. This
   identifier is `kinframe_social_apple_client_id`.
3. Open the Services ID, tick **Sign in with Apple** → Configure: primary App ID = the kinframe app; **Domains and
   Subdomains** `auth.kinframe.appmire.be`; **Return URLs** `https://auth.kinframe.appmire.be/callback/apple`. Save,
   Continue, Save.
4. No key is needed. Logto's Apple connector uses Apple's `code id_token` form-post flow and verifies the ID token with
   Apple's public keys; it never calls Apple's token endpoint, so it needs no client-secret JWT, no Sign in with Apple
   key (`.p8`), Key ID or Team ID. (Should a future connector need them: the Team ID is the Appmire team's Team ID
   under Membership details in the Apple Developer account. Don't put a literal ID in this repo.) If
   `KINFRAME_SOCIAL_APPLE_TEAM_ID`, `_KEY_ID`, `_PRIVATE_KEY` or `_CLIENT_SECRET` are set, the bootstrap ignores them
   and logs a warning.
5. Email relay: people who choose "Hide My Email" get a `@privaterelay.appleid.com` address. Apple only forwards mail
   to it from registered senders, so register the SMTP sender (the domain and the `kinframe_smtp_from` address, with
   SPF passing) under Certificates, Identifiers & Profiles → Services → **Sign in with Apple for Email Communication**;
   otherwise their sign-in codes and kinframe emails are dropped.
6. Apple sends the name and email only on the very first authorization. If a test account was authorized before the
   connector worked, remove kinframe under Apple ID → Sign-In & Security → Sign in with Apple and try again.

**Google** (Google Cloud console, Google Auth Platform):
1. Create (or pick) a project. **Branding**: app name "kinframe", support email, logo, home page
   `https://kinframe.appmire.be`, privacy policy link, authorized domain `appmire.be`.
2. **Audience**: External. **Data access**: only `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`
   (non-sensitive scopes).
3. **Clients** → Create client → Web application: authorized JavaScript origin `https://auth.kinframe.appmire.be`,
   authorized redirect URI `https://auth.kinframe.appmire.be/callback/google`. The client ID and secret go to the vault.
4. Gotcha: while the app's publishing status is **Testing**, only the listed **test users** (up to 100) can sign in.
   Add the family's addresses as test users meanwhile, then **Publish app**.
   With only non-sensitive scopes there is no security assessment, but showing the logo and app name on the consent
   screen needs brand verification (a few business days; the domain must be verified in Search Console).

**Microsoft** (Microsoft Entra admin center → App registrations → New registration):
1. Name "kinframe"; **Supported account types: "Accounts in any organizational directory and personal Microsoft
   accounts"** (that is what tenant `common` needs; a single-tenant app with `common` fails at sign-in).
2. Redirect URI: platform **Web**, `https://auth.kinframe.appmire.be/callback/microsoft`.
3. The **Application (client) ID** is `kinframe_social_microsoft_client_id`. Certificates & secrets → New client secret:
   its **Value** (not the Secret ID) is `kinframe_social_microsoft_client_secret`. Secrets expire (at most 24 months):
   put the expiry date in the calendar and rotate by updating the vault and re-running the play.
4. API permissions: the default delegated Microsoft Graph `User.Read` is all that is needed; no admin consent.
5. Gotchas: without **publisher verification** (a Microsoft Partner Network ID for Appmire) the consent screen shows
   "unverified", and some work tenants block user consent to unverified multi-tenant apps; personal accounts are not
   affected. `kinframe_social_microsoft_tenant: consumers` restricts to personal accounts, `organizations` to work and
   school accounts.

**Facebook / Meta** (developers.facebook.com, a Meta developer account in Appmire's business portfolio):
1. Create app → use case **"Authenticate and request data from users with Facebook Login"**.
2. Facebook Login → Settings: Client OAuth login and Web OAuth login on, Enforce HTTPS on, **Valid OAuth Redirect
   URIs** `https://auth.kinframe.appmire.be/callback/facebook`.
3. App settings → Basic: App ID (`kinframe_social_facebook_client_id`), App secret (`_secret`), app domain
   `appmire.be`, privacy policy URL, **user data deletion** instructions URL, icon and category.
4. Use case permissions: `email` and `public_profile` only.
5. Gotchas: in **Development** mode only people with a role on the app (admins, developers, testers) can sign in.
   Switching to **Live** needs the privacy policy and data deletion URLs; `email` and `public_profile` need Advanced
   Access, which Meta normally grants without App Review but can make conditional on **Business Verification** of
   Appmire (check App Review → Permissions and features). Meta also asks for a yearly data use checkup.
6. Instagram: there is no consumer "Sign in with Instagram" any more. The Instagram Basic Display API was shut down
   on 4 December 2024; the remaining Instagram APIs are for business and creator accounts and are not a login for
   families. Facebook Login is the Meta option.

**GitHub** (Settings → Developer settings → **OAuth Apps** → New OAuth App; under the Appmire organization if it
should not belong to a person):
1. Application name "kinframe", homepage `https://kinframe.appmire.be`, **Authorization callback URL**
   `https://auth.kinframe.appmire.be/callback/github`.
2. Generate a client secret. Client ID and secret go to the vault. Works immediately, no review. (Use an OAuth App,
   not a GitHub App.)

Then re-run the play. The bootstrap log lists every created connector with its callback URL.

### Sign-in page branding

`bootstrap-logto` also gives Logto's sign-in pages the kinframe look (`kinframe_logto_branding: true`, the default):
the wordmark logo for light and dark (`https://kinframe.appmire.be/logo-light.svg` / `logo-dark.svg`), the favicon,
the terracotta primary colour (`#a7471e`, dark mode `#f09a6d`, from `web/src/app.css`), dark mode following the
system setting, and custom CSS kept in the repo as `server/internal/logtoboot/signin.css` (paper background, card,
pill buttons, inputs, Fraunces headings loaded from `https://kinframe.appmire.be/fonts/`, focus rings). Edit the CSS
there and re-run the play; changes made in the console's Sign-in experience → Branding tab are overwritten on the next
run, or set `kinframe_logto_branding: false` to manage the look in the console.

There are no terms or privacy links on the sign-up page until `kinframe_terms_url` / `kinframe_privacy_url` are set
(Google, Meta and Apple want a privacy policy URL in their registrations anyway). Logto OSS refuses to hide its
"Powered by Logto" footer (a Logto Cloud feature), so the CSS only tones it down. Browser tab titles are Logto's step
titles ("Sign in to your account"); the favicon is kinframe's.

### Media storage (S3)

Media is stored in the `media` volume by default (`kinframe_blob_backend: fs`). The role can also run an S3 object
store next to the server and switch to it, which later makes moving to managed S3-compatible storage (Hetzner Object
Storage, B2, R2, AWS) a matter of pointing `kinframe_s3_endpoint` at it and running the same copy again.

**Image choice.** MinIO Inc. stopped publishing community builds in October 2025 and archived the repository in 2026;
`minio/minio` is no longer on Docker Hub. The role uses **Silo** (`pgsty/silo`, formerly `pgsty/minio`), the
community fork maintained by Pigsty: same S3 API, `MINIO_*` settings and on-disk format, regular security releases,
multi-arch images that include `mc` and `curl` (healthcheck and bootstrap need no second image). Pinned to a release
tag; bump `kinframe_minio_version` deliberately.

**Switching hetz1 to S3** (from the `kinframe-deploy` repo; nothing changes for users, media keeps being served at
the same URLs):

1. Add to the vault (`group_vars/all/vault.yml`), all generated fresh:
   `kinframe_minio_root_user` (e.g. `kinframe_root`), `kinframe_minio_root_password` (`openssl rand -hex 32`),
   `kinframe_s3_access_key` (e.g. `kinframe-media`), `kinframe_s3_secret_key` (`openssl rand -hex 32`).
2. In `group_vars/all.yml` set `kinframe_minio: true` (backend still `fs`) and run
   `ansible-playbook site.yml --tags app`. This starts `kinframe-minio`, runs `minio-bootstrap` and restarts the
   server with the S3 settings in its env (still serving from the volume).
3. Copy the media, first as a dry run:
   ```
   ssh appmire-hetz1 'cd /opt/kinframe && docker compose exec -T server kinframe-server migrate-blobs --from fs --to s3 --dry-run </dev/null'
   ssh appmire-hetz1 'cd /opt/kinframe && docker compose exec -T server kinframe-server migrate-blobs --from fs --to s3 </dev/null'
   ```
   Inside the server container both configs are present: `/data/media` (fs) and `KINFRAME_S3_*` (s3). The command is
   idempotent and resumable; rerun it after an interruption or a failure.
4. Set `kinframe_blob_backend: s3` and run the play again. The server restarts on the bucket.
5. Run step 3 (without `--dry-run`) once more to copy anything written between the first copy and the restart. It
   should report only a handful of copies; a third run must report `copied=0`.
6. Check a frame and the web app, then add the `miniodata` volume to the host backups. Keep `/data/media` for a while
   as a fallback; remove it later with `docker compose exec server rm -rf /data/media`.

Rollback: set `kinframe_blob_backend: fs`, run the play, then
`migrate-blobs --from s3 --to fs` for media created on S3 in the meantime.

Managed storage later: create the bucket and an access key limited to it at the provider. `migrate-blobs` knows one
S3 configuration at a time, so go through the volume: `migrate-blobs --from s3 --to fs` (Silo → `/data/media`), set
`kinframe_minio: false`, `kinframe_s3_endpoint`/`_region`/`_path_style` and the new key in the vault, run the play,
then `migrate-blobs --from fs --to s3` (twice, as above).

`kinframe_media_redirect: presigned` makes signed media URLs and avatars redirect to presigned bucket URLs, which
only helps when clients can reach the bucket (`kinframe_s3_public_endpoint`); with the bundled store it stays `off`.

### Pre-order shop

The hosted server can take **reservations for frames** (no payment) while stock is being arranged: a "Get a …"
section on the landing page, a reservation form at `/shop`, emails to the customer and the operator, and a list with
CSV export under Settings → Shop reservations for operators. API details: `docs/api/README.md` §5.11.

The shop only exists when the operator configures it; the role default (`kinframe_shop: {}`) means no shop, and so
does a server without `KINFRAME_SHOP_FILE`. The real values (seller, prices, inbox) belong in the **private**
deployment repo, never here. Example with placeholders, in `group_vars/all.yml`:

```yaml
kinframe_shop:
  brand: kinframe                    # consumer brand in shop texts and emails; default: kinframe_server_name
  reference_prefix: KF               # reservation numbers like KF-7Q3M (1-4 capitals)
  currency: EUR
  vat_percent: 21                    # prices below include it
  seller:
    name: Example BV
    email: shop@example.org          # shown on the site; customers' replies go here
    legal: "Example BV · Example Street 1, 1000 Brussels · VAT BE0000.000.000"
    privacy_url: https://example.org/privacy   # optional
  notify_email: orders@example.org   # gets a mail per reservation and cancellation; not published
  estimated_delivery: { en: "spring 2027", nl: "voorjaar 2027", fr: "printemps 2027" }
  retention_days: 365                # delete reservations a year after the last change (cancelled: after 30 days)
  products:
    - id: frame-10
      name: 'kinframe 10.1"'
      size: '10.1"'
      price_cents: 7900
      max_qty: 5
      image: frame-10                # illustration: frame-10 (16:10) or frame-15 (16:9, drawn larger)
      specs:                         # short bullets per language; en is the fallback
        en: ["1280×800 IPS touchscreen", "Wi-Fi", "kinframe preinstalled, no subscription"]
        nl: ["1280×800 IPS-touchscreen", "Wifi", "kinframe vooraf geïnstalleerd, geen abonnement"]
        fr: ["Écran tactile IPS 1280×800", "Wi-Fi", "kinframe préinstallé, sans abonnement"]
  shipping:
    countries: [BE, NL]              # ISO codes; the form only offers these
    flat_cents: 695
    free_from_cents: 10000           # item subtotal for free shipping; null = never free
  domains: [shop.example.org]        # optional storefront host names, see "Storefront domain" below
  default_locale: en                 # storefront language when the browser asks for none of en/nl/fr/de
```

The role writes this (plus `enabled: true`) to `/opt/kinframe/shop.json`, mounts it read-only at
`/etc/kinframe/shop.json`, sets `KINFRAME_SHOP_FILE` and restarts the server when it changes. Removing the variable
removes the file and the shop. The server refuses to start on an invalid file (unknown keys, bad addresses or
country codes, prices ≤ 0…), so a typo shows up in the deploy instead of on the site.

Mail goes out over the same `kinframe_smtp_*` settings as Logto's sign-in codes. Customers get their confirmation
in English, Dutch, French or German (others: English) with a link to cancel; there are no tracking pixels.

**Privacy.** A reservation stores name, email, country, optional postal code and note, language, the items and
the time of consent (the form has an explicit, unticked consent checkbox). No IP addresses. Reservations are
deleted automatically `retention_days` after their last change, cancelled ones 30 days after cancelling; the CSV
export is for the operator's own follow-up. The site footer names the seller, the legal line and this policy.

#### Storefront domain

The shop can also live on its own domain under the consumer brand, while the server, the API, sign-in and the
family app stay on `KINFRAME_PUBLIC_URL`. List the host names in `kinframe_shop.domains` (no scheme, port or path;
not the app's own host). On those hosts the same server and the same web build act as a storefront:

- `/` is a landing page for the brand (`brand`): hero, how it works, the products, a short FAQ, and a footer link
  "Already have a frame? Open the app" to the app's origin. `/shop` and `/shop/cancel` work as on the app.
- Every other path (frames, send, settings, `/callback`, join links…) redirects to the same path on the app's
  origin (discovery `web`). Nobody signs in on a storefront, so Logto needs no extra redirect URIs or CORS origins.
- The web app calls the API on the storefront's own origin (`https://<storefront>/v1/...`), so no CORS is needed;
  the server knows from the `Host` header (or `X-Forwarded-Host` with `KINFRAME_TRUST_PROXY=1`) where a
  reservation was made, and the customer's cancel link in the confirmation email points back to that storefront.
  Operator links and everything else keep using `KINFRAME_PUBLIC_URL`.
- `index.html` on a storefront carries the brand's title, description, `<html lang>` and Open Graph tags (so a
  shared link previews as the brand), in the visitor's language (`Accept-Language`, one of en/nl/fr/de, else
  `default_locale`). The web app picks its default language the same way; a language the visitor chose wins.

What the extra nginx vhost needs (the role doesn't manage nginx; the operator's own nginx setup does):

- TLS for the storefront host, and `proxy_pass` of **all** paths to the same upstream as the app vhost
  (`127.0.0.1:<kinframe_port>`), including `/v1/` and `/.well-known/kinframe`, with `proxy_set_header Host $host`
  (or `X-Forwarded-Host $host` behind `KINFRAME_TRUST_PROXY=1`), `X-Forwarded-For` and `X-Forwarded-Proto` like the
  app vhost.
- No websocket or upload locations are needed (nothing on a storefront uses them), but reusing the app vhost's
  configuration as-is is fine.
- Nothing else changes: no DNS or Logto changes for the app, no extra server instance. The server refuses to start
  when a storefront domain is the app's own host.

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
