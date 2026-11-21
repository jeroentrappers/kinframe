# Releasing kinframe

One tag releases everything. Push `vX.Y.Z` on `main`, approve once in GitHub, and
[`.github/workflows/release.yml`](../.github/workflows/release.yml) does the rest:

- deploys the server image to kinframe.appmire.be;
- ships the family apps to Google Play, the App Store and the Mac App Store;
- ships the frame app to Google Play.

[`release-frame.yml`](../.github/workflows/release-frame.yml) publishes the frame APK as a GitHub release for the same
tag, as before.

```sh
echo 1.2.4 > VERSION_FAMILY          # only when the family apps should ship too, with their release notes updated
git commit -am "Release 0.7.4"       # (example)
git tag v0.7.4 && git push origin main v0.7.4
```

Then open the run under **Actions → Release**, check the plan in its summary, and approve **production**.

## Versions

| What | Version | Source |
|---|---|---|
| Server image (`kinframe_version`) | `0.7.4` | The tag without `v`. `ci.yml` publishes `ghcr.io/jeroentrappers/kinframe-server:0.7.4` for it, and `sha-<7>` for every commit. |
| Frame app (GitHub and Play) | `0.7.4`, code `70499` | The tag (`major·1 000 000 + minor·10 000 + patch·100 + 99`), see [play-frame.md](play-frame.md) |
| Family apps (Android, iOS, Mac) | `VERSION_FAMILY`, e.g. `1.2.4` | The file [`VERSION_FAMILY`](../VERSION_FAMILY) at the tagged commit |
| Family build number / version code | `git rev-list --count <tag>` | The same for Android, iOS and Mac, as in the manual releases |

The family apps (1.2.x) and the server/frame (0.7.x) have their own version lines, so the tag can't name both. On a
tag push, the family apps ship only when `VERSION_FAMILY` differs from its value at the previous release tag. A
server-only release therefore leaves the stores alone, and you ship the family apps by bumping one committed file. All
three platforms get the same family version. Store versions only need to go up, so 1.2.4 can follow Android 1.2.3 and
iOS 1.2.1.

Tags with a suffix (`v0.8.0-beta.1`) don't run this workflow. They stay with `release-frame.yml` (beta channel).

## What a run does

```
plan ─ wait-image ─┬─ linux  [production]  server deploy → Android internal → production → frame app on Play   (ryzen)
                   └─ apple  [production]  wait for the server deploy → iOS → App Review → Mac → App Review   (mini4)
```

1. **plan** (ryzen) checks that the tag is `vX.Y.Z`, exists and is on `main`. It then works out the versions and which
   parts run, and checks the committed release notes (`scripts/release/check-release-notes.py`): no emoji (App Store
   Connect rejects them in "What's New"), at most 500 characters for Play and 4000 for the App Store. It warns when
   the family release notes didn't change since the previous tag.
2. **wait-image** polls `ci.yml`'s run for the tag until its *Publish server image* job has succeeded.
3. **Approval.** The `linux` and `apple` jobs both use the environment `production`. They become ready together, so one
   approval starts both.
4. **linux** (ryzen) runs the server deploy, then the Android family app and then the frame app:
   - **Server deploy** runs `scripts/release/deploy-server.sh <image>` in a fresh kinframe-deploy clone:
     1. `pg_dump` of database `kinframe` on appmire-hetz1 to `~/kinframe-backups/kinframe-<UTC>-pre-<version>.sql.gz`;
        the job fails unless the dump is over 100 KB.
     2. `kinframe_version` in `group_vars/all/main.yml`.
     3. `scripts/fetch-role.sh main`.
     4. `ansible-playbook site.yml --tags app`.
     5. Health check: `GET https://kinframe.appmire.be/healthz` must answer `ok` (it pings the database), and
        `kinframe-server` must run the new image.
     6. A gpg-signed commit "kinframe <version>" as Jeroen Trappers, then push.
   - **Android family app:** `fastlane android internal`, then `promote track:production rollout:1.0`.
   - **Frame app on Play:** `frame_internal tag:<tag>`, then `frame_promote track:production rollout:1.0`. Until Google
     has reviewed the frame app, its releases stay drafts and nothing is promoted. This is set by the repository
     *variable* `FRAME_PLAY_RELEASE_STATUS` (`draft`); set it to `completed` once the app is live.
