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
export type SettingType = 'text' | 'number' | 'boolean' | 'choice' | 'url' | 'secret' | 'entity' | 'color' | 'duration';
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

export interface Manifest {
	id: string;
	name: string;
	version: string;
	description?: string;
	slots?: Slot[];
	permissions?: { http?: string[]; websocket?: string[]; location?: boolean };
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
	const out: string[] = [];
	for (const e of m.permissions?.http ?? []) {
		if (e.startsWith('settings.')) continue;
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

export type FieldProblem = 'required' | 'number' | 'color' | 'url' | 'enum';

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
	}
	if (enabled) for (const k of missingRequired(m, d)) out[k] ??= 'required';
	return out;
}

/** The instance as it is sent: the stored one with the form applied, `secrets` only for changes, no `secrets_set`. */
export function instanceFrom(m: Manifest, inst: PluginInstance, d: Draft, enabled: boolean = d.enabled): PluginInstance {
	const { secrets_set: _s, secrets: _w, ...rest } = inst;
	const out: PluginInstance = {
		...rest,
		plugin_id: m.id,
		version: m.version,
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

export type StatusKind = 'not_on_frame' | 'incomplete' | 'off' | 'error' | 'waiting' | 'running';

/** The instance list's status line (§6.1): its error, "Waiting for the frame" before its first report, else nothing. */
export function statusKind(m: Manifest | undefined, inst: PluginInstance, st: InstanceStatus | undefined): StatusKind {
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
