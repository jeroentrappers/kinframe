// The plugin's `ctx` (docs/plugins.md §3.2), built on the host's `__kf` bindings. Runs once per instance, before the
// plugin's module. Everything the plugin sees goes through here; `__kf` itself is removed from the global scope.
(() => {
  const K = globalThis.__kf;
  // The raw bindings stay private to this closure. (Everything they do is checked on the Kotlin side anyway.)
  if (!delete globalThis.__kf) Object.defineProperty(globalThis, '__kf', { value: undefined, writable: false, configurable: false, enumerable: false });
  const str = (v) => (typeof v === 'string' ? v : JSON.stringify(v));

  async function request(method, url, o) {
    o = o || {};
    let body = o.body === undefined || o.body === null ? null : o.body;
    let type = o.contentType || null;
    if (body !== null && typeof body !== 'string') { body = JSON.stringify(body); type = type || 'application/json'; }
    let raw;
    try {
      raw = await K.http(method, String(url), JSON.stringify({ headers: o.headers || {}, body, type }));
    } finally {
      K.resumed(); // the host's JavaScript clock runs again from here
    }
    const r = JSON.parse(raw);
    return {
      status: r.status,
      ok: r.status >= 200 && r.status < 300,
      contentType: r.type,
      text: r.body,
      json() { return JSON.parse(r.body); },
    };
  }

  let socketHandlers = null;
  const ws = {
    connect(url, handlers) { socketHandlers = handlers || {}; K.ws_connect(String(url)); },
    send(message) { K.ws_send(str(message)); },
    close() { socketHandlers = null; K.ws_close(); },
  };

  let dataTime = null;
  const ctx = Object.freeze({
    settings: Object.freeze(JSON.parse(K.settings())),
    state: {},
    store: Object.freeze({
      get(key) { const v = K.store_get(String(key)); return v === undefined ? null : v; },
      set(key, value) { K.store_put(String(key), value === null || value === undefined ? null : String(value)); },
      getJson(key) { const v = K.store_get(String(key)); try { return v == null ? null : JSON.parse(v); } catch (e) { return null; } },
      setJson(key, value) { K.store_put(String(key), value === null || value === undefined ? null : JSON.stringify(value)); },
    }),
    http: Object.freeze({
      get: (url, o) => request('GET', url, o),
      post: (url, body, o) => request('POST', url, Object.assign({}, o, { body })),
    }),
    ws: Object.freeze(ws),
    invalidate() { K.invalidate(); },
    format: Object.freeze({
      power: (w) => K.fmt('power', +w),
      energy: (kwh) => K.fmt('energy', +kwh),
      volume: (m3) => K.fmt('volume', +m3),
      percent: (v) => K.fmt('percent', +v),
      temperature: (c, decimals) => K.fmt('temperature', +c, decimals | 0),
      number: (v, decimals, unit) => K.fmt('number', +v, decimals | 0, unit == null ? null : String(unit)),
      duration: (seconds) => K.fmt('duration', +seconds),
      time: (epochMs) => K.fmt('time', +epochMs),
    }),
    t(key, vars) { return K.t(String(key), vars ? JSON.stringify(vars) : null); },
    log(...args) { K.log(args.map(str).join(' ')); },
    now() { return K.now(); },
    // The frame's calendar for an instant (default now): { date: "2026-10-25", year, month, day, hour, minute,
    // weekday (1 = Monday) } in the frame's time zone. QuickJS's Date only knows the device's zone.
    localTime(epochMs) { return JSON.parse(K.local_time(epochMs === undefined ? K.now() : +epochMs)); },
    get timezone() { return K.zone(); },
    get language() { return K.language(); },
    get dataTime() { return dataTime; },
    set dataTime(v) { dataTime = v == null ? null : +v; K.data_time(dataTime == null ? -1 : dataTime); },
  });

  globalThis.__kf_call = async (name, arg) => {
    K.js_thread(); // the host's JavaScript clock follows this thread
    const p = globalThis.__plugin;
    const f = p && p[name];
    if (typeof f !== 'function') { K.ret(null, false); return; }
    try {
      const v = arg === undefined ? await f.call(p, ctx) : await f.call(p, ctx, arg);
      K.ret(v === undefined || v === null ? null : JSON.stringify(v), true);
    } catch (e) {
      K.fail(e && e.message ? String(e.message) : String(e));
    }
  };

  globalThis.__kf_ws_event = async (type, data) => {
    K.js_thread();
    const h = socketHandlers;
    if (!h) return;
    try {
      if (type === 'open') { if (h.onOpen) await h.onOpen(ws); }
      else if (type === 'message') {
        let m = data;
        try { m = JSON.parse(data); } catch (e) { /* text frame */ }
        if (h.onMessage) await h.onMessage(ws, m);
      } else if (type === 'close') { if (h.onClose) await h.onClose(ws, data); }
    } catch (e) {
      K.log('ws ' + type + ' handler failed: ' + (e && e.message ? e.message : e));
    }
  };
})();