5. **apple** (mini4) waits for the step *Deploy the server* to succeed, then for each of iOS and Mac:
   - **Upload:** `fastlane release upload:true` (iOS) or `fastlane mac_release upload:true` (Mac).
   - **Submit:** `fastlane submit` or `mac_submit`, with `app_version`, `build_number` and `metadata:true`.
     "What's New" comes from the committed `ios/fastlane/metadata/*/release_notes.txt`, and the release is automatic
     after approval. A fresh build takes App Store Connect a few minutes to process, so
     `scripts/release/app-store-submit.sh` retries while it reports that the build doesn't exist.

If the server deploy fails, no app is released. A failed Android step doesn't stop the frame app, and a failed iOS step
doesn't stop the Mac one. The `concurrency: release` group never cancels a running release. GitHub keeps one more run
pending behind it and replaces any older pending run.

### Partial releases and reruns

**Actions → Release → Run workflow** takes an existing tag and switches for each part: `server`, `android`, `ios`,
`mac` and `frame_play`. A dispatched run releases exactly what you tick, whatever `VERSION_FAMILY` did. Run it from
`main`.

A store refuses the same build twice. When the upload worked but the second step failed, dispatch again with
**store_step_only**: it only runs the Play promotions and the App Review submissions for the build already uploaded.
Typical causes:

- **App Review:** only one version per platform can be in review at a time. Wait until the earlier one is approved or
  rejected, or remove it from review in App Store Connect.
- **Play:** a promotion clashed with a release that was still rolling out.

## Secrets

Every secret is a secret of the GitHub environment **`production`**. Only the `linux` and `apple` jobs can read
them, and only after a reviewer has approved the run. Runs from other branches, forks or pull requests never can. The
runners hold no secrets. Each job writes what it needs into `$RUNNER_TEMP/kinframe-release`
(`scripts/release/runner-secrets.sh`) and deletes it in an `if: always()` step. On mini4 that includes a per-run
signing keychain, which is removed from the search list and deleted. Values derived from secrets, such as key ids and
the keychain password, are masked with `::add-mask::`. Nothing runs under `set -x`.

