// The Plugins section of the frame settings (docs/plugins.md §6, §6.1, §6.2): the form logic, kept free of
// Svelte so it can be tested against the real manifests in plugins/.

import type { Schemas } from './api/client';

export type PluginInstance = Schemas['PluginInstance'];
export type CatalogPlugin = Schemas['CatalogPlugin'];
export type FramePlugins = Schemas['FramePlugins'];
export type InstanceStatus = Schemas['PluginInstanceStatus'];
export type DiscoveredEntity = Schemas['DiscoveredEntity'];
export type DiscoveredService = Schemas['DiscoveredService'];

export type Slot = 'top-left' | 'top-right';
export type Visibility = 'always' | 'on_change' | 'on_tap';
export type SettingType = 'text' | 'number' | 'boolean' | 'choice' | 'url' | 'secret' | 'entity' | 'color' | 'duration' | 'timezone';
type Json = string | number | boolean | null | Json[] | { [k: string]: Json };

export const SLOTS: readonly Slot[] = ['top-left', 'top-right'];
export const VISIBILITIES: readonly Visibility[] = ['always', 'on_change', 'on_tap'];
/** §6.1: at most 8 instances per frame. */
export const MAX_INSTANCES = 8;
/** §6.1: a choice with up to this many options is a segmented control, else a menu. */
export const SEGMENTED_MAX = 4;

export interface SettingSpec {
	key: string;
	type: SettingType;
	label: string;
	help?: string;
	placeholder?: string;
	required?: boolean;
	default?: Json;
	options?: string[];
	domain?: string;
	unit?: string;
	multiple?: boolean;
	discover?: string;
	local_only?: boolean;
}

export interface Preset {
	id: string;
	label: string;
	settings: Record<string, Json>;
}

/** A manifest's `permissions` (docs/plugins.md §3.1); network entries are fixed origins or `settings.<key>`. */
export interface Permissions {
	http?: string[];
	websocket?: string[];
	location?: boolean;
	actions?: boolean;
}

export interface Manifest {
	id: string;
	name: string;
	version: string;
	author?: { name?: string; url?: string };
	license?: string;
	description?: string;
	slots?: Slot[];
	permissions?: Permissions;
	settings?: SettingSpec[];
	presets?: Preset[];
	default_visibility?: Visibility;
}

/** The contract types `manifest` and `settings` as plain objects; this is what they hold. */
export function manifestOf(p: CatalogPlugin): Manifest {
	return p.manifest as unknown as Manifest;
}

export function slotsOf(m: Manifest): Slot[] {
	const s = (m.slots ?? []).filter((x): x is Slot => SLOTS.includes(x));
	return s.length ? s : [...SLOTS];
}

export function findPlugin(catalog: CatalogPlugin[] | undefined, pluginId: string): CatalogPlugin | undefined {
	return catalog?.find((p) => manifestOf(p).id === pluginId);
}

// ------------------------------------------------------------------ texts (§6.1 "Manifest translations")

/**
 * A translated manifest text: the app's language, then its base language (`zh` for `zh-TW`), then the manifest's own
 * (English) text, then `locales.en` (where English option labels live), then `fallback`.
 */
function text(p: CatalogPlugin, locale: string, key: string, manifestText: string | undefined, fallback: string): string {
	const l = p.locales ?? {};
	const base = locale.split('-')[0] ?? locale;
	return l[locale]?.[key] || l[base]?.[key] || manifestText || l.en?.[key] || fallback;
}

export function pluginName(p: CatalogPlugin, locale: string): string {
	const m = manifestOf(p);
	return text(p, locale, '@name', m.name, m.id);
}

export function pluginDescription(p: CatalogPlugin, locale: string): string {
	return text(p, locale, '@description', manifestOf(p).description, '');
}

export function settingLabel(p: CatalogPlugin, s: SettingSpec, locale: string): string {
	return text(p, locale, `@setting.${s.key}`, s.label, s.key);
}

export function settingHelp(p: CatalogPlugin, s: SettingSpec, locale: string): string {
	return text(p, locale, `@setting.${s.key}.help`, s.help, '');
}

export function settingPlaceholder(p: CatalogPlugin, s: SettingSpec, locale: string): string {
	return text(p, locale, `@setting.${s.key}.placeholder`, s.placeholder, '');
}

export function optionLabel(p: CatalogPlugin, key: string, value: string, locale: string): string {
	return text(p, locale, `@option.${key}.${value}`, undefined, value);
}

