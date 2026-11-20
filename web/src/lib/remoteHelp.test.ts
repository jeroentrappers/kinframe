import { describe, expect, it } from 'vitest';
import { endedKey, formatClock, isRunning, summaryFromSession, upsertSession, type RemoteHelpSession } from './remoteHelp';

const session = (over: Partial<RemoteHelpSession> = {}): RemoteHelpSession => ({
	id: 's1',
	frame_id: 'f1',
	frame_name: 'Oma',
	state: 'pending',
	reason: 'Ben asked by mail',
	started_by_name: 'Op',
	started_at: '2026-11-20T10:00:00Z',
	ends_at: '2026-11-20T18:00:00Z',
	ended_at: null,
	ended_by: null,
	ended_by_name: null,
	hostname: 'kinframe-1234abcd',
	adb_command: 'adb connect kinframe-1234abcd:5555',
	...over
});

describe('remote help', () => {
	it('turns an event into the banner, or null once it ended', () => {
		expect(summaryFromSession(session({ state: 'on' }))).toEqual({ ends_at: '2026-11-20T18:00:00Z', reason: 'Ben asked by mail' });
		expect(summaryFromSession(session({ state: 'ended' }))).toBeNull();
	});

	it('hides the banner after the end time', () => {
		const s = { ends_at: '2026-11-20T18:00:00Z', reason: 'x' };
		expect(isRunning(s, new Date('2026-11-20T17:59:00Z'))).toBe(true);
		expect(isRunning(s, new Date('2026-11-20T18:00:01Z'))).toBe(false);
		expect(isRunning(null)).toBe(false);
	});

	it('formats the end time as a clock time', () => {
		expect(formatClock('2026-11-20T18:05:00Z', 'en')).toMatch(/\d{1,2}:05/);
		expect(formatClock('nope')).toBe('');
	});

	it('keeps active sessions first, newest first', () => {
		const old = session({ id: 'a', state: 'ended', started_at: '2026-11-19T10:00:00Z' });
		const newer = session({ id: 'b', state: 'ended', started_at: '2026-11-20T09:00:00Z' });
		const active = session({ id: 'c', state: 'on', started_at: '2026-11-18T10:00:00Z' });
		expect(upsertSession([old, newer], active).map((s) => s.id)).toEqual(['c', 'b', 'a']);
		expect(upsertSession([active, newer], session({ id: 'c', state: 'ended', started_at: '2026-11-18T10:00:00Z' })).map((s) => s.id)).toEqual(['b', 'c']);
	});

	it('says who ended a session', () => {
		expect(endedKey(session())).toBeNull();
		expect(endedKey(session({ ended_by: 'owner', ended_by_name: 'Ben' }))).toEqual({ key: 'remoteHelp.endedOwner', name: 'Ben' });
		expect(endedKey(session({ ended_by: 'operator' }))).toEqual({ key: 'remoteHelp.endedOperator' });
		expect(endedKey(session({ ended_by: 'expired' }))?.key).toBe('remoteHelp.endedExpired');
	});
});