| Secret | Holds | Used by | Manual-setup source (`--from-files`) |
|---|---|---|---|
| `KINFRAME_UPLOAD_KEYSTORE_B64` | Family app upload keystore, base64 | linux | file at `KINFRAME_UPLOAD_KEYSTORE` in `~/.config/play/kinframe-signing.env` |
| `KINFRAME_UPLOAD_KEYSTORE_PASSWORD` | Its store password | linux | same env file |
| `KINFRAME_UPLOAD_KEY_ALIAS` | Its key alias | linux | same env file |
| `KINFRAME_UPLOAD_KEY_PASSWORD` | Its key password | linux | same env file |
| `KINFRAME_FRAME_UPLOAD_KEYSTORE_B64` | Frame app upload keystore (`kinframe-frame-upload.jks`), base64 | linux | file at `KINFRAME_FRAME_UPLOAD_KEYSTORE` |
| `KINFRAME_FRAME_UPLOAD_KEYSTORE_PASSWORD` | Its store password | linux | same env file |
| `KINFRAME_FRAME_UPLOAD_KEY_ALIAS` | Its key alias | linux | same env file |
| `KINFRAME_FRAME_UPLOAD_KEY_PASSWORD` | Its key password | linux | same env file |
| `SUPPLY_JSON_KEY_DATA` | Play service account JSON (content of the `SUPPLY_JSON_KEY` file) | linux | file at `SUPPLY_JSON_KEY` |
| `KINFRAME_GOOGLE_SERVICES_JSON` | Firebase `google-services.json` of the family app (FCM) | linux | file at `KINFRAME_GOOGLE_SERVICES` |
| `KINFRAME_SENTRY_DSN_FAMILY` | GlitchTip DSN, project `family-android` | linux | same env file |
| `KINFRAME_SENTRY_DSN_FRAME` | GlitchTip DSN, project `frame-android` | linux | same env file |
| `KINFRAME_SENTRY_DSN` | GlitchTip DSN, project `family-apple` | apple | `~/.appstoreconnect/signing.env` |
| `SENTRY_AUTH_TOKEN` | GlitchTip token for mapping.txt and dSYM uploads (environment-level; `ci.yml` keeps its repository secret) | both | Play env file, else `signing.env` |
| `APP_STORE_CONNECT_API_KEY_JSON` | fastlane API key JSON: `key_id`, `issuer_id`, `key` (the .p8 text) | apple | `~/.appstoreconnect/api_key.json`, with the .p8 at `ASC_KEY_PATH` (`~/.appstoreconnect/env`) when it has no `key` |
| `MATCH_PASSWORD` | Passphrase of the match repo | apple | `~/.appstoreconnect/signing.env` |
| `MATCH_DEPLOY_KEY` | ssh key, read-only deploy key on `jeroentrappers/apple-certificates` | apple | `~/.ssh/kinframe-release-match` (made for this, see below) |
| `REVIEW_PHONE` | App Review contact phone (`+32 …`) | apple | `~/.appstoreconnect/review.env` |
| `REVIEW_PASSWORD` | Password of the App Review demo account | apple | `~/.appstoreconnect/review.env` |
| `KINFRAME_DEPLOY_VAULT_PASSWORD` | kinframe-deploy's Ansible Vault passphrase (its `.vault-pass`, `ansible.cfg` `vault_password_file`) | linux | `kinframe-deploy/.vault-pass` |
| `DEPLOY_SSH_HOST` | Address of appmire-hetz1 (the `HostName` of the ssh alias; kept out of the public repo) | linux | `ssh -G appmire-hetz1` |
| `DEPLOY_SSH_KEY` | ssh key of user `ansible` on appmire-hetz1 (it also runs the `sudo docker` backup) | linux | `~/.ssh/kinframe-release-hetz1` (made for this) |
| `DEPLOY_REPO_KEY` | ssh key, deploy key **with write access** on the kinframe-deploy GitLab project | linux | `~/.ssh/kinframe-release-gitlab` (made for this) |
| `RELEASE_SSH_KNOWN_HOSTS` | known_hosts lines for appmire-hetz1, gitlab.com and github.com | both | `ssh-keygen -F` on `~/.ssh/known_hosts` |
| `DEPLOY_GPG_PRIVATE_KEY` | The signing **subkey** for the version-bump commit, armored (`--export-secret-subkeys`, no primary secret key) | linux | `gpg --export-secret-subkeys $KINFRAME_DEPLOY_GPG_KEY` |
| `DEPLOY_GPG_PASSPHRASE` | Its passphrase (may be empty) | linux | asked for by the script |

`KINFRAME_DEPLOY_REPO` can be set as a variable if the clone URL ever differs from
`git@gitlab.com:jeroentrappers/kinframe-deploy.git`. The frame app's **release** key (`ANDROID_KEYSTORE_*`) stays a
repository secret of `release-frame.yml`. The Play jobs never need it.

### Keys for the release jobs

The jobs can't type a passphrase, and your personal keys shouldn't leave your machines. Make dedicated keys once:

