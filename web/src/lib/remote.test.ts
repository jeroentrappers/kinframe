import { describe, expect, it } from 'vitest';
import { frameLabel, groupFollows, isOtherServer, loadOtherServers, parseHost, parseInviteUrl, saveOtherServers, serverName, upsertFollow, type RemoteSubscription } from './remote';

const TOKEN = '9cQ2xV0kFq7Lm3Zt8bW1rA';

const follow = (p: Partial<RemoteSubscription>): RemoteSubscription => ({
	id: 'r',
	frame_id: 'f',
	status: 'active',
	feed_name: 'Janssens',
	server: { server_name: 'Janssens', server_url: 'https://kinframe.janssens.be', host: 'kinframe.janssens.be' },
	history: 'last_n',
	on_end: 'remove',
	created_at: '2026-11-19T10:00:00Z',
	...p
});

describe('invite links of other servers', () => {
	it('recognises frame invite links, with or without a scheme', () => {
		expect(parseInviteUrl(`https://kinframe.janssens.be/fi/${TOKEN}`)).toEqual({ url: `https://kinframe.janssens.be/fi/${TOKEN}`, host: 'kinframe.janssens.be', token: TOKEN });
		expect(parseInviteUrl(`  kinframe.janssens.be/fi/${TOKEN}/ `)?.url).toBe(`https://kinframe.janssens.be/fi/${TOKEN}`);
		expect(parseInviteUrl(`http://localhost:4173/fi/${TOKEN}`)?.host).toBe('localhost:4173');
	});

	it('refuses anything else', () => {
		expect(parseInviteUrl('')).toBeNull();
		expect(parseInviteUrl('KF7Q-M2XA')).toBeNull();
		expect(parseInviteUrl(`https://kinframe.janssens.be/i/${TOKEN}`)).toBeNull();
		expect(parseInviteUrl('https://kinframe.janssens.be/fi/short')).toBeNull();
		expect(parseInviteUrl(`ftp://kinframe.janssens.be/fi/${TOKEN}`)).toBeNull();
		expect(parseInviteUrl(`https://intranet/fi/${TOKEN}`)).toBeNull();
	});

	it('tells this server from others', () => {
		expect(isOtherServer('kinframe.janssens.be', 'kinframe.appmire.be')).toBe(true);
		expect(isOtherServer('Kinframe.Appmire.be', 'kinframe.appmire.be')).toBe(false);
	});
});

describe('labels', () => {
	it('adds the server to frames on other servers only', () => {
		const fmt = (f: string, s: string) => `${f} (${s})`;
		expect(frameLabel('Oma’s frame', 'kinframe.appmire.be', fmt)).toBe('Oma’s frame (kinframe.appmire.be)');
		expect(frameLabel('Oma’s frame', null, fmt)).toBe('Oma’s frame');
		expect(frameLabel('Oma’s frame', '  ', fmt)).toBe('Oma’s frame');
	});

	it('names a server by its label, else its host', () => {
		expect(serverName({ server_name: 'Janssens', server_url: 'https://a.example' })).toBe('Janssens');
		expect(serverName({ server_name: '', host: 'a.example', server_url: 'https://a.example' })).toBe('a.example');
		expect(serverName({ server_name: '', server_url: 'https://b.example:8443/x' })).toBe('b.example:8443');
	});

	it('parses typed server addresses', () => {
		expect(parseHost('Kinframe.Example.org')).toBe('kinframe.example.org');
		expect(parseHost('https://kinframe.example.org/settings')).toBe('kinframe.example.org');
		expect(parseHost('nope')).toBeNull();
		expect(parseHost('')).toBeNull();
	});
});

describe('follows', () => {
	it('groups live and ended follows, newest first', () => {
		const g = groupFollows([
			follow({ id: 'a', created_at: '2026-11-01T00:00:00Z' }),
			follow({ id: 'b', status: 'pending', created_at: '2026-11-10T00:00:00Z' }),
			follow({ id: 'c', status: 'removed', removed_reason: 'server_blocked' }),
			follow({ id: 'd', status: 'error' })
		]);
		expect(g.live.map((s) => s.id)).toEqual(['d', 'b', 'a']);
		expect(g.ended.map((s) => s.id)).toEqual(['c']);
	});

	it('replaces or adds a follow', () => {
		expect(upsertFollow([follow({})], follow({ status: 'error' }))[0]!.status).toBe('error');
		expect(upsertFollow([follow({})], follow({ id: 'n' })).map((s) => s.id)).toEqual(['n', 'r']);
	});

	it('keeps the web’s links to other servers', () => {
		const mem: Record<string, string> = {};
		const storage = { getItem: (k: string) => mem[k] ?? null, setItem: (k: string, v: string) => void (mem[k] = v) };
		saveOtherServers(storage, ['a.example', 'a.example', 'b.example']);
		expect(loadOtherServers(storage)).toEqual(['a.example', 'b.example']);
		mem['kinframe.otherServers'] = '["ok.example", 3, "<bad>"]';
		expect(loadOtherServers(storage)).toEqual(['ok.example']);
		mem['kinframe.otherServers'] = '{';
		expect(loadOtherServers(storage)).toEqual([]);
		expect(loadOtherServers(null)).toEqual([]);
	});
});
