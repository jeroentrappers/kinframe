import { describe, expect, it } from 'vitest';
import type { Subscription } from './api/client';
import { dedupePosts, framesOf, rankFamilies, surnameOf } from './family';

describe('home family', () => {
	const fam = (id: string, posts: number, role: 'owner' | 'admin' | 'poster' = 'poster', created = '2026-01-01T00:00:00Z') => ({ id, name: id, role, my_post_count: posts, created_at: created });
	it('is the family you post to most', () => {
		expect(rankFamilies([fam('a', 3), fam('b', 12), fam('c', 0, 'owner')]).map((f) => f.id)).toEqual(['b', 'a', 'c']);
	});
	it('on a tie: the one you created', () => {
		expect(rankFamilies([fam('a', 4), fam('b', 4, 'owner'), fam('c', 4, 'admin')])[0]!.id).toBe('b');
		expect(rankFamilies([fam('a', 0), fam('b', 0, 'owner')])[0]!.id).toBe('b');
	});
	it('else the oldest membership', () => {
		const list = [fam('a', 2), fam('b', 2)];
		expect(rankFamilies(list, { a: { joined_at: '2026-05-01T00:00:00Z' }, b: { joined_at: '2026-02-01T00:00:00Z' } })[0]!.id).toBe('b');
		expect(rankFamilies(list, { a: { joined_at: '2026-01-01T00:00:00Z' }, b: { joined_at: '2026-02-01T00:00:00Z' } })[0]!.id).toBe('a');
	});
	it('without the membership yet, the older family; then the name', () => {
		expect(rankFamilies([fam('a', 0, 'poster', '2026-06-01T00:00:00Z'), fam('b', 0, 'poster', '2026-03-01T00:00:00Z')])[0]!.id).toBe('b');
		expect(rankFamilies([fam('b', 1), fam('a', 1)])[0]!.id).toBe('a');
	});
	it('posting more beats having created the family', () => {
		expect(rankFamilies([fam('mine', 1, 'owner'), fam('oma', 2)])[0]!.id).toBe('oma');
	});
	it('does not change the list it is given', () => {
		const list = [fam('a', 1), fam('b', 2)];
		rankFamilies(list);
		expect(list.map((f) => f.id)).toEqual(['a', 'b']);
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
