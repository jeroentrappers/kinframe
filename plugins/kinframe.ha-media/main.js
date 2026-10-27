// Now playing via Home Assistant's media_player (works for Spotify through its Home Assistant integration, Sonos,
// Cast, …): title, artist and album art in the corner or band, and in the detail card a moving progress bar and buttons
// to skip, pause and change the volume (docs/plugins.md §4.2). Hidden ({type: "none"}) while nothing plays.

import * as ha from 'kinframe/ha';

// ---- the player ----

// media_player supported_features bits (Home Assistant's MediaPlayerEntityFeature).
const PAUSE = 1, VOLUME_SET = 4, PREVIOUS = 16, NEXT = 32, VOLUME_STEP = 1024, PLAY = 16384;

const short = (s, n) => { s = String(s || ''); return s.length > n ? s.slice(0, n - 1) + '…' : s; };

export function read(e, base, now) {
  if (!e || e.missing) return null;
  const a = e.attributes || {};
  const pic = a.entity_picture ? (/^https?:\/\//.test(a.entity_picture) ? a.entity_picture : base + a.entity_picture) : null;
  const at = a.media_position_updated_at ? Date.parse(a.media_position_updated_at) : NaN;
  return {
    state: e.state, title: a.media_title || null, artist: a.media_artist || a.media_series_title || null, album: a.media_album_name || null,
    app: a.app_name || null, art: pic, duration: typeof a.media_duration === 'number' ? a.media_duration : null,
    position: typeof a.media_position === 'number' ? a.media_position : null, at: Number.isFinite(at) ? at : null,
    volume: typeof a.volume_level === 'number' ? a.volume_level : null, features: a.supported_features | 0, name: a.friendly_name || null,
  };
}

const active = (p, showPaused) => !!p && (p.state === 'playing' || p.state === 'buffering' || (p.state === 'paused' && showPaused));

export function views(p, ctx) {
  const t = (k, v) => ctx.t(k, v);
  if (!active(p, ctx.settings.show_paused !== false)) return { main: { type: 'none' }, detail: { type: 'none' } };
  const title = short(p.title || p.app || p.name || '', 60);
  // A self-titled album would read "Jacques Dutronc · Jacques Dutronc".
  const who = short([p.artist, p.album !== p.artist ? p.album : null].filter(Boolean).join(' · '), 60);
  const art = p.art ? { type: 'image', src: p.art, description: t('album_art') } : null;
  const lines = [title, short(p.artist || p.app || '', 60)].filter(Boolean);
  const text = { type: 'text', lines: lines.length ? lines : [p.name || '♪'], icon: art ? undefined : 'music' };
  const main = art ? { type: 'stack', direction: 'horizontal', items: [art, text] } : text;

  const items = [art ? { type: 'stack', direction: 'horizontal', items: [art, { type: 'text', lines: [title, who].filter(Boolean) }] } : { type: 'text', lines: [title, who].filter(Boolean), icon: 'music' }];
  if (p.duration && p.duration > 0) {
    const pos = Math.max(0, Math.min(p.duration, p.position || 0));
    items.push({ type: 'progress', value: pos, max: p.duration, at: p.at || undefined, running: p.state === 'playing', time: true });
  }
  const f = p.features;
  const buttons = [];
  if (f & PREVIOUS) buttons.push({ id: 'previous', icon: 'skip-previous', label: t('previous') });
  if (f & (PAUSE | PLAY)) buttons.push(p.state === 'playing'
    ? { id: 'play_pause', icon: 'pause', label: t('pause'), primary: true }
    : { id: 'play_pause', icon: 'play', label: t('play'), primary: true });
  if (f & NEXT) buttons.push({ id: 'next', icon: 'skip-next', label: t('next') });
  if (f & (VOLUME_SET | VOLUME_STEP)) {
    buttons.push({ id: 'volume_down', icon: 'volume-down', label: t('quieter') });
    buttons.push({ id: 'volume_up', icon: 'volume-up', label: p.volume !== null ? t('volume', { n: Math.round(p.volume * 100) }) : t('louder') });
  }
  if (buttons.length) items.push({ type: 'actions', items: buttons.slice(0, 5) });
  return { main, detail: { type: 'stack', items: items.slice(0, 4) } };
}

function player(ctx) {
  const h = ha.connection(ctx);
  const id = ctx.settings.entity;
  ha.requireToken(ctx);
  return read(h.entities[id], h.base, ctx.now());
}

/** Calls a media_player service and updates the local copy at once, so the card answers before Home Assistant does. */
function call(ctx, service, data) {
  return ha.callService(ctx, 'media_player', service, Object.assign({ entity_id: ctx.settings.entity }, data || {}));
}

export default {
  async start(ctx) {
    if (!ha.configured(ctx) || !ctx.settings.entity) return null;
    ha.subscribe(ctx, [ctx.settings.entity]);
    return null;
  },
  async update(ctx) {
    if (!ha.configured(ctx) || !ctx.settings.entity) return;
    await ha.refresh(ctx, [ctx.settings.entity]);
  },
  render(ctx) {
    if (!ha.configured(ctx) || !ctx.settings.entity) return null;
    return views(player(ctx), ctx).main;
  },
  detail(ctx) {
    return views(player(ctx), ctx).detail;
  },
  async action(ctx, id) {
    const p = player(ctx);
    if (!p) return null;
    const e = ha.connection(ctx).entities[ctx.settings.entity];
    if (id === 'previous') await call(ctx, 'media_previous_track');
    else if (id === 'next') await call(ctx, 'media_next_track');
    else if (id === 'play_pause') {
      await call(ctx, 'media_play_pause');
      e.state = p.state === 'playing' ? 'paused' : 'playing';
    } else if (id === 'volume_up' || id === 'volume_down') {
      if (p.features & VOLUME_SET && p.volume !== null) {
        const v = Math.max(0, Math.min(1, Math.round((p.volume + (id === 'volume_up' ? 0.05 : -0.05)) * 100) / 100));
        await call(ctx, 'volume_set', { volume_level: v });
        e.attributes.volume_level = v;
      } else {
        await call(ctx, id);
      }
    } else return null;
    ctx.invalidate();
    return views(read(e, ha.connection(ctx).base, ctx.now()), ctx).main;
  },
};
