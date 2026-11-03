// kinframe/ha — a Home Assistant connection for plugins (docs/plugins.md §13.1).
//
//   import { subscribe, refresh, entities, requireToken, callService } from 'kinframe/ha';
//
//   start(ctx)  { subscribe(ctx, ids); }        // live: subscribe_entities over the WebSocket API
//   update(ctx) { await refresh(ctx, ids); }    // REST, only while the WebSocket isn't live
//   render(ctx) { requireToken(ctx); const e = entities(ctx)['sensor.x']; … }
//
// Settings it reads: `base_url` (url, local_only) and `token` (secret: a long-lived access token). The plugin needs
// `permissions.http` and `permissions.websocket` on `settings.base_url`. The connection lives in `ctx.state.ha`;
// an entity is `{ state, attributes, updated }` (updated: epoch ms) or `{ missing: true }` (404 over REST).
// A plugin may define `token_refused` in its locales; the English text below is the fallback.

export function baseUrl(s) {
  const h = String(s || '').trim().replace(/\/+$/, '');
  return /^https?:\/\//i.test(h) ? h : 'http://' + h;
}

/** The connection's state for this instance: `{ base, entities, live, error, nextId }`. */
export function connection(ctx) {
  if (!ctx.state.ha) ctx.state.ha = { base: baseUrl(ctx.settings.base_url), entities: {}, live: false, error: null, nextId: 1 };
  return ctx.state.ha;
}

/** Entity id → `{ state, attributes, updated }` as last seen (WebSocket or REST). */
export function entities(ctx) { return connection(ctx).entities; }

/** True while the WebSocket subscription delivers updates. */
export function isLive(ctx) { return connection(ctx).live; }

/** Whether the instance has what it needs to connect (`base_url` and `token`). */
export function configured(ctx) { return !!(ctx.settings.base_url && ctx.settings.token); }

/** Applies a subscribe_entities event: "a" adds full states, "c" changes ("+" set, "-" removed attributes), "r" removes. */
export function applyEvent(entities, ev) {
  for (const id in ev.a || {}) {
    const e = ev.a[id];
    entities[id] = { state: e.s, attributes: e.a || {}, updated: Math.round((e.lu || e.lc || 0) * 1000) };
  }
  for (const id in ev.c || {}) {
    const d = ev.c[id];
    const e = entities[id] || (entities[id] = { state: null, attributes: {}, updated: 0 });
    const add = d['+'];
    if (add) {
      if ('s' in add) e.state = add.s;
      if (add.a) Object.assign(e.attributes, add.a);
      if (add.lu || add.lc) e.updated = Math.round((add.lu || add.lc) * 1000);
    }
    if (d['-'] && d['-'].a) for (const k of d['-'].a) delete e.attributes[k];
  }
  for (const id of ev.r || []) delete entities[id];
}

/**
 * Opens the WebSocket, authenticates with the token and subscribes to [ids]; every event updates the entities and
 * asks for a render (`ctx.invalidate()`). The host reconnects with backoff; until it's live again, `refresh` polls.
 */
export function subscribe(ctx, ids) {
  const h = connection(ctx);
  ctx.ws.connect(h.base.replace(/^http/i, 'ws') + '/api/websocket', {
    onMessage(ws, m) {
      if (m.type === 'auth_required') ws.send({ type: 'auth', access_token: ctx.settings.token });
      else if (m.type === 'auth_ok') ws.send({ id: h.nextId++, type: 'subscribe_entities', entity_ids: ids });
      else if (m.type === 'auth_invalid') { h.error = 'token'; ws.close(); }
      else if (m.type === 'event' && m.event) { applyEvent(h.entities, m.event); h.live = true; ctx.invalidate(); }
    },
    onClose() { h.live = false; },
  });
}

/** REST: one GET per entity. A refused token stops (see `requireToken`); an unknown entity is marked missing. */
export async function poll(ctx, ids) {
  const h = connection(ctx);
  for (const id of ids) {
    const r = await ctx.http.get(h.base + '/api/states/' + id, { headers: { Authorization: 'Bearer ' + ctx.settings.token } });
    if (r.status === 401) { h.error = 'token'; break; }
    if (r.status === 404) { h.entities[id] = { missing: true }; continue; }
    if (!r.ok) throw new Error('Home Assistant: HTTP ' + r.status);
    const e = r.json();
    h.entities[id] = { state: e.state, attributes: e.attributes || {}, updated: Date.parse(e.last_updated) || 0 };
  }
}

/** For `update()`: polls [ids] over REST unless the WebSocket is live. */
export async function refresh(ctx, ids) {
  if (!connection(ctx).live) await poll(ctx, ids);
}

function refusedMessage(ctx) {
  const m = ctx.t('token_refused');
  return m === 'token_refused' ? 'Home Assistant refused the token' : m;
}

/** Throws the plugin's "token refused" message once Home Assistant has refused the token (REST or WebSocket). */
export function requireToken(ctx) {
  if (connection(ctx).error === 'token') throw new Error(refusedMessage(ctx));
}

/** Calls a service (`POST /api/services/<domain>/<service>`), e.g. `callService(ctx, 'media_player', 'media_pause', { entity_id })`. */
export async function callService(ctx, domain, service, data) {
  const h = connection(ctx);
  const r = await ctx.http.post(h.base + '/api/services/' + domain + '/' + service, data || {},
    { headers: { Authorization: 'Bearer ' + ctx.settings.token } });
  if (r.status === 401) throw new Error(refusedMessage(ctx));
  if (!r.ok) throw new Error('Home Assistant: HTTP ' + r.status);
}
