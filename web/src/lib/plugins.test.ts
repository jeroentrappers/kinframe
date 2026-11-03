import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
	applyInstanceOp,
	applyPreset,
	approveUpdate,
	catalogGroups,
	communityMeta,
	isCommunity,
	pendingUpdate,
	permissionChanges,
	permissionsOf,
	withdrawnReason,
	draftOf,
	fieldProblems,
	filterEntities,
	filterTimeZones,
	formatUtcOffset,
	knownTimeZone,
	timeZoneIds,
	timeZoneOption,
	timeZoneOptions,
	instanceComplete,
	instanceFrom,
	instancesPatch,
	internetHosts,
	mapErrors,
	missingRequired,
	newInstance,
	newInstanceId,
	optionLabel,
	pluginDescription,
	pluginName,
	presetInputs,
	presetLabel,
	secretsPatch,
	settingHelp,
	settingLabel,
	settingsOf,
	statusKind,
	suggestions,
	usesLocation,
	validUrl,
	type CatalogPlugin,
	type FramePlugins,
	type Manifest,
	type PluginInstance,
	type SettingSpec
} from './plugins';

// The real manifests the frame ships (plugins/), as the frame reports them in its catalog.
const read = (path: string) => JSON.parse(readFileSync(new URL(`../../../plugins/${path}`, import.meta.url), 'utf8'));
const manifest = (dir: string): Manifest => read(`${dir}/plugin.json`);
const catalogPlugin = (dir: string, locales: Record<string, Record<string, string>> = {}): CatalogPlugin =>
	({ manifest: manifest(dir), locales }) as unknown as CatalogPlugin;

const energy = manifest('kinframe.ha-energy');
const ev = manifest('kinframe.ha-ev');
const p1 = manifest('kinframe.homewizard-p1');
const air = manifest('examples/community.air-quality');
const irail = manifest('examples/community.irail-departures');

const spec = (m: Manifest, key: string): SettingSpec => m.settings!.find((s) => s.key === key)!;
const inst = (m: Manifest, extra: Partial<PluginInstance> = {}): PluginInstance => ({
	instance_id: 'p-abcdefghij',
	plugin_id: m.id,
	slot: 'top-right',
	visibility: 'always',
	enabled: true,
	settings: {},
	...extra
});
const settings = (s: Record<string, unknown>) => s as unknown as PluginInstance['settings'];

describe('texts', () => {
	const p = catalogPlugin('kinframe.ha-energy', {
		en: { '@option.grid_sign.import': 'Taking from the grid' },
		nl: { '@name': 'Energiestroom', '@setting.grid': 'Netvermogen', '@setting.token.help': 'Profiel → Beveiliging', '@option.grid_sign.import': 'Afname' },
		zh: { '@name': '能源流' }
	});

	it('uses the app language, then the manifest, then locales.en, then the raw value', () => {
		expect(pluginName(p, 'nl')).toBe('Energiestroom');
		expect(pluginName(p, 'de')).toBe('Home Assistant energy flow');
		expect(settingLabel(p, spec(energy, 'grid'), 'nl')).toBe('Netvermogen');
		expect(settingLabel(p, spec(energy, 'solar'), 'nl')).toBe('Solar power');
		expect(settingHelp(p, spec(energy, 'token'), 'nl')).toBe('Profiel → Beveiliging');
		expect(settingHelp(p, spec(energy, 'token'), 'fr')).toContain('Long-lived access tokens');
		expect(settingHelp(p, spec(energy, 'grid'), 'fr')).toBe('');
		expect(optionLabel(p, 'grid_sign', 'import', 'nl')).toBe('Afname');
		expect(optionLabel(p, 'grid_sign', 'import', 'fr')).toBe('Taking from the grid');
		expect(optionLabel(p, 'grid_sign', 'export', 'fr')).toBe('export');
		expect(pluginDescription(p, 'fr')).toContain('Solar, grid');
	});

	it('falls back from a region tag to its base language', () => {
		expect(pluginName(p, 'zh-TW')).toBe('能源流');
	});

	it('labels presets', () => {
		const c = catalogPlugin('kinframe.ha-ev', { nl: { '@preset.kia_hyundai': 'Kia / Hyundai (NL)' } });
		const preset = ev.presets![0]!;
		expect(presetLabel(c, preset, 'nl')).toBe('Kia / Hyundai (NL)');
		expect(presetLabel(c, preset, 'de')).toBe(preset.label);
	});

	it('lists fixed internet hosts and the location permission, not setting addresses', () => {
		expect(internetHosts(energy)).toEqual([]);
		expect(internetHosts(air)).toEqual(['air-quality-api.open-meteo.com']);
		expect(internetHosts(irail)).toEqual(['api.irail.be']);
		expect(usesLocation(air)).toBe(true);
		expect(usesLocation(energy)).toBe(false);
	});
});

