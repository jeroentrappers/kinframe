// Example: the next departures from a Belgian station (iRail, https://docs.irail.be). Shows the limits of API 1 for a
// departure board: rows hold one value and a label, and the band drops the labels, so the destination disappears there.

function fit(head, tail, max) {
  return head.length + tail.length <= max ? head + tail : head.slice(0, max - tail.length - 1) + '…' + tail;
}

export function board(json, ctx) {
  const towards = (ctx.settings.towards || '').trim().toLowerCase();
  const all = ((json.departures && json.departures.departure) || []).filter((d) => d.left !== '1');
  const deps = all.filter((d) => !towards || d.station.toLowerCase().includes(towards)).slice(0, Math.max(1, Math.min(4, ctx.settings.count | 0 || 3)));
  if (!deps.length) return { type: 'text', lines: [ctx.t('none')], icon: 'train' };
  return {
    type: 'rows',
    items: deps.map((d) => {
      const delay = Math.round((+d.delay || 0) / 60);
      const canceled = d.canceled === '1';
      return {
        type: 'value',
        value: ctx.format.time(+d.time * 1000) + (delay > 0 ? ' +' + delay : ''),
        // Labels are at most 32 characters: the station name gives way ("Brussels Airport - Za… · spoor 2").
        label: fit((canceled ? ctx.t('canceled') + ' · ' : '') + d.station, ' · ' + ctx.t('platform', { n: d.platform }), 32),
        icon: 'train',
        tone: canceled ? 'bad' : delay >= 5 ? 'warn' : undefined,
      };
    }),
  };
}

export default {
  async update(ctx) {
    const st = encodeURIComponent(String(ctx.settings.station || '').trim());
    if (!st) return;
    const lang = ctx.language.slice(0, 2);
    const r = await ctx.http.get(`https://api.irail.be/v1/liveboard/?station=${st}&format=json&arrdep=departure&lang=${['nl', 'fr', 'de', 'en'].includes(lang) ? lang : 'en'}`);
    if (!r.ok) throw new Error('iRail: HTTP ' + r.status);
    ctx.state.board = r.json();
  },
  render(ctx) {
    return ctx.state.board ? board(ctx.state.board, ctx) : null;
  },
};
