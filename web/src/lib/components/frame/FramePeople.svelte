<script lang="ts">
	import { api, call, type Frame, type Invite, type JoinRequest, type Member, type Permissions } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { can } from '$lib/frames.svelte';
	import { live } from '$lib/live.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { relativeTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, type MessageKey } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import RoleBadge from '../RoleBadge.svelte';
	import TransferDialog from './TransferDialog.svelte';

	let { frame, manage, onchanged }: { frame: Frame; manage: boolean; onchanged?: () => void } = $props();

	let transferOpen = $state(false);
	let transferTo = $state<string | null>(null);

	const canInvite = $derived(can(frame, 'invite'));
	const PERMS: { key: keyof Permissions; label: MessageKey; hint: MessageKey }[] = [
		{ key: 'send', label: 'perm.send', hint: 'perm.sendHint' },
		{ key: 'invite', label: 'perm.invite', hint: 'perm.inviteHint' },
		{ key: 'see_library', label: 'perm.see_library', hint: 'perm.see_libraryHint' },
		{ key: 'manage_library', label: 'perm.manage_library', hint: 'perm.manage_libraryHint' },
		{ key: 'change_settings', label: 'perm.change_settings', hint: 'perm.change_settingsHint' }
	];

	let members = $state<Member[] | null>(null);
	let requests = $state<JoinRequest[]>([]);
	let invites = $state<Invite[]>([]);
	let error = $state<unknown>(null);
	let expanded = $state<string | null>(null);

	async function load() {
		try {
			const [m, r, i] = await Promise.all([
				call(api.GET('/v1/frames/{frameId}/members', { params: { path: { frameId: frame.id } } })),
				manage
					? call(api.GET('/v1/frames/{frameId}/join-requests', { params: { path: { frameId: frame.id } } }))
					: Promise.resolve([]),
				canInvite
					? call(api.GET('/v1/frames/{frameId}/invites', { params: { path: { frameId: frame.id } } }))
					: Promise.resolve([])
			]);
			members = m;
			requests = r;
			invites = i;
		} catch (e) {
			error = e;
		}
	}

	$effect(() => {
		void frame.id;
		void load();
		return live.on((e) => {
			if (e.type === 'join_request' && e.frame_id === frame.id && !requests.some((r) => r.id === e.request.id))
				requests = [e.request, ...requests];
			if (e.type === 'membership.updated' && e.frame_id === frame.id) void load();
		});
	});

	// Owner > admin > member, then by name.
	const sorted = $derived(
		[...(members ?? [])].sort(
			(a, b) =>
				['owner', 'admin', 'member'].indexOf(a.role) - ['owner', 'admin', 'member'].indexOf(b.role) ||
				a.display_name.localeCompare(b.display_name)
		)
	);

	function editable(m: Member) {
		if (!manage || m.role === 'owner' || m.user_id === auth.me?.id) return false;
		if (frame.role === 'admin' && m.role === 'admin') return false;
		return true;
	}

	// ---- join requests
	async function decide(r: JoinRequest, decision: 'approve' | 'deny') {
		const before = requests;
		requests = requests.filter((x) => x.id !== r.id);
		try {
			await call(
				api.POST('/v1/frames/{frameId}/join-requests/{requestId}/{decision}', {
					params: { path: { frameId: frame.id, requestId: r.id, decision } }
				})
			);
			toasts.success(t(decision === 'approve' ? 'people.approved' : 'people.denied', { name: r.user.display_name ?? '' }));
			if (decision === 'approve') void load();
		} catch (e) {
			requests = before;
			toasts.error(problemMessage(e));
		}
	}

	// ---- member edits (optimistic)
	async function updateMember(m: Member, body: { role?: 'admin' | 'member'; permissions?: Permissions }) {
		const before = { role: m.role, permissions: { ...m.permissions } };
		if (body.role) m.role = body.role;
		if (body.permissions) m.permissions = body.permissions;
		try {
			const updated = await call(
				api.PATCH('/v1/frames/{frameId}/members/{userId}', {
					params: { path: { frameId: frame.id, userId: m.user_id } },
					body
				})
			);
			Object.assign(m, updated);
		} catch (e) {
			m.role = before.role;
			m.permissions = before.permissions;
			toasts.error(problemMessage(e));
		}
	}

	let removing = $state<Member | null>(null);
	let removeOpen = $state(false);
	let removeItems = $state(false);

	async function removeMember() {
		const m = removing;
		if (!m) return;
		removeOpen = false;
		const before = members;
		members = (members ?? []).filter((x) => x.user_id !== m.user_id);
		try {
			await call(
				api.DELETE('/v1/frames/{frameId}/members/{userId}', {
					params: { path: { frameId: frame.id, userId: m.user_id }, query: { remove_items: removeItems } }
				})
			);
			toasts.success(t('people.removed', { name: m.display_name }));
		} catch (e) {
			members = before;
			toasts.error(problemMessage(e));
		}
	}

	// ---- invites
	let expiresIn = $state(168);
	let uses = $state<'single' | 'unlimited'>('single');
	let note = $state('');
	let creating = $state(false);
	let fresh = $state<Invite | null>(null);

	async function createInvite(e: SubmitEvent) {
		e.preventDefault();
		creating = true;
		try {
			const inv = await call(
				api.POST('/v1/frames/{frameId}/invites', {
					params: { path: { frameId: frame.id }, header: { 'Idempotency-Key': newIdempotencyKey() } },
					body: { expires_in_hours: expiresIn, max_uses: uses === 'single' ? 1 : null, ...(note.trim() ? { note: note.trim() } : {}) }
				})
			);
			fresh = inv;
			invites = [inv, ...invites];
			note = '';
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			creating = false;
		}
	}

	async function copy(url: string) {
		try {
			await navigator.clipboard.writeText(url);
			toasts.success(t('people.copied'));
		} catch {
			toasts.error(t('people.copyFailed'));
		}
	}

	async function share(inv: Invite) {
		if (navigator.share) {
			try {
				await navigator.share({ title: t('people.shareTitle', { name: frame.name }), text: t('people.shareText', { name: frame.name }), url: inv.url });
			} catch {
				/* cancelled */
			}
		} else void copy(inv.url);
	}

	function revoke(inv: Invite) {
		const before = invites;
		toasts.deferred({
			message: t('people.revoked'),
			undoLabel: t('common.undo'),
			apply: () => {
				invites = invites.filter((x) => x.id !== inv.id);
				if (fresh?.id === inv.id) fresh = null;
			},
			rollback: () => (invites = before),
			commit: () =>
				call(api.DELETE('/v1/frames/{frameId}/invites/{inviteId}', { params: { path: { frameId: frame.id, inviteId: inv.id } } })),
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}
</script>

{#if error}
	<div class="callout callout-error" role="alert"><Icon name="alert" />{problemMessage(error)}</div>
{:else}
	<div class="grid">
		<div class="stack">
			{#if requests.length > 0}
				<section class="card card-pad requests" aria-labelledby="req-title" in:softFly>
					<div class="section-title"><h2 id="req-title"><Icon name="bell" size={18} /> {t('people.requests')}</h2></div>
					<ul class="list" role="list">
						{#each requests as r (r.id)}
							<li class="req" out:softFly>
								<Avatar name={r.user.display_name ?? '?'} url={r.user.avatar_url} size={40} />
								<div class="who">
									<strong>{r.user.display_name}</strong>
									<span class="muted small">
										{r.via === 'invite' && r.invited_by_name ? t('people.viaInvite', { name: r.invited_by_name }) : t('people.viaCode')} · {relativeTime(r.created_at, i18n.tag)}
									</span>
									{#if r.note}<span class="small">“{r.note}”</span>{/if}
								</div>
								<div class="row-wrap">
									<button class="btn btn-sm btn-ghost" onclick={() => decide(r, 'deny')}>{t('people.deny')}</button>
									<button class="btn btn-sm btn-primary" onclick={() => decide(r, 'approve')}><Icon name="check" size={16} />{t('people.approve')}</button>
								</div>
							</li>
						{/each}
					</ul>
				</section>
			{/if}

			<section class="card card-pad" aria-labelledby="mem-title">
				<div class="section-title">
					<h2 id="mem-title">{t('people.members')}</h2>
					{#if members}<span class="muted small num">{members.length}</span>{/if}
				</div>
				{#if !members}
					<div class="stack-sm">{#each [0, 1, 2] as i (i)}<div class="skeleton" style="height:52px"></div>{/each}</div>
				{:else}
					<ul class="list" role="list">
						{#each sorted as m (m.user_id)}
							<li class="member" class:open={expanded === m.user_id}>
								<div class="m-row">
									<Avatar name={m.display_name} url={m.avatar_url} size={40} />
									<div class="who">
										<strong>{m.display_name}{#if m.user_id === auth.me?.id}<span class="faint you">({t('people.you')})</span>{/if}</strong>
										<span class="muted small">
											{m.last_sent_at ? t('people.lastSent', { when: relativeTime(m.last_sent_at, i18n.tag) }) : t('people.neverSent')}
										</span>
									</div>
									<RoleBadge role={m.role} />
									{#if editable(m)}
										<button
											class="btn btn-ghost btn-icon btn-sm"
											aria-expanded={expanded === m.user_id}
											aria-controls="perm-{m.user_id}"
											aria-label={t('people.editMember', { name: m.display_name })}
											onclick={() => (expanded = expanded === m.user_id ? null : m.user_id)}
										>
											<Icon name="chevronDown" size={18} />
										</button>
									{/if}
								</div>
								{#if expanded === m.user_id}
									<div class="perm-panel" id="perm-{m.user_id}" in:softFly>
										{#if frame.role === 'owner'}
											<div class="field">
												<label for="role-{m.user_id}">{t('people.role')}</label>
												<select
													id="role-{m.user_id}"
													class="select"
													value={m.role}
													onchange={(e) => updateMember(m, { role: e.currentTarget.value as 'admin' | 'member' })}
												>
													<option value="member">{t('role.member')}</option>
													<option value="admin">{t('role.admin')}</option>
												</select>
												<span class="hint">{m.role === 'admin' ? t('people.adminHint') : t('people.memberHint')}</span>
											</div>
										{/if}
										{#if m.role === 'member'}
											<fieldset class="perms">
												<legend class="label">{t('people.permissions')}</legend>
												{#each PERMS as p (p.key)}
													<label class="switch">
														<input
															type="checkbox"
															checked={m.permissions[p.key] === true}
															onchange={(e) => updateMember(m, { permissions: { ...m.permissions, [p.key]: e.currentTarget.checked } })}
														/>
														<span><span class="p-label">{t(p.label)}</span><span class="hint">{t(p.hint)}</span></span>
													</label>
												{/each}
											</fieldset>
										{/if}
										{#if frame.role === 'owner'}
											<button
												class="btn btn-ghost btn-sm"
												onclick={() => {
													transferTo = m.user_id;
													transferOpen = true;
												}}><Icon name="crown" size={16} />{t('people.makeOwner')}</button
											>
										{/if}
										<button
											class="btn btn-danger-ghost btn-sm"
											onclick={() => {
												removing = m;
												removeItems = false;
												removeOpen = true;
											}}><Icon name="trash" size={16} />{t('people.remove')}</button
										>
									</div>
								{/if}
							</li>
						{/each}
					</ul>
				{/if}
			</section>
		</div>

		{#if canInvite}
			<section class="card card-pad invites" aria-labelledby="inv-title">
				<div class="section-title"><h2 id="inv-title">{t('people.invite')}</h2></div>
				<p class="muted small">{frame.invite_requires_approval ? t('people.inviteHintApproval') : t('people.inviteHint')}</p>

				{#if fresh}
					<div class="fresh" in:softFly>
						<p class="label">{t('people.linkReady')}</p>
						<div class="link-box">
							<code>{fresh.url}</code>
						</div>
						<div class="row-wrap">
							<button class="btn btn-primary btn-sm" onclick={() => fresh && copy(fresh.url)}><Icon name="copy" size={16} />{t('people.copy')}</button>
							<button class="btn btn-sm" onclick={() => fresh && share(fresh)}><Icon name="share" size={16} />{t('people.share')}</button>
							<button class="btn btn-ghost btn-sm" onclick={() => (fresh = null)}>{t('common.done')}</button>
						</div>
					</div>
				{:else}
					<form class="stack" onsubmit={createInvite}>
						<div class="two">
							<div class="field">
								<label for="inv-exp">{t('people.expires')}</label>
								<select id="inv-exp" class="select" bind:value={expiresIn}>
									<option value={24}>{t('people.exp1d')}</option>
									<option value={168}>{t('people.exp7d')}</option>
									<option value={720}>{t('people.exp30d')}</option>
								</select>
							</div>
							<div class="field">
								<label for="inv-uses">{t('people.uses')}</label>
								<select id="inv-uses" class="select" bind:value={uses}>
									<option value="single">{t('people.usesSingle')}</option>
									<option value="unlimited">{t('people.usesUnlimited')}</option>
								</select>
							</div>
						</div>
						<div class="field">
							<label for="inv-note">{t('people.note')} <span class="faint">({t('common.optional')})</span></label>
							<input id="inv-note" class="input" maxlength="80" bind:value={note} placeholder={t('people.notePlaceholder')} />
						</div>
						<button class="btn btn-soft" type="submit" disabled={creating}>
							{#if creating}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="link" size={18} />{/if}{t('people.create')}
						</button>
					</form>
				{/if}

				{#if invites.length > 0}
					<hr class="sep" />
					<h3 class="small-h">{t('people.activeInvites')}</h3>
					<ul class="list" role="list">
						{#each invites as inv (inv.id)}
							<li class="inv" out:softFly>
								<span class="inv-icon"><Icon name="link" size={16} /></span>
								<div class="who">
									<span class="small">{inv.note ?? (inv.created_by_name ? t('people.byName', { name: inv.created_by_name }) : t('people.inviteLink'))}</span>
									<span class="muted tiny num">
										{inv.max_uses ? t('people.usesOf', { n: inv.uses ?? 0, max: inv.max_uses }) : t('people.usesN', { n: inv.uses ?? 0 })} · {t('people.expiresIn', { when: relativeTime(inv.expires_at, i18n.tag) })}
									</span>
								</div>
								<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('people.copy')} onclick={() => copy(inv.url)}><Icon name="copy" size={16} /></button>
								<button class="btn btn-danger-ghost btn-icon btn-sm" aria-label={t('people.revoke')} onclick={() => revoke(inv)}><Icon name="x" size={16} /></button>
							</li>
						{/each}
					</ul>
				{/if}
			</section>
		{/if}
	</div>
{/if}

<TransferDialog
	bind:open={transferOpen}
	{frame}
	preselect={transferTo}
	ondone={(r) => {
		toasts.success(t('transfer.done', { name: r.newOwner.display_name }));
		expanded = null;
		void load();
		onchanged?.();
	}}
/>

<Dialog bind:open={removeOpen} title={t('people.removeConfirm', { name: removing?.display_name ?? '' })}>
	<div class="stack">
		<p class="muted">{t('people.removeBody')}</p>
		<label class="switch"><input type="checkbox" bind:checked={removeItems} /><span>{t('people.removeItems')}</span></label>
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (removeOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={removeMember}>{t('people.remove')}</button>
	{/snippet}
</Dialog>

<style>
	.grid {
		display: grid;
		gap: var(--s-4);
		align-items: start;
	}
	@media (min-width: 900px) {
		.grid {
			grid-template-columns: 1.3fr 1fr;
		}
	}
	.requests {
		border-color: color-mix(in srgb, var(--primary) 40%, var(--border));
		background: color-mix(in srgb, var(--primary-soft) 35%, var(--surface));
	}
	.requests h2 {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.list > li + li {
		border-top: 1px solid var(--border);
	}
	.req,
	.m-row,
	.inv {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 10px 0;
	}
	.req {
		flex-wrap: wrap;
	}
	@media (max-width: 520px) {
		.req .row-wrap {
			flex-basis: 100%;
			justify-content: flex-end;
		}
	}
	.who {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.who strong {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.member.open {
		background: linear-gradient(var(--surface-2), var(--surface-2)) padding-box;
		margin: 0 calc(-1 * var(--s-3));
		padding: 0 var(--s-3);
		border-radius: var(--r);
	}
	.perm-panel {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		padding: 0 0 var(--s-3) 52px;
	}
	@media (max-width: 520px) {
		.perm-panel {
			padding-left: 0;
		}
	}
	.perm-panel .btn {
		align-self: flex-start;
	}
	.perms {
		border: 0;
		margin: 0;
		padding: 0;
	}
	.perms .switch > span {
		display: flex;
		flex-direction: column;
	}
	.p-label {
		font-weight: 600;
		font-size: 0.92rem;
	}
	.two {
		display: grid;
		gap: var(--s-3);
		grid-template-columns: 1fr 1fr;
	}
	.invites > p {
		margin-bottom: var(--s-4);
	}
	.invites form .btn {
		align-self: flex-start;
	}
	.fresh {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		padding: var(--s-3);
		border-radius: var(--r);
		background: var(--accent-soft);
	}
	.link-box {
		padding: 10px 12px;
		border-radius: var(--r-sm);
		background: var(--surface);
		border: 1px solid var(--border);
		overflow-x: auto;
	}
	code {
		font-size: 0.85rem;
		white-space: nowrap;
	}
	.small-h {
		font-family: var(--font-body);
		font-size: 0.8rem;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: var(--text-faint);
		margin-bottom: 4px;
	}
	.you {
		margin-left: 0.35em;
		font-weight: 500;
	}
	.inv-icon {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
		flex: none;
	}
</style>
