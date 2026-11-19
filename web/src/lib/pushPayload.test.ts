import { describe, expect, it } from 'vitest';
import { base64UrlToBytes, pushTag, pushUrl, sameKey } from './pushPayload';

const SEND = '0192f0a0-0005-7000-8000-000000000001';
const FRAME = '0192f0a0-000f-7000-8000-000000000002';

describe('push payloads', () => {
	it('opens the most useful page', () => {
		expect(pushUrl({ key: 'delivered', ids: { send_id: SEND, frame_id: FRAME } })).toBe(`/history/${SEND}`);
		expect(pushUrl({ key: 'reaction', ids: { send_id: SEND, placement_id: 'x', frame_id: FRAME } })).toBe(`/history/${SEND}`);
		expect(pushUrl({ key: 'join_request', ids: { frame_id: FRAME, request_id: 'r' } })).toBe(`/frames/${FRAME}?tab=people`);
		expect(pushUrl({ key: 'membership', ids: { frame_id: FRAME } })).toBe(`/frames/${FRAME}`);
		expect(pushUrl({ type: 'event_invitation.decided', key: 'event_invitation.accepted', ids: { frame_id: FRAME } })).toBe(`/frames/${FRAME}?tab=calendar`);
		expect(pushUrl({})).toBe('/history');
		// Never builds a URL from something that isn't an id.
		expect(pushUrl({ ids: { send_id: '../../evil' } })).toBe('/history');
	});

	it('opens the email pending list and the Telegram chat in the settings', () => {
		const FEED = '0192f0a0-0010-7000-8000-000000000003';
		const ADDR = '0192f0a0-0020-7000-8000-000000000006';
		const CHAT = '0192f0a0-0021-7000-8000-000000000007';
		expect(pushUrl({ key: 'email_pending_sender', ids: { frame_id: FRAME, address_id: ADDR, sender_id: SEND } })).toBe(`/frames/${FRAME}?tab=settings&email=${ADDR}`);
		expect(pushUrl({ key: 'email_pending_sender', ids: { feed_id: FEED, address_id: ADDR } })).toBe(`/feeds/${FEED}?tab=settings&email=${ADDR}`);
		expect(pushUrl({ key: 'telegram_attention', ids: { frame_id: FRAME, chat_id: CHAT } })).toBe(`/frames/${FRAME}?tab=settings&telegram=${CHAT}`);
		expect(pushUrl({ key: 'telegram_attention', ids: { feed_id: FEED, chat_id: CHAT } })).toBe(`/feeds/${FEED}?tab=settings&telegram=${CHAT}`);
		expect(pushUrl({ key: 'telegram_attention', ids: { frame_id: FRAME, chat_id: '../x' } })).toBe(`/frames/${FRAME}?tab=settings`);
		expect(pushTag({ key: 'email_pending_sender', ids: { address_id: ADDR, sender_id: SEND, frame_id: FRAME } })).toBe(`email_pending_sender:${SEND}`);
		expect(pushTag({ key: 'telegram_attention', ids: { chat_id: CHAT, frame_id: FRAME } })).toBe(`telegram_attention:${CHAT}`);
	});

	it('opens the feed pages for feed notifications', () => {
		const FEED = '0192f0a0-0010-7000-8000-000000000003';
		const POST = '0192f0a0-0011-7000-8000-000000000004';
		expect(pushUrl({ key: 'subscription_request', ids: { feed_id: FEED, subscription_id: 's' } })).toBe(`/feeds/${FEED}?tab=subscriptions`);
		expect(pushUrl({ key: 'feed_join_request', ids: { feed_id: FEED, request_id: 'r' } })).toBe(`/feeds/${FEED}?tab=members`);
		expect(pushUrl({ key: 'feed_membership', ids: { feed_id: FEED } })).toBe(`/feeds/${FEED}`);
		expect(pushUrl({ key: 'feed_membership' })).toBe('/feeds');
		expect(pushUrl({ key: 'subscription_decided', ids: { feed_id: FEED, frame_id: FRAME } })).toBe(`/frames/${FRAME}?tab=feeds`);
		expect(pushUrl({ key: 'subscription_removed', ids: { feed_id: FEED, frame_id: FRAME } })).toBe(`/frames/${FRAME}?tab=feeds`);
		expect(pushUrl({ key: 'subscription_decided', ids: { feed_id: FEED } })).toBe(`/feeds/${FEED}`);
		// Reactions on a feed post open the post, not the send.
		expect(pushUrl({ key: 'reaction', ids: { send_id: SEND, feed_id: FEED, post_id: POST, frame_id: FRAME } })).toBe(`/feeds/${FEED}?post=${POST}`);
		expect(pushTag({ key: 'reaction', ids: { send_id: SEND, post_id: POST } })).toBe(`reaction:${POST}`);
	});

	it('opens the Birthdays tab and tags greetings per person and day', () => {
		const FEED = '0192f0a0-0010-7000-8000-000000000003';
		const BDAY = '0192f0a0-0012-7000-8000-000000000005';
		for (const key of ['birthday_suggestion', 'birthday_decided', 'birthday_today', 'birthday_greeting'])
			expect(pushUrl({ key, ids: { feed_id: FEED, birthday_id: BDAY } })).toBe(`/feeds/${FEED}?tab=birthdays`);
		expect(pushUrl({ key: 'birthday_today' })).toBe('/feeds');
		const now = new Date(2026, 2, 12, 10, 14);
		expect(pushTag({ key: 'birthday_greeting', ids: { feed_id: FEED, birthday_id: BDAY, greeting_id: 'g' } }, now)).toBe(`birthday:${BDAY}:2026-03-12`);
		expect(pushTag({ key: 'birthday_suggestion', ids: { feed_id: FEED, birthday_id: BDAY } })).toBe(`birthday_suggestion:${BDAY}`);
		expect(pushTag({ key: 'birthday_today', ids: {} }, now)).toBe('birthday_today:2026-03-12');
		// The server's date and tag win over the device's local date.
		expect(pushTag({ key: 'birthday_greeting', ids: { feed_id: FEED, birthday_id: BDAY, frame_id: FRAME, date: '2026-03-11' } }, now)).toBe(`birthday:${BDAY}:2026-03-11`);
		expect(pushTag({ key: 'birthday_today', ids: { date: '2026-03-11' } }, now)).toBe('birthday_today:2026-03-11');
		expect(pushTag({ key: 'birthday_greeting', tag: `birthday:${BDAY}:2026-03-10`, ids: { birthday_id: BDAY, date: '2026-03-11' } }, now)).toBe(`birthday:${BDAY}:2026-03-10`);
		// A tag that isn't a plain token is ignored.
		expect(pushTag({ key: 'birthday_greeting', tag: '<script>', ids: { birthday_id: BDAY } }, now)).toBe(`birthday:${BDAY}:2026-03-12`);
	});

	it('collapses notifications about the same send', () => {
		expect(pushTag({ key: 'reaction', ids: { send_id: SEND } })).toBe(`reaction:${SEND}`);
		expect(pushTag({ type: 'join_request', ids: { request_id: 'r1' } })).toBe('join_request:r1');
	});

	it('decodes VAPID keys and compares them', () => {
		const bytes = base64UrlToBytes('BAEC_-8');
		expect([...bytes]).toEqual([4, 1, 2, 255, 239]);
		expect(sameKey(bytes.buffer, bytes)).toBe(true);
		expect(sameKey(new Uint8Array([1]).buffer, bytes)).toBe(false);
		expect(sameKey(null, bytes)).toBe(true);
	});
	it('opens the photo source or the finished Google import', () => {
		const FEED = '0192f0a0-0010-7000-8000-000000000003';
		const SRC = '0192f0a0-0013-7000-8000-000000000006';
		const ALBUM = '0192f0a0-0014-7000-8000-000000000007';
		const IMP = '0192f0a0-0015-7000-8000-000000000008';
		expect(pushUrl({ key: 'photo_source_attention', ids: { source_id: SRC, feed_id: FEED } })).toBe(`/feeds/${FEED}?tab=sources`);
		expect(pushUrl({ key: 'photo_source_attention', ids: { source_id: SRC, frame_id: FRAME, album_id: ALBUM } })).toBe(`/frames/${FRAME}?tab=library&album=${ALBUM}`);
		expect(pushUrl({ key: 'photo_source_attention', ids: { source_id: SRC } })).toBe('/photo-sources');
		expect(pushUrl({ key: 'google_import_done', ids: { import_id: IMP } })).toBe(`/photo-sources?google_import=${IMP}`);
		expect(pushUrl({ key: 'google_import_done', ids: { import_id: '../x' } })).toBe('/photo-sources');
		expect(pushTag({ key: 'photo_source_attention', ids: { source_id: SRC, feed_id: FEED } })).toBe(`photo_source_attention:${SRC}`);
	});
});
