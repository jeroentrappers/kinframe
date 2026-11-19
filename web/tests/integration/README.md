# Integration specs: the web app against a real server

The specs in `tests/` run the web app against the in-memory mock (`tests/mock-api.ts`). These run it against a real
kinframe server in dev-auth mode with a real PostgreSQL, with several dev users in several browsers at once, so they
catch what the mock can't: real response shapes, permissions, websocket events between people, the device API.

They cover family social end to end: families, invite links and roles (Reader), posts to the family and to a
circle, the timeline, threads (comments, replies, mentions, reactions, close, mute, read), chats (family chat,
direct, group, circle chat, photos, voice, Put on a frame, Share in chat, edit, delete, rename, mute, archive,
leave), blocks and reports with both review queues, Ask for new photos and freshness, frame reactions and comment
cards on the device API, prompts, streak and reminder settings, badges, and the browser frame (`/tv/frame`).
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

The fake microphone (Chrome's `--use-fake-device-for-media-stream`) records the voice message. Media processing needs
`vips` and `ffmpeg` on the server's PATH, as for the server's own media tests. Some server work is batched: comment
cards for frames go out every 15 s, so those steps wait up to 40 s.
