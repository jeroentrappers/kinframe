import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DeviceClient, DeviceRevokedError } from './client';
import { CredentialStore, credentialsKey } from './credentials';
import { FrameEngine } from './engine';
import { FakeServer, FakeSocket, placementChange } from './fakeServer';
import { FrameFeed } from './feed';
import { Playlist, orderIds, uniform } from './playlist';
import { sleepState } from './sleep';
import { backoffMs, DeviceSocket, UNAUTHORIZED } from './socket';

function memoryStorage() {
	const m = new Map<string, string>();
	return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), m };
}

function client(server: FakeServer, storage = memoryStorage()) {
	return new DeviceClient({ base: 'https://k.test', credentials: new CredentialStore(storage), fetch: server.fetch as typeof fetch });
}

const ENROLL = { hardware: { model: 'Chrome' }, screen: { width: 1920, height: 1080 }, app: { version: '0.1.0', version_code: 1 } };

describe('credentials', () => {
	it('are scoped to the API origin and survive bad data', () => {
		expect(credentialsKey('')).toBe('kinframe.browser-frame.v1');
		expect(credentialsKey('https://api.example')).toBe('kinframe.browser-frame.v1@https://api.example');
		const s = memoryStorage();
		const c = new CredentialStore(s);
		expect(c.load()).toBeNull();
		s.setItem('kinframe.browser-frame.v1', '{not json');
		expect(c.load()).toBeNull();
		c.save({ device_id: 'd', device_secret: 's', enrolled_at: 'now' });
		expect(c.load()).toEqual({ device_id: 'd', device_secret: 's', enrolled_at: 'now' });
		c.clear();
		expect(c.load()).toBeNull();
	});
});

describe('device client', () => {
	it('enrolls as a browser, then calls with a bearer token and retries once on token_expired', async () => {
		const server = new FakeServer();
		const c = client(server);
		await c.enroll(ENROLL);
		expect(server.calls[0]).toMatchObject({ path: '/v1/devices/enroll', body: { kind: 'browser', hardware: { model: 'Chrome' } } });
		expect(c.credentials?.device_id).toBe('d1');
		server.expireOnce = true;
		const st = await c.state();
		expect(st?.claimed).toBe(false);
		const auths = server.calls.filter((x) => x.path === '/v1/device/state').map((x) => x.auth);
		expect(auths).toEqual(['Bearer tok1', 'Bearer tok2']);
	});

	it('puts the token in media and websocket URLs only', async () => {
		const server = new FakeServer();
		const c = client(server);
		expect(c.mediaUrl('p1')).toBeNull();
		await c.enroll(ENROLL);
		await c.token();
		expect(c.mediaUrl('p1', 'poster')).toBe('https://k.test/v1/device/media/p1?variant=poster&access_token=tok1');
		expect(await c.socketUrl('https://page.test')).toBe('wss://k.test/v1/device/ws?access_token=tok1');
	});

	it('turns a revoked or unknown credential into DeviceRevokedError', async () => {
		const server = new FakeServer();
		const c = client(server);
		await expect(c.state()).rejects.toBeInstanceOf(DeviceRevokedError);
		await c.enroll(ENROLL);
		server.revoked = true;
		await expect(c.state()).rejects.toBeInstanceOf(DeviceRevokedError);
	});
});

describe('device websocket', () => {
	it('backs off from 1 s to 60 s with ±20 % jitter', () => {
		expect(backoffMs(0, () => 0.5)).toBe(1000);
		expect(backoffMs(3, () => 0.5)).toBe(8000);
		expect(backoffMs(20, () => 0.5)).toBe(60_000);
		expect(backoffMs(0, () => 0)).toBe(800);
		expect(backoffMs(0, () => 1)).toBe(1200);
	});

	it('reconnects after a drop and refreshes the token after 4401', async () => {
		const timers: (() => void)[] = [];
		const messages: unknown[] = [];
		let unauthorized = 0;
		const s = new DeviceSocket({
			url: async () => 'wss://k.test/v1/device/ws',
			create: (u) => new FakeSocket(u),
			setTimer: (fn) => timers.push(fn),
			clearTimer: () => {},
			events: { message: (m) => messages.push(m), status: () => {}, unauthorized: async () => (unauthorized++, true) }
		});
		s.start();
		await Promise.resolve();
		const first = FakeSocket.last!;
		first.open();
		first.receive({ type: 'hello', latest_seq: 3 });
		expect(messages).toEqual([{ type: 'hello', latest_seq: 3 }]);
		expect(s.send({ type: 'ping' })).toBe(true);
		first.close(1006);
		expect(timers).toHaveLength(1);
		timers.shift()!();
		await Promise.resolve();
		const second = FakeSocket.last!;
		expect(second).not.toBe(first);
		second.open();
		second.close(UNAUTHORIZED);
		await Promise.resolve();
		await Promise.resolve();
		expect(unauthorized).toBe(1);
		expect(timers).toHaveLength(1);
		s.stop();
	});
});