describe('new instances', () => {
	it('makes ids of p- and 10 lowercase letters or digits', () => {
		for (let i = 0; i < 50; i++) expect(newInstanceId()).toMatch(/^p-[a-z0-9]{10}$/);
		expect(newInstanceId(() => 0)).toBe('p-aaaaaaaaaa');
		expect(newInstanceId(() => 0.9999)).toBe('p-9999999999');
	});

	it('starts off, top right, with the manifest default visibility, after the others in its corner', () => {
		const existing = [inst(energy, { instance_id: 'p-1', slot: 'top-right', order: 0 })];
		const n = newInstance({ ...ev, default_visibility: 'on_change' }, existing);
		expect(n).toMatchObject({ plugin_id: 'kinframe.ha-ev', version: '1.0.0', slot: 'top-right', order: 1, visibility: 'on_change', enabled: false, settings: {} });
		expect(newInstance(p1, []).visibility).toBe('always');
	});

	it('takes the only corner a plugin allows, and never reuses an id', () => {
		const left: Manifest = { ...p1, slots: ['top-left'] };
		expect(newInstance(left, []).slot).toBe('top-left');
		let calls = 0;
		const rnd = () => (calls++ < 10 ? 0 : 0.5);
		expect(newInstance(p1, [inst(p1, { instance_id: 'p-aaaaaaaaaa' })], rnd).instance_id).not.toBe('p-aaaaaaaaaa');
	});
});

