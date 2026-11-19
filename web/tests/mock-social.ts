// In-memory family social for the mock API (README §5.18–§5.20): timeline, threads, reactions, circles, chats,
// blocks and reports, and encouragement to share. Hooked into tests/mock-api.ts; only answers while discovery has
// the area's flag, like the server (whose operations answer 501 until they are built).
import type { Route } from '@playwright/test';
import type { components } from '../src/lib/api/schema';

type S = components['schemas'];

export interface SocialMock {
	posts: S['TimelinePost'][];
	comments: Record<string, S['Comment'][]>;
	/** People's reactions per target (`kind:id`), newest last. */
	reactions: Record<string, { person: S['PersonRef']; emoji: S['PersonReaction']; at: string }[]>;
	circles: Record<string, S['Circle'][]>;
	conversations: S['Conversation'][];
	messages: Record<string, S['Message'][]>;
	contacts: S['Person'][];
	blocks: S['Block'][];
	reports: S['Report'][];
	badge: S['Badge'];
	engagement: Record<string, S['FeedEngagement']>;
	streaks: Record<string, S['FeedStreak']>;
	prompts: Record<string, S['FeedPrompt'][]>;
	library: S['PromptLibraryEntry'][];
	onThisDay: S['OnThisDay'];
	weekly: Record<string, S['WeeklySummary']>;
	milestones: S['Milestone'][];
	reminders: S['ReminderPreferences'];
	photoRequests: S['PhotoRequest'][];
	linkPreviews: Record<string, S['LinkPreview']>;
}

export function emptySocial(): SocialMock {
	return {
		posts: [],
		comments: {},
		reactions: {},
		circles: {},
		conversations: [],
		messages: {},
		contacts: [],
		blocks: [],
		reports: [],
		badge: { total: 0, conversations: 0, threads: 0 },
		engagement: {},
		streaks: {},
		prompts: {},
		library: [
			{ key: 'cooked', title: 'Something you cooked' },
			{ key: 'view', title: 'Your view right now' },
			{ key: 'old_photo', title: 'An old photo of you' }
		],
		onThisDay: { date: new Date().toISOString().slice(0, 10), groups: [] },
		weekly: {},
		milestones: [],
		reminders: {
			level: 'normal',
			auto_reduced: false,
			frame_requests: true,
			weekly_summary: true,
			birthday_reminders: true,
			snoozed_until: null,
			quiet_hours: { start: '21:00', end: '09:00' },
			email_digest: 'off',
			email_digest_available: true
		},
		photoRequests: [],
		linkPreviews: {}
	};
}

export const EMPTY_SUMMARY: S['ReactionSummary'] = { total: 0, counts: [], people: [], frames: [] };

/** The minimal mock state this module needs from tests/mock-api.ts. */
interface Host {
	discovery: S['Discovery'];
	me: S['User'] | null;
	frames: S['Frame'][];
	feeds: S['Feed'][];
	feedMembers: Record<string, S['FeedMember'][]>;
	social?: SocialMock;
}

let seq = 0;
/** Ids that sort by time like the server's UUIDv7. */
export const sid = (p = 'aaaa') => `0193${String(Date.now()).slice(-8).padStart(8, '0')}-${p.padStart(4, '0').slice(0, 4)}-7000-8000-${String(++seq).padStart(12, '0')}`;

const now = () => new Date().toISOString();
const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
const none = (route: Route) => route.fulfill({ status: 204 });
const problem = (route: Route, status: number, code: string, title: string) =>
	route.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({ type: `https://kinframe.app/errors/${code}`, title, status, code }) });

function summary(soc: SocialMock, key: string, meId: string | undefined, frames: S['FrameReactionRef'][] = []): S['ReactionSummary'] {
	const list = soc.reactions[key] ?? [];
	const counts = new Map<S['PersonReaction'], number>();
	for (const r of list) counts.set(r.emoji, (counts.get(r.emoji) ?? 0) + 1);
	for (const f of frames) counts.set(f.reaction, (counts.get(f.reaction) ?? 0) + 1);
	return {
		total: list.length + frames.length,
		counts: [...counts.entries()].map(([emoji, count]) => ({ emoji, count })).sort((a, b) => b.count - a.count),
		people: [...list].reverse().slice(0, 3).map((r) => ({ person: r.person, emoji: r.emoji })),
		frames: frames.slice(0, 3),
		mine: list.find((r) => r.person.user_id === meId)?.emoji ?? null
	};
}

