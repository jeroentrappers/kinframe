// Family social on the browser frame (docs/spec/family-social.md §8, §9.3; README §5.20): "Ask for new photos",
// "New photos from Ben", the comment card and the weekly prompt's title card. The same rules as the Android frame
// (SocialLogic), as pure functions plus a small store for what must survive a reload; the engine wires them to the
// change feed, the slideshow and the device API.
import type { components } from '../api/schema';
import type { FramePlacement, FrameSettings } from './feed';

type S = components['schemas'];
export type FramePhotoRequest = S['FramePhotoRequest'];
export type PlacementComments = S['PlacementComments'];
export type PlacementComment = PlacementComments['comments'][number];
export type FramePrompts = S['FramePrompts'];
export type FramePrompt = FramePrompts['prompts'][number];

/**
 * The social capabilities this browser frame lists in its heartbeat `features` (informational, README §5.20).
 * Not `feed_backfill` (the browser frame would celebrate history placements) and not `birthdays` (not shown here).
 */
export const SOCIAL_FEATURES = ['comments', 'photo_requests', 'prompts'] as const;

export const DAY_MS = 24 * 3_600_000;
/** How long the comment card and a prompt's title card stay (family-social.md §8). */
export const CARD_MS = 8_000;
/** How long "New photos from Ben" stays. */
export const NOTE_MS = 10_000;
/** The note waits this long after the wake (or the new photo) before it fades in. */
export const NOTE_DELAY_MS = 1_500;
/** How long the line after a tap on "Ask for new photos" stays. */
export const CONFIRMATION_MS = 6_000;
/** A server without "Ask for new photos" (404/501) is asked again after this long. */
export const UNAVAILABLE_HIDE_MS = 6 * 3_600_000;
/** At most this many names are remembered for the note. */
export const MAX_NAMES = 6;

export interface SocialSettings {
	showComments: boolean;
	askForPhotos: boolean;
	promptCards: boolean;
	newPhotosFrom: boolean;
}

/** `settings.social` with the server's defaults (README §5.20). */
export function socialSettings(s: FrameSettings | null | undefined): SocialSettings {
	const x = s?.social;
	return {
		showComments: x?.show_comments ?? false,
		askForPhotos: x?.ask_for_photos ?? true,
		promptCards: x?.prompt_cards ?? true,
		newPhotosFrom: x?.new_photos_from ?? true
	};
}

/** The "Ask for new photos" button: ready to ask, "Asked today 💛", or "Ben sent new photos 💛". */
export type AskState = { kind: 'ready' } | { kind: 'asked' } | { kind: 'answered'; name: string };

const time = (s: string | null | undefined) => {
	const t = s ? Date.parse(s) : NaN;
	return Number.isFinite(t) ? t : 0;
};

/** Until when the button stays "asked": the server's `next_allowed_at` or 24 h after a local tap, whichever is later. */
export function askedUntil(request: FramePhotoRequest | null, askedAtMs: number | null): number {
	return Math.max(time(request?.next_allowed_at), askedAtMs ? askedAtMs + DAY_MS : 0);
}

export function askState(request: FramePhotoRequest | null, askedAtMs: number | null, now: number): AskState {
	if (now >= askedUntil(request, askedAtMs)) return { kind: 'ready' };
	const name = request?.answered_by_name?.trim();
	return request?.state === 'answered' && name ? { kind: 'answered', name } : { kind: 'asked' };
}

export function sameAsk(a: AskState | null, b: AskState | null): boolean {
	return a?.kind === b?.kind && (a?.kind !== 'answered' || a.name === (b as { name: string }).name);
}

/**
 * Who to name for a new placement in "New photos from Ben": a person's own photo (a send or a family post, also one
 * mailed or posted in Telegram), never a share between frames, an import, a photo-source sync or history.
 */
export function senderName(p: Pick<FramePlacement, 'origin' | 'imported' | 'backfill' | 'senderName' | 'via' | 'viaService'>): string | null {
	if (p.backfill || p.imported) return null;
	if (p.origin && p.origin !== 'send' && p.origin !== 'feed') return null;
	let name: string | null;
	if (!p.viaService) name = p.senderName;
	else if (p.viaService === 'email' || p.viaService === 'telegram') name = p.via?.trim() || p.senderName;
	else return null;
	return name?.trim() || null;
}

/** Adds `more` (at the front with `first`: who answered the request), once per name, at most MAX_NAMES. */
export function addNames(names: readonly string[], more: readonly string[], first = false): string[] {
	const all = first ? [...more, ...names] : [...names, ...more];
	const seen = new Set<string>();
	const out: string[] = [];
	for (const raw of all) {
		const n = raw.trim();
		const k = n.toLocaleLowerCase();
		if (!n || seen.has(k)) continue;
		seen.add(k);
		out.push(n);
		if (out.length >= MAX_NAMES) break;
	}
	return out;
}

/** Removes the names that were announced; names that arrived meanwhile stay for the next wake. */
export function removeNames(names: readonly string[], shown: readonly string[]): string[] {
	const gone = new Set(shown.map((n) => n.toLocaleLowerCase()));
	return names.filter((n) => !gone.has(n.toLocaleLowerCase()));
}