```sh
ssh-keygen -t ed25519 -N '' -C kinframe-release-match  -f ~/.ssh/kinframe-release-match
ssh-keygen -t ed25519 -N '' -C kinframe-release-hetz1  -f ~/.ssh/kinframe-release-hetz1
ssh-keygen -t ed25519 -N '' -C kinframe-release-gitlab -f ~/.ssh/kinframe-release-gitlab
```

- **match:** in GitHub, open `jeroentrappers/apple-certificates` → Settings → Deploy keys and add
  `kinframe-release-match.pub`, read-only.
- **hetz1:** append `kinframe-release-hetz1.pub` to `~ansible/.ssh/authorized_keys` on appmire-hetz1. If it is managed
  there, add it through appmire-infra.
- **GitLab:** in kinframe-deploy, open Settings → Repository → Deploy keys and add `kinframe-release-gitlab.pub` with
  **Grant write permissions**. If `main` is protected, allow that deploy key to push to it.
- **gpg:** add a signing subkey (`gpg --quick-add-key <primary fpr> ed25519 sign 2y`), then publish the updated public
  key to GitLab (Settings → GPG keys) so the commits show as Verified. Export only that subkey:
  `KINFRAME_DEPLOY_GPG_KEY=<subkey id>!`. If the secret leaks, revoke the subkey and keep the primary key.
- **fail2ban:** appmire-hetz1 bans bursts of ssh from untrusted IPs (docs/deploy.md). ryzen must connect from an
  address in appmire-infra `trusted_ips`, or through the home Tailscale exit node.

### Bitwarden: the backup, and the source

Bitwarden holds the backup of every release secret, and it is where the GitHub secrets come from. Each secret is one
item named after it, in the folder **`kinframe-release`**:

- **Single values** go in a secure note with a hidden custom field `value`.
- **Files** go in as the attachment `<NAME>.txt`, holding exactly what GitHub gets: base64 for the `*_B64` keystores,
  the text itself for JSON and keys. Attachments need Bitwarden Premium or an organization. Without either, set
  `KINFRAME_BW_FILES_AS_NOTES=1` to store files as the note's text instead (up to 10 000 characters).

Install the Bitwarden password manager CLI, `bw`, on the machine you run the scripts on:

```sh
brew install bitwarden-cli              # macOS (mini4)
sudo npm install -g @bitwarden/cli      # Debian, with Node.js; or the native binary from
                                        # https://bitwarden.com/download/ ("Command line interface", Linux zip)
bw login                                # once per machine; the scripts never see the master password
```

The flow has three steps, all run by you on a trusted machine. The scripts never print a value and pass values only
through private temporary files and stdin.

```sh
export BW_SESSION=$(bw unlock --raw)

# 1. Back up to Bitwarden, from the files of the manual setup (mini4 or the laptop). Running it again updates the
#    items in place: same item, field and attachment replaced, never a duplicate.
scripts/secrets-backup-to-bitwarden.sh

# 2. Sync Bitwarden to GitHub (environment production). --from-files skips Bitwarden and reads the local files.
scripts/release-secrets.sh                 # all; or name some: scripts/release-secrets.sh MATCH_PASSWORD
scripts/release-secrets.sh --dry-run       # what would be set, without reading anything

# 3. Release: push the tag.
bw lock
```

To change a secret, such as a rotated key or a new token, update the local file or the Bitwarden item, then repeat
steps 1 and 2 for that name.

**Restoring a lost file from Bitwarden** (`bw get attachment` writes the file as stored):

```sh
export BW_SESSION=$(bw unlock --raw)
id=$(bw list items --search KINFRAME_UPLOAD_KEYSTORE_B64 | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')
bw get attachment KINFRAME_UPLOAD_KEYSTORE_B64.txt --itemid "$id" --raw | base64 --decode > kinframe-upload.jks
# Text files are stored as they are, e.g. the Play service account:
bw get attachment SUPPLY_JSON_KEY_DATA.txt --itemid "$(bw list items --search SUPPLY_JSON_KEY_DATA | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')" --raw > play-service-account.json
# A value: the hidden field "value"
bw get item MATCH_PASSWORD | python3 -c 'import json,sys; print(next(f["value"] for f in json.load(sys.stdin)["fields"] if f["name"]=="value"))'
```