describe('field values', () => {
	it('shows stored values, else defaults', () => {
		const d = draftOf(energy, inst(energy, { settings: settings({ grid: 'sensor.grid', solar: ['sensor.a', 'sensor.b'] }), secrets_set: ['token'] }));
		expect(d.values.grid).toEqual(['sensor.grid']);
		expect(d.values.solar).toEqual(['sensor.a', 'sensor.b']);
		expect(d.values.grid_sign).toBe('import');
		expect(d.values.battery).toEqual([]);
		expect(d.values.base_url).toBe('');
		expect(d.secrets.token).toEqual({ isSet: true, action: 'keep', value: '' });
		expect('token' in d.values).toBe(false);
		expect(draftOf(p1, inst(p1)).values.max_w).toBe('4000');
	});

	it('leaves out empty fields and untouched defaults, and sends numbers as numbers', () => {
		const d = draftOf(p1, inst(p1));
		d.values.host = ' 192.168.1.50 ';
		d.values.warn_w = '2500';
		expect(settingsOf(p1, d)).toEqual({ host: '192.168.1.50', warn_w: 2500 });
		d.values.max_w = '';
		d.values.style = 'gauge';
		expect(settingsOf(p1, d)).toEqual({ host: '192.168.1.50', warn_w: 2500, style: 'gauge' });
	});

	it('sends one entity id, or a list for a multiple entity', () => {
		const d = draftOf(energy, inst(energy));
		d.values.grid = ['sensor.grid'];
		d.values.solar = ['sensor.a', ' ', 'sensor.b'];
		expect(settingsOf(energy, d)).toMatchObject({ grid: 'sensor.grid', solar: ['sensor.a', 'sensor.b'] });
	});

	it('keeps secrets out of settings and sends only the changed ones', () => {
		const d = draftOf(energy, inst(energy, { secrets_set: ['token'] }));
		expect(secretsPatch(d)).toBeUndefined();
		d.secrets.token = { ...d.secrets.token!, action: 'set', value: 'abc' };
		expect(secretsPatch(d)).toEqual({ token: 'abc' });
		d.secrets.token = { ...d.secrets.token!, action: 'remove', value: '' };
		expect(secretsPatch(d)).toEqual({ token: null });
		d.secrets.token = { ...d.secrets.token!, action: 'set', value: '  ' };
		expect(secretsPatch(d)).toBeUndefined();
	});

	it('builds the instance to send without secrets_set', () => {
		const stored = inst(energy, { order: 1, secrets_set: ['token'], settings: settings({ grid: 'sensor.old', gone: 1 }) });
		const d = draftOf(energy, stored);
		d.values.grid = ['sensor.grid'];
		d.slot = 'top-left';
		d.secrets.token = { isSet: true, action: 'set', value: 'new' };
		expect(instanceFrom(energy, stored, d, false)).toEqual({
			instance_id: 'p-abcdefghij',
			plugin_id: 'kinframe.ha-energy',
			version: '1.0.0',
			slot: 'top-left',
			order: 1,
			visibility: 'always',
			enabled: false,
			settings: { grid: 'sensor.grid' },
			secrets: { token: 'new' }
		});
	});

	it('checks numbers, colours, addresses and choices', () => {
		const m: Manifest = {
			id: 'x.y',
			name: 'x',
			version: '1.0.0',
			settings: [
				{ key: 'n', type: 'number', label: 'n' },
				{ key: 'c', type: 'color', label: 'c' },
				{ key: 'u', type: 'url', label: 'u' },
				{ key: 'o', type: 'choice', label: 'o', options: ['a'] }
			]
		};
		const d = draftOf(m, inst(m));
		d.values = { n: '1,5', c: '#ff8800', u: '192.168.1.50:8080', o: 'a' };
		expect(fieldProblems(m, d)).toEqual({});
		expect(settingsOf(m, d)).toMatchObject({ n: 1.5 });
		d.values = { n: 'abc', c: 'red', u: 'ftp://x', o: 'b' };
		expect(fieldProblems(m, d)).toEqual({ n: 'number', c: 'color', u: 'url', o: 'enum' });
	});

	it('takes http(s) addresses and bare hosts as addresses', () => {
		for (const ok of ['http://homeassistant.local:8123', 'https://ha.example.org', '192.168.1.50', 'p1meter.local', 'host:80', '[fe80::1]:8123'])
			expect(validUrl(ok), ok).toBe(true);
		for (const bad of ['ftp://x', 'http://', 'two words', 'a/b']) expect(validUrl(bad), bad).toBe(false);
	});
});

describe('required check (same as the server)', () => {
	it('needs every required setting without a default, and secrets set', () => {
		const d = draftOf(energy, inst(energy));
		expect(missingRequired(energy, d)).toEqual(['base_url', 'token', 'grid']);
		d.values.base_url = 'http://ha.local:8123';
		d.values.grid = ['sensor.grid'];
		d.secrets.token = { isSet: false, action: 'set', value: 'abc' };
		expect(missingRequired(energy, d)).toEqual([]);
		d.secrets.token = { isSet: true, action: 'remove', value: '' };
		expect(missingRequired(energy, d)).toEqual(['token']);
	});

	it('is only shown for an enabled instance', () => {
		const d = draftOf(irail, inst(irail));
		expect(fieldProblems(irail, d, false)).toEqual({});
		expect(fieldProblems(irail, d, true)).toEqual({ station: 'required' });
	});

	it('tells whether a stored instance can be switched on', () => {
		expect(instanceComplete(p1, inst(p1))).toBe(false);
		expect(instanceComplete(p1, inst(p1, { settings: settings({ host: '192.168.1.50' }) }))).toBe(true);
		expect(instanceComplete(energy, inst(energy, { settings: settings({ base_url: 'http://ha', grid: 's.g' }) }))).toBe(false);
		expect(instanceComplete(energy, inst(energy, { settings: settings({ base_url: 'http://ha', grid: 's.g' }), secrets_set: ['token'] }))).toBe(true);
		expect(instanceComplete(air, inst(air))).toBe(true);
	});
});