/** The message key and values for "New photos from Ben" (one, two, or two "and others"). */
export function noteMessage(names: readonly string[]): { key: string; values: Record<string, string> } | null {
	const [a, b] = names;
	if (!a) return null;
	if (!b) return { key: 'browserFrame.newPhotosFromOne', values: { name: a } };
	return { key: names.length === 2 ? 'browserFrame.newPhotosFromTwo' : 'browserFrame.newPhotosFromMore', values: { name: a, other: b } };
}

/**
 * The prompt's title card before this answer: prompt cards are on, the prompt is one of the frame's current ones, and
 * the photo before it was not an answer to the same prompt (a run of answers gets one card).
 */
export function promptCard(enabled: boolean, promptId: string | null, previousPromptId: string | null, prompts: ReadonlyMap<string, FramePrompt>): FramePrompt | null {
	if (!enabled || !promptId || promptId === previousPromptId) return null;
	const p = prompts.get(promptId);
	return p && p.title.trim() ? p : null;
}

/** The comment the card shows: the newest one with text. */
export function newestComment(doc: PlacementComments | null | undefined): PlacementComment | null {
	return doc?.comments.find((c) => c.body.trim()) ?? null;
}

/** The calendar day at `now` in the frame's time zone, `YYYY-MM-DD` (the "once per photo per day" of the comment card). */
export function localDay(now: number, timeZone?: string | null): string {
	const fmt = (tz?: string) => new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: tz }).format(new Date(now));
	try {
		return fmt(timeZone || undefined);
	} catch {
		return fmt('UTC');
	}
}

type KeyValueStore = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

interface Persisted {
	/** When this browser last tapped "Ask for new photos" (the button says "Asked today" until a day later). */
	askedAt: number | null;
	/** The tap not sent yet (offline): sent when the frame is back online, dropped after a day. */
	pendingAsk: number | null;
	/** Names for "New photos from Ben", kept until the screen is awake. */
	names: string[];
	/** placement id → the day its comment card last showed. */
	commentsShown: Record<string, string>;
}

const EMPTY: Persisted = { askedAt: null, pendingAsk: null, names: [], commentsShown: {} };

/**
 * What the social features must remember across a reload (the change feed itself is pulled again from 0): kept in
 * localStorage next to the credential, in memory when the browser refuses storage.
 */
export class SocialStore {
	readonly #storage: KeyValueStore | null;
	readonly #key: string;
	#v: Persisted;

	constructor(storage: KeyValueStore | null, key: string) {
		this.#storage = storage;
		this.#key = key;
		this.#v = this.#load();
	}

	#load(): Persisted {
		try {
			const raw = this.#storage?.getItem(this.#key);
			if (!raw) return { ...EMPTY };
			const v = JSON.parse(raw) as Partial<Persisted>;
			return {
				askedAt: typeof v.askedAt === 'number' ? v.askedAt : null,
				pendingAsk: typeof v.pendingAsk === 'number' ? v.pendingAsk : null,
				names: Array.isArray(v.names) ? v.names.filter((n): n is string => typeof n === 'string') : [],
				commentsShown: v.commentsShown && typeof v.commentsShown === 'object' ? { ...v.commentsShown } : {}
			};
		} catch {
			return { ...EMPTY };
		}
	}

	#save(): void {
		try {
			this.#storage?.setItem(this.#key, JSON.stringify(this.#v));
		} catch {
			/* private mode or full: kept in memory until the page reloads */
		}
	}

	get askedAt(): number | null {
		return this.#v.askedAt;
	}

	get pendingAsk(): number | null {
		return this.#v.pendingAsk;
	}

	get names(): readonly string[] {
		return this.#v.names;
	}

	/** A tap on "Ask for new photos": remembered at once and queued. */
	ask(now: number): void {
		this.#v = { ...this.#v, askedAt: now, pendingAsk: now };
		this.#save();
	}

	/** The queued tap was sent (`keepAsked`) or refused / dropped (the button may ask again). */
	settleAsk(keepAsked: boolean): void {
		this.#v = { ...this.#v, pendingAsk: null, askedAt: keepAsked ? this.#v.askedAt : null };
		this.#save();
	}

	setNames(names: string[]): void {
		this.#v = { ...this.#v, names };
		this.#save();
	}

	/** Records that the comment card of `placementId` shows today; false when it already did. Older days are pruned. */
	markCommentShown(placementId: string, day: string): boolean {
		if (this.#v.commentsShown[placementId] === day) return false;
		const kept = Object.fromEntries(Object.entries(this.#v.commentsShown).filter(([, d]) => d >= day));
		this.#v = { ...this.#v, commentsShown: { ...kept, [placementId]: day } };
		this.#save();
		return true;
	}

	clear(): void {
		this.#v = { ...EMPTY, commentsShown: {} };
		try {
			this.#storage?.removeItem(this.#key);
		} catch {
			/* nothing stored */
		}
	}
}
