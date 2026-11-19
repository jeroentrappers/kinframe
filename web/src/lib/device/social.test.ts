import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { heartbeat } from './browserInfo';
import { DeviceClient } from './client';
import { CredentialStore } from './credentials';
import { FrameEngine } from './engine';
import { FakeServer, FakeSocket, placementChange } from './fakeServer';
import { FrameFeed, toFramePlacement } from './feed';
import {
	addNames, askState, localDay, newestComment, noteMessage, promptCard, removeNames, senderName, SOCIAL_FEATURES, socialSettings,
	SocialStore, type FramePhotoRequest, type FramePrompt
} from './social';
import { DeviceSocket } from './socket';

function memoryStorage() {
	const m = new Map<string, string>();
	return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), m };
}

const NOW = Date.parse('2026-11-19T12:00:00Z');
const H = 3_600_000;

function request(o: Partial<FramePhotoRequest> = {}): FramePhotoRequest {
	return { id: 'r1', state: 'open', created_at: new Date(NOW - H).toISOString(), next_allowed_at: new Date(NOW + 23 * H).toISOString(), ...o };
}

const prompt = (id: string, title = 'Your view right now', feed = 'Peeters'): FramePrompt => ({ prompt_id: id, feed_id: 'fd1', feed_name: feed, title, week_of: '2026-11-16' });

describe('family social rules', () => {
	it('reads settings.social with the server defaults', () => {
		expect(socialSettings(null)).toEqual({ showComments: false, askForPhotos: true, promptCards: true, newPhotosFrom: true });
		expect(socialSettings({ version: 1, social: { show_comments: true, ask_for_photos: false } })).toMatchObject({ showComments: true, askForPhotos: false, promptCards: true });
	});

	it('the button asks, then says "Asked today" until next_allowed_at or a day after a local tap, or who answered', () => {
		expect(askState(null, null, NOW)).toEqual({ kind: 'ready' });
		expect(askState(request(), null, NOW)).toEqual({ kind: 'asked' });
		expect(askState(request(), null, NOW + 23 * H)).toEqual({ kind: 'ready' });
		// A tap still waiting in the queue (offline): asked for a day, whatever the server knows.
		expect(askState(null, NOW - 2 * H, NOW)).toEqual({ kind: 'asked' });
		expect(askState(null, NOW - 25 * H, NOW)).toEqual({ kind: 'ready' });
		expect(askState(request({ state: 'answered', answered_by_name: ' Ben ' }), null, NOW)).toEqual({ kind: 'answered', name: 'Ben' });
		expect(askState(request({ state: 'answered', answered_by_name: null }), null, NOW)).toEqual({ kind: 'asked' });
	});

	it('names a person’s own new photo, never a share, an import, a photo source or history', () => {
		const fp = (o: Parameters<typeof placementChange>[1], via?: { service: string; label: string }) => {
			const c = placementChange('a', o);
			if (via) (c.item as { via?: unknown }).via = via;
			return toFramePlacement(c);
		};
		expect(senderName(fp({ sender: 'Ben', origin: 'send' }))).toBe('Ben');
		expect(senderName(fp({ sender: 'Ben', origin: 'feed' }))).toBe('Ben');
		expect(senderName(fp({ sender: 'Ben' }))).toBe('Ben');
		expect(senderName(fp({ sender: 'Ben', origin: 'share' }))).toBeNull();
		expect(senderName(fp({ sender: 'Ben', origin: 'import' }))).toBeNull();
		expect(senderName(fp({ sender: 'Ben', origin: 'feed', backfill: true }))).toBeNull();
		expect(senderName(fp({ sender: 'Ben' }, { service: 'immich', label: 'Immich' }))).toBeNull();
		expect(senderName(fp({ sender: 'Olga' }, { service: 'email', label: 'Jan' }))).toBe('Jan');
		expect(senderName(fp({ sender: ' ' }))).toBeNull();
	});

	it('keeps each name once (who answered first), at most six, and forgets only the announced ones', () => {
		expect(addNames(['Ben'], ['ben', 'Anna', ' '])).toEqual(['Ben', 'Anna']);
		expect(addNames(['Anna'], ['Ben'], true)).toEqual(['Ben', 'Anna']);
		expect(addNames([], ['a', 'b', 'c', 'd', 'e', 'f', 'g'])).toHaveLength(6);
		expect(removeNames(['Ben', 'Anna', 'Lies'], ['anna', 'BEN'])).toEqual(['Lies']);
		expect(noteMessage([])).toBeNull();
		expect(noteMessage(['Ben'])).toEqual({ key: 'browserFrame.newPhotosFromOne', values: { name: 'Ben' } });
		expect(noteMessage(['Ben', 'Anna'])).toEqual({ key: 'browserFrame.newPhotosFromTwo', values: { name: 'Ben', other: 'Anna' } });
		expect(noteMessage(['Ben', 'Anna', 'Lies'])).toEqual({ key: 'browserFrame.newPhotosFromMore', values: { name: 'Ben', other: 'Anna' } });
	});

	it('one prompt card per run of answers, only for current prompts and when turned on', () => {
		const prompts = new Map([['p1', prompt('p1')], ['p2', prompt('p2', '  ')]]);
		expect(promptCard(true, 'p1', null, prompts)?.title).toBe('Your view right now');
		expect(promptCard(true, 'p1', 'p1', prompts)).toBeNull();
		expect(promptCard(false, 'p1', null, prompts)).toBeNull();
		expect(promptCard(true, 'gone', null, prompts)).toBeNull();
		expect(promptCard(true, 'p2', null, prompts)).toBeNull();
		expect(promptCard(true, null, null, prompts)).toBeNull();
	});

	it('the comment card shows the newest comment with text, once per photo per day in the frame’s time zone', () => {
		const doc = { placement_id: 'a', comments: [{ id: 'c2', author_name: 'Ben', body: ' ', created_at: '' }, { id: 'c1', author_name: 'Anna', body: 'Lovely!', created_at: '' }] };
		expect(newestComment(doc)?.id).toBe('c1');
		expect(newestComment(null)).toBeNull();
		expect(localDay(Date.parse('2026-11-19T23:30:00Z'), 'Europe/Brussels')).toBe('2026-11-20');
		expect(localDay(Date.parse('2026-11-19T23:30:00Z'), 'Not/AZone')).toBe('2026-11-19');
		const st = new SocialStore(memoryStorage(), 'k');
		expect(st.markCommentShown('a', '2026-11-19')).toBe(true);
		expect(st.markCommentShown('a', '2026-11-19')).toBe(false);
		expect(st.markCommentShown('a', '2026-11-20')).toBe(true);
	});

	it('the store survives a reload and bad data', () => {
		const s = memoryStorage();
		const a = new SocialStore(s, 'k');
		a.ask(NOW);
		a.setNames(['Ben']);
		const b = new SocialStore(s, 'k');
		expect([b.askedAt, b.pendingAsk, b.names]).toEqual([NOW, NOW, ['Ben']]);
		b.settleAsk(false);
		expect([b.askedAt, b.pendingAsk]).toEqual([null, null]);
		s.setItem('k', '{nope');
		expect(new SocialStore(s, 'k').names).toEqual([]);
		b.clear();
		expect(s.getItem('k')).toBeNull();
	});

	it('the heartbeat lists the social features, like the Android frame', () => {
		expect(SOCIAL_FEATURES).toEqual(['comments', 'photo_requests', 'prompts']);
		expect(heartbeat(Date.now(), 'on').features).toEqual(['comments', 'photo_requests', 'prompts']);
	});
});