describe('presets', () => {
	const kia = ev.presets!.find((p) => p.id === 'kia_hyundai')!;

	it('names the text settings its placeholders need', () => {
		expect(presetInputs(kia)).toEqual(['prefix']);
	});

	it('needs its inputs first, then fills in its values, overwriting', () => {
		const d = draftOf(ev, inst(ev));
		d.values.range = ['sensor.mine'];
		expect(applyPreset(ev, 'kia_hyundai', d)).toBeNull();
		d.values.prefix = '  ev3 ';
		const out = applyPreset(ev, 'kia_hyundai', d)!;
		expect(out.values.battery_level).toEqual(['sensor.ev3_ev_battery_level']);
		expect(out.values.range).toEqual(['sensor.ev3_ev_range']);
		expect(out.values.charging).toEqual(['binary_sensor.ev3_ev_battery_charge']);
		expect(settingsOf(ev, out)).toMatchObject({ prefix: 'ev3', updated: 'sensor.ev3_last_updated_at' });
		expect(d.values.range).toEqual(['sensor.mine']);
		expect(applyPreset(ev, 'nope', d)).toBeNull();
	});

	it('handles numbers, choices and lists', () => {
		const m: Manifest = {
			...energy,
			presets: [{ id: 'x', label: 'X', settings: { solar: ['sensor.{p}_a', 'sensor.{p}_b'], style: 'rows' } }],
			settings: [...energy.settings!, { key: 'p', type: 'text', label: 'p' }]
		};
		const d = draftOf(m, inst(m));
		d.values.p = 'q';
		const out = applyPreset(m, 'x', d)!;
		expect(out.values.solar).toEqual(['sensor.q_a', 'sensor.q_b']);
		expect(settingsOf(m, out)).toMatchObject({ solar: ['sensor.q_a', 'sensor.q_b'], style: 'rows' });
	});
});

describe('discovery', () => {
	const entities = [
		{ entity_id: 'sensor.solar_power', name: 'Solar power', unit: 'W' },
		{ entity_id: 'sensor.grid_power', name: 'Grid power', unit: 'W' },
		{ entity_id: 'sensor.grid_power_kw', name: 'Grid power kW', unit: 'kW' },
		{ entity_id: 'sensor.battery', name: 'Battery', unit: '%' },
		{ entity_id: 'binary_sensor.ev_charging', name: 'Charging' }
	];

	it('filters entities by domain, unit and every word typed', () => {
		expect(filterEntities(entities, spec(energy, 'grid')).map((e) => e.entity_id)).toEqual(['sensor.grid_power', 'sensor.solar_power']);
		expect(filterEntities(entities, spec(energy, 'grid'), 'GRID pow').map((e) => e.entity_id)).toEqual(['sensor.grid_power']);
		expect(filterEntities(entities, spec(energy, 'home')).length).toBe(4);
		expect(filterEntities(entities, spec(ev, 'charging')).map((e) => e.entity_id)).toEqual(['binary_sensor.ev_charging']);
		expect(filterEntities(undefined, spec(ev, 'charging'))).toEqual([]);
	});

	it('suggests what the frame found for a url setting with discover', () => {
		const fp = { catalog: [], services: { '_hwenergy._tcp': [{ name: 'P1 meter', url: 'http://192.168.1.20' }] } } as FramePlugins;
		expect(suggestions(fp, spec(p1, 'host'))).toEqual([{ name: 'P1 meter', url: 'http://192.168.1.20' }]);
		expect(suggestions(fp, spec(energy, 'base_url'))).toEqual([]);
		expect(suggestions(fp, spec(irail, 'station'))).toEqual([]);
	});

	it('gives the status line', () => {
		const ok = inst(p1, { settings: settings({ host: '192.168.1.50' }) });
		expect(statusKind(undefined, ok, undefined)).toBe('not_on_frame');
		expect(statusKind(p1, ok, undefined)).toBe('waiting');
		expect(statusKind(p1, ok, { instance_id: ok.instance_id, running: true })).toBe('running');
		expect(statusKind(p1, ok, { instance_id: ok.instance_id, running: true, error: 'Meter not reachable' })).toBe('error');
		expect(statusKind(p1, { ...ok, enabled: false }, undefined)).toBe('off');
		expect(statusKind(p1, inst(p1, { enabled: false }), undefined)).toBe('incomplete');
	});
});