Put the restored files back where `~/.config/play/kinframe-signing.env` (or `~/.appstoreconnect/`) expects them, and
check them before use. For a keystore, `keytool -list -keystore kinframe-upload.jks` must show the upload certificate
Play expects. For gpg, use `gpg --import`.

**Alternative: Bitwarden Secrets Manager.** Bitwarden Secrets Manager is a separate, paid product with its own
`bws` CLI and machine accounts. With it, the jobs could fetch the secrets straight from Bitwarden through the
official [`bitwarden/sm-action`](https://github.com/bitwarden/sm-action) instead of from GitHub secrets. GitHub would
then store only the machine account's access token, as an environment secret of `production`. It is not used here:
GitHub environment secrets stay the primary path, with the password manager as backup and source. Moving over would
mean creating the secrets in a Secrets Manager project under the same names, and replacing the `${{ secrets.X }}`
references in `release.yml` with an `sm-action` step per job.

## One-time setup

1. **Environment.** Run `scripts/setup-release-env.sh` (you, with gh as repository admin). It creates `production`
   with you as required reviewer (self-review allowed) and allows deployments only from `v*` tags and `main`. It also
   requires approval for fork pull request workflows from all outside contributors, and sets the variable
   `FRAME_PLAY_RELEASE_STATUS=draft`. The same can be done in Settings → Environments.
2. **Keys** (above), then **secrets**: steps 1 and 2 of the Bitwarden flow.
3. **Runners**: ryzen and mini4, below.
4. Test with a dispatched run of the current tag, with only `server` ticked. It redeploys the same image, takes a
   backup and checks health. The commit step finds nothing to commit.

## Runners

| Runner | Labels | Runs |
|---|---|---|
| ryzen (Debian, Tailscale `ryzen-debian`) | `self-hosted, Linux, kinframe-release` | plan, wait-image, server deploy, Android family app, frame app on Play |
| mini4 (Mac mini) | `self-hosted, macOS, kinframe-release` | iOS and Mac family app |

Both need toolchains only. Neither holds secrets.

### Security (public repository)

- **Release workflow only.** Only `release.yml` uses the label `kinframe-release`, and its triggers are `push` of `v*`
  tags and `workflow_dispatch`. No `pull_request` or `pull_request_target` workflow may ever use the label: `ci.yml`
  and the others run on GitHub-hosted runners. Keep it that way in review.
- **Forks.** Pull requests from forks run workflows only after approval (Settings → Actions → General, set by
  `setup-release-env.sh`). Even then, they get GitHub-hosted runners and no environment secrets.
- **Repository guard.** The plan job and both environment jobs check
  `github.repository == 'jeroentrappers/kinframe'`, and every other job depends on the plan. A fork that enables
  Actions never targets these labels with this workflow.
- **Deployment rules.** The secrets live in the environment, behind a required reviewer, and only `v*` tags and
  `main` may deploy to it.
- **Runner group.** Keep the runners in a group (or the repository) that only `jeroentrappers/kinframe` can use.
- **Isolation.** Run each runner as a dedicated user with its own work directory, never as an admin or root.

### ryzen: systemd service

As root on ryzen:

```sh
adduser --disabled-password --gecos "kinframe release runner" kinframe-runner
apt install git curl python3 perl openssh-client gnupg ansible openjdk-21-jdk-headless unzip \
            ruby-full build-essential zlib1g-dev
gem install bundler -v 2.7.2                     # Gemfile.lock: BUNDLED WITH 2.7.2
```

As `kinframe-runner` (`sudo -iu kinframe-runner`), install the Android SDK (cmdline-tools from developer.android.com):

```sh
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-<version>_latest.zip
unzip commandlinetools-linux-*_latest.zip && mv cmdline-tools latest
echo 'export ANDROID_HOME=$HOME/android-sdk; export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH' >> ~/.bashrc
. ~/.bashrc && yes | sdkmanager --licenses && sdkmanager "platforms;android-37.0" "build-tools;37.0.0" platform-tools
# optional, for mapping.txt uploads to GlitchTip: sentry-cli (https://cli.sentry.dev) on the PATH
```

Then register the runner: in GitHub, open Settings → Actions → Runners → New self-hosted runner → Linux x64. Use the
download URL and token shown there:

```sh
mkdir ~/actions-runner && cd ~/actions-runner
curl -o runner.tar.gz -L <download URL from that page> && tar xzf runner.tar.gz
./config.sh --url https://github.com/jeroentrappers/kinframe --token <token> --unattended \
  --name ryzen --labels kinframe-release --work /home/kinframe-runner/_work
```

As root again, install it as a systemd service running as that user, and start it:

```sh
cd /home/kinframe-runner/actions-runner && ./svc.sh install kinframe-runner && ./svc.sh start && ./svc.sh status
```

The jobs run their tools in an interactive login shell (`scripts/release/in-login-shell.sh`: zsh if installed, else
bash), so the `PATH` from `~/.bashrc`/`~/.profile` applies. Check as `kinframe-runner`:
`bash -lic 'java -version; ruby -v; bundle -v; ansible --version; echo $ANDROID_HOME'`. ryzen needs no
kinframe-deploy checkout, no ssh or gpg keys and no `~/.config/play`: the job clones kinframe-deploy and builds the
rest from secrets on every run.

### mini4: launchd service

Use a dedicated macOS user with its own work directory if possible. The runner then installs as that user's
LaunchAgent, and that user must be logged in, for example with automatic login, because Xcode signing needs a GUI
session. If the runner runs as your own user, it still uses only its own temporary keychain and leaves yours alone.

```sh
mkdir ~/actions-runner && cd ~/actions-runner
curl -o runner.tar.gz -L <download URL from Settings → Actions → Runners → New self-hosted runner → macOS ARM64>
tar xzf runner.tar.gz
./config.sh --url https://github.com/jeroentrappers/kinframe --token <token> --unattended \
  --name mini4 --labels kinframe-release --work /Volumes/NVMESSD/actions-runner-work
./svc.sh install && ./svc.sh start && ./svc.sh status      # ~/Library/LaunchAgents/actions.runner.*.plist
```

Toolchains: Xcode (`xcode-select -p`), an iPhone simulator for the smoke test, and the Ruby with bundler 2.7.2 that
`zsh -lic 'cd ios && bundle install'` finds. sentry-cli (`brew install getsentry/tools/sentry-cli`) is needed for
dSYM uploads, and Python 3 and openssl come with the Command Line Tools. The jobs no longer use
`~/.appstoreconnect`, the `kinframe-signing` keychain or `~/Developer/github/kinframe`; manual releases still can.
The checkout is a fresh, clean one in the runner's work directory. Keep that directory on the NVMe disk
(`/Volumes/NVMESSD`): the internal disk is small, and the job points `TMPDIR` at the work directory's temp folder.

## By hand

The scripts work outside Actions as well, from mini4 or the laptop with the manual setup:

- **Server:** `scripts/release/deploy-server.sh 0.7.4`. It uses the kinframe-deploy checkout at
  `KINFRAME_DEPLOY_DIR`, your ssh config and your gpg.
- **Store lanes:** see [ios/README.md](../ios/README.md#releasing), [play-frame.md](play-frame.md#releasing-the-play-build)
  and `android/fastlane/Fastfile`. `fastlane submit` and `fastlane mac_submit` take `build_number:`, `app_version:`
  and `metadata:true`.
