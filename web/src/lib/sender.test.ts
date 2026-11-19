import { describe, expect, it } from 'vitest';
import { authorCredit, isChannel, placementCredit, senderLabel, summaryVia, withVia } from './sender';

describe('senderLabel', () => {
	it('shows the sender, or the original sender of a Frameo import', () => {
		expect(senderLabel({ sender_name: 'Oma' })).toBe('Oma');
		expect(senderLabel({ sender_name: "Fien's frame", imported_from: 'frameo', imported_sender_name: 'Anna' })).toBe('Anna · via Frameo');
		expect(senderLabel({ sender_name: "Fien's frame", imported_from: 'frameo', imported_sender_name: ' ' })).toBe('via Frameo');
		expect(senderLabel({})).toBe('');
	});
});

describe('placementCredit', () => {
	it('adds the feed name to feed placements ("Anna · Peeters kids")', () => {
		expect(placementCredit({ origin: 'feed', origin_feed_name: 'Peeters kids', item: { sender_name: 'Anna' } })).toBe('Anna · Peeters kids');
		expect(placementCredit({ origin: 'send', origin_feed_name: 'Peeters kids', item: { sender_name: 'Anna' } })).toBe('Anna');
		expect(placementCredit({ origin: 'feed', origin_feed_name: null, item: { sender_name: 'Anna' } })).toBe('Anna');
		expect(placementCredit({ origin: 'feed', origin_feed_name: 'Peeters kids', item: {} })).toBe('Peeters kids');
		expect(placementCredit({ item: { sender_name: 'Oma' } })).toBe('Oma');
	});
});

describe('photo source credit (ItemVia)', () => {
	const via = { service: 'immich' as const, label: 'Immich', source_id: null };
	it('adds the service: "Anna via Immich", "Anna · Peeters kids via Immich"', () => {
		expect(senderLabel({ sender_name: 'Anna', via })).toBe('Anna via Immich');
		expect(senderLabel({ via })).toBe('via Immich');
		expect(senderLabel({ sender_name: 'Anna', via: null })).toBe('Anna');
		expect(placementCredit({ origin: 'feed', origin_feed_name: 'Peeters kids', item: { sender_name: 'Anna', via } })).toBe('Anna · Peeters kids via Immich');
		expect(placementCredit({ origin: 'send', item: { sender_name: 'Anna', via: { service: 'google_photos', label: 'Google Photos' } } })).toBe('Anna via Google Photos');
		expect(withVia('Anna', { service: 'icloud', label: ' ' })).toBe('Anna');
	});
	it('adds it to send and post summaries: "14 photos via Immich"', () => {
		expect(summaryVia('14 photos', via)).toBe('14 photos via Immich');
		expect(summaryVia('14 photos', null)).toBe('14 photos');
	});
});

describe('email and Telegram credit (ItemVia email, telegram)', () => {
	const email = { service: 'email' as const, label: 'Jan', address_id: 'a1' };
	const telegram = { service: 'telegram' as const, label: 'Ben', telegram_chat_id: 'c1' };
	it('shows the person instead of the sender: "Jan via email"', () => {
		// The sender is the address owner (Anna) sending on Jan's behalf.
		expect(senderLabel({ sender_name: 'Anna', via: email })).toBe('Jan via email');
		expect(senderLabel({ sender_name: 'Anna', via: telegram })).toBe('Ben via Telegram');
		expect(withVia('', email)).toBe('Jan via email');
		expect(withVia('Anna', { service: 'email', label: ' ' })).toBe('Anna');
		expect(isChannel(email)).toBe(true);
		expect(isChannel({ service: 'immich' })).toBe(false);
	});
	it('puts the feed between the person and the channel: "Jan · Peeters kids via email"', () => {
		expect(placementCredit({ origin: 'feed', origin_feed_name: 'Peeters kids', item: { sender_name: 'Anna', via: email } })).toBe('Jan · Peeters kids via email');
		expect(placementCredit({ origin: 'feed', origin_feed_name: 'Peeters kids', item: { sender_name: 'Anna', via: telegram } })).toBe('Ben · Peeters kids via Telegram');
		expect(placementCredit({ origin: 'send', item: { sender_name: 'Anna', via: email } })).toBe('Jan via email');
	});
	it('marks the owner’s sends and the feed posts', () => {
		expect(summaryVia('4 photos', email)).toBe('4 photos via email from Jan');
		expect(summaryVia('12 photos', telegram)).toBe('12 photos via Telegram from Ben');
		expect(authorCredit('Anna', email)).toBe('Jan via email');
		expect(authorCredit('Anna', { service: 'immich', label: 'Immich' })).toBe('Anna');
		expect(authorCredit('Anna', null)).toBe('Anna');
	});
});