describe('saving', () => {
	const a = inst(p1, { instance_id: 'p-a', secrets_set: [] });
	const b = inst(energy, { instance_id: 'p-b', secrets_set: ['token'] });

	it('sends the whole list, without secrets_set, with secrets only on the changed instance', () => {
		const changed = { ...b, secrets: { token: 'x' }, enabled: false };
		const { instances, index } = applyInstanceOp([a, b], { kind: 'upsert', instance: changed });
		expect(index).toBe(1);
		expect(instances.map((x) => x.instance_id)).toEqual(['p-a', 'p-b']);
		expect(instances.every((x) => !('secrets_set' in x))).toBe(true);
		expect(instances[0]).not.toHaveProperty('secrets');
		expect(instances[1]).toMatchObject({ enabled: false, secrets: { token: 'x' } });
		expect(instancesPatch(instances)).toEqual({ plugins: { instances } });
	});

	it('appends a new instance and removes one', () => {
		const c = inst(irail, { instance_id: 'p-c' });
		expect(applyInstanceOp([a, b], { kind: 'upsert', instance: c })).toMatchObject({ index: 2 });
		const r = applyInstanceOp([a, b, { ...c, secrets: { x: 'y' } }], { kind: 'remove', id: 'p-b' });
		expect(r.index).toBe(-1);
		expect(r.instances.map((x) => x.instance_id)).toEqual(['p-a', 'p-c']);
		expect(r.instances[1]).not.toHaveProperty('secrets');
	});

	it('maps 422 fields back to the form of the instance sent', () => {
		const m = mapErrors(
			[
				{ field: 'plugins.instances.1.settings.grid', code: 'required' },
				{ field: 'plugins.instances.1.secrets.token', code: 'required' },
				{ field: 'plugins.instances.1.slot', code: 'enum' },
				{ field: 'plugins.instances.0.plugin_id', code: 'not_on_frame' },
				{ field: 'plugins.layout.mode', code: 'enum' }
			],
			1
		);
		expect(m.fields).toEqual({ grid: 'required', token: 'required' });
		expect(m.instance).toEqual({ slot: 'enum' });
		expect(m.other).toHaveLength(2);
		expect(mapErrors(undefined, 0)).toEqual({ fields: {}, instance: {}, other: [] });
	});
});