export function presetLabel(p: CatalogPlugin, preset: Preset, locale: string): string {
	return text(p, locale, `@preset.${preset.id}`, preset.label, preset.id);
}

/** The fixed internet hosts the plugin may reach (its `permissions.http` origins, not `settings.*`). */
export function internetHosts(m: Manifest): string[] {
	return fixedHosts(m.permissions?.http);
}

function fixedHosts(entries: readonly string[] | undefined): string[] {
	const out: string[] = [];
	for (const e of entries ?? []) {
		if (typeof e !== 'string' || e.startsWith('settings.')) continue;
		let host = e;
		try {
			host = new URL(e).host;
		} catch {
			/* keep as written */
		}
		if (!out.includes(host)) out.push(host);
	}
	return out;
}

export function usesLocation(m: Manifest): boolean {
	return m.permissions?.location === true;
}

// ------------------------------------------------------------------ community plugins (§7)

/** A plugin from the community registry (`builtin: false`); an entry that doesn't say is the frame's own. */
export function isCommunity(p: CatalogPlugin): boolean {
	return p.builtin === false;
}

/** The catalog as "Add plugin" shows it: the built-in plugins first, then the community's, each by name. */
export function catalogGroups(catalog: CatalogPlugin[] | undefined, locale: string, tag: string = locale): { builtin: CatalogPlugin[]; community: CatalogPlugin[] } {
	const sorted = [...(catalog ?? [])].sort((a, b) => pluginName(a, locale).localeCompare(pluginName(b, locale), tag));
	return { builtin: sorted.filter((p) => !isCommunity(p)), community: sorted.filter(isCommunity) };
}

/** One thing a plugin may do, as the owner reads it before adding a plugin or approving an update. */
export type Permission =
	| { kind: 'internet'; hosts: string[] }
	| { kind: 'addresses'; settings: string[] }
	| { kind: 'location' }
	| { kind: 'actions' };

/** The `settings.<key>` entries of the network permissions: the addresses the owner types in. */
function addressSettings(p: Permissions | undefined): string[] {
	const out: string[] = [];
	for (const e of [...(p?.http ?? []), ...(p?.websocket ?? [])]) {
		if (typeof e === 'string' && e.startsWith('settings.')) {
			const k = e.slice('settings.'.length);
			if (k && !out.includes(k)) out.push(k);
		}
	}
	return out;
}

function hostsOf(p: Permissions | undefined): string[] {
	return fixedHosts([...(p?.http ?? []), ...(p?.websocket ?? [])]);
}

function permissionList(hosts: string[], settings: string[], location: boolean, actions: boolean): Permission[] {
	const out: Permission[] = [];
	if (hosts.length) out.push({ kind: 'internet', hosts });
	if (settings.length) out.push({ kind: 'addresses', settings });
	if (location) out.push({ kind: 'location' });
	if (actions) out.push({ kind: 'actions' });
	return out;
}

/** Everything `p` allows: the internet hosts it reaches, the addresses the owner types in, location, buttons. */
export function permissionsOf(p: Permissions | undefined): Permission[] {
	return permissionList(hostsOf(p), addressSettings(p), p?.location === true, p?.actions === true);
}

/** What `to` allows that `from` didn't (`added`), and what it no longer does (`removed`). */
export function permissionChanges(from: Permissions | undefined, to: Permissions | undefined): { added: Permission[]; removed: Permission[] } {
	const diff = (a: Permissions | undefined, b: Permissions | undefined) => {
		const hostsA = hostsOf(a);
		const setA = addressSettings(a);
		return permissionList(
			hostsOf(b).filter((h) => !hostsA.includes(h)),
			addressSettings(b).filter((k) => !setA.includes(k)),
			b?.location === true && a?.location !== true,
			b?.actions === true && a?.actions !== true
		);
	};
	return { added: diff(from, to), removed: diff(to, from) };
}

export interface PendingUpdate {
	version: string;
	/** What the owner approves: the changes when the current version's manifest is known, else all of the new one's. */
	asks: Permission[];
	/** Whether [asks] are the changes (the catalog holds the version the frame runs). */
	diffed: boolean;
	/** Diffed, and the new version only drops permissions. */
	fewer: boolean;
}

/**
 * The newer registry version waiting for the owner's approval (§7 "On the frame"), or null. Once the instance names
 * that version (approved, the frame not caught up yet) there is nothing left to approve.
 */
