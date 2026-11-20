import { describe, expect, it } from 'vitest';
import type { Subscription } from './api/client';
import { dedupePosts, framesOf, rankFamilies, surnameOf } from './family';

const fam = (id: string, name: string, last?: string) => ({ id, name, last_post_at: last ?? null, created_at: '2026-01-01T00:00:00Z' });

describe('home family', () => {
	const fam = (id: string, role: 'owner' | 'admin' | 'poster' = 'poster') => ({ id, name: id, role, created_at: '2026-01-01T00:00:00Z' });
	it('is the family posted to most recently', () => {
		const list = [fam('a'), fam('b')];
		expect(rankFamilies(list, { a: { last_posted_at: '2026-11-01T00:00:00Z' }, b: { last_posted_at: '2026-11-10T00:00:00Z' } })[0]!.id).toBe('b');
	});
	it('without posts: the one you created, then the oldest membership', () => {
		expect(rankFamilies([fam('a'), fam('b', 'owner')], {})[0]!.id).toBe('b');
		expect(rankFamilies([fam('a'), fam('b')], { a: { joined_at: '2026-05-01T00:00:00Z' }, b: { joined_at: '2026-02-01T00:00:00Z' } })[0]!.id).toBe('b');
	});
});

describe('dedupePosts', () => {
	it('shows a post sent to several families once', () => {
		const p = (id: string, user: string, items: string[], send?: string) => ({ id, poster: { user_id: user }, items: items.map((i) => ({ id: i })), send_id: send ?? null });
		const list = [p('1', 'u', ['x', 'y']), p('2', 'u', ['x', 'y']), p('3', 'v', ['x', 'y']), p('4', 'u', ['z'], 's1'), p('5', 'u', ['z'], 's1'), p('6', 'u', [])];
		expect(dedupePosts(list).map((x) => x.id)).toEqual(['1', '3', '4', '6']);
	});
});

describe('surnameOf', () => {
	it('takes the last word of a full name', () => {
		expect(surnameOf('Jan Peeters')).toBe('Peeters');
		expect(surnameOf('  Anna  van Dam ')).toBe('Dam');
	});
	it('has none for a single name', () => {
		expect(surnameOf('Oma')).toBeNull();
		expect(surnameOf('')).toBeNull();
		expect(surnameOf(undefined)).toBeNull();
	});
});

describe('framesOf', () => {
	const sub = (id: string, name: string, status: Subscription['status']) =>
		({ id: `s-${id}`, status, frame: { id, name, online: id === 'f1' }, feed: { id: 'fd', name: 'F' } }) as unknown as Subscription;
	it('lists live frames first, then pending ones, and leaves out ended ones', () => {
		const list = framesOf([sub('f3', 'Kitchen', 'pending'), sub('f2', 'Oma', 'active'), sub('f1', 'Attic', 'active'), sub('f4', 'Old', 'removed')]);
		expect(list.map((f) => [f.id, f.status])).toEqual([
			['f1', 'active'],
			['f2', 'active'],
			['f3', 'pending']
		]);
		expect(list[0]!.online).toBe(true);
	});
});