describe('timezone settings', () => {
	const clock: Manifest = {
		id: 'x.clock',
		name: 'Clock',
		version: '1.0.0',
		settings: [
			{ key: 'city_1', type: 'timezone', label: 'City 1', required: true },
			{ key: 'city_2', type: 'timezone', label: 'City 2' }
		]
	};
	const inst = (settings: Record<string, unknown>): PluginInstance => ({ ...newInstance(clock, []), settings: settings as PluginInstance['settings'] });
	const july = new Date('2026-07-01T12:00:00Z');

	it('checks that a value is a known zone; empty is fine unless required', () => {
		expect(fieldProblems(clock, draftOf(clock, inst({})), false)).toEqual({});
		expect(fieldProblems(clock, draftOf(clock, inst({})), true)).toEqual({ city_1: 'required' });
		expect(fieldProblems(clock, draftOf(clock, inst({ city_1: 'Asia/Tokyo', city_2: 'Europe/Brussels' })), true)).toEqual({});
		expect(fieldProblems(clock, draftOf(clock, inst({ city_2: 'Mars/Olympus_Mons' })), false)).toEqual({ city_2: 'timezone' });
		expect(fieldProblems(clock, draftOf(clock, inst({ city_2: '+01:00' })), false)).toEqual({ city_2: 'timezone' });
		expect(settingsOf(clock, draftOf(clock, inst({ city_1: ' Asia/Tokyo ', city_2: '' })))).toEqual({ city_1: 'Asia/Tokyo' });
		expect(knownTimeZone('UTC')).toBe(true);
		expect(knownTimeZone('Asia/Calcutta')).toBe(true);
		expect(knownTimeZone('')).toBe(false);
	});

	it('shows a zone as its city, with region and UTC offset', () => {
		expect(timeZoneOption('America/New_York', july)).toEqual({
			id: 'America/New_York',
			city: 'New York',
			region: 'America',
			offset: 'UTC\u22124',
			detail: 'America · UTC\u22124'
		});
		expect(timeZoneOption('America/Argentina/Buenos_Aires', july).region).toBe('America / Argentina');
		expect(timeZoneOption('Asia/Kolkata', july).offset).toBe('UTC+5:30');
		expect(timeZoneOption('Asia/Kathmandu', july).offset).toBe('UTC+5:45');
		expect(timeZoneOption('Europe/Brussels', new Date('2026-01-15T12:00:00Z')).offset).toBe('UTC+1');
		expect(timeZoneOption('Pacific/Chatham', new Date('2026-01-15T12:00:00Z')).offset).toBe('UTC+13:45');
		expect(formatUtcOffset(0)).toBe('UTC');
		expect(formatUtcOffset(-210)).toBe('UTC\u22123:30');
		// An id the browser doesn't know still shows.
		expect(timeZoneOption('Mars/Olympus_Mons', july)).toMatchObject({ city: 'Olympus Mons', region: 'Mars', offset: '', detail: 'Mars' });
	});

	it('lists region zones under today’s names, with a fallback', () => {
		const ids = timeZoneIds();
		expect(ids).toContain('Europe/Brussels');
		expect(ids).toContain('Asia/Kolkata');
		expect(ids).not.toContain('Asia/Calcutta');
		expect(ids.every((id) => id.includes('/') && !id.startsWith('Etc/'))).toBe(true);
		expect(timeZoneIds(() => ['UTC', 'Etc/GMT+5', 'Asia/Calcutta', 'Asia/Kolkata', 'Europe/Paris'])).toEqual(['Asia/Kolkata', 'Europe/Paris']);
		const fallback = timeZoneIds(() => {
			throw new TypeError('not supported');
		});
		expect(fallback).toContain('Asia/Tokyo');
		expect(fallback.length).toBeGreaterThan(20);
	});

	it('searches by city, region or id', () => {
		const all = timeZoneOptions(july);
		expect(all.length).toBe(timeZoneIds().length);
		expect(all[0]!.city <= all[1]!.city).toBe(true);
		expect(filterTimeZones(all, 'new york').map((o) => o.id)).toEqual(['America/New_York']);
		expect(filterTimeZones(all, 'NEW_YORK').map((o) => o.id)).toEqual(['America/New_York']);
		expect(filterTimeZones(all, 'tokyo').map((o) => o.id)).toEqual(['Asia/Tokyo']);
		expect(filterTimeZones(all, 'kolkata').map((o) => o.id)).toEqual(['Asia/Kolkata']);
		expect(filterTimeZones(all, 'australia').length).toBeGreaterThan(3);
		expect(filterTimeZones(all, 'america/argentina').every((o) => o.id.startsWith('America/Argentina/'))).toBe(true);
		expect(filterTimeZones(all, '  ')).toBe(all);
	});
});