export async function socialRoute(state: Host, route: Route, method: string, path: string, url: URL, body: unknown): Promise<unknown | null> {
	const d = state.discovery;
	const soc = (state.social ??= emptySocial());
	const me = state.me;
	const meRef: S['PersonRef'] = { user_id: me?.id ?? 'me', display_name: me?.display_name ?? 'Me', avatar_url: me?.avatar_url ?? null };
	let m: RegExpMatchArray | null;
	const b = (body ?? {}) as Record<string, unknown>;

	const socialOn = d.social === true && d.feeds === true;
	const chatsOn = d.chats === true;
	const engOn = d.engagement === true;
	const off = (route: Route) => problem(route, 501, 'feature_disabled', 'Not implemented');

	// ---------------------------------------------------------------- timeline, posts, threads
	const post = (id: string) => soc.posts.find((p) => p.id === id);
	const refreshThread = (p: S['TimelinePost']) => {
		const list = (soc.comments[p.id] ?? []).filter((c) => c.kind === 'person' && !c.deleted);
		p.thread = { ...p.thread, comment_count: list.length, latest_comments: list.slice(-2) };
	};

	if (path === '/v1/timeline' && method === 'GET') {
		if (!socialOn) return off(route);
		let list = soc.posts;
		const feedId = url.searchParams.get('feed_id');
		const promptId = url.searchParams.get('prompt_id');
		if (feedId) list = list.filter((p) => p.feed_id === feedId);
		if (promptId) list = list.filter((p) => p.prompt?.id === promptId);
		const start = Number(url.searchParams.get('before')?.replace('c:', '') ?? 0) || 0;
		const limit = Number(url.searchParams.get('limit') ?? 20);
		const page = list.slice(start, start + limit);
		return json(route, 200, { posts: page, next_before: start + limit < list.length ? `c:${start + limit}` : null } satisfies S['TimelinePage']);
	}
	if ((m = path.match(/^\/v1\/posts\/([^/]+)$/)) && method === 'GET') {
		if (!socialOn) return off(route);
		const p = post(m[1]!);
		return p ? json(route, 200, p) : problem(route, 404, 'not_found', 'Not found');
	}
	if ((m = path.match(/^\/v1\/posts\/([^/]+)\/comments$/))) {
		if (!socialOn) return off(route);
		const p = post(m[1]!);
		if (!p) return problem(route, 404, 'not_found', 'Not found');
		const list = (soc.comments[p.id] ??= []);
		if (method === 'GET') {
			const after = url.searchParams.get('after');
			const from = after ? list.findIndex((c) => c.id === after) + 1 : 0;
			const limit = Number(url.searchParams.get('limit') ?? 100);
			return json(route, 200, { comments: list.slice(from, from + limit), has_more: from + limit < list.length } satisfies S['CommentPage']);
		}
		if (method === 'POST') {
			if (p.thread.state === 'closed') return problem(route, 409, 'comments_closed', 'Comments are closed');
			const c: S['Comment'] = {
				id: sid('c'),
				post_id: p.id,
				kind: 'person',
				parent_id: (b.parent_id as string) ?? null,
				author: meRef,
				quoted: b.quoted_user_id ? (list.find((x) => x.author?.user_id === b.quoted_user_id)?.author ?? null) : null,
				body: String(b.body),
				mentions: (b.mentions as S['Mention'][]) ?? [],
				reactions: EMPTY_SUMMARY,
				created_at: now(),
				edited_at: null,
				can_edit: true,
				can_delete: true
			};
			list.push(c);
			refreshThread(p);
			return json(route, 201, c);
		}
	}
	if ((m = path.match(/^\/v1\/posts\/([^/]+)\/comments\/([^/]+)$/))) {
		const p = post(m[1]!);
		const list = soc.comments[m[1]!] ?? [];
		const c = list.find((x) => x.id === m![2]);
		if (!p || !c) return problem(route, 404, 'not_found', 'Not found');
		if (method === 'PATCH') {
			Object.assign(c, { body: b.body, mentions: b.mentions ?? [], edited_at: now() });
			refreshThread(p);
			return json(route, 200, c);
		}
		if (method === 'DELETE') {
			const own = c.author?.user_id === me?.id;
			const replies = list.filter((x) => x.parent_id === c.id);
			if (own && replies.length) Object.assign(c, { deleted: true, body: null });
			else soc.comments[p.id] = list.filter((x) => x.id !== c.id && (own || x.parent_id !== c.id));
			refreshThread(p);
			return none(route);
		}
	}
	if ((m = path.match(/^\/v1\/posts\/([^/]+)\/(thread|read|mute|prompt)$/)) && method === 'PUT') {
		const p = post(m[1]!);
		if (!p) return problem(route, 404, 'not_found', 'Not found');
		if (m[2] === 'thread') {
			p.thread = { ...p.thread, state: b.state as S['ThreadState'], can_comment: b.state === 'open' };
			return json(route, 200, p.thread);
		}
		if (m[2] === 'read') {
			p.thread = { ...p.thread, read_up_to: b.up_to as string, unread_count: 0, unread_mentions: 0 };
			return none(route);
		}
		if (m[2] === 'mute') {
			p.thread = { ...p.thread, muted: b.muted as boolean };
			return none(route);
		}
		const pr = Object.values(soc.prompts).flat().find((x) => x.id === b.prompt_id);
		p.prompt = pr ? { id: pr.id, title: pr.title } : null;
		return json(route, 200, p);
	}
	if ((m = path.match(/^\/v1\/reactions\/([^/]+)\/([^/]+)$/))) {
		const [kind, id] = [m[1]!, m[2]!];
		const key = `${kind}:${id}`;
		const framesOf = () => (kind === 'post' ? (post(id)?.thread.reactions.frames ?? []) : []);
		if (method === 'GET') return json(route, 200, { people: [...(soc.reactions[key] ?? [])].reverse(), frames: framesOf() } satisfies S['ReactionList']);
		if (method === 'PUT') {
			const list = (soc.reactions[key] ??= []).filter((r) => r.person.user_id !== me?.id);
			if (b.emoji) list.push({ person: meRef, emoji: b.emoji as S['PersonReaction'], at: now() });
			soc.reactions[key] = list;
			const s = summary(soc, key, me?.id, framesOf());
			if (kind === 'post') {
				const p = post(id);
				if (p) p.thread = { ...p.thread, reactions: s };
			} else if (kind === 'comment') {
				for (const cs of Object.values(soc.comments)) for (const c of cs) if (c.id === id) c.reactions = s;
			} else if (kind === 'message') {
				for (const ms of Object.values(soc.messages)) for (const x of ms) if (x.id === id) x.reactions = s;
			}
			return json(route, 200, s);
		}
	}

	// ---------------------------------------------------------------- circles
	if ((m = path.match(/^\/v1\/feeds\/([^/]+)\/circles(?:\/([^/]+)(?:\/members(?:\/([^/]+))?)?)?$/))) {
		if (!socialOn) return off(route);
		const feedId = m[1]!;
		const list = (soc.circles[feedId] ??= []);
		const members = state.feedMembers[feedId] ?? [];
		const memberOf = (u: string): S['CircleMember'] => {
			const fm = members.find((x) => x.user_id === u);
			return { user_id: u, display_name: fm?.display_name ?? (u === me?.id ? meRef.display_name : u), avatar_url: fm?.avatar_url ?? null, role: 'member', added_at: now() };
		};
		const recount = (c: S['Circle']) => {
			c.member_count = c.members.length;
			c.my_role = c.members.find((x) => x.user_id === me?.id)?.role ?? null;
		};
		if (!m[2]) {
			if (method === 'GET') return json(route, 200, list);
			if (method === 'POST') {
				const c: S['Circle'] = {
					id: sid('ci'),
					feed_id: feedId,
					name: String(b.name),
					members: [{ ...memberOf(me!.id), role: 'admin' }, ...((b.member_ids as string[]) ?? []).map(memberOf)],
					member_count: 0,
					frame_ids: (b.frame_ids as string[]) ?? [],
					frames_reached: [],
					conversation_id: sid('cv'),
					created_at: now()
				};
				recount(c);
				list.push(c);
				return json(route, 201, c);
			}
		}
		const c = list.find((x) => x.id === m![2]);
		if (!c) return problem(route, 404, 'not_found', 'Not found');
		if (!path.includes('/members')) {
			if (method === 'GET') return json(route, 200, c);
			if (method === 'PATCH') {
				Object.assign(c, b);
				return json(route, 200, c);
			}
			if (method === 'DELETE') {
				soc.circles[feedId] = list.filter((x) => x.id !== c.id);
				return none(route);
			}
		}
		if (!m[3] && method === 'POST') {
			for (const u of (b.user_ids as string[]) ?? []) if (!c.members.some((x) => x.user_id === u)) c.members.push(memberOf(u));
			recount(c);
			return json(route, 200, c);
		}
		if (m[3] && method === 'PATCH') {
			const cm = c.members.find((x) => x.user_id === m![3]);
			if (cm) cm.role = b.role as S['CircleRole'];
			recount(c);
			return json(route, 200, c);
		}
		if (m[3] && method === 'DELETE') {
			c.members = c.members.filter((x) => x.user_id !== m![3]);
			recount(c);
			return none(route);
		}
	}

	// ---------------------------------------------------------------- chats
	const conv = (id: string) => soc.conversations.find((c) => c.id === id);
	if (path === '/v1/conversations') {
		if (!chatsOn) return off(route);
		if (method === 'GET') {
			const archived = url.searchParams.get('archived') === 'true';
			return json(route, 200, soc.conversations.filter((c) => archived || !c.archived));
		}
		if (method === 'POST') {
			const ids = (b.member_ids as string[]) ?? [];
			const blockedBy = soc.contacts.filter((p) => ids.includes(p.person.user_id) && (p as S['Person'] & { blocked_me?: boolean }).blocked_me);
			if (blockedBy.length) return problem(route, 403, 'cannot_message', 'Cannot message');
			if (b.kind === 'direct') {
				const existing = soc.conversations.find((c) => c.kind === 'direct' && c.other?.user_id === ids[0]);
				if (existing) return json(route, 200, existing);
			}
			const people = ids.map((u) => soc.contacts.find((p) => p.person.user_id === u)?.person ?? { user_id: u, display_name: u });
			const c: S['Conversation'] = {
				id: sid('cv'),
				kind: b.kind as 'direct' | 'group',
				name: b.kind === 'direct' ? people[0]!.display_name : String(b.name),
				photo_url: null,
				other: b.kind === 'direct' ? people[0] : null,
				members: [{ person: meRef, role: b.kind === 'group' ? 'admin' : 'member' }, ...people.map((p) => ({ person: p, role: 'member' as const }))],
				member_count: people.length + 1,
				my_role: b.kind === 'group' ? 'admin' : 'member',
				unread_count: 0,
				last_message: null,
				last_activity_at: now(),
				muted: false,
				archived: false,
				read_only: false,
				read_receipts: true,
				created_at: now()
			};
			soc.conversations.unshift(c);
			soc.messages[c.id] = [];
			return json(route, 201, c);
		}
	}
	if ((m = path.match(/^\/v1\/conversations\/([^/]+)(\/.*)?$/))) {
		if (!chatsOn) return off(route);
		const c = conv(m[1]!);
		if (!c) return problem(route, 404, 'not_found', 'Not found');
		const rest = m[2] ?? '';
		const msgs = (soc.messages[c.id] ??= []);
		if (rest === '') {
			if (method === 'GET') return json(route, 200, c);
			if (method === 'PATCH') {
				c.name = String(b.name);
				return json(route, 200, c);
			}
		}
		if (rest === '/photo') {
			if (method === 'PUT') {
				c.photo_url = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg"/%3E';
				return json(route, 200, c);
			}
			c.photo_url = null;
			return none(route);
		}
		if (rest === '/preferences' && method === 'PATCH') {
			if (b.mute) Object.assign(c, { muted: b.mute !== 'off', muted_until: b.mute === 'always' || b.mute === 'off' ? null : new Date(Date.now() + 3_600_000).toISOString() });
			if (b.archived !== undefined) c.archived = b.archived as boolean;
			return json(route, 200, c);
		}
		if (rest === '/members' && method === 'POST') {
			for (const u of (b.user_ids as string[]) ?? []) {
				const p = soc.contacts.find((x) => x.person.user_id === u)?.person ?? { user_id: u, display_name: u };
				c.members = [...(c.members ?? []), { person: p, role: 'member' }];
			}
			c.member_count = c.members?.length ?? c.member_count;
			return json(route, 200, c);
		}
		if ((m = rest.match(/^\/members\/([^/]+)$/))) {
			if (method === 'PATCH') {
				const cm = c.members?.find((x) => x.person.user_id === m![1]);
				if (cm) cm.role = b.role as S['ConversationRole'];
				return json(route, 200, c);
			}
			c.members = c.members?.filter((x) => x.person.user_id !== m![1]);
			c.member_count = c.members?.length ?? 0;
			if (m[1] === me?.id) soc.conversations = soc.conversations.filter((x) => x.id !== c.id);
			return none(route);
		}
		if (rest === '/messages') {
			if (method === 'GET') {
				const before = url.searchParams.get('before');
				const after = url.searchParams.get('after');
				const limit = Number(url.searchParams.get('limit') ?? 50);
				let from = 0;
				let to = msgs.length;
				if (before) to = msgs.findIndex((x) => x.id === before);
				if (after) from = msgs.findIndex((x) => x.id === after) + 1;
				if (!after) from = Math.max(0, to - limit);
				else to = Math.min(msgs.length, from + limit);
				return json(route, 200, { messages: msgs.slice(from, to), has_more_before: from > 0, has_more_after: to < msgs.length } satisfies S['MessagePage']);
			}
			if (method === 'POST') {
				if (c.read_only) return problem(route, 403, 'cannot_message', 'Cannot message');
				const dup = msgs.find((x) => b.client_id && x.client_id === b.client_id);
				if (dup) return json(route, 201, dup);
				const reply = b.reply_to_id ? msgs.find((x) => x.id === b.reply_to_id) : null;
				const shared = b.shared_post_id ? post(String(b.shared_post_id)) : null;
				const msg: S['Message'] = {
					id: sid('m'),
					conversation_id: c.id,
					kind: 'person',
					author: meRef,
					client_id: (b.client_id as string) ?? null,
					body: (b.body as string) ?? null,
					mentions: (b.mentions as S['Mention'][]) ?? [],
					items: ((b.items as { upload_id: string }[]) ?? []).map((it) => ({ id: sid('i'), kind: 'photo', status: 'ready', sender_id: me!.id, sender_name: me!.display_name, created_at: now() })),
					voice: b.voice ? { item_id: sid('v'), status: 'ready', duration_ms: (b.voice as { duration_ms?: number }).duration_ms ?? 3000, waveform: Array.from({ length: 64 }, (_, i) => 20 + ((i * 37) % 70)) } : null,
					reply_to: reply ? { id: reply.id, author: reply.author, excerpt: reply.body?.slice(0, 200) ?? null, deleted: reply.deleted, voice: !!reply.voice } : null,
					shared_post: b.shared_post_id
						? { post_id: String(b.shared_post_id), available: !!shared, kind: shared?.kind ?? null, feed_name: shared?.feed_name ?? null, poster: shared?.poster ?? null, item_count: shared?.items.length ?? 0, thumb_urls: [], caption: null }
						: null,
					link_preview: null,
					reactions: EMPTY_SUMMARY,
					frame_activity: [],
					created_at: now(),
					edited_at: null,
					deleted: false
				};
				msgs.push(msg);
				c.last_message = msg;
				c.last_activity_at = msg.created_at;
				return json(route, 201, msg);
			}
		}
		if ((m = rest.match(/^\/messages\/([^/]+)(\/send)?$/))) {
			const msg = msgs.find((x) => x.id === m![1]);
			if (!msg) return problem(route, 404, 'not_found', 'Not found');
			if (m[2] && method === 'POST') {
				const target = body as S['PutOnFrameRequest'];
				const names = target.frame_ids.map((f) => state.frames.find((x) => x.id === f)?.name ?? f);
				msgs.push({
					id: sid('m'),
					conversation_id: c.id,
					kind: 'system',
					author: null,
					items: [],
					reactions: EMPTY_SUMMARY,
					deleted: false,
					created_at: now(),
					system: { type: 'put_on_frame', person: meRef, item_count: msg.items.length, frame_names: names, feed_names: [], source_message_id: msg.id }
				});
				return json(route, 202, { id: sid('5'), created_at: now(), items: msg.items, frames: [] });
			}
			if (method === 'GET') return json(route, 200, msg);
			if (method === 'PATCH') {
				Object.assign(msg, { body: b.body, mentions: b.mentions ?? [], edited_at: now() });
				return json(route, 200, msg);
			}
			if (method === 'DELETE') {
				Object.assign(msg, { deleted: true, body: null, items: [], voice: null, removed_by_admin: msg.author?.user_id !== me?.id });
				return none(route);
			}
		}
		if (rest === '/read' && method === 'PUT') {
			Object.assign(c, { read_up_to: b.message_id, unread_count: 0, unread_mentions: 0 });
			return none(route);
		}
		if (rest === '/typing' && method === 'POST') return none(route);
	}
	if (path === '/v1/contacts' && method === 'GET') return chatsOn ? json(route, 200, soc.contacts) : off(route);
	if ((m = path.match(/^\/v1\/people\/([^/]+)$/)) && method === 'GET') {
		if (!chatsOn) return off(route);
		if (m[1] === me?.id) return json(route, 200, { person: meRef, feeds: [], frames: [], blocked: false, milestones: soc.milestones.map((x) => x.key) } satisfies S['Person']);
		const p = soc.contacts.find((x) => x.person.user_id === m![1]);
		return p ? json(route, 200, { ...p, blocked: soc.blocks.some((x) => x.person.user_id === p.person.user_id) }) : problem(route, 404, 'not_found', 'Not found');
	}
	if (path === '/v1/me/badge' && method === 'GET') return chatsOn || socialOn ? json(route, 200, soc.badge) : off(route);
	if (path === '/v1/link-previews' && method === 'POST') {
		if (!d.link_previews) return problem(route, 503, 'feature_disabled', 'Off');
		return json(route, 200, soc.linkPreviews[String(b.url)] ?? { url: String(b.url), status: 'failed' });
	}
	if (path === '/v1/blocks' && method === 'GET') return chatsOn ? json(route, 200, soc.blocks) : off(route);
	if ((m = path.match(/^\/v1\/blocks\/([^/]+)$/))) {
		if (method === 'PUT') {
			const p = soc.contacts.find((x) => x.person.user_id === m![1])?.person ?? { user_id: m[1]!, display_name: m[1]! };
			if (!soc.blocks.some((x) => x.person.user_id === m![1])) soc.blocks.push({ person: p, created_at: now() });
		} else soc.blocks = soc.blocks.filter((x) => x.person.user_id !== m![1]);
		return none(route);
	}
	if (path === '/v1/reports' && method === 'POST') {
		const r: S['Report'] = { id: sid('r'), target_kind: b.target_kind as S['ReportTargetKind'], target_id: String(b.target_id), reason: b.reason as S['ReportReason'], status: 'open', mine: true, created_at: now(), note: (b.note as string) ?? null };
		soc.reports.push(r);
		return json(route, 201, r);
	}
	if ((m = path.match(/^\/v1\/feeds\/([^/]+)\/reports$/)) && method === 'GET') return json(route, 200, soc.reports.filter((r) => r.feed_id === m![1]));
	if (path === '/v1/operator/reports' && method === 'GET') {
		if (!me?.is_operator) return problem(route, 403, 'forbidden', 'Operator only');
		return json(route, 200, soc.reports.filter((r) => !r.feed_id));
	}
	if ((m = path.match(/^\/v1\/reports\/([^/]+)\/resolve$/)) && method === 'POST') {
		const r = soc.reports.find((x) => x.id === m![1]);
		if (!r) return problem(route, 404, 'not_found', 'Not found');
		if (r.status !== 'open') return problem(route, 409, 'already_decided', 'Already decided');
		Object.assign(r, { status: b.action === 'remove' ? 'removed' : 'dismissed', resolved_at: now() });
		return json(route, 200, r);
	}

	// ---------------------------------------------------------------- encouragement to share
	if ((m = path.match(/^\/v1\/feeds\/([^/]+)\/engagement$/))) {
		if (!engOn) return off(route);
		const e = (soc.engagement[m[1]!] ??= { feed_id: m[1]!, streak_enabled: true, streak_goal: 3, prompts_enabled: false, quiet_after_days: 7, monthly_recap: true, timezone: 'Europe/Brussels' });
		if (method === 'PATCH') Object.assign(e, b);
		return json(route, 200, e);
	}
	if ((m = path.match(/^\/v1\/feeds\/([^/]+)\/streak$/)) && method === 'GET') {
		if (!engOn) return off(route);
		return json(route, 200, soc.streaks[m[1]!] ?? { enabled: true, goal: 3, current_weeks: 0, best_weeks: 0, freezes_left: 1, week: { week_of: '2026-11-16', photos: 0, met: false, helpers: [] } });
	}
	if ((m = path.match(/^\/v1\/feeds\/([^/]+)\/prompts(?:\/([^/]+))?$/))) {
		if (!engOn) return off(route);
		const list = (soc.prompts[m[1]!] ??= []);
		if (!m[2]) return json(route, 200, list);
		const p = list.find((x) => x.week_of === m![2]);
		if (!p) return problem(route, 404, 'not_found', 'Not found');
		if (method === 'PUT') {
			if (b.skip) Object.assign(p, { status: 'skipped', title: '' });
			else if (b.title) Object.assign(p, { source: 'custom', title: b.title, library_key: null, set_by: meRef });
			else Object.assign(p, { source: 'server', library_key: b.library_key, title: soc.library.find((l) => l.key === b.library_key)?.title ?? '', set_by: meRef });
			return json(route, 200, p);
		}
		if (method === 'DELETE') {
			Object.assign(p, { set_by: null, status: p.status === 'skipped' ? 'planned' : p.status });
			return none(route);
		}
	}
	if (path === '/v1/prompts' && method === 'GET') return engOn ? json(route, 200, soc.library) : off(route);
	if (path === '/v1/me/on-this-day' && method === 'GET') return engOn ? json(route, 200, soc.onThisDay) : off(route);
	if ((m = path.match(/^\/v1\/me\/weekly-summaries\/([^/]+)$/)) && method === 'GET') {
		if (!engOn) return off(route);
		const w = soc.weekly[m[1]!];
		return w ? json(route, 200, w) : problem(route, 404, 'not_found', 'Not found');
	}
	if (path === '/v1/me/milestones' && method === 'GET') return engOn ? json(route, 200, soc.milestones) : off(route);
	if ((m = path.match(/^\/v1\/me\/milestones\/([^/]+)$/)) && method === 'PATCH') {
		const ms = soc.milestones.find((x) => x.key === m![1]);
		if (!ms) return problem(route, 404, 'not_found', 'Not found');
		Object.assign(ms, b);
		return json(route, 200, ms);
	}
	if (path === '/v1/me/reminders') {
		if (!engOn) return off(route);
		if (method === 'PATCH') {
			const r = soc.reminders;
			if (b.snooze_days !== undefined) r.snoozed_until = b.snooze_days ? new Date(Date.now() + (b.snooze_days as number) * 86_400_000).toISOString() : null;
			if (b.auto_reduced_seen) r.auto_reduced = false;
			for (const k of ['level', 'frame_requests', 'weekly_summary', 'birthday_reminders', 'quiet_hours', 'email_digest'] as const) if (b[k] !== undefined) (r as Record<string, unknown>)[k] = b[k];
		}
		return json(route, 200, soc.reminders);
	}
	if ((m = path.match(/^\/v1\/frames\/([^/]+)\/photo-requests$/)) && method === 'GET') return json(route, 200, soc.photoRequests.filter((r) => r.frame_id === m![1]));
	if ((m = path.match(/^\/v1\/photo-requests\/([^/]+)$/)) && method === 'GET') {
		const r = soc.photoRequests.find((x) => x.id === m![1]);
		return r ? json(route, 200, r) : problem(route, 404, 'not_found', 'Not found');
	}
	return null;
}
