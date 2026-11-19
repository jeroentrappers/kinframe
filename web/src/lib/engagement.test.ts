import { describe, expect, it } from 'vitest';
import { currentPrompt, familiesToPreselect, freshnessWords, lastWeekOf, monthDate, nextMondays, requestOpen, requestSendUrl, ringStyle, weekOf } from './engagement';
import { actionBody, pushActions, pushTag, pushUrl } from './pushPayload';

describe('frame requests through a family', () => {
	it('preselects the families reaching the frame that you can post to', () => {
		expect(familiesToPreselect(['b', 'c'], ['a', 'c', 'b'])).toEqual(['c', 'b']);
		expect(familiesToPreselect(['x'], ['a'])).toEqual([]);
		expect(requestSendUrl('f1', 'r1')).toBe('/send?frame=f1&request=r1');
		expect(requestSendUrl('f1')).toBe('/send?frame=f1');
	});
});

describe('freshness', () => {
	it('uses words, not numbers', () => {
		expect(freshnessWords({ state: 'fresh', level: 1, days_quiet: 0 })).toEqual({ key: 'fresh' });
		expect(freshnessWords({ state: 'recent', level: 0.7, days_quiet: 4 })).toEqual({ key: 'days', count: 4 });
		expect(freshnessWords({ state: 'quiet', level: 0.4, days_quiet: 9 })).toEqual({ key: 'daysQuiet', count: 9 });
		expect(freshnessWords({ state: 'quiet', level: 0, days_quiet: 15 })).toEqual({ key: 'weeksQuiet', count: 2 });
		expect(freshnessWords({ state: 'never', level: 0, days_quiet: null })).toEqual({ key: 'never' });
		expect(freshnessWords(undefined)).toBeNull();
	});

	it('draws the ring by level and tone', () => {
		expect(ringStyle({ state: 'fresh', level: 1 })).toEqual({ fraction: 1, tone: 'warm' });
		expect(ringStyle({ state: 'quiet', level: 0 })).toEqual({ fraction: 0.06, tone: 'pale' });
		expect(ringStyle({ state: 'recent', level: 0.5 })).toEqual({ fraction: 0.5, tone: 'mild' });
		expect(ringStyle(null)).toEqual({ fraction: 0, tone: 'none' });
	});

	it('knows an open photo request', () => {
		const r = { id: 'r', frame_id: 'f', frame_name: 'Oma', created_at: '', expires_at: '2026-11-20T00:00:00Z', state: 'open' as const };
		expect(requestOpen(r, Date.parse('2026-11-19T00:00:00Z'))).toBe(true);
		expect(requestOpen(r, Date.parse('2026-11-21T00:00:00Z'))).toBe(false);
		expect(requestOpen({ ...r, state: 'answered' })).toBe(false);
		expect(requestOpen(null)).toBe(false);
	});
});

describe('weeks', () => {
	it('finds Mondays', () => {
		expect(weekOf(new Date(2026, 10, 19))).toBe('2026-11-16'); // a Thursday
		expect(weekOf(new Date(2026, 10, 16))).toBe('2026-11-16'); // the Monday itself
		expect(weekOf(new Date(2026, 10, 22))).toBe('2026-11-16'); // Sunday
		expect(lastWeekOf(new Date(2026, 10, 19))).toBe('2026-11-09');
		expect(nextMondays(new Date(2026, 11, 30), 2)).toEqual(['2026-12-28', '2027-01-04']);
		expect(monthDate('2026-10').getMonth()).toBe(9);
	});

	it('picks the current prompt, not a skipped one', () => {
		const p = { id: 'x', feed_id: 'f', week_of: '2026-11-16', source: 'server' as const, title: 'Your view' };
		expect(currentPrompt([{ ...p, status: 'past' }, { ...p, id: 'y', status: 'current' }])?.id).toBe('y');
		expect(currentPrompt([{ ...p, status: 'current', title: '' }])).toBeNull();
	});
});

describe('family social pushes', () => {
	const POST = '0192f0a0-0001-7000-8000-000000000001';
	const COMMENT = '0192f0a0-0002-7000-8000-000000000002';
	const CONV = '0192f0a0-0003-7000-8000-000000000003';
	const FEED = '0192f0a0-0004-7000-8000-000000000004';
	const FRAME = '0192f0a0-0005-7000-8000-000000000005';

	it('opens the thread, the chat or the right page', () => {
		expect(pushUrl({ key: 'comment', ids: { post_id: POST, comment_id: COMMENT, feed_id: FEED } })).toBe(`/posts/${POST}?comment=${COMMENT}`);
		expect(pushUrl({ key: 'comment_reply', ids: { post_id: POST } })).toBe(`/posts/${POST}`);
		expect(pushUrl({ key: 'mention', ids: { conversation_id: CONV, message_id: COMMENT } })).toBe(`/chats/${CONV}`);
		expect(pushUrl({ key: 'mention', ids: { post_id: POST, comment_id: COMMENT } })).toBe(`/posts/${POST}?comment=${COMMENT}`);
		expect(pushUrl({ key: 'message', ids: { conversation_id: CONV } })).toBe(`/chats/${CONV}`);
		expect(pushUrl({ key: 'conversation_added', ids: { conversation_id: CONV } })).toBe(`/chats/${CONV}`);
		expect(pushUrl({ key: 'reaction_person', ids: { target_kind: 'message', conversation_id: CONV } })).toBe(`/chats/${CONV}`);
		expect(pushUrl({ key: 'reaction_person', ids: { target_kind: 'post', post_id: POST } })).toBe(`/posts/${POST}`);
		expect(pushUrl({ key: 'reaction_digest', ids: {} })).toBe('/home');
		expect(pushUrl({ key: 'report', ids: { feed_id: FEED } })).toBe(`/feeds/${FEED}?tab=reports`);
		expect(pushUrl({ key: 'report', ids: {} })).toBe('/settings/reports');
		expect(pushUrl({ key: 'frame_request', ids: { frame_id: FRAME } })).toBe(`/send?frame=${FRAME}`);
		// The request id lets the send page find the families of a frame the person isn't on.
		expect(pushUrl({ key: 'frame_request', ids: { frame_id: FRAME, request_id: POST } })).toBe(`/send?frame=${FRAME}&request=${POST}`);
		expect(pushUrl({ key: 'reminder_quiet', ids: { frame_id: FRAME } })).toBe(`/send?frame=${FRAME}`);
		expect(pushUrl({ key: 'reminder_streak', ids: { feed_id: FEED } })).toBe(`/feeds/${FEED}`);
		expect(pushUrl({ key: 'weekly_summary', ids: { week_of: '2026-11-16' } })).toBe('/home?week=2026-11-16');
		expect(pushUrl({ key: 'weekly_summary', ids: { week_of: '../x' } })).toBe('/home');
		expect(pushUrl({ key: 'message', ids: { conversation_id: '../x' } })).toBe('/history');
	});

	it('uses the server tag and knows the reminder buttons', () => {
		expect(pushTag({ key: 'message', tag: `conversation:${CONV}`, ids: {} })).toBe(`conversation:${CONV}`);
		expect(pushActions({ actions: ['snooze_month', 'fewer_reminders', 'nope'] })).toEqual(['snooze_month', 'fewer_reminders']);
		expect(pushActions({})).toEqual([]);
		expect(actionBody('snooze_month')).toEqual({ snooze_days: 30 });
		expect(actionBody('fewer_reminders')).toEqual({ level: 'fewer' });
	});
});
