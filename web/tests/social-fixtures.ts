import type { Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, makeFrame, mockApi, uid, type MockState } from './mock-api';
import { EMPTY_SUMMARY, emptySocial, sid } from './mock-social';

// A family with family social on (docs/spec/family-social.md): shared by the social specs.

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
const monday = (d = new Date()) => {
	const x = new Date(d.getFullYear(), d.getMonth(), d.getDate());
	x.setDate(x.getDate() - ((x.getDay() + 6) % 7));
	return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`;
};

export function socialState(opts: { flags?: boolean } = {}) {
	const st = emptyState();
	st.discovery.feeds = true;
	if (opts.flags !== false) Object.assign(st.discovery, { social: true, chats: true, engagement: true, voice_messages: true });
	st.me = { id: uid('u'), display_name: 'Anna', email: 'anna@example.org', avatar_url: null, locale: 'en', created_at: ago(9999), show_read_receipts: true };
	const me: S['PersonRef'] = { user_id: st.me.id, display_name: 'Anna' };
	const ben: S['PersonRef'] = { user_id: uid('u'), display_name: 'Ben' };
	const clara: S['PersonRef'] = { user_id: uid('u'), display_name: 'Clara' };

	const oma = makeFrame({ name: 'Oma’s frame', role: 'owner' });
	Object.assign(oma, {
		freshness: { state: 'quiet', level: 0.3, days_quiet: 9, last_shared_at: ago(9 * 1440), last_shared_by: ben },
		photo_request: { id: uid('rq'), frame_id: oma.id, frame_name: oma.name, state: 'open', created_at: ago(60), expires_at: new Date(Date.now() + 86_400_000).toISOString() }
	});
	st.frames = [oma];
	st.settings[oma.id] = defaultSettings(1);

	const famConv = sid('cv');
	const feed: S['Feed'] = {
		id: uid('fd'),
		name: 'Peeters',
		cover_url: null,
		role: 'owner',
		member_count: 3,
		subscription_count: 1, my_post_count: 0,
		last_post_at: ago(30),
		created_at: ago(9000),
		owner_name: 'Anna',
		members_see_reactions: true,
		members_see_receipts: false,
		new_members_can_post: true,
		members_can_create_circles: true,
		post_count: 4,
		pending_subscription_count: 0,
		pending_join_request_count: 0,
		freshness: { state: 'fresh', level: 1, days_quiet: 0 },
		streak: { current_weeks: 12, week_photos: 2, goal: 3, met: false },
		conversation_id: famConv,
		mute: 'none'
	};
	// A family where Anna only reads: it must not be offered when sending.
	const readOnly: S['Feed'] = { ...feed, id: uid('fd'), name: 'Janssens', role: 'member', conversation_id: null, streak: null };
	st.feeds = [feed, readOnly];
	st.feedMembers[feed.id] = [
		{ user_id: me.user_id, display_name: 'Anna', role: 'owner', joined_at: ago(9000) },
		{ user_id: ben.user_id, display_name: 'Ben', role: 'poster', joined_at: ago(5000) },
		{ user_id: clara.user_id, display_name: 'Clara', role: 'member', joined_at: ago(4000) }
	];
	st.subscriptions = [
		{ id: uid('sb'), status: 'active', history: 'none', feed: { id: feed.id, name: feed.name }, frame: { id: oma.id, name: oma.name }, created_at: ago(900), activated_at: ago(900), can_manage: true, can_decide: true }
	];

	const soc = emptySocial();
	st.social = soc;
	const item = (sender: S['PersonRef']): S['Item'] => ({ id: uid('i'), kind: 'photo', status: 'ready', sender_id: sender.user_id, sender_name: sender.display_name, caption: null, created_at: ago(30) });
	const thread = (postId: string, extra: Partial<S['ThreadSummary']> = {}): S['ThreadSummary'] => ({
		post_id: postId,
		state: 'open',
		comment_count: 0,
		latest_comments: [],
		reactions: EMPTY_SUMMARY,
		can_comment: true,
		can_moderate: true,
		unread_count: 0,
		muted: false,
		...extra
	});
	const promptId = sid('pr');
	const p4 = sid('po');
	const p3 = sid('po');
	const p2 = sid('po');
	const p1 = sid('po');
	const c1: S['Comment'] = { id: sid('c'), post_id: p1, kind: 'person', author: clara, body: 'Look at her face 😂', mentions: [], reactions: EMPTY_SUMMARY, created_at: ago(20), can_edit: false, can_delete: true };
	const c2: S['Comment'] = { id: sid('c'), post_id: p1, kind: 'person', parent_id: c1.id, author: ben, body: 'Haha, yes', mentions: [], reactions: EMPTY_SUMMARY, created_at: ago(15), can_edit: false, can_delete: true };
	const c3: S['Comment'] = { id: sid('c'), post_id: p1, kind: 'frame', author: null, frame: { frame_id: oma.id, frame_name: 'Oma’s frame', reaction: 'love' }, body: null, created_at: ago(14) };
	const mine: S['Comment'] = { id: sid('c'), post_id: p1, kind: 'person', author: me, body: 'So sweet', mentions: [], reactions: EMPTY_SUMMARY, created_at: ago(10), can_edit: true, can_delete: true };
	soc.comments[p1] = [c1, c2, c3, mine];
	const reactions: S['ReactionSummary'] = {
		total: 2,
		counts: [
			{ emoji: 'love', count: 1 },
			{ emoji: 'laugh', count: 1 }
		],
		people: [{ person: clara, emoji: 'laugh' }],
		frames: [{ frame_id: oma.id, frame_name: 'Oma’s frame', reaction: 'love' }],
		mine: null
	};
	soc.reactions[`post:${p1}`] = [{ person: clara, emoji: 'laugh', at: ago(20) }];
	soc.posts = [
		{
			id: p1,
			kind: 'feed',
			feed_id: feed.id,
			feed_name: 'Peeters',
			poster: ben,
			created_at: ago(30),
			items: [item(ben), item(ben)],
			seen: { frames: 1, seen: 1 },
			thread: thread(p1, { comment_count: 3, latest_comments: [c2, mine], unread_count: 1, reactions })
		},
		{ id: p2, kind: 'feed', feed_id: feed.id, feed_name: 'Peeters', poster: clara, created_at: ago(60), items: [item(clara)], prompt: { id: promptId, title: 'Your view right now' }, thread: thread(p2) },
		{ id: p3, kind: 'feed', feed_id: feed.id, feed_name: 'Peeters', poster: me, send_id: uid('5'), created_at: ago(90), items: [item(me)], prompt: { id: promptId, title: 'Your view right now' }, thread: thread(p3), can_remove: true },
		{ id: p4, kind: 'frame', frame_id: oma.id, frame_name: 'Oma’s frame', poster: me, send_id: uid('5'), created_at: ago(200), items: [item(me)], thread: thread(p4) }
	];
	soc.prompts[feed.id] = [{ id: promptId, feed_id: feed.id, week_of: monday(), status: 'current', source: 'server', library_key: 'view', title: 'Your view right now', answer_count: 2 }];
	soc.streaks[feed.id] = { enabled: true, goal: 3, current_weeks: 12, best_weeks: 14, freezes_left: 1, week: { week_of: monday(), photos: 2, met: false, helpers: [ben, clara] } };
	soc.onThisDay = { date: new Date().toISOString().slice(0, 10), groups: [{ feed_id: feed.id, feed_name: 'Peeters', years_ago: 1, post_ids: [p3], items: [item(me), item(me)] }] };
	soc.milestones = [{ key: 'first_photo', achieved_at: ago(100), shown: false, seen: false }];

	// Chats: the family chat, a direct chat with Ben, and Ben's photo in it.
	const direct = sid('cv');
	const benMsg: S['Message'] = { id: sid('m'), conversation_id: direct, kind: 'person', author: ben, body: 'Hi Anna! Look at this', items: [], reactions: EMPTY_SUMMARY, deleted: false, created_at: ago(12) };
	const benPhoto: S['Message'] = { id: sid('m'), conversation_id: direct, kind: 'person', author: ben, body: null, items: [item(ben)], reactions: EMPTY_SUMMARY, deleted: false, created_at: ago(11) };
	const myMsg: S['Message'] = { id: sid('m'), conversation_id: direct, kind: 'person', author: me, body: 'Lovely!', items: [], reactions: EMPTY_SUMMARY, deleted: false, created_at: ago(10) };
	const recapMsg: S['Message'] = {
		id: sid('m'),
		conversation_id: famConv,
		kind: 'system',
		author: null,
		items: [],
		reactions: EMPTY_SUMMARY,
		deleted: false,
		created_at: ago(300),
		system: { type: 'monthly_recap', month: '2026-10', recap: { feed_id: feed.id, month: '2026-10', photos_shared: 42, videos_shared: 3, sharer_count: 5, new_members: [clara], streak_weeks: 11 } }
	};
	soc.messages[direct] = [benMsg, benPhoto, myMsg];
	soc.messages[famConv] = [recapMsg];
	const members = (people: S['PersonRef'][]): S['ConversationMember'][] => people.map((p) => ({ person: p, role: 'member' }));
	soc.conversations = [
		{
			id: direct,
			kind: 'direct',
			name: 'Ben',
			other: ben,
			members: [
				{ person: me, role: 'member', last_read_message_id: myMsg.id },
				{ person: ben, role: 'member', last_read_message_id: benPhoto.id }
			],
			member_count: 2,
			my_role: 'member',
			unread_count: 0,
			last_message: myMsg,
			last_activity_at: myMsg.created_at,
			muted: false,
			archived: false,
			read_only: false,
			read_receipts: true,
			read_up_to: myMsg.id
		},
		{
			id: famConv,
			kind: 'family',
			name: 'Peeters',
			feed_id: feed.id,
			members: members([me, ben, clara]),
			member_count: 3,
			my_role: 'member',
			unread_count: 2,
			last_message: recapMsg,
			last_activity_at: ago(300),
			muted: false,
			archived: false,
			read_only: false,
			read_receipts: false
		}
	];
	soc.contacts = [
		{ person: ben, feeds: [{ feed_id: feed.id, feed_name: 'Peeters' }], frames: [], blocked: false, direct_conversation_id: direct },
		{ person: clara, feeds: [{ feed_id: feed.id, feed_name: 'Peeters' }], frames: [], blocked: false }
	];
	soc.blocks = [{ person: { user_id: uid('u'), display_name: 'Spammy' }, created_at: ago(100) }];
	soc.reports = [{ id: sid('r'), target_kind: 'comment', target_id: c1.id, post_id: p1, feed_id: feed.id, reason: 'unkind', author: clara, excerpt: 'Look at her face 😂', status: 'open', created_at: ago(5) }];
	soc.badge = { total: 1, conversations: 1, threads: 0 };
	return { st, soc, oma, feed, readOnly, me, ben, clara, p1, p2, p3, p4, c1, c2, mine, promptId, direct, famConv, benMsg, benPhoto, myMsg };
}

export async function signIn(page: Page, st: MockState, landing: RegExp = /\/home$/) {
	await mockApi(page, st);
	await page.goto('/');
	await page.getByLabel('Email').fill('anna@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(landing);
}

export const call = (st: MockState, method: string, path: string | RegExp) =>
	st.calls.find((c) => c.method === method && (typeof path === 'string' ? c.path === path : path.test(c.path)));