describe('change feed: family social', () => {
	it('keeps comments, the photo request and prompts; an answer after the snapshot names who; reset drops them', () => {
		const f = new FrameFeed();
		f.apply({
			changes: [
				{ seq: 1, type: 'placement.upsert', placement: placementChange('a', { prompt: 'p1' }) },
				{ seq: 2, type: 'comments', id: 'a', comments: { placement_id: 'a', comments: [{ id: 'c1', author_name: 'Anna', body: 'Hi', created_at: '' }] } },
				{ seq: 3, type: 'photo_request', id: 'f1', photo_request: request({ state: 'answered', answered_by_name: 'Old' }) },
				{ seq: 4, type: 'prompts', id: 'f1', prompts: { generated_at: '', prompts: [prompt('p1')] } }
			],
			next_seq: 4,
			has_more: false
		});
		f.synced = true;
		expect(f.placements.get('a')?.promptId).toBe('p1');
		expect(f.comments.get('a')?.comments[0]?.body).toBe('Hi');
		expect(f.prompts.get('p1')?.feed_name).toBe('Peeters');
		const r = f.apply({
			changes: [
				{ seq: 5, type: 'photo_request', id: 'f1', photo_request: request({ id: 'r2', state: 'answered', answered_by_name: 'Ben' }) },
				{ seq: 6, type: 'placement.upsert', placement: placementChange('b', { sender: 'Ben', origin: 'feed' }) },
				{ seq: 7, type: 'placement.upsert', placement: placementChange('h', { sender: 'Lies', origin: 'feed', backfill: true }) },
				{ seq: 8, type: 'comments', id: 'a', comments: null },
				{ seq: 9, type: 'prompts', id: 'f1', prompts: null }
			],
			next_seq: 9,
			has_more: false
		});
		expect(r).toMatchObject({ added: ['b'], newFrom: ['Ben'], answeredBy: 'Ben', socialChanged: true });
		expect(f.comments.has('a')).toBe(false);
		expect(f.prompts.size).toBe(0);
		// The same answered request again: nobody new to name.
		expect(f.apply({ changes: [{ seq: 10, type: 'photo_request', id: 'f1', photo_request: request({ id: 'r2', state: 'answered', answered_by_name: 'Ben' }) }], next_seq: 10, has_more: false }).answeredBy).toBeNull();
		f.apply({ changes: [{ seq: 11, type: 'reset' }], next_seq: 0, has_more: false });
		expect(f.photoRequest).toBeNull();
	});
});

