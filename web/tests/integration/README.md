# Integration specs: the web app against a real server

The specs in `tests/` run the web app against the in-memory mock (`tests/mock-api.ts`). These run it against a real
kinframe server in dev-auth mode with a real PostgreSQL, with several dev users in several browsers at once, so they
catch what the mock can't: real response shapes, permissions, websocket events between people, the device API.

They cover family social end to end: families, invite links and roles (Reader), posts to the family and to a
circle, the timeline, threads (comments, replies, mentions, reactions, close, mute, read), chats (family chat,
direct, group, circle chat, photos, voice, Put on a frame, Share in chat, edit, delete, rename, mute, archive,
leave), blocks and reports with both review queues, Ask for new photos and freshness, frame reactions and comment
cards on the device API, prompts, streak and reminder settings, badges, and the browser frame (`/tv/frame`).

`leaving.spec.ts` covers leaving (README §5.21): the leave sheets of families and frames (keep or take the photos,
delete comments, leave frames too, and after handing a frame over), photos leaving the device feed and the browser
frame, the frame trash ("Taken away by Tom") and the family's, Ask to keep (all or picked photos) and the answers
from the history (all, some, decline), restored photos coming back, taking photos away after being removed,
old clients leaving without a body, account deletion (take, and leave with the "(former member)" credit), memories
(leave people out, hide a memory on On this day), read-only direct chats and the operator's ownership transfer.
The 30-day purge needs a clock and is covered by the server's e2e tests (`server/internal/e2e/leaving_test.go`).

`federation.spec.ts` covers families on different servers (README §5.22) with two servers; see below.

Every page records failed API calls and page errors; a spec fails on any it didn't expect.

## Running

1. A PostgreSQL 17, e.g. a throwaway container:

   ```sh
   docker run -d --name kinframe-e2e-pg -e POSTGRES_USER=kinframe -e POSTGRES_PASSWORD=kinframe-dev \
     -e POSTGRES_DB=kinframe -p 127.0.0.1:55433:5432 postgres:17-alpine
   ```

   (`make db-up` in `server/` works too; adjust the URL below.)

2. The web build and the server serving it, on one origin:

   ```sh
   (cd web && npm run build)
   cd server && go build -o bin/kinframe-server ./cmd/kinframe-server && \
     KINFRAME_DEV_AUTH=1 KINFRAME_DATABASE_URL='postgres://kinframe:kinframe-dev@127.0.0.1:55433/kinframe?sslmode=disable' \
     KINFRAME_DATA_DIR=$HOME/.cache/kinframe-e2e-data KINFRAME_LISTEN=127.0.0.1:18110 \
     KINFRAME_PUBLIC_URL=http://localhost:18110 KINFRAME_WEB_DIR=../web/build ./bin/kinframe-server
   ```

   `KINFRAME_PUBLIC_URL` must be the origin the browser uses: the browser frame talks to the API URL from discovery.

3. The specs (they make fresh users each run, so the same database can be reused):

   ```sh
   cd web && REAL_SERVER=http://localhost:18110 npx playwright test -c tests/integration
   ```

4. Optional: `REAL_PSQL`, a command that reads SQL on stdin against the server's database, for the memories spec
   (it moves posts a year back for On this day; skipped without it):

   ```sh
   REAL_PSQL='docker exec -i kinframe-e2e-pg psql -U kinframe -d kinframe -q'
   ```

### Two servers (federation.spec.ts)

The federation spec needs a second server B with its own database, data directory, port and public URL, and both
servers started with `KINFRAME_FEDERATION_DEV_LOOPBACK=1` so they may reach each other on 127.0.0.1 (other servers
are otherwise reached at public addresses only; the variable is refused unless `KINFRAME_DEV_AUTH=1`).
`KINFRAME_SERVER_LABEL` gives each a recognizable name. Without `REAL_SERVER_B` the spec is skipped.

```sh
docker exec kinframe-e2e-pg psql -U kinframe -c 'CREATE DATABASE kinframe_b'
cd server
# A
KINFRAME_DEV_AUTH=1 KINFRAME_FEDERATION_DEV_LOOPBACK=1 KINFRAME_SERVER_LABEL=Janssens \
  KINFRAME_DATABASE_URL='postgres://kinframe:kinframe-dev@127.0.0.1:55433/kinframe?sslmode=disable' \
  KINFRAME_DATA_DIR=$HOME/.cache/kinframe-e2e-data KINFRAME_LISTEN=127.0.0.1:18110 \
  KINFRAME_PUBLIC_URL=http://localhost:18110 KINFRAME_WEB_DIR=../web/build ./bin/kinframe-server &
# B
KINFRAME_DEV_AUTH=1 KINFRAME_FEDERATION_DEV_LOOPBACK=1 KINFRAME_SERVER_LABEL=Appmire \
  KINFRAME_DATABASE_URL='postgres://kinframe:kinframe-dev@127.0.0.1:55433/kinframe_b?sslmode=disable' \
  KINFRAME_DATA_DIR=$HOME/.cache/kinframe-e2e-data-b KINFRAME_LISTEN=127.0.0.1:18111 \
  KINFRAME_PUBLIC_URL=http://localhost:18111 KINFRAME_WEB_DIR=../web/build ./bin/kinframe-server &
cd ../web && REAL_SERVER=http://localhost:18110 REAL_SERVER_B=http://localhost:18111 \
  npx playwright test -c tests/integration federation.spec.ts
```

It covers the well-known document and discovery (`server_id`, `server_name`), "Allow frames on other servers", a
subscribe invite for other servers, a frame manager on B adding the family in the web app (the preview naming both
servers, the history option), the request in A's subscriptions list and its approval, the family's posts on B's
browser frame (circle posts never), a frame reaction on B in A's thread and reaction rows with B's name, removals and
taking photos away on A reaching B, unfollowing from either side, blocking B for the family and the operator's list
of other servers. Pings are debounced 10 s, so steps wait up to 90 s.

The fake microphone (Chrome's `--use-fake-device-for-media-stream`) records the voice message. Media processing needs
`vips` and `ffmpeg` on the server's PATH, as for the server's own media tests. Some server work is batched: comment
cards for frames go out every 15 s, so those steps wait up to 40 s.