describe('change feed', () => {
	it('applies upserts, removals, settings and resets; only later arrivals are new', () => {
		const f = new FrameFeed();
		f.apply({ changes: [{ seq: 1, type: 'placement.upsert', placement: placementChange('a') }, { seq: 2, type: 'settings', settings: { version: 1 } }], next_seq: 2, has_more: false });
		f.synced = true;
		const r = f.apply({
			changes: [
				{ seq: 3, type: 'placement.upsert', placement: placementChange('b', { media: false }) },
				{ seq: 4, type: 'placement.upsert', placement: placementChange('c', { hidden: true }) }
			],
			next_seq: 4,
			has_more: false
		});
		expect(r.added).toEqual([]);
		const r2 = f.apply({ changes: [{ seq: 5, type: 'placement.upsert', placement: placementChange('b') }, { seq: 6, type: 'placement.remove', id: 'a' }], next_seq: 6, has_more: false });
		expect(r2.added).toEqual(['b']);
		expect(f.visible().map((p) => p.id)).toEqual(['b']);
		expect(f.seq).toBe(6);
		expect(f.apply({ changes: [{ seq: 6, type: 'reset' }], next_seq: 0, has_more: false }).reset).toBe(true);
		expect(f.placements.size).toBe(0);
	});
});

describe('playlist', () => {
	const items = [
		{ id: 'old', receivedAt: '2026-01-01T00:00:00Z', capturedAt: '2020-01-01T00:00:00Z' },
		{ id: 'mid', receivedAt: '2026-05-01T00:00:00Z', capturedAt: null },
		{ id: 'new', receivedAt: '2026-09-01T00:00:00Z', capturedAt: '2010-01-01T00:00:00Z' }
	];

	it('orders newest first, by taken date, reversed, or as a stable seeded shuffle', () => {
		expect(orderIds(items, 'received', false, 1)).toEqual(['new', 'mid', 'old']);
		expect(orderIds(items, 'received', true, 1)).toEqual(['old', 'mid', 'new']);
		expect(orderIds(items, 'taken', false, 1)).toEqual(['mid', 'old', 'new']);
		const a = orderIds(items, 'shuffle', false, 7, Date.parse('2026-10-01'));
		expect([...a].sort()).toEqual(['mid', 'new', 'old']);
		expect(orderIds(items, 'shuffle', false, 7, Date.parse('2026-10-01'))).toEqual(a);
		expect(uniform(1, 'x')).toBeGreaterThan(0);
		expect(uniform(1, 'x')).toBeLessThan(1);
	});

	it('plays new arrivals first, then resumes; previous and forward retrace', () => {
		const p = new Playlist(1);
		p.update(items, 'received', false);
		expect([p.next(), p.next()]).toEqual(['new', 'mid']);
		p.addNew(['old']);
		expect(p.next()).toBe('old');
		expect(p.next()).toBe('new');
		expect(p.previous()).toBe('old');
		expect(p.previous()).toBe('mid');
		expect(p.next()).toBe('old');
		expect(p.jumpTo('mid')).toBe(true);
		expect(p.jumpTo('gone')).toBe(false);
	});
});

describe('sleep schedule', () => {
	const rules = [{ sleep_at: '22:00', wake_at: '07:00', days: ['fri'], enabled: true }];
	it('a rule belongs to the day its sleep starts and runs past midnight', () => {
		// 2026-10-23 is a Friday.
		expect(sleepState(rules, new Date('2026-10-23T21:59:00Z'), 'UTC')).toBeNull();
		expect(sleepState(rules, new Date('2026-10-23T22:00:00Z'), 'UTC')).toBe('off');
		expect(sleepState(rules, new Date('2026-10-24T06:59:00Z'), 'UTC')).toBe('off');
		expect(sleepState(rules, new Date('2026-10-24T07:00:00Z'), 'UTC')).toBeNull();
		expect(sleepState(rules, new Date('2026-10-24T22:30:00Z'), 'UTC')).toBeNull();
		// The frame's time zone, not the browser's.
		expect(sleepState(rules, new Date('2026-10-23T20:30:00Z'), 'Europe/Brussels')).toBe('off');
		expect(sleepState([{ ...rules[0]!, mode: 'dim' }], new Date('2026-10-23T23:00:00Z'), 'UTC')).toBe('dim');
		expect(sleepState([{ ...rules[0]!, enabled: false }], new Date('2026-10-23T23:00:00Z'), 'UTC')).toBeNull();
	});
});