describe('browser frame engine: family social', () => {
	let server: FakeServer;
	let engine: FrameEngine;
	let storage: ReturnType<typeof memoryStorage>;

	const flush = async () => {
		for (let i = 0; i < 30; i++) await Promise.resolve();
	};

	function makeEngine() {
		const c = new DeviceClient({ base: 'https://k.test', credentials: new CredentialStore(storage), fetch: server.fetch as typeof fetch });
		return new FrameEngine({
			client: c,
			socket: (events) => new DeviceSocket({ url: () => c.socketUrl('https://k.test'), create: (u) => new FakeSocket(u), events }),
			enrollInfo: () => ({ hardware: { model: 'Chrome' }, screen: { width: 1920, height: 1080 }, app: { version: 'web', version_code: 1 } }),
			heartbeat: () => ({ app_version: 'web', uptime_s: 1 }),
			reload: () => {},
			social: new SocialStore(storage, 'social')
		});
	}

	async function running(settings: Record<string, unknown> = {}) {
		server.claimed = true;
		server.push({ type: 'settings', settings: { version: 1, slideshow: { interval_s: 10 }, locale: { timezone: 'UTC' }, ...settings } });
		await engine.start();
		await flush();
		FakeSocket.last!.open();
		await flush();
		expect(engine.view.status).toBe('running');
	}

	const changes = async () => {
		FakeSocket.last!.receive({ type: 'changes', latest_seq: server.feed.length });
		await flush();
	};

	beforeEach(() => {
		vi.useFakeTimers();
		vi.setSystemTime(NOW);
		server = new FakeServer();
		storage = memoryStorage();
		engine = makeEngine();
	});
	afterEach(() => {
		engine.stop();
		vi.useRealTimers();
	});

	it('asks for new photos, then says "Asked today" until the server allows the next one', async () => {
		await running();
		expect(engine.view.ask).toEqual({ kind: 'ready' });
		expect(engine.askForPhotos()).toBe(true);
		expect(engine.view.askConfirmation).toBe('asked');
		expect(engine.view.ask).toEqual({ kind: 'asked' });
		await flush();
		expect(server.photoRequests).toBe(1);
		expect(server.calls.some((c) => c.method === 'POST' && c.path === '/v1/device/photo-requests')).toBe(true);
		expect(engine.askForPhotos()).toBe(false);
		await vi.advanceTimersByTimeAsync(6_000);
		expect(engine.view.askConfirmation).toBeNull();

		// Ben answers: the photo_request change says so, and "New photos from Ben" follows.
		server.push({ type: 'photo_request', id: 'f1', photo_request: request({ state: 'answered', answered_by_name: 'Ben', next_allowed_at: new Date(NOW + 24 * H).toISOString() }) });
		await changes();
		expect(engine.view.ask).toEqual({ kind: 'answered', name: 'Ben' });
		await vi.advanceTimersByTimeAsync(1_500);
		expect(engine.view.note).toEqual(['Ben']);
		await vi.advanceTimersByTimeAsync(10_000);
		expect(engine.view.note).toBeNull();

		await vi.advanceTimersByTimeAsync(24 * H);
		expect(engine.view.ask).toEqual({ kind: 'ready' });
	});

	it('queues the request offline and sends it when the frame is back', async () => {
		await running();
		server.photoRequestStatus = 0;
		FakeSocket.last!.close(1006);
		expect(engine.view.online).toBe(false);
		engine.askForPhotos();
		expect(engine.view.askConfirmation).toBe('willAsk');
		await flush();
		expect(engine.view.ask).toEqual({ kind: 'asked' });
		// A reload keeps the queued tap.
		expect(new SocialStore(storage, 'social').pendingAsk).toBe(NOW);
		server.photoRequestStatus = 201;
		await vi.advanceTimersByTimeAsync(60_000);
		expect(server.photoRequests).toBe(1);
		expect(new SocialStore(storage, 'social').pendingAsk).toBeNull();
	});

	it('hides the button when the owner turned it off or the server refuses', async () => {
		await running({ social: { ask_for_photos: false } });
		expect(engine.view.ask).toBeNull();
		server.push({ type: 'settings', settings: { version: 2, social: { ask_for_photos: true } } });
		await changes();
		expect(engine.view.ask).toEqual({ kind: 'ready' });
		server.photoRequestStatus = 403;
		engine.askForPhotos();
		await flush();
		// Nobody was asked: the button may ask again.
		expect(engine.view.ask).toEqual({ kind: 'ready' });
		expect(engine.view.askConfirmation).toBeNull();
		server.photoRequestStatus = 404;
		engine.askForPhotos();
		await flush();
		expect(engine.view.ask).toBeNull();
	});

	it('says who sent new photos on the next wake, not while asleep, and not for history', async () => {
		server.push({ type: 'placement.upsert', placement: placementChange('a') });
		await running();
		FakeSocket.last!.receive({ type: 'command', id: 'k1', command_type: 'sleep', args: {} });
		expect(engine.view.screen).toBe('off');
		server.push({ type: 'placement.upsert', placement: placementChange('h', { sender: 'Lies', origin: 'feed', backfill: true }) });
		await changes();
		// New photos wake a frame put to sleep by hand: the note follows the wake.
		server.push({ type: 'placement.upsert', placement: placementChange('b', { sender: 'Ben', origin: 'send' }) });
		await changes();
		expect(engine.view.screen).toBe('on');
		await vi.advanceTimersByTimeAsync(1_500);
		expect(engine.view.note).toEqual(['Ben']);
		await vi.advanceTimersByTimeAsync(10_000);
		expect(engine.view.note).toBeNull();
		expect(new SocialStore(storage, 'social').names).toEqual([]);
	});

	it('shows the newest comment for 8 s once per photo per day, and the prompt card over the first answer of a run', async () => {
		server.push({ type: 'placement.upsert', placement: placementChange('a', { received: '2026-11-19T10:00:00Z', prompt: 'p1' }) });
		server.push({ type: 'placement.upsert', placement: placementChange('b', { received: '2026-11-19T09:00:00Z', prompt: 'p1' }) });
		server.push({ type: 'placement.upsert', placement: placementChange('c', { received: '2026-11-19T08:00:00Z' }) });
		server.push({ type: 'comments', id: 'c', comments: { placement_id: 'c', comments: [{ id: 'x', author_name: 'Anna', body: 'So sweet', created_at: '' }] } });
		server.push({ type: 'prompts', id: 'f1', prompts: { generated_at: '', prompts: [prompt('p1')] } });
		await running({ social: { show_comments: true } });
		expect(engine.view.slide?.placement.id).toBe('a');
		expect(engine.view.cards?.prompt?.title).toBe('Your view right now');
		expect(engine.view.cards?.comment).toBeNull();
		await vi.advanceTimersByTimeAsync(8_000);
		expect(engine.view.cards).toBeNull();
		await vi.advanceTimersByTimeAsync(2_500);
		// The second answer of the run: no card again.
		expect(engine.view.slide?.placement.id).toBe('b');
		expect(engine.view.cards).toBeNull();
		await vi.advanceTimersByTimeAsync(10_000);
		expect(engine.view.slide?.placement.id).toBe('c');
		expect(engine.view.cards?.comment?.body).toBe('So sweet');
		expect(engine.view.cards?.prompt).toBeNull();
		await vi.advanceTimersByTimeAsync(10_000);
		expect(engine.view.slide?.placement.id).toBe('a');
		// Round again the same day: no comment card for c, but a new run of p1 gets its card.
		expect(engine.view.cards?.prompt?.prompt_id).toBe('p1');
		await vi.advanceTimersByTimeAsync(20_000);
		expect(engine.view.slide?.placement.id).toBe('c');
		expect(engine.view.cards).toBeNull();
	});

	it('shows no comment card when Show comments is off', async () => {
		server.push({ type: 'placement.upsert', placement: placementChange('c') });
		server.push({ type: 'comments', id: 'c', comments: { placement_id: 'c', comments: [{ id: 'x', author_name: 'Anna', body: 'So sweet', created_at: '' }] } });
		await running();
		expect(engine.view.slide?.placement.id).toBe('c');
		expect(engine.view.cards).toBeNull();
	});
});