export function pendingUpdate(plugin: CatalogPlugin | undefined, inst: PluginInstance, st: InstanceStatus | undefined): PendingUpdate | null {
	const u = st?.update;
	if (!u?.version || inst.version === u.version) return null;
	const next = u.permissions as Permissions | undefined;
	const current = st?.version || inst.version;
	const m = plugin ? manifestOf(plugin) : undefined;
	if (m && current && m.version === current) {
		const { added, removed } = permissionChanges(m.permissions, next);
		if (added.length || removed.length) return { version: u.version, asks: added, diffed: true, fewer: added.length === 0 };
	}
	return { version: u.version, asks: permissionsOf(next), diffed: false, fewer: false };
}

/** The instance with update `version` approved: the frame then runs it (saved like any other instance change). */
export function approveUpdate(inst: PluginInstance, version: string): PluginInstance {
	return { ...inst, version };
}

/** "by Jane Doe · MIT · version 1.2.0" for a community plugin: who made it, its licence and version. */
export function communityMeta(m: Manifest): { author?: string; license?: string; version?: string } {
	const author = typeof m.author?.name === 'string' && m.author.name.trim() ? m.author.name.trim() : undefined;
	const license = typeof m.license === 'string' && m.license.trim() ? m.license.trim() : undefined;
	const version = typeof m.version === 'string' && m.version ? m.version : undefined;
	return { author, license, version };
}

const WITHDRAWN = 'withdrawn:';

/** The registry's reason when the frame reports the instance's plugin as withdrawn (`withdrawn: …`), else null. */
export function withdrawnReason(st: InstanceStatus | undefined): string | null {
	const e = st?.error;
	if (!e || !e.toLowerCase().startsWith(WITHDRAWN)) return null;
	return e.slice(WITHDRAWN.length).trim();
}

// ------------------------------------------------------------------ new instances

const ID_CHARS = 'abcdefghijklmnopqrstuvwxyz0123456789';

/** `p-` and 10 random lowercase letters or digits (§6.1). */
export function newInstanceId(random: () => number = secureRandom): string {
	let s = 'p-';
	for (let i = 0; i < 10; i++) s += ID_CHARS[Math.floor(random() * ID_CHARS.length) % ID_CHARS.length];
	return s;
}

function secureRandom(): number {
	const a = new Uint32Array(1);
	globalThis.crypto.getRandomValues(a);
	return a[0]! / 2 ** 32;
}

/**
 * A new instance of `m` (§6.1): a fresh id, the top-right corner if allowed, the manifest's `default_visibility`, and
 * off until the owner saves a complete form. It goes after the instances already in its corner.
 */
export function newInstance(m: Manifest, existing: PluginInstance[], random?: () => number): PluginInstance {
	let id = newInstanceId(random);
	while (existing.some((x) => x.instance_id === id)) id = newInstanceId(random);
	const slots = slotsOf(m);
	const slot: Slot = slots.includes('top-right') ? 'top-right' : slots[0]!;
	const inSlot = existing.filter((x) => (x.slot ?? 'top-right') === slot);
	const order = inSlot.reduce((n, x) => Math.max(n, (x.order ?? 0) + 1), 0);
	return {
		instance_id: id,
		plugin_id: m.id,
		version: m.version,
		slot,
		order: Math.min(order, 99),
		visibility: m.default_visibility ?? 'always',
		enabled: false,
		settings: {}
	};
}

// ------------------------------------------------------------------ the form's values

/** What a field holds while editing: text for typed fields (numbers too), a boolean, or entity ids. */
export type FieldValue = string | boolean | string[];

export interface SecretDraft {
	/** The server has a value (`secrets_set`). */
	isSet: boolean;
	/** keep: send nothing; set: send `value`; remove: send null. */
	action: 'keep' | 'set' | 'remove';
	value: string;
}

export interface Draft {
	values: Record<string, FieldValue>;
	secrets: Record<string, SecretDraft>;
	slot: Slot;
	visibility: Visibility;
	enabled: boolean;
}

export function settingsSpecs(m: Manifest): SettingSpec[] {
	return m.settings ?? [];
}

function storedSettings(inst: PluginInstance): Record<string, Json> {
	return (inst.settings ?? {}) as unknown as Record<string, Json>;
}