describe('browser frame engine', () => {
	let server: FakeServer;
	let engine: FrameEngine;

	beforeEach(() => {
		vi.useFakeTimers();
		vi.setSystemTime(new Date('2026-10-25T12:00:00Z'));
		server = new FakeServer();
		const c = client(server);
		engine = new FrameEngine({
			client: c,
			socket: (events) =>
				new DeviceSocket({ url: () => c.socketUrl('https://k.test'), create: (u) => new FakeSocket(u), events }),
			enrollInfo: () => ENROLL,
			heartbeat: () => ({ app_version: '0.1.0-web', uptime_s: 1 }),
			reload: () => {}
		});
	});
	afterEach(() => {
		engine.stop();
		vi.useRealTimers();
	});

	const flush = async () => {
		for (let i = 0; i < 20; i++) await Promise.resolve();
	};

	it('enrolls, shows its code, starts when claimed, plays, celebrates a new photo and answers commands', async () => {
		server.push({ type: 'settings', settings: { version: 1, slideshow: { interval_s: 10, transition: 'kenburns' } } });
		server.push({ type: 'frame', frame: { name: 'TV frame', owner_name: 'Olga' } });
		server.push({ type: 'placement.upsert', placement: placementChange('a', { received: '2026-10-01T00:00:00Z' }) });
		server.push({ type: 'placement.upsert', placement: placementChange('b', { received: '2026-10-02T00:00:00Z' }) });
		await engine.start();
		await flush();
		expect(engine.view.status).toBe('code');
		expect(engine.view.code?.code).toBe('12345678');

		const ws = FakeSocket.last!;
		ws.open();
		server.claimed = true;
		ws.receive({ type: 'claimed', owner_name: 'Olga' });
		await flush();
		expect(engine.view.status).toBe('running');
		expect(engine.view.frameName).toBe('TV frame');
		expect(engine.view.slide?.placement.id).toBe('b');
		expect(engine.view.slide?.kenBurns).toBe(true);
		expect(engine.view.next?.id).toBe('a');
		expect(ws.sent).toContainEqual(expect.objectContaining({ type: 'now_showing', placement_id: 'b', next_placement_id: 'a' }));

		await vi.advanceTimersByTimeAsync(10_500);
		expect(engine.view.slide?.placement.id).toBe('a');

		// A new photo arrives: it shows at once, with the celebration.
		server.push({ type: 'placement.upsert', placement: placementChange('c', { sender: 'Bea', received: '2026-10-25T11:59:00Z' }) });
		ws.receive({ type: 'changes', latest_seq: 5 });
		await flush();
		expect(engine.view.slide?.placement.id).toBe('c');
		expect(engine.view.celebrate).toEqual({ senderName: 'Bea', count: 1 });

		// Remote commands answer with command_result.
		ws.receive({ type: 'command', id: 'k1', command_type: 'show_placement', args: { placement_id: 'a' } });
		expect(engine.view.slide?.placement.id).toBe('a');
		ws.receive({ type: 'command', id: 'k2', command_type: 'sleep', args: {} });
		expect(engine.view.screen).toBe('off');
		ws.receive({ type: 'command', id: 'k3', command_type: 'wake', args: {} });
		expect(engine.view.screen).toBe('on');
		ws.receive({ type: 'command', id: 'k4', command_type: 'teleport', args: {} });
		const results = ws.sent.filter((m) => (m as { type: string }).type === 'command_result');
		expect(results).toEqual([
			{ type: 'command_result', id: 'k1', ok: true, error_code: null },
			{ type: 'command_result', id: 'k2', ok: true, error_code: null },
			{ type: 'command_result', id: 'k3', ok: true, error_code: null },
			{ type: 'command_result', id: 'k4', ok: false, error_code: 'unsupported' }
		]);

		// Reactions, and batched acks (delivered + seen).
		expect(await engine.react('love')).toBe(true);
		expect(server.calls.some((c) => c.method === 'PUT' && c.path === '/v1/device/placements/a/reaction' && (c.body as { reaction: string }).reaction === 'love')).toBe(true);
		await vi.advanceTimersByTimeAsync(10_000);
		const acks = server.calls.filter((c) => c.path === '/v1/device/acks').map((c) => c.body as { delivered: string[]; seen: { placement_id: string }[] });
		expect(acks.flatMap((a) => a.delivered).sort()).toEqual(['a', 'b', 'c']);
		expect(acks.flatMap((a) => a.seen.map((s) => s.placement_id))).toEqual(expect.arrayContaining(['a', 'b', 'c']));
		await vi.advanceTimersByTimeAsync(15_000);
		expect(server.calls.some((c) => c.path === '/v1/device/heartbeat')).toBe(true);
	});

	it('goes back to setup when the frame is removed', async () => {
		server.claimed = true;
		await engine.start();
		await flush();
		expect(engine.view.status).toBe('running');
		FakeSocket.last!.open();
		FakeSocket.last!.receive({ type: 'revoked' });
		expect(engine.view.status).toBe('revoked');
		expect(engine.feed.placements.size).toBe(0);
	});
});
