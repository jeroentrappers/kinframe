// The browser frame: the device protocol (enrolment, claim code, change feed, websocket
// nudges and commands, acks, heartbeat) plus the slideshow decisions (order, timing,
// sleep schedule, new-photo celebration). Plain TypeScript with injected clock, timers and
// network so it is unit-tested; the /tv/frame page renders `view` and reports what the
// screen does (video ended, a reaction).
//
// Plugin runtime hook (docs/plugins.md §11.3): `on(event, fn)` exposes the same moments a
// frame-side plugin would need: every applied change, every slide, every command.
import { DeviceApiError, DeviceRevokedError, type DeviceClient, type FrameCode, type Heartbeat, type Reaction } from './client';
import { FrameFeed, type FramePlacement, type FrameSettings, type Weather } from './feed';
import { Playlist, type PlayOrder } from './playlist';
import { sleepState } from './sleep';
import type { DeviceSocket, ServerMessage, SocketEvents } from './socket';

export type FrameStatus = 'starting' | 'enrolling' | 'code' | 'running' | 'revoked' | 'error';
export type Screen = 'on' | 'dimmed' | 'sleeping' | 'off';
export type Transition = 'crossfade' | 'slide' | 'cut';

export interface ShownSlide {
	placement: FramePlacement;
	/** Increases with every slide (a new key for the renderer). */
	seq: number;
	startedAt: number;
	transition: Transition;
	transitionMs: number;
	direction: 1 | -1;
	kenBurns: boolean;
	holdMs: number;
	/** Shown because it just arrived (the celebration). */
	fresh: boolean;
}

export interface FrameView {
	status: FrameStatus;
	online: boolean;
	code: FrameCode | null;
	frameName: string;
	ownerName: string;
	settings: FrameSettings | null;
	weather: Weather | null;
	slide: ShownSlide | null;
	next: FramePlacement | null;
	screen: Screen;
	/** Frame name flashed by the `identify` command. */
	identify: string | null;
	/** "New photo from Anna" while a new arrival shows. */
	celebrate: { senderName: string; count: number } | null;
	count: number;
	paused: boolean;
}

export interface EngineDeps {
	client: DeviceClient;
	socket: (events: SocketEvents) => Pick<DeviceSocket, 'start' | 'stop' | 'send' | 'reconnectNow' | 'open'>;
	enrollInfo: () => Parameters<DeviceClient['enroll']>[0];
	heartbeat: () => Heartbeat;
	now?: () => number;
	setTimer?: (fn: () => void, ms: number) => unknown;
	clearTimer?: (t: unknown) => void;
	/** `restart`: reload the page. */
	reload?: () => void;
}

type Events = {
	view: FrameView;
	slide: ShownSlide;
	changes: { added: string[]; settingsChanged: boolean };
	command: { type: string; args: Record<string, unknown> };
};

const HEARTBEAT_MS = 5 * 60_000;
const POLL_MS = 5 * 60_000;
const ACK_MS = 10_000;
const CODE_POLL_MS = 30_000;
const SCHEDULE_MS = 30_000;
const IDLE_WAKE_MS = 5 * 60_000;
const CELEBRATE_MS = 6_000;

export class FrameEngine {
	readonly feed = new FrameFeed();
	readonly playlist = new Playlist();
	view: FrameView = {
		status: 'starting', online: false, code: null, frameName: '', ownerName: '', settings: null, weather: null,
		slide: null, next: null, screen: 'on', identify: null, celebrate: null, count: 0, paused: false
	};

	readonly #d: Required<EngineDeps>;
	#socket: ReturnType<EngineDeps['socket']> | null = null;
	#timers = new Map<string, unknown>();
	#listeners: { [K in keyof Events]?: ((v: Events[K]) => void)[] } = {};
	#seq = 0;
	/** Placements known to be here, and the ones whose delivery is not acknowledged yet. */
	#known = new Set<string>();
	#toDeliver = new Set<string>();
	#seen: { placement_id: string; at: string }[] = [];
	#seenIds = new Set<string>();
	#videoDone = true;
	#manualSleep = false;
	#wokenUntil = 0;
	#pulling: Promise<void> | null = null;
	#pullAgain = false;
	#stopped = false;