describe('community plugins (registry, §7)', () => {
	const socket = manifest('kinframe.homewizard-socket');
	const bins = manifest('examples/community.bin-calendar');
	const community = (m: Manifest, extra: Partial<Manifest> = {}): CatalogPlugin =>
		({ manifest: { ...m, ...extra }, builtin: false }) as unknown as CatalogPlugin;
	const builtin = (m: Manifest): CatalogPlugin => ({ manifest: m, builtin: true }) as unknown as CatalogPlugin;

	it('tells registry plugins from built-in ones and groups the catalog, built-in first, by name', () => {
		const old = { manifest: energy } as unknown as CatalogPlugin; // an older frame that doesn't say
		expect(isCommunity(old)).toBe(false);
		expect(isCommunity(builtin(p1))).toBe(false);
		expect(isCommunity(community(air))).toBe(true);
		const g = catalogGroups([community(irail), builtin(p1), community(air), old], 'en');
		expect(g.builtin.map((p) => (p.manifest as { id: string }).id)).toEqual(['kinframe.ha-energy', 'kinframe.homewizard-p1']);
		expect(g.community.map((p) => (p.manifest as { id: string }).id)).toEqual(['community.air-quality', 'community.irail-departures']);
		expect(catalogGroups(undefined, 'en')).toEqual({ builtin: [], community: [] });
	});

	it('shows author, licence and version', () => {
		expect(communityMeta({ ...bins, author: { name: 'Jane Doe', url: 'https://x' }, license: 'MIT', version: '1.2.0' })).toEqual({
			author: 'Jane Doe',
			license: 'MIT',
			version: '1.2.0'
		});
		expect(communityMeta({ id: 'x', name: 'X', version: '' })).toEqual({ author: undefined, license: undefined, version: undefined });
	});

	it('lists what a plugin may do: hosts, the addresses typed in, location, buttons', () => {
		expect(permissionsOf(air.permissions)).toEqual([{ kind: 'internet', hosts: ['air-quality-api.open-meteo.com'] }, { kind: 'location' }]);
		expect(permissionsOf(bins.permissions)).toEqual([{ kind: 'addresses', settings: ['calendar_url'] }]);
		expect(permissionsOf(socket.permissions)).toEqual([{ kind: 'addresses', settings: ['host'] }, { kind: 'actions' }]);
		expect(permissionsOf({ http: ['settings.base_url', 'https://api.example.org/v1'], websocket: ['settings.base_url', 'wss://push.example.org'] })).toEqual([
			{ kind: 'internet', hosts: ['api.example.org', 'push.example.org'] },
			{ kind: 'addresses', settings: ['base_url'] }
		]);
		expect(permissionsOf(undefined)).toEqual([]);
	});

	it('diffs the permissions of two versions', () => {
		const from = { http: ['settings.calendar_url', 'https://a.example.org'] };
		const to = { http: ['settings.calendar_url', 'https://a.example.org', 'https://b.example.org'], location: true, actions: true };
		expect(permissionChanges(from, to)).toEqual({
			added: [{ kind: 'internet', hosts: ['b.example.org'] }, { kind: 'location' }, { kind: 'actions' }],
			removed: []
		});
		expect(permissionChanges(to, from)).toEqual({
			added: [],
			removed: [{ kind: 'internet', hosts: ['b.example.org'] }, { kind: 'location' }, { kind: 'actions' }]
		});
		expect(permissionChanges(from, { http: ['settings.url'] })).toEqual({
			added: [{ kind: 'addresses', settings: ['url'] }],
			removed: [{ kind: 'internet', hosts: ['a.example.org'] }, { kind: 'addresses', settings: ['calendar_url'] }]
		});
	});

	it('gives a new instance of a registry plugin the catalog version, like a built-in one', () => {
		const m = { ...bins, version: '1.1.0' };
		expect(newInstance(m, []).version).toBe('1.1.0');
		expect(newInstance(p1, []).version).toBe(p1.version);
	});

	it('keeps the approved version when a registry instance is edited; a built-in one takes the manifest’s', () => {
		const m = { ...bins, version: '1.3.0' };
		const stored = inst(m, { version: '1.0.0', settings: settings({ calendar_url: 'https://cal.example.org/a.ics' }) });
		expect(instanceFrom(m, stored, draftOf(m, stored), true, true).version).toBe('1.0.0');
		expect(instanceFrom(m, { ...stored, version: undefined }, draftOf(m, stored), true, true).version).toBe('1.3.0');
		const b = inst(p1, { version: '0.9.0' });
		expect(instanceFrom(p1, b, draftOf(p1, b)).version).toBe(p1.version);
	});

	describe('an update that asks for other permissions', () => {
		const v1 = community(bins, { version: '1.0.0' });
		const stored = inst(bins, { version: '1.0.0' });
		const status = (extra: object = {}) => ({
			instance_id: stored.instance_id,
			running: true,
			version: '1.0.0',
			update: { version: '1.2.0', permissions: { http: ['settings.calendar_url', 'https://holidays.example.org'], location: true } },
			...extra
		});

		it('lists what changed when the catalog holds the version the frame runs', () => {
			expect(pendingUpdate(v1, stored, status())).toEqual({
				version: '1.2.0',
				asks: [{ kind: 'internet', hosts: ['holidays.example.org'] }, { kind: 'location' }],
				diffed: true,
				fewer: false
			});
		});

		it('else lists everything the new version may do', () => {
			const newest = community(bins, { version: '1.2.0' });
			expect(pendingUpdate(newest, stored, status())).toEqual({
				version: '1.2.0',
				asks: [{ kind: 'internet', hosts: ['holidays.example.org'] }, { kind: 'addresses', settings: ['calendar_url'] }, { kind: 'location' }],
				diffed: false,
				fewer: false
			});
			// The frame runs 1.1.0 (same permissions as 1.0.0) while the catalog says 1.0.0: no diff either.
			expect(pendingUpdate(v1, stored, status({ version: '1.1.0' }))?.diffed).toBe(false);
		});

		it('says when it only asks for less', () => {
			const u = pendingUpdate(v1, stored, status({ update: { version: '1.2.0', permissions: {} } }));
			expect(u).toMatchObject({ asks: [], diffed: true, fewer: true });
		});

		it('is gone once the instance names that version, or without an update', () => {
			expect(pendingUpdate(v1, { ...stored, version: '1.2.0' }, status())).toBeNull();
			expect(pendingUpdate(v1, stored, status({ update: null }))).toBeNull();
			expect(pendingUpdate(v1, stored, undefined)).toBeNull();
		});

		it('is approved by saving the instance with that version, in the whole list', () => {
			const other = inst(p1, { instance_id: 'p-other', secrets_set: [] });
			const approved = approveUpdate({ ...stored, secrets_set: ['x'] }, '1.2.0');
			expect(approved).toMatchObject({ instance_id: stored.instance_id, version: '1.2.0', settings: {} });
			const { instances, index } = applyInstanceOp([other, { ...stored, secrets_set: ['x'] }], { kind: 'upsert', instance: approved });
			expect(index).toBe(1);
			expect(instances).toEqual([
				{ instance_id: 'p-other', plugin_id: p1.id, slot: 'top-right', visibility: 'always', enabled: true, settings: {} },
				{ instance_id: stored.instance_id, plugin_id: bins.id, slot: 'top-right', visibility: 'always', enabled: true, settings: {}, version: '1.2.0' }
			]);
		});
	});

	it('reads a withdrawn plugin from the frame’s error', () => {
		const st = (error: string | null) => ({ instance_id: 'p-x', running: false, error });
		expect(withdrawnReason(st('withdrawn: sent the token to the wrong host'))).toBe('sent the token to the wrong host');
		expect(withdrawnReason(st('Meter not reachable'))).toBeNull();
		expect(withdrawnReason(st(null))).toBeNull();
		expect(statusKind(undefined, inst(bins), st('withdrawn: broken'))).toBe('withdrawn');
		expect(statusKind(bins, inst(bins), st('withdrawn: broken'))).toBe('withdrawn');
	});
});
