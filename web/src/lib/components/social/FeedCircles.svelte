<script lang="ts">
	import { api, call, type Circle, type Feed, type FeedMember, type Subscription } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { isFeedAdmin } from '$lib/feeds';
	import { live } from '$lib/live.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import EmptyState from '../EmptyState.svelte';
	import Icon from '../Icon.svelte';
	import { forgetCircles } from './AudiencePicker.svelte';
	import MoreMenu, { type MenuItem } from './MoreMenu.svelte';

	/** Circles inside a family (README §5.18): smaller groups with their own chat and as an audience for posts. */
	let { feed }: { feed: Feed } = $props();

	let circles = $state<Circle[] | null>(null);
	let error = $state<unknown>(null);
	let members = $state<FeedMember[]>([]);
	let frames = $state<{ id: string; name: string }[]>([]);
	const meId = $derived(auth.me?.id);
	const admin = $derived(isFeedAdmin(feed.role));
	const canCreate = $derived(admin || feed.members_can_create_circles !== false);
	const max = $derived(discovery.limits.max_circles_per_feed);

	async function load() {
		try {
			circles = await call(api.GET('/v1/feeds/{feedId}/circles', { params: { path: { feedId: feed.id } } }));
			error = null;
		} catch (e) {
			error = e;
		}
	}

	$effect(() => {
		const id = feed.id;
		void load();
		call(api.GET('/v1/feeds/{feedId}/members', { params: { path: { feedId: id } } }))
			.then((m) => (members = m))
			.catch(() => (members = []));
		call(api.GET('/v1/feeds/{feedId}/subscriptions', { params: { path: { feedId: id } } }))
			.then((s: Subscription[]) => (frames = s.filter((x) => x.status === 'active').map((x) => ({ id: x.frame.id, name: x.frame.name }))))
			.catch(() => (frames = []));
		return live.on((e) => {
			if (e.type === 'circle.updated' && e.feed_id === id) {
				const list = circles ?? [];
				circles = list.some((c) => c.id === e.circle.id) ? list.map((c) => (c.id === e.circle.id ? e.circle : c)) : [...list, e.circle];
				forgetCircles(id);
			} else if (e.type === 'circle.removed' && e.feed_id === id) {
				circles = (circles ?? []).filter((c) => c.id !== e.circle_id);
				forgetCircles(id);
			}
		});
	});

	const canManage = (c: Circle) => admin || c.my_role === 'admin';

	// ---- create / edit
	let editOpen = $state(false);
	let editing = $state<Circle | null>(null);
	let name = $state('');
	let picked = $state<string[]>([]);
	let frameIds = $state<string[]>([]);
	let busy = $state(false);

	function openCreate() {
		editing = null;
		name = '';
		picked = [];
		frameIds = [];
		editOpen = true;
	}
	function openEdit(c: Circle) {
		editing = c;
		name = c.name;
		picked = c.members.map((m) => m.user_id).filter((u) => u !== meId);
		frameIds = [...(c.frame_ids ?? [])];
		editOpen = true;
	}

	const toggle = (list: string[], id: string) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);

	function upsert(c: Circle) {
		const list = circles ?? [];
		circles = list.some((x) => x.id === c.id) ? list.map((x) => (x.id === c.id ? c : x)) : [...list, c];
		forgetCircles(feed.id);
	}

	async function save() {
		busy = true;
		try {
			if (!editing) {
				const c = await call(
					api.POST('/v1/feeds/{feedId}/circles', {
						params: { path: { feedId: feed.id }, header: { 'Idempotency-Key': newIdempotencyKey() } },
						body: { name: name.trim(), member_ids: picked, ...(frameIds.length ? { frame_ids: frameIds } : {}) }
					})
				);
				upsert(c);
				toasts.success(t('social.circleCreated', { name: c.name }));
			} else {
				const path = { feedId: feed.id, circleId: editing.id };
				let c = editing;
				if (name.trim() !== c.name || frameIds.join() !== (c.frame_ids ?? []).join())
					c = await call(api.PATCH('/v1/feeds/{feedId}/circles/{circleId}', { params: { path }, body: { name: name.trim(), frame_ids: frameIds } }));
				const current = new Set(c.members.map((m) => m.user_id));
				const add = picked.filter((u) => !current.has(u));
				if (add.length) c = await call(api.POST('/v1/feeds/{feedId}/circles/{circleId}/members', { params: { path }, body: { user_ids: add } }));
				for (const u of [...current].filter((u) => u !== meId && !picked.includes(u))) {
					await call(api.DELETE('/v1/feeds/{feedId}/circles/{circleId}/members/{userId}', { params: { path: { ...path, userId: u } } }));
					c = { ...c, members: c.members.filter((m) => m.user_id !== u), member_count: c.member_count - 1 };
				}
				upsert(c);
				toasts.success(t('common.saved'));
			}
			editOpen = false;
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}

	async function setRole(c: Circle, userId: string, role: 'admin' | 'member') {
		try {
			upsert(await call(api.PATCH('/v1/feeds/{feedId}/circles/{circleId}/members/{userId}', { params: { path: { feedId: feed.id, circleId: c.id, userId } }, body: { role } })));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	async function leave(c: Circle) {
		if (!meId) return;
		try {
			await call(api.DELETE('/v1/feeds/{feedId}/circles/{circleId}/members/{userId}', { params: { path: { feedId: feed.id, circleId: c.id, userId: meId } } }));
			if (admin) upsert({ ...c, my_role: null, members: c.members.filter((m) => m.user_id !== meId), member_count: c.member_count - 1 });
			else circles = (circles ?? []).filter((x) => x.id !== c.id);
			forgetCircles(feed.id);
			toasts.success(t('social.circleLeft', { name: c.name }));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	let deleteOpen = $state(false);
	let deleting = $state<Circle | null>(null);
	async function remove() {
		if (!deleting) return;
		busy = true;
		try {
			await call(api.DELETE('/v1/feeds/{feedId}/circles/{circleId}', { params: { path: { feedId: feed.id, circleId: deleting.id } } }));
			circles = (circles ?? []).filter((x) => x.id !== deleting!.id);
			forgetCircles(feed.id);
			toasts.success(t('social.circleDeleted', { name: deleting.name }));
			deleteOpen = false;
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}

	function menu(c: Circle): MenuItem[] {
		const items: MenuItem[] = [];
		if (canManage(c)) items.push({ label: t('social.editCircle'), icon: 'edit', run: () => openEdit(c) });
		if (c.my_role) items.push({ label: t('social.leaveCircle'), icon: 'logout', run: () => leave(c) });
		if (canManage(c)) items.push({ label: t('social.deleteCircle'), icon: 'trash', danger: true, run: () => ((deleting = c), (deleteOpen = true)) });
		return items;
	}
</script>

<div class="toolbar">
	<p class="muted small intro">{t('social.circlesIntro')}</p>
	{#if canCreate && (circles?.length ?? 0) < max}
		<button class="btn btn-primary" onclick={openCreate}><Icon name="plus" size={18} />{t('social.newCircle')}</button>
	{/if}
</div>

{#if error}
	<div class="callout callout-error" role="alert"><Icon name="alert" /><span>{problemMessage(error)}</span></div>
{:else if circles === null}
	<div class="skeleton" style="height:120px"></div>
{:else if circles.length === 0}
	<div class="card"><EmptyState compact art="people" title={t('social.noCircles')} body={t('social.noCirclesBody')} /></div>
{:else}
	<ul class="circles" role="list">
		{#each circles as c (c.id)}
			<li class="card card-pad circle">
				<div class="top">
					<span class="ic" aria-hidden="true"><Icon name="users" size={20} /></span>
					<div class="nm">
						<h3>{c.name}</h3>
						<span class="muted small">{tn('social.circleMembers', c.member_count)}{#if c.my_role === 'admin'} · {t('chat.admin')}{/if}</span>
					</div>
					<span class="spacer"></span>
					{#if c.conversation_id && c.my_role && discovery.chats}<a class="btn btn-sm btn-soft" href="/chats/{c.conversation_id}"><Icon name="message" size={16} />{t('social.circleChat')}</a>{/if}
					<MoreMenu label={t('social.circleActions', { name: c.name })} items={menu(c)} />
				</div>
				<ul class="faces" role="list" aria-label={t('social.circleMembersLabel')}>
					{#each c.members.slice(0, 12) as m (m.user_id)}
						<li title={m.display_name}>
							<Avatar name={m.display_name} url={m.avatar_url} size={30} />
							{#if canManage(c) && m.user_id !== meId}
								<button class="role" onclick={() => setRole(c, m.user_id, m.role === 'admin' ? 'member' : 'admin')} aria-label={m.role === 'admin' ? t('chat.removeAdmin') : t('chat.makeAdmin')} title={m.role === 'admin' ? t('chat.removeAdmin') : t('chat.makeAdmin')}>
									<Icon name={m.role === 'admin' ? 'crown' : 'user'} size={11} />
								</button>
							{:else if m.role === 'admin'}<span class="role static" aria-hidden="true"><Icon name="crown" size={11} /></span>{/if}
						</li>
					{/each}
				</ul>
				{#if c.frames_reached?.length}
					<p class="small muted reach"><Icon name="frame" size={14} />{t('social.circleReaches', { frames: c.frames_reached.map((f) => f.frame_name).join(', ') })}</p>
				{:else}
					<p class="small faint reach"><Icon name="frame" size={14} />{t('social.circleNoFrames')}</p>
				{/if}
			</li>
		{/each}
	</ul>
{/if}

<Dialog bind:open={editOpen} title={editing ? t('social.editCircle') : t('social.newCircle')} size="md">
	<div class="stack">
		<div class="field">
			<label for="circle-name">{t('social.circleName')}</label>
			<input id="circle-name" class="input" maxlength="40" bind:value={name} placeholder={t('social.circleNamePlaceholder')} />
		</div>
		<fieldset class="pick">
			<legend class="label">{t('social.circlePeople')}</legend>
			{#each members.filter((m) => m.user_id !== meId) as m (m.user_id)}
				<label class="opt" class:checked={picked.includes(m.user_id)}>
					<input type="checkbox" checked={picked.includes(m.user_id)} onchange={() => (picked = toggle(picked, m.user_id))} />
					<Avatar name={m.display_name} url={m.avatar_url} size={28} />
					<span class="opt-nm">{m.display_name}</span>
					<span class="check" aria-hidden="true"><Icon name="check" size={16} stroke={3} /></span>
				</label>
			{/each}
		</fieldset>
		{#if frames.length}
			<fieldset class="pick">
				<legend class="label">{t('social.circleFrames')}</legend>
				<p class="hint">{t('social.circleFramesHint')}</p>
				{#each frames as f (f.id)}
					<label class="opt" class:checked={frameIds.includes(f.id)}>
						<input type="checkbox" checked={frameIds.includes(f.id)} onchange={() => (frameIds = toggle(frameIds, f.id))} />
						<Icon name="frame" size={18} />
						<span class="opt-nm">{f.name}</span>
						<span class="check" aria-hidden="true"><Icon name="check" size={16} stroke={3} /></span>
					</label>
				{/each}
			</fieldset>
		{/if}
	</div>
	{#snippet footer()}
		<button class="btn" onclick={() => (editOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" disabled={busy || !name.trim()} onclick={save}>{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{editing ? t('common.save') : t('social.createCircle')}</button>
	{/snippet}
</Dialog>

<Dialog bind:open={deleteOpen} title={t('social.deleteCircleTitle', { name: deleting?.name ?? '' })}>
	<p>{t('social.deleteCircleBody')}</p>
	{#snippet footer()}
		<button class="btn" onclick={() => (deleteOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" disabled={busy} onclick={remove}>{t('social.deleteCircle')}</button>
	{/snippet}
</Dialog>

<style>
	.toolbar {
		display: flex;
		gap: var(--s-3);
		align-items: center;
		justify-content: space-between;
		flex-wrap: wrap;
		margin-bottom: var(--s-4);
	}
	.intro {
		margin: 0;
		max-width: 520px;
	}
	.circles {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-3);
	}
	.circle {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.top {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		flex-wrap: wrap;
	}
	.ic {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.nm {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	h3 {
		font-size: 1.02rem;
	}
	.faces {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.faces li {
		position: relative;
	}
	.role {
		position: absolute;
		bottom: -3px;
		inset-inline-end: -3px;
		width: 18px;
		height: 18px;
		border-radius: 50%;
		border: 1px solid var(--border);
		background: var(--surface);
		display: grid;
		place-items: center;
		padding: 0;
		cursor: pointer;
		color: var(--text-muted);
	}
	.role.static {
		cursor: default;
		color: var(--warn);
	}
	.reach {
		margin: 0;
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.pick {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
		max-height: 260px;
		overflow-y: auto;
	}
	.opt {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 6px 10px;
		border-radius: var(--r);
		border: 1px solid transparent;
		cursor: pointer;
	}
	.opt.checked {
		border-color: var(--primary);
		background: var(--primary-soft);
	}
	.opt input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.opt:has(input:focus-visible) {
		outline: 3px solid var(--focus);
	}
	.opt-nm {
		flex: 1;
		font-weight: 600;
	}
	.check {
		color: var(--primary);
		visibility: hidden;
	}
	.opt.checked .check {
		visibility: visible;
	}
</style>