	constructor(deps: EngineDeps) {
		this.#d = {
			now: Date.now,
			setTimer: (fn, ms) => setTimeout(fn, ms),
			clearTimer: (t) => clearTimeout(t as ReturnType<typeof setTimeout>),
			reload: () => location.reload(),
			...deps
		};
	}

	on<K extends keyof Events>(event: K, fn: (v: Events[K]) => void): () => void {
		const list = (this.#listeners[event] ??= []) as ((v: Events[K]) => void)[];
		list.push(fn);
		return () => list.splice(list.indexOf(fn) >>> 0, 1);
	}

	#emit<K extends keyof Events>(event: K, v: Events[K]): void {
		for (const fn of (this.#listeners[event] ?? []) as ((v: Events[K]) => void)[]) fn(v);
	}

	#set(patch: Partial<FrameView>): void {
		this.view = { ...this.view, ...patch };
		this.#emit('view', this.view);
	}

	#after(name: string, ms: number, fn: () => void): void {
		const old = this.#timers.get(name);
		if (old) this.#d.clearTimer(old);
		this.#timers.set(
			name,
			this.#d.setTimer(() => {
				this.#timers.delete(name);
				if (!this.#stopped) fn();
			}, ms)
		);
	}

	#cancel(name: string): void {
		const t = this.#timers.get(name);
		if (t) this.#d.clearTimer(t);
		this.#timers.delete(name);
	}

	// ------------------------------------------------------------------ lifecycle

	async start(): Promise<void> {
		this.#stopped = false;
		try {
			if (!this.#d.client.credentials) {
				this.#set({ status: 'enrolling' });
				await this.#d.client.enroll(this.#d.enrollInfo());
			}
			const st = await this.#d.client.state();
			this.#openSocket();
			if (st?.claimed) {
				this.#set({ frameName: st.frame_name ?? '', ownerName: st.owner_name ?? '' });
				await this.#startRunning();
			} else {
				await this.#showCode();
			}
		} catch (e) {
			this.#fail(e);
		}
	}

	stop(): void {
		this.#stopped = true;
		for (const t of this.#timers.values()) this.#d.clearTimer(t);
		this.#timers.clear();
		this.#socket?.stop();
		this.#socket = null;
		void this.#flushAcks();
	}

	/** Forget the credential and enrol again from scratch (after revocation, or on request). */
	async reset(): Promise<void> {
		this.stop();
		this.#d.client.forget();
		this.feed.clear();
		this.playlist.update([], 'received', false);
		this.#set({ status: 'starting', slide: null, next: null, code: null, celebrate: null, count: 0 });
		await this.start();
	}

	#fail(e: unknown): void {
		if (e instanceof DeviceRevokedError) {
			this.stop();
			this.#d.client.forget();
			this.feed.clear();
			this.#set({ status: 'revoked', slide: null, next: null, code: null });
			return;
		}
		console.warn('browser frame:', e);
		if (this.view.status === 'starting' || this.view.status === 'enrolling') {
			this.#set({ status: 'error' });
			// Network trouble at start-up: try again calmly.
			this.#after('restart', 15_000, () => void this.start());
		}
	}

	#openSocket(): void {
		if (this.#socket) return;
		this.#socket = this.#d.socket({
			message: (m) => this.#onMessage(m),
			status: (open) => {
				this.#set({ online: open });
				if (open) this.#reportNowShowing();
			},
			unauthorized: async () => {
				try {
					await this.#d.client.token(true);
					return true;
				} catch (e) {
					if (e instanceof DeviceRevokedError) this.#fail(e);
					return !(e instanceof DeviceRevokedError);
				}
			}
		});
		this.#socket.start();
	}

	// ------------------------------------------------------------------ setup code

	async #showCode(): Promise<void> {
		const code = await this.#d.client.code();
		this.#set({ status: 'code', code });
		// The websocket's `claimed` is the fast path; polling covers a missed message and renews the code.
		this.#after('code', CODE_POLL_MS, () => void this.#checkClaimed());
	}

	async #checkClaimed(): Promise<void> {
		try {
			const st = await this.#d.client.state();
			if (st?.claimed) return await this.#onClaimed(st.owner_name ?? '', st.frame_name ?? '');
			const exp = this.view.code ? Date.parse(this.view.code.expires_at) : 0;
			if (!exp || exp - this.#d.now() < 11 * 60_000) return await this.#showCode();
			this.#after('code', CODE_POLL_MS, () => void this.#checkClaimed());
		} catch (e) {
			if (e instanceof DeviceRevokedError) return this.#fail(e);
			this.#after('code', CODE_POLL_MS, () => void this.#checkClaimed());
		}
	}

	async #onClaimed(owner: string, frameName = ''): Promise<void> {
		if (this.view.status === 'running') return;
		this.#cancel('code');
		this.#set({ ownerName: owner, frameName, code: null });
		await this.#startRunning();
	}

	// ------------------------------------------------------------------ running

	async #startRunning(): Promise<void> {
		this.#set({ status: 'running' });
		await this.pull();
		this.#after('heartbeat', 15_000, () => void this.#heartbeat());
		this.#after('poll', POLL_MS, () => this.#poll());
		this.#scheduleCheck();
	}

	#poll(): void {
		void this.pull();
		this.#after('poll', POLL_MS, () => this.#poll());
	}

	/** Pulls the change feed (coalesced: a hint during a pull pulls once more after it). */
	pull(): Promise<void> {
		if (this.#pulling) {
			this.#pullAgain = true;
			return this.#pulling;
		}
		this.#pulling = (async () => {
			do {
				this.#pullAgain = false;
				try {
					const r = await this.feed.pull(this.#d.client);
					this.#applyFeed(r.added);
					this.#emit('changes', { added: r.added, settingsChanged: r.settingsChanged });
				} catch (e) {
					if (e instanceof DeviceRevokedError) return this.#fail(e);
					if (!(e instanceof DeviceApiError)) console.warn('pull failed', e);
				}
			} while (this.#pullAgain);
		})().finally(() => (this.#pulling = null));
		return this.#pulling;
	}

	#applyFeed(added: string[]): void {
		const s = this.feed.settings;
		const albumId = s?.slideshow?.album_id ?? null;
		const all = this.feed.visible();
		const inAlbum = albumId ? all.filter((p) => p.albumIds.includes(albumId)) : [];
		const playable = inAlbum.length ? inAlbum : all;
		this.playlist.update(playable, (s?.slideshow?.order ?? 'received') as PlayOrder, s?.slideshow?.reverse ?? false, this.#d.now());
		// Everything the feed delivered is "delivered": the browser loads media on demand.
		for (const p of all) {
			if (this.#known.has(p.id)) continue;
			this.#known.add(p.id);
			this.#toDeliver.add(p.id);
		}
		if (this.#toDeliver.size) this.#queueAck();
		const fresh = added.filter((id) => playable.some((p) => p.id === id));
		this.#set({
			settings: s,
			weather: this.feed.weather,
			frameName: this.feed.frame?.name ?? this.view.frameName,
			ownerName: this.feed.frame?.ownerName ?? this.view.ownerName,
			count: playable.length
		});
		if (fresh.length) {
			this.playlist.addNew(fresh);
			// New photos wake a frame put to sleep by hand, never during scheduled sleep (as on the frame).
			if (this.#manualSleep && !this.#scheduledSleep()) this.wake();
			this.#advance(1, { fresh: true });
			return;
		}
		const cur = this.view.slide?.placement;
		const still = cur ? this.feed.placements.get(cur.id) : undefined;
		if (!cur || !still?.visible || !playable.some((p) => p.id === cur.id)) {
			if (playable.length) this.#advance(1, {});
			else this.#set({ slide: null, next: null });
		} else if (still !== cur) {
			// Caption, reaction or crop changed: update in place.
			this.#set({ slide: { ...this.view.slide!, placement: still } });
		}
		this.#updateNext();
	}

	#updateNext(): void {
		const id = this.playlist.peekNext();
		const next = id && id !== this.view.slide?.placement.id ? (this.feed.placements.get(id) ?? null) : null;
		if (next !== this.view.next) this.#set({ next });
	}

	// ------------------------------------------------------------------ slideshow

	get #intervalMs(): number {
		return Math.min(86_400, Math.max(2, this.feed.settings?.slideshow?.interval_s ?? 30)) * 1000;
	}

	#advance(dir: 1 | -1, o: { manual?: boolean; fresh?: boolean; id?: string }): void {
		let id: string | null;
		if (o.id) id = this.playlist.jumpTo(o.id) ? o.id : null;
		else id = dir === 1 ? this.playlist.next() : this.playlist.previous();
		const p = id ? this.feed.placements.get(id) : undefined;
		if (!p) return;
		const ss = this.feed.settings?.slideshow;
		const kind: Transition = o.manual ? 'slide' : ss?.transition === 'slide' ? 'slide' : 'crossfade';
		const transitionMs = kind === 'slide' ? 450 : Math.min(3000, Math.max(200, ss?.transition_ms ?? 800));
		const slide: ShownSlide = {
			placement: p, seq: ++this.#seq, startedAt: this.#d.now(), transition: this.view.slide ? kind : 'crossfade', transitionMs,
			direction: dir, kenBurns: ss?.transition === 'kenburns' && p.kind !== 'video', holdMs: this.#intervalMs + transitionMs, fresh: !!o.fresh
		};
		this.#videoDone = p.kind !== 'video';
		const pending = this.playlist.pendingNew.length;
		this.#set({ slide, celebrate: o.fresh ? { senderName: p.senderName, count: pending + 1 } : null });
		if (o.fresh) this.#after('celebrate', CELEBRATE_MS, () => this.#set({ celebrate: null }));
		this.#updateNext();
		this.#markSeen(p.id);
		this.#emit('slide', slide);
		this.#reportNowShowing();
		this.#scheduleNext();
	}

	#scheduleNext(): void {
		this.#cancel('advance');
		if (this.view.paused || this.view.screen === 'sleeping' || this.view.screen === 'off') return;
		if (this.playlist.size <= 1 && !this.playlist.pendingNew.length) return;
		const p = this.view.slide?.placement;
		const playback = this.feed.settings?.video?.playback ?? 'once_hold';
		const elapsed = this.view.slide ? this.#d.now() - this.view.slide.startedAt : 0;
		let wait = this.#intervalMs - elapsed;
		if (p?.kind === 'video' && !this.#videoDone) {
			// Videos move on when they end (videoEnded); this is only a safety net for a stalled one.
			if (playback !== 'loop') wait = Math.max(wait, (p.durationMs ?? 0) + 10_000);
		}
		this.#after('advance', Math.max(500, wait), () => this.#advance(1, {}));
	}

	/** The page reports the end of a video. */
	videoEnded(): void {
		const p = this.view.slide?.placement;
		if (!p || p.kind !== 'video') return;
		this.#videoDone = true;
		const playback = this.feed.settings?.video?.playback ?? 'once_hold';
		if (playback === 'once_advance' || this.#d.now() - (this.view.slide?.startedAt ?? 0) >= this.#intervalMs) this.#advance(1, {});
		else this.#scheduleNext();
	}

	next(): void {
		this.#poke();
		this.#advance(1, { manual: true });
	}

	previous(): void {
		this.#poke();
		this.#advance(-1, { manual: true });
	}

	show(id: string): boolean {
		if (!this.feed.placements.get(id)?.visible) return false;
		this.#poke();
		this.#advance(1, { id });
		return true;
	}

	/** Shows the waiting new photos (the "N new" card). */
	showNew(): void {
		const id = this.playlist.pendingNew[0];
		if (id) this.#advance(1, { id, fresh: true });
	}

	setPaused(paused: boolean): void {
		this.#set({ paused });
		this.#scheduleNext();
	}

	async react(reaction: Reaction | null): Promise<boolean> {
		const p = this.view.slide?.placement;
		if (!p) return false;
		this.#poke();
		try {
			await this.#d.client.reaction(p.id, reaction);
			const updated = { ...p, reaction };
			this.feed.placements.set(p.id, updated);
			this.#set({ slide: { ...this.view.slide!, placement: updated } });
			return true;
		} catch (e) {
			if (e instanceof DeviceRevokedError) this.#fail(e);
			return false;
		}
	}

	// ------------------------------------------------------------------ sleep and wake

	#scheduledSleep(): 'off' | 'dim' | null {
		const s = this.feed.settings;
		return sleepState(s?.display?.sleep_schedules as never, new Date(this.#d.now()), s?.locale?.timezone);
	}

	#scheduleCheck(): void {
		this.#evaluateScreen();
		this.#after('schedule', SCHEDULE_MS, () => this.#scheduleCheck());
	}

	#evaluateScreen(): void {
		const sched = this.#scheduledSleep();
		let screen: Screen = 'on';
		if (this.#manualSleep) screen = 'off';
		else if (sched && this.#d.now() >= this.#wokenUntil) screen = sched === 'dim' ? 'dimmed' : 'sleeping';
		if (!sched) this.#wokenUntil = 0;
		if (screen !== this.view.screen) {
			this.#set({ screen });
			this.#reportNowShowing();
			this.#scheduleNext();
		}
	}

	sleepNow(): void {
		this.#manualSleep = true;
		this.#evaluateScreen();
	}

	/** Wake (remote `wake`, a key press): during a scheduled sleep the screen stays on for a while. */
	wake(): void {
		this.#manualSleep = false;
		if (this.#scheduledSleep()) this.#wokenUntil = this.#d.now() + IDLE_WAKE_MS;
		this.#evaluateScreen();
	}

	#poke(): void {
		if (this.view.screen !== 'on') this.wake();
		else if (this.#wokenUntil) this.#wokenUntil = this.#d.now() + IDLE_WAKE_MS;
	}

	/** Any key press or touch on the page. */
	activity(): void {
		this.#poke();
	}

	// ------------------------------------------------------------------ websocket

	#onMessage(m: ServerMessage): void {
		switch (m.type) {
			case 'hello':
			case 'changes':
				if (this.view.status === 'running') void this.pull();
				break;
			case 'claimed':
				void this.#onClaimed(String((m as { owner_name?: string }).owner_name ?? ''));
				break;
			case 'revoked':
				this.#fail(new DeviceRevokedError());
				break;
			case 'command': {
				const c = m as { id: string; command_type: string; args?: Record<string, unknown> };
				const err = this.#command(c.command_type, c.args ?? {});
				this.#emit('command', { type: c.command_type, args: c.args ?? {} });
				this.#socket?.send({ type: 'command_result', id: c.id, ok: err === null, error_code: err });
				break;
			}
		}
	}

	/** Remote commands (README §5.6). Returns null on success or an error code. */
	#command(type: string, args: Record<string, unknown>): string | null {
		switch (type) {
			case 'next':
				this.wake();
				this.#advance(1, { manual: false });
				return null;
			case 'previous':
				this.wake();
				this.#advance(-1, { manual: false });
				return null;
			case 'show_placement': {
				const id = typeof args.placement_id === 'string' ? args.placement_id : '';
				if (!id) return 'validation_failed';
				this.wake();
				return this.show(id) ? null : 'not_found';
			}
			case 'sleep':
				this.sleepNow();
				return null;
			case 'wake':
				this.wake();
				return null;
			case 'sync_now':
				void this.pull();
				void this.#heartbeat(false);
				return null;
			case 'identify':
				this.wake();
				this.#set({ identify: this.view.frameName || 'kinframe' });
				this.#after('identify', 8000, () => this.#set({ identify: null }));
				return null;
			case 'restart':
				this.#after('reload', 500, () => this.#d.reload());
				return null;
			default:
				return 'unsupported';
		}
	}

	/** `now_showing` (README §6.1): a browser frame can be mirrored like any frame. */
	#reportNowShowing(): void {
		const s = this.view.slide;
		this.#socket?.send({
			type: 'now_showing',
			placement_id: s?.placement.id ?? null,
			placement_ids: s ? [s.placement.id] : [],
			started_at: new Date(s?.startedAt ?? this.#d.now()).toISOString(),
			transition: s?.transition ?? 'cut',
			transition_ms: s?.transitionMs ?? 0,
			direction: s?.direction ?? 1,
			ken_burns: s?.kenBurns ?? false,
			hold_ms: s?.holdMs ?? 0,
			video: null,
			next_placement_id: this.view.next?.id ?? null,
			screen: this.view.screen,
			sent_at: new Date(this.#d.now()).toISOString()
		});
	}

	// ------------------------------------------------------------------ acks and heartbeat

	#markSeen(id: string): void {
		if (this.#seenIds.has(id)) return;
		this.#seenIds.add(id);
		this.#seen.push({ placement_id: id, at: new Date(this.#d.now()).toISOString() });
		this.#queueAck();
	}

	/** Acks are batched every 10 s or 50 items (README §5.4). */
	#queueAck(): void {
		if (this.#seen.length >= 50) void this.#flushAcks();
		else if (!this.#timers.has('acks')) this.#after('acks', ACK_MS, () => void this.#flushAcks());
	}

	async #flushAcks(): Promise<void> {
		this.#cancel('acks');
		const delivered = [...this.#toDeliver].slice(0, 500);
		const seen = this.#seen.splice(0, 500);
		if (!delivered.length && !seen.length) return;
		try {
			await this.#d.client.acks(delivered, seen);
			for (const id of delivered) this.#toDeliver.delete(id);
		} catch (e) {
			if (e instanceof DeviceRevokedError) return this.#fail(e);
			this.#seen.unshift(...seen);
		}
		if (!this.#stopped && (this.#seen.length || this.#toDeliver.size)) this.#after('acks', ACK_MS, () => void this.#flushAcks());
	}

	async #heartbeat(again = true): Promise<void> {
		try {
			await this.#d.client.heartbeat(this.#d.heartbeat());
		} catch (e) {
			if (e instanceof DeviceRevokedError) return this.#fail(e);
		}
		if (again) this.#after('heartbeat', HEARTBEAT_MS, () => void this.#heartbeat());
	}

	/** The page became visible or the network came back. */
	resume(): void {
		this.#socket?.reconnectNow();
		if (this.view.status === 'running') void this.pull();
		this.#evaluateScreen();
	}
}
