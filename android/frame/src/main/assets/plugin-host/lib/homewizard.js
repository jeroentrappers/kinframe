// kinframe/homewizard — HomeWizard Energy devices on the home network, local API v1 (docs/plugins.md §13.1): the P1
// meter (HWE-P1), the energy socket (HWE-SKT), the water meter (HWE-WTR), the kWh meters (SDM230/630). The owner
// switches on "Local API" per device in the HomeWizard Energy app; the devices announce themselves as `_hwenergy._tcp`.
//
//   import { data, number, total } from 'kinframe/homewizard';
//
//   update(ctx) { const d = await data(ctx); ctx.state.w = number(d, 'active_power_w'); }
//
// Settings it reads: `host` (url, local_only, "192.168.1.50" or "http://…"). The plugin needs `permissions.http` on
// `settings.host`. Readings are flat JSON; devices leave out what they don't measure, so read with `number`.

/** "192.168.1.50", "meter.local/" or "http://…" → "http://192.168.1.50". */
export function baseUrl(host) {
  const h = String(host).trim().replace(/\/+$/, '');
  return /^https?:\/\//i.test(h) ? h : 'http://' + h;
}

/** Whether the instance has a device address. */
export function configured(ctx) { return !!ctx.settings.host; }

async function call(ctx, method, path, body) {
  const url = baseUrl(ctx.settings.host) + path;
  const o = { headers: { Accept: 'application/json' } };
  const res = method === 'PUT' ? await ctx.http.put(url, body, o) : await ctx.http.get(url, o);
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

/** `GET /api/v1/data`: the live reading. */
export function data(ctx) { return call(ctx, 'GET', '/api/v1/data'); }

/** `GET /api`: `{ product_type, product_name, serial, firmware_version, api_version }`. */
export function device(ctx) { return call(ctx, 'GET', '/api'); }

/** `GET /api/v1/state` (energy socket): `{ power_on, switch_lock, brightness }`. */
export function state(ctx) { return call(ctx, 'GET', '/api/v1/state'); }

/** `PUT /api/v1/state` (energy socket) with the fields to change, e.g. `{ power_on: false }`; returns what changed. */
export function setState(ctx, patch) { return call(ctx, 'PUT', '/api/v1/state', patch); }

/** A number field, or null when it's missing or not a number (devices omit what they don't measure). */
export function number(o, key) { return o && typeof o[key] === 'number' ? o[key] : null; }

/**
 * A meter total in kWh: `<prefix>_kwh`, or the sum of the tariff registers `<prefix>_t1_kwh` and `<prefix>_t2_kwh` on
 * older firmware. `total(d, 'total_power_import')`.
 */
export function total(o, prefix) {
  const all = number(o, prefix + '_kwh');
  if (all !== null) return all;
  const parts = [number(o, prefix + '_t1_kwh'), number(o, prefix + '_t2_kwh')].filter((v) => v !== null);
  return parts.length ? parts.reduce((a, b) => a + b, 0) : null;
}

/** The per-phase powers that are present (`active_power_l1_w` …), in order. */
export function phases(o) {
  return ['active_power_l1_w', 'active_power_l2_w', 'active_power_l3_w'].map((k) => number(o, k)).filter((v) => v !== null);
}