/** A stored (or default, or preset) value as the field shows it. */
export function toField(s: SettingSpec, v: Json | undefined): FieldValue {
	if (s.type === 'boolean') return v === true;
	if (s.type === 'entity') {
		if (Array.isArray(v)) return v.filter((x): x is string => typeof x === 'string' && x.trim() !== '');
		return typeof v === 'string' && v.trim() !== '' ? [v] : [];
	}
	if (v == null || typeof v === 'object') return '';
	return String(v);
}

/** The form for an instance: stored values, else the manifest's defaults; secrets untouched. */
export function draftOf(m: Manifest, inst: PluginInstance): Draft {
	const stored = storedSettings(inst);
	const values: Record<string, FieldValue> = {};
	const secrets: Record<string, SecretDraft> = {};
	for (const s of settingsSpecs(m)) {
		if (s.type === 'secret') {
			secrets[s.key] = { isSet: (inst.secrets_set ?? []).includes(s.key), action: 'keep', value: '' };
		} else {
			values[s.key] = toField(s, stored[s.key] ?? s.default);
		}
	}
	const slots = slotsOf(m);
	const slot = inst.slot && slots.includes(inst.slot) ? inst.slot : slots.includes('top-right') ? 'top-right' : slots[0]!;
	return { values, secrets, slot, visibility: inst.visibility ?? 'always', enabled: inst.enabled ?? true };
}

/** A field's value as it goes in `settings`, or undefined when it is empty (left out) or not valid. */
export function fromField(s: SettingSpec, v: FieldValue | undefined): Json | undefined {
	if (v === undefined) return undefined;
	switch (s.type) {
		case 'boolean':
			return v === true;
		case 'entity': {
			const ids = (Array.isArray(v) ? v : typeof v === 'string' ? [v] : []).map((x) => x.trim()).filter(Boolean);
			if (ids.length === 0) return undefined;
			return s.multiple ? ids : ids[0];
		}
		case 'number': {
			const t = typeof v === 'string' ? v.trim().replace(',', '.') : '';
			if (t === '') return undefined;
			const n = Number(t);
			return Number.isFinite(n) ? n : undefined;
		}
		default: {
			const t = typeof v === 'string' ? v.trim() : '';
			return t === '' ? undefined : t;
		}
	}
}

const same = (a: Json | undefined, b: Json | undefined) => JSON.stringify(a) === JSON.stringify(b);

/**
 * The instance's `settings`: every non-secret field in manifest order, leaving out empty fields and those that still
 * hold the manifest's default (a default "shows as the field's value until changed"), so a later default reaches the
 * frame too. A switch without a default is left out when off.
 */
export function settingsOf(m: Manifest, d: Draft): Record<string, Json> {
	const out: Record<string, Json> = {};
	for (const s of settingsSpecs(m)) {
		if (s.type === 'secret') continue;
		const v = fromField(s, d.values[s.key]);
		if (v === undefined) continue;
		if (s.default !== undefined ? same(v, s.default) : s.type === 'boolean' && v === false) continue;
		out[s.key] = v;
	}
	return out;
}

/** Whether a secret has a value after saving: set and not removed, or being set. */
export function secretHasValue(d: SecretDraft | undefined): boolean {
	if (!d) return false;
	if (d.action === 'set' && d.value.trim() !== '') return true;
	if (d.action === 'remove') return false;
	return d.isSet;
}

/** The `secrets` write: only the secrets the owner changed; undefined when none. */
export function secretsPatch(d: Draft): Record<string, string | null> | undefined {
	const out: Record<string, string | null> = {};
	for (const [k, s] of Object.entries(d.secrets)) {
		if (s.action === 'set' && s.value.trim() !== '') out[k] = s.value.trim();
		else if (s.action === 'remove' && s.isSet) out[k] = null;
	}
	return Object.keys(out).length ? out : undefined;
}

function isEmpty(v: Json | undefined): boolean {
	return v == null || (typeof v === 'string' && v.trim() === '') || (Array.isArray(v) && v.length === 0);
}

/**
 * The settings an enabled instance still needs, as the server checks them (§6 "Checks"): every `required` setting
 * without a `default` filled in, secrets set (or being set).
 */
export function missingRequired(m: Manifest, d: Draft): string[] {
	const out: string[] = [];
	for (const s of settingsSpecs(m)) {
		if (!s.required || s.default != null) continue;
		if (s.type === 'secret' ? !secretHasValue(d.secrets[s.key]) : isEmpty(fromField(s, d.values[s.key]))) out.push(s.key);
	}
	return out;
}

