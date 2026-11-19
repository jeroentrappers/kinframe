<script lang="ts">
	import { api, authFetch, call, type BirthdayPerson, type Feed, type FeedBirthdayGreeting, type FeedMember } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import {
		addGreeting,
		addMode,
		ageDisplay,
		birthdayView,
		canRemoveBirthday,
		countdown,
		editScope,
		formatLocalDate,
		greetingsFor,
		groupBirthdays,
		hasOwnEntry,
		recreateBody,
		removeBirthday,
		todayIn,
		upsertBirthday,
		type LocalDate
	} from '$lib/birthdays';
	import { isFeedAdmin } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Avatar from '../Avatar.svelte';
	import Icon from '../Icon.svelte';
	import BirthdayEditor from './BirthdayEditor.svelte';

	/** The feed's birthday people (birthdays.md §3): list, suggestions, greetings and the member's notification switch. */
	let { feed, onupdated }: { feed: Feed; onupdated?: (f: Feed) => void } = $props();

	const admin = $derived(isFeedAdmin(feed.role));
	const mode = $derived(addMode(feed));
	const viewer = $derived({ role: feed.role, userId: auth.me?.id, postersCanAdd: feed.posters_can_add_birthdays === true });

	let list = $state<BirthdayPerson[] | null>(null);
	let members = $state<FeedMember[]>([]);
	let greetings = $state<FeedBirthdayGreeting[]>([]);
	let error = $state<unknown>(null);
	let today = $state<LocalDate>(todayIn());
	const todayIso = $derived(formatLocalDate(today));

	async function load() {
		try {
			const [b, m, g] = await Promise.all([
				call(api.GET('/v1/feeds/{feedId}/birthdays', { params: { path: { feedId: feed.id } } })),
				call(api.GET('/v1/feeds/{feedId}/members', { params: { path: { feedId: feed.id } } })).catch(() => [] as FeedMember[]),
				call(api.GET('/v1/feeds/{feedId}/birthday-greetings', { params: { path: { feedId: feed.id }, query: { limit: 30 } } })).catch(() => ({
					greetings: [] as FeedBirthdayGreeting[],
					next_cursor: null
				}))
			]);
			list = b;
			members = m;
			greetings = g.greetings;
			error = null;
		} catch (e) {
			error = e;
		}
	}

	$effect(() => {
		void feed.id;
		void admin;
		void load();
		// "Today" moves at midnight while the page stays open.
		const tick = setInterval(() => {
			const now = todayIn();
			if (formatLocalDate(now) !== formatLocalDate(today)) today = now;
		}, 60_000);
		const off = live.on((e) => {
			if (!('feed_id' in e) || e.feed_id !== feed.id || !list) return;
			if (e.type === 'birthday.updated' || e.type === 'birthday.suggested' || e.type === 'birthday.decided') list = upsertBirthday(list, e.birthday);
			else if (e.type === 'birthday.removed') list = removeBirthday(list, e.birthday_id);
			else if (e.type === 'birthday.greeting') greetings = addGreeting(greetings, e.greeting);
		});
		return () => {
			clearInterval(tick);
			off();
		};
	});

	const groups = $derived(groupBirthdays(list ?? [], today));
	const showAddMine = $derived(Boolean(auth.me) && list !== null && !hasOwnEntry(list, auth.me?.id));

	// ---- formatting
	const dateFmt = $derived(new Intl.DateTimeFormat(i18n.tag, { day: 'numeric', month: 'long', timeZone: 'UTC' }));
	const dateYearFmt = $derived(new Intl.DateTimeFormat(i18n.tag, { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }));
	const timeFmt = $derived(new Intl.DateTimeFormat(i18n.tag, { hour: '2-digit', minute: '2-digit' }));
	const rtf = $derived(new Intl.RelativeTimeFormat(i18n.tag, { numeric: 'auto' }));

	/** "12 March", or "12 March 1946" when the viewer may see the year; 29 February always shows as such. */
	function dateText(p: BirthdayPerson): string {
		if (p.birth_year != null) return dateYearFmt.format(Date.UTC(p.birth_year, p.month - 1, p.day));
		return dateFmt.format(Date.UTC(2000, p.month - 1, p.day));
	}
	function whenText(days: number): string {
		if (days === 0) return t('birthdays.today');
		const c = countdown(days);
		return rtf.format(c.value, c.unit);
	}
	const memberName = (id: string | null | undefined) => members.find((m) => m.user_id === id)?.display_name ?? null;

	// ---- editor
	let editorOpen = $state(false);
	let editing = $state<BirthdayPerson | null>(null);
	let editingMine = $state(false);
	function openEditor(p: BirthdayPerson | null, mine = false) {
		editing = p;
		editingMine = mine;
		editorOpen = true;
	}
	function onsaved(p: BirthdayPerson, created: boolean) {
		list = upsertBirthday(list ?? [], p);
		if (created) {
			toasts.success(p.status === 'pending' ? t('birthdays.suggested') : t('birthdays.added', { name: p.display_name }));
			void refreshFeed();
		} else toasts.success(t('common.saved'));
	}

	// ---- approve / reject (owner, admins)
	let busy = $state<string | null>(null);
	async function decide(p: BirthdayPerson, decision: 'approve' | 'reject') {
		busy = p.id;
		try {
			const path = { feedId: feed.id, birthdayId: p.id };
			const res =
				decision === 'approve'
					? await call(api.POST('/v1/feeds/{feedId}/birthdays/{birthdayId}/approve', { params: { path } }))
					: await call(api.POST('/v1/feeds/{feedId}/birthdays/{birthdayId}/reject', { params: { path } }));
			list = upsertBirthday(list ?? [], res);
			toasts.success(t(decision === 'approve' ? 'birthdays.approved' : 'birthdays.rejected', { name: res.display_name }));
			void refreshFeed();
		} catch (e) {
			if (isCode(e, 'duplicate_birthday')) {
				const other = (list ?? []).find((x) => x.id === e.detail);
				toasts.error(other ? t('birthdays.duplicate', { name: other.display_name }) : t('problem.duplicate_birthday'));
			} else toasts.error(isCode(e, 'already_decided') ? t('birthdays.alreadyDecided') : problemMessage(e));
			if (isCode(e, 'already_decided')) void load();
		} finally {
			busy = null;
		}
	}

	// ---- remove, with Undo instead of "are you sure" (§5.1)
	async function remove(p: BirthdayPerson) {
		const before = list ?? [];
		list = removeBirthday(before, p.id);
		// Keep the photo while we can, so Undo brings it back too.
		const photo = p.has_own_photo && p.photo_url ? fetch(p.photo_url).then((r) => (r.ok ? r.blob() : null), () => null) : Promise.resolve(null);
		try {
			await call(api.DELETE('/v1/feeds/{feedId}/birthdays/{birthdayId}', { params: { path: { feedId: feed.id, birthdayId: p.id } } }));
			void refreshFeed();
			const withdrawn = p.status === 'pending' && !admin;
			toasts.show(withdrawn ? t('birthdays.withdrawn') : t('birthdays.removed', { name: p.display_name }), {
				action: { label: t('common.undo'), run: () => void undo(p, photo) },
				duration: 8000
			});
		} catch (e) {
			list = before;
			toasts.error(problemMessage(e));
		}
	}
	async function undo(p: BirthdayPerson, photo: Promise<Blob | null>) {
		try {
			let back = await call(
				api.POST('/v1/feeds/{feedId}/birthdays', {
					params: { path: { feedId: feed.id }, header: { 'Idempotency-Key': newIdempotencyKey() } },
					body: recreateBody(p)
				})
			);
			const blob = await photo;
			if (blob) {
				try {
					const res = await authFetch(`/v1/feeds/${encodeURIComponent(feed.id)}/birthdays/${encodeURIComponent(back.id)}/photo`, {
						method: 'PUT',
						headers: { 'Content-Type': blob.type || 'image/webp' },
						body: blob
					});
					back = (await res.json()) as BirthdayPerson;
				} catch {
					/* the person is back; the photo can be added again */
				}
			}
			list = upsertBirthday(list ?? [], back);
			void refreshFeed();
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	async function refreshFeed() {
		try {
			const f = (await call(api.GET('/v1/feeds/{feedId}', { params: { path: { feedId: feed.id } } }))) as Feed;
			feeds.upsert(f);
			onupdated?.(f);
		} catch {
			/* counts only */
		}
	}

	// ---- the member's own switch (PATCH …/preferences)
	let prefBusy = $state(false);
	async function togglePref(e: Event & { currentTarget: HTMLInputElement }) {
		const on = e.currentTarget.checked;
		prefBusy = true;
		try {
			const f = (await call(
				api.PATCH('/v1/feeds/{feedId}/preferences', { params: { path: { feedId: feed.id } }, body: { birthday_notifications: on } })
			)) as Feed;
			onupdated?.(f);
			toasts.success(t('common.saved'));
		} catch (err) {
			e.currentTarget.checked = !on;
			toasts.error(problemMessage(err));
		} finally {
			prefBusy = false;
		}
	}

	const recent = $derived(greetings.slice(0, 12));
</script>

<div class="bdays stack">
	<div class="toolbar">
		<button class="btn btn-primary" onclick={() => openEditor(null)}>
			<Icon name="plus" size={18} />{mode === 'add' ? t('birthdays.add') : t('birthdays.suggest')}
		</button>
		{#if showAddMine}
			<button class="btn" onclick={() => openEditor(null, true)}><Icon name="cake" size={18} />{t('birthdays.addMine')}</button>
		{/if}
	</div>
	{#if mode === 'suggest'}<p class="hint">{t('birthdays.suggestHint')}</p>{/if}

	{#if error}
		<div class="callout callout-error" role="alert"><Icon name="alert" />{problemMessage(error)}</div>
	{:else if !list}
		<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>{#each [0, 1, 2] as i (i)}<div class="skeleton" style="height:64px"></div>{/each}</div>
	{:else}
		{#if groups.pending.length > 0}
			<section class="card card-pad stack-sm" aria-labelledby="bd-pending">
				<h2 id="bd-pending">{t('birthdays.pendingTitle')}</h2>
				<ul class="people" role="list">
					{#each groups.pending as p (p.id)}
						<li class="person" in:softFly>
							<Avatar name={p.display_name} url={p.photo_url} size={44} />
							<div class="who">
								<strong>{p.display_name}</strong>
								<span class="small muted">
									{dateText(p)} · {admin ? t('birthdays.suggestedBy', { name: p.created_by_name ?? t('birthdays.formerMember') }) : t('birthdays.waiting')}
								</span>
							</div>
							<div class="row-wrap acts">
								{#if p.can_decide}
									<button class="btn btn-primary btn-sm" onclick={() => decide(p, 'approve')} disabled={busy === p.id}><Icon name="check" size={16} />{t('birthdays.approve')}</button>
									<button class="btn btn-ghost btn-sm" onclick={() => decide(p, 'reject')} disabled={busy === p.id}>{t('birthdays.reject')}</button>
								{/if}
								{#if editScope(p, viewer) !== 'none'}
									<button class="btn btn-ghost btn-sm btn-icon" aria-label={t('birthdays.editName', { name: p.display_name })} onclick={() => openEditor(p)}><Icon name="edit" size={16} /></button>
								{/if}
								{#if !p.can_decide && canRemoveBirthday(p, viewer)}
									<button class="btn btn-ghost btn-sm" onclick={() => remove(p)}>{t('birthdays.withdraw')}</button>
								{/if}
							</div>
						</li>
					{/each}
				</ul>
			</section>
		{/if}

		{#if groups.approved.length === 0 && groups.pending.length === 0}
			<div class="card card-pad empty">
				<Icon name="cake" size={28} />
				<h2>{t('birthdays.emptyTitle')}</h2>
				<p class="muted">{t('birthdays.emptyBody')}</p>
			</div>
		{:else if groups.approved.length > 0}
			<ul class="people list" role="list" aria-label={t('feed.tabBirthdays')}>
				{#each groups.approved as p (p.id)}
					{@const v = birthdayView(p, today)}
					{@const age = ageDisplay(p, v.next)}
					{@const todays = v.days === 0 ? greetingsFor(greetings, p.id, todayIso) : []}
					<li class="card person" class:is-today={v.days === 0} in:softFly data-testid="birthday-row">
						<Avatar name={p.display_name} url={p.photo_url} size={48} />
						<div class="who">
							<strong>{p.display_name}</strong>
							<span class="small">
								<span class="muted">{dateText(p)}</span> · <span class="when" class:today={v.days === 0}>{whenText(v.days)}</span>{#if age.kind !== 'none'} · <span class="age">{t('birthdays.turns', { age: age.age })}</span>{#if age.kind === 'private'}<span class="private" title={t('birthdays.ageHidden')}><Icon name="eyeOff" size={14} /><span class="visually-hidden">{t('birthdays.ageHidden')}</span></span>{/if}{/if}
							</span>
							{#if p.user_id && (p.linked_member_name || memberName(p.user_id))}
								<span class="small muted">{t('birthdays.linked', { name: p.linked_member_name ?? memberName(p.user_id) ?? '' })}</span>
							{/if}
							{#if todays.length > 0}
								<span class="small greet">🎉 {t('birthdays.greetingsToday')}: {todays.map((g) => `${g.frame_name} ${timeFmt.format(new Date(g.created_at))}`).join(' · ')}</span>
							{/if}
						</div>
						<div class="row-wrap acts">
							{#if editScope(p, viewer) !== 'none'}
								<button class="btn btn-ghost btn-sm btn-icon" aria-label={t('birthdays.editName', { name: p.display_name })} onclick={() => openEditor(p)}><Icon name="edit" size={16} /></button>
							{/if}
							{#if canRemoveBirthday(p, viewer)}
								<button class="btn btn-ghost btn-sm btn-icon" aria-label={t('birthdays.removeName', { name: p.display_name })} onclick={() => remove(p)}><Icon name="trash" size={16} /></button>
							{/if}
						</div>
					</li>
				{/each}
			</ul>
		{/if}

		{#if groups.rejected.length > 0}
			<section class="stack-sm declined" aria-labelledby="bd-declined">
				<h3 id="bd-declined" class="small">{t('birthdays.declinedTitle')}</h3>
				<ul class="plain" role="list">
					{#each groups.rejected as p (p.id)}
						<li class="small muted">{p.display_name} · {dateText(p)}{#if p.decided_at} · {relativeTime(p.decided_at, i18n.tag)}{/if}</li>
					{/each}
				</ul>
				<p class="hint">{t('birthdays.declinedHint')}</p>
			</section>
		{/if}

		{#if recent.length > 0}
			<section class="card card-pad stack-sm" aria-labelledby="bd-greetings">
				<h2 id="bd-greetings">{t('birthdays.greetingsTitle')}</h2>
				<ul class="plain greetings" role="list">
					{#each recent as g (g.id)}
						<li in:softFly>
							<span>{t('birthdays.greeting', { frame: g.frame_name, name: g.birthday_name })}</span>
							<span class="small muted">{g.frame_location ? `${g.frame_location} · ` : ''}{relativeTime(g.created_at, i18n.tag)}</span>
						</li>
					{/each}
				</ul>
			</section>
		{/if}
	{/if}

	<section class="card card-pad" aria-label={t('birthdays.notifications')}>
		<label class="switch">
			<input type="checkbox" checked={feed.birthday_notifications ?? true} onchange={togglePref} disabled={prefBusy} />
			<span><span class="s-label">{t('birthdays.notifications')}</span><span class="hint">{t('birthdays.notificationsHint')}</span></span>
		</label>
	</section>
</div>

<BirthdayEditor bind:open={editorOpen} {feed} person={editing} mine={editingMine} {members} list={list ?? []} {onsaved} />

<style>
	.bdays {
		max-width: 760px;
	}
	.toolbar {
		display: flex;
		gap: var(--s-3);
		flex-wrap: wrap;
	}
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: var(--s-2);
		color: var(--text-muted);
	}
	.empty h2 {
		color: var(--text);
		font-size: 1.15rem;
	}
	h2 {
		font-size: 1.05rem;
	}
	.people,
	.plain {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.list {
		gap: var(--s-3);
	}
	.person {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		flex-wrap: wrap;
	}
	.list .person {
		padding: var(--s-3) var(--s-4);
	}
	.person.is-today {
		border-color: color-mix(in srgb, var(--accent) 55%, var(--border));
		background: color-mix(in srgb, var(--accent) 8%, var(--surface));
	}
	.who {
		flex: 1;
		min-width: 12em;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.who strong {
		overflow-wrap: anywhere;
	}
	.when.today {
		font-weight: 700;
	}
	.private {
		display: inline-flex;
		vertical-align: -2px;
		margin-inline-start: 4px;
		color: var(--text-muted);
	}
	.greet {
		color: var(--text);
	}
	.acts {
		gap: 4px;
		margin-inline-start: auto;
	}
	.greetings li {
		display: flex;
		flex-direction: column;
	}
	.declined h3 {
		margin: 0;
		color: var(--text-muted);
	}
	.switch > span {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 600;
	}
</style>