/** Whether a stored instance could be switched on as it is. */
export function instanceComplete(m: Manifest, inst: PluginInstance): boolean {
	return missingRequired(m, draftOf(m, inst)).length === 0;
}

export type FieldProblem = 'required' | 'number' | 'color' | 'url' | 'enum' | 'timezone';

const COLOR = /^#[0-9a-fA-F]{6}$/;
const HOST = /^(\[[0-9a-fA-F:.]+\]|[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*\.?)(:\d{1,5})?$/;

/** An http(s) address, or a bare `host[:port]` (the HomeWizard plugins store `192.168.1.50`). */
export function validUrl(v: string): boolean {
	const t = v.trim();
	if (!t.includes('://')) return HOST.test(t);
	try {
		const u = new URL(t);
		return (u.protocol === 'http:' || u.protocol === 'https:') && u.host !== '';
	} catch {
		return false;
	}
}

/**
 * Problems the form can see before saving, by setting key: values that don't fit their type, and (for an instance
 * that is to be enabled) missing required ones.
 */
export function fieldProblems(m: Manifest, d: Draft, enabled: boolean = d.enabled): Record<string, FieldProblem> {
	const out: Record<string, FieldProblem> = {};
	for (const s of settingsSpecs(m)) {
		const raw = d.values[s.key];
		const t = typeof raw === 'string' ? raw.trim() : '';
		if (s.type === 'number' && t !== '' && fromField(s, raw) === undefined) out[s.key] = 'number';
		else if (s.type === 'color' && t !== '' && !COLOR.test(t)) out[s.key] = 'color';
		else if (s.type === 'url' && t !== '' && !validUrl(t)) out[s.key] = 'url';
		else if (s.type === 'choice' && t !== '' && !(s.options ?? []).includes(t)) out[s.key] = 'enum';
		else if (s.type === 'timezone' && t !== '' && !knownTimeZone(t)) out[s.key] = 'timezone';
	}
	if (enabled) for (const k of missingRequired(m, d)) out[k] ??= 'required';
	return out;
}

/**
 * The instance as it is sent: the stored one with the form applied, `secrets` only for changes, no `secrets_set`.
 * A built-in plugin's version is the manifest's (informational); a registry plugin keeps the version the owner
 * approved (`keepVersion`), so editing its settings never approves an update with other permissions on the side.
 */
export function instanceFrom(m: Manifest, inst: PluginInstance, d: Draft, enabled: boolean = d.enabled, keepVersion = false): PluginInstance {
	const { secrets_set: _s, secrets: _w, ...rest } = inst;
	const out: PluginInstance = {
		...rest,
		plugin_id: m.id,
		version: keepVersion && inst.version ? inst.version : m.version,
		slot: d.slot,
		visibility: d.visibility,
		enabled,
		settings: settingsOf(m, d) as unknown as PluginInstance['settings']
	};
	const secrets = secretsPatch(d);
	if (secrets) out.secrets = secrets;
	return out;
}

// ------------------------------------------------------------------ presets

const PLACEHOLDER = /\{([a-z][a-z0-9_]{0,31})\}/g;

/** The text settings a preset's `{placeholders}` need, in order of appearance. */
export function presetInputs(p: Preset): string[] {
	const out: string[] = [];
	const scan = (s: string) => {
		for (const m of s.matchAll(PLACEHOLDER)) if (!out.includes(m[1]!)) out.push(m[1]!);
	};
	for (const v of Object.values(p.settings)) {
		if (typeof v === 'string') scan(v);
		else if (Array.isArray(v)) for (const e of v) if (typeof e === 'string') scan(e);
	}
	return out;
}

/**
 * The form with preset `id` applied (its values set, overwriting, placeholders filled in from the form), or null while
 * a placeholder's setting is empty: the same rule as `PluginManifest.applyPreset` on the frame.
 */
export function applyPreset(m: Manifest, id: string, d: Draft): Draft | null {
	const p = m.presets?.find((x) => x.id === id);
	if (!p) return null;
	const inputs: Record<string, string> = {};
	for (const k of presetInputs(p)) {
		const v = d.values[k];
		inputs[k] = typeof v === 'string' ? v.trim() : '';
		if (!inputs[k]) return null;
	}
	const fill = (s: string) => s.replace(PLACEHOLDER, (_, k: string) => inputs[k] ?? '');
	const values = { ...d.values };
	for (const [k, v] of Object.entries(p.settings)) {
		const spec = settingsSpecs(m).find((s) => s.key === k);
		if (!spec || spec.type === 'secret') continue;
		const filled: Json = typeof v === 'string' ? fill(v) : Array.isArray(v) ? v.map((e) => (typeof e === 'string' ? fill(e) : e)) : v;
		values[k] = toField(spec, filled);
	}
	return { ...d, values };
}

// ------------------------------------------------------------------ time zones (`timezone` settings)

/** Used when the browser can't list its zones (`Intl.supportedValuesOf` is from 2022). */
const FALLBACK_ZONES = [
	'Africa/Cairo', 'Africa/Johannesburg', 'Africa/Lagos', 'Africa/Nairobi', 'America/Anchorage', 'America/Argentina/Buenos_Aires',
	'America/Bogota', 'America/Chicago', 'America/Denver', 'America/Halifax', 'America/Los_Angeles', 'America/Mexico_City',
	'America/New_York', 'America/Sao_Paulo', 'America/Toronto', 'Asia/Bangkok', 'Asia/Dubai', 'Asia/Hong_Kong', 'Asia/Jakarta',
	'Asia/Jerusalem', 'Asia/Kolkata', 'Asia/Manila', 'Asia/Seoul', 'Asia/Shanghai', 'Asia/Singapore', 'Asia/Tokyo',
	'Atlantic/Reykjavik', 'Australia/Perth', 'Australia/Sydney', 'Europe/Amsterdam', 'Europe/Athens', 'Europe/Berlin',
	'Europe/Brussels', 'Europe/Istanbul', 'Europe/Lisbon', 'Europe/London', 'Europe/Madrid', 'Europe/Moscow', 'Europe/Paris',
	'Europe/Rome', 'Europe/Stockholm', 'Europe/Warsaw', 'Pacific/Auckland', 'Pacific/Honolulu'
];

/**
 * Browsers list some zones by an old name (CLDR's canonical one, "Asia/Calcutta"); people look for today's, and the
 * frame knows both. Only renames from 2016 or before, so an older frame's time zone data has the new name too.
 */
const RENAMED: Record<string, string> = {
	'Africa/Asmera': 'Africa/Asmara',
	'America/Buenos_Aires': 'America/Argentina/Buenos_Aires',
	'America/Catamarca': 'America/Argentina/Catamarca',
	'America/Coral_Harbour': 'America/Atikokan',
	'America/Cordoba': 'America/Argentina/Cordoba',
	'America/Indianapolis': 'America/Indiana/Indianapolis',
	'America/Jujuy': 'America/Argentina/Jujuy',
	'America/Louisville': 'America/Kentucky/Louisville',
	'America/Mendoza': 'America/Argentina/Mendoza',
	'Asia/Calcutta': 'Asia/Kolkata',
	'Asia/Katmandu': 'Asia/Kathmandu',
	'Asia/Rangoon': 'Asia/Yangon',
	'Asia/Saigon': 'Asia/Ho_Chi_Minh',
	'Atlantic/Faeroe': 'Atlantic/Faroe',
	'Pacific/Ponape': 'Pacific/Pohnpei',
	'Pacific/Truk': 'Pacific/Chuuk'
};

/** A region id ("Europe/Brussels"), not `Etc/GMT+5`, `SystemV/…` or a bare alias ("EST", "UTC"). */
export function isRegionZone(id: string): boolean {
	return id.includes('/') && !id.startsWith('Etc/') && !id.startsWith('SystemV/');
}

const ZONE_ID = /^[A-Za-z][A-Za-z0-9_+-]*(\/[A-Za-z0-9_+-]+)*$/;
const formats = new Map<string, Intl.DateTimeFormat | null>();

/** The zone's calendar formatter (cached), or null when the browser doesn't know `id`. */
function zoneFormat(id: string): Intl.DateTimeFormat | null {
	let f = formats.get(id);
	if (f === undefined) {
		try {
			f = ZONE_ID.test(id)
				? new Intl.DateTimeFormat('en-US', {
						timeZone: id,
						hourCycle: 'h23',
						year: 'numeric',
						month: 'numeric',
						day: 'numeric',
						hour: 'numeric',
						minute: 'numeric',
						second: 'numeric'
					})
				: null;
		} catch {
			f = null;
		}
		formats.set(id, f);
	}
	return f;
}

/** Whether `id` is a time zone id the browser knows ("Asia/Tokyo", also "UTC" or an older name; not "+01:00"). */
export function knownTimeZone(id: string): boolean {
	return zoneFormat(id) !== null;
}

/** The zones a `timezone` setting offers, by id: the browser's region zones under today's names. */
export function timeZoneIds(list: () => string[] = () => Intl.supportedValuesOf('timeZone')): string[] {
	let ids: string[];
	try {
		ids = list();
	} catch {
		ids = [];
	}
	if (!ids.length) ids = FALLBACK_ZONES;
	const out = new Set<string>();
	for (const id of ids) {
		if (!isRegionZone(id)) continue;
		const now = RENAMED[id];
		out.add(now && knownTimeZone(now) ? now : id);
	}
	return [...out].sort();
}

/** "New York" for "America/New_York". */
export function zoneCity(id: string): string {
	return (id.split('/').pop() ?? id).replaceAll('_', ' ');
}

/** "America", "America / Argentina" for "America/Argentina/Buenos_Aires"; empty for "UTC". */
export function zoneRegion(id: string): string {
	return id
		.split('/')
		.slice(0, -1)
		.filter(Boolean)
		.map((p) => p.replaceAll('_', ' '))
		.join(' / ');
}

/** Minutes ahead of UTC in zone `id` at `at`, or null when the browser doesn't know it. */
export function zoneOffsetMinutes(id: string, at: Date = new Date()): number | null {
	const f = zoneFormat(id);
	if (!f) return null;
	const p: Record<string, number> = {};
	for (const x of f.formatToParts(at)) if (x.type !== 'literal') p[x.type] = Number(x.value);
	const wall = Date.UTC(p.year!, p.month! - 1, p.day!, p.hour! % 24, p.minute!, p.second!);
	const instant = Math.floor(at.getTime() / 1000) * 1000;
	return Math.round((wall - instant) / 60000);
}

/** "UTC−4" (a real minus sign), "UTC+5:30", "UTC" for no offset. */
export function formatUtcOffset(minutes: number): string {
	if (minutes === 0) return 'UTC';
	const abs = Math.abs(minutes);
	const h = Math.floor(abs / 60);
	const m = abs % 60;
	return `UTC${minutes < 0 ? '\u2212' : '+'}${h}${m ? `:${String(m).padStart(2, '0')}` : ''}`;
}

export interface TimeZoneOption {
	/** The IANA id, what is stored. */
	id: string;
	/** "New York" */
	city: string;
	/** "America" */
	region: string;
	/** "UTC−4"; empty for a zone the browser doesn't know. */
	offset: string;
	/** "America · UTC−4": the line under the city. */
	detail: string;
}

/** How a zone shows in the picker at `at`; an unknown id still gets its city and region. */
export function timeZoneOption(id: string, at: Date = new Date()): TimeZoneOption {
	const city = zoneCity(id);
	const region = zoneRegion(id);
	const mins = zoneOffsetMinutes(id, at);
	const offset = mins === null ? '' : formatUtcOffset(mins);
	return { id, city, region, offset, detail: [region, offset].filter(Boolean).join(' · ') };
}

/** Every offered zone at `at`, by city. */
export function timeZoneOptions(at: Date = new Date(), ids: string[] = timeZoneIds()): TimeZoneOption[] {
	return ids.map((id) => timeZoneOption(id, at)).sort((a, b) => a.city.localeCompare(b.city, 'en') || a.id.localeCompare(b.id));
}

/** The options with every word of `query` in their city, region or id ("new york", "tokyo", "america/"). */
export function filterTimeZones(options: TimeZoneOption[], query = ''): TimeZoneOption[] {
	const words = query.toLowerCase().split(/\s+/).filter(Boolean);
	if (!words.length) return options;
	return options.filter((o) => {
		const hay = `${o.city} ${o.region} ${o.id} ${o.id.replaceAll('_', ' ')}`.toLowerCase();
		return words.every((w) => hay.includes(w));
	});
}

// ------------------------------------------------------------------ discovery (§6.2)

/** Entities for an `entity` setting: its `domain` and `unit`, then every word of `query` in the name or id. */
export function filterEntities(entities: DiscoveredEntity[] | undefined, s: SettingSpec, query = ''): DiscoveredEntity[] {
	const words = query.toLowerCase().split(/\s+/).filter(Boolean);
	return (entities ?? [])
		.filter((e) => !s.domain || e.entity_id.split('.')[0] === s.domain)
		.filter((e) => !s.unit || e.unit === s.unit)
		.filter((e) => {
			const hay = `${e.name ?? ''} ${e.entity_id}`.toLowerCase();
			return words.every((w) => hay.includes(w));
		})
		.sort((a, b) => (a.name || a.entity_id).localeCompare(b.name || b.entity_id) || a.entity_id.localeCompare(b.entity_id));
}

/** What the frame found on its network for a `url` setting with `discover`. */
export function suggestions(fp: FramePlugins | null | undefined, s: SettingSpec): DiscoveredService[] {
	if (s.type !== 'url' || !s.discover) return [];
	return fp?.services?.[s.discover] ?? [];
}

export function statusOf(fp: FramePlugins | null | undefined, instanceId: string): InstanceStatus | undefined {
	return fp?.instances?.find((x) => x.instance_id === instanceId);
}

export type StatusKind = 'withdrawn' | 'not_on_frame' | 'incomplete' | 'off' | 'error' | 'waiting' | 'running';

/**
 * The instance list's status line (§6.1): withdrawn from the registry (§7), its error, "Waiting for the frame" before
 * its first report, else nothing.
 */
export function statusKind(m: Manifest | undefined, inst: PluginInstance, st: InstanceStatus | undefined): StatusKind {
	if (withdrawnReason(st) !== null) return 'withdrawn';
	if (!m) return 'not_on_frame';
	if (inst.enabled === false) return instanceComplete(m, inst) ? 'off' : 'incomplete';
	if (st?.error) return 'error';
	if (!st || (!st.running && !st.updated_at)) return 'waiting';
	return 'running';
}

// ------------------------------------------------------------------ saving

export type InstanceOp = { kind: 'upsert'; instance: PluginInstance } | { kind: 'remove'; id: string };

/** An instance as it goes in a patch: never `secrets_set`; `secrets` only where the op wrote them. */
function wire(inst: PluginInstance, keepSecrets: boolean): PluginInstance {
	const { secrets_set: _s, secrets, ...rest } = inst;
	return keepSecrets && secrets ? { ...rest, secrets } : rest;
}

/**
 * The whole `instances` array after `op` on `list` (the version read), and where the op's instance is in it (for
 * mapping 422 fields back; -1 for a removal). A patch replaces the array, so it is always sent in full.
 */
export function applyInstanceOp(list: PluginInstance[], op: InstanceOp): { instances: PluginInstance[]; index: number } {
	if (op.kind === 'remove') return { instances: list.filter((x) => x.instance_id !== op.id).map((x) => wire(x, false)), index: -1 };
	const out = list.map((x) => wire(x, false));
	const at = out.findIndex((x) => x.instance_id === op.instance.instance_id);
	const inst = wire(op.instance, true);
	if (at >= 0) out[at] = inst;
	else out.push(inst);
	return { instances: out, index: at >= 0 ? at : out.length - 1 };
}

/** The merge patch for the settings: `plugins.instances` whole. */
export function instancesPatch(instances: PluginInstance[]): { plugins: { instances: PluginInstance[] } } {
	return { plugins: { instances } };
}

export interface MappedErrors {
	/** Setting key (plain or secret) → problem code from the server. */
	fields: Record<string, string>;
	/** `slot`, `plugin_id`, `instance_id`… of this instance → code. */
	instance: Record<string, string>;
	/** Anything else (another instance, an unknown path). */
	other: { field?: string; code?: string }[];
}

const FIELD = /^plugins\.instances\.(\d+)\.(?:(settings|secrets)\.([^.]+)|([^.]+))$/;

/** Map a 422's `plugins.instances.N.settings.key` / `.secrets.key` fields back to the form of instance `index`. */
export function mapErrors(errors: { field?: string; code?: string }[] | undefined, index: number): MappedErrors {
	const out: MappedErrors = { fields: {}, instance: {}, other: [] };
	for (const e of errors ?? []) {
		const m = e.field ? FIELD.exec(e.field) : null;
		if (!m || Number(m[1]) !== index) {
			out.other.push(e);
			continue;
		}
		if (m[3]) out.fields[m[3]] = e.code ?? 'invalid';
		else out.instance[m[4]!] = e.code ?? 'invalid';
	}
	return out;
}
