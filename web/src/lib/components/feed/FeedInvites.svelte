<script lang="ts">
	import { api, call, type Feed, type FeedInvite, type FeedInvitePurpose } from '$lib/api/client';
	import { formatFeedCode } from '$lib/codes';
	import { inviteBody, isFeedAdmin, type InviteUses } from '$lib/feeds';
	import { relativeTime } from '$lib/format';
	import { newIdempotencyKey } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import QrCode from '../QrCode.svelte';
	import { discovery } from '$lib/discovery.svelte';

	let { feed }: { feed: Feed } = $props();

	/** Subscribe invites pre-approve a frame, so only owner and admins make them (feeds.md §2). */
	const admin = $derived(isFeedAdmin(feed.role));

	let invites = $state<FeedInvite[]>([]);
	let loadError = $state<unknown>(null);

	async function load() {
		try {
			invites = await call(api.GET('/v1/feeds/{feedId}/invites', { params: { path: { feedId: feed.id } } }));
			loadError = null;
		} catch (e) {
			loadError = e;
		}
	}
	$effect(() => {
		void feed.id;
		void load();
	});

	let purpose = $state<FeedInvitePurpose>('join');
	// "Frames on other servers may use this link" (README §5.22): subscribe invites of families that allow it.
	let allowRemote = $state(false);
	const remoteOk = $derived(discovery.remoteFamilies && purpose === 'subscribe' && feed.allow_remote_frames === true);
	let expiresIn = $state(168);
	let uses = $state<InviteUses>('single');
	let note = $state('');
	let creating = $state(false);
	let fresh = $state<FeedInvite | null>(null);

	$effect(() => {
		if (!admin) purpose = 'join';
	});

	async function create(e: SubmitEvent) {
		e.preventDefault();
		creating = true;
		try {
			const inv = await call(
				api.POST('/v1/feeds/{feedId}/invites', {
					params: { path: { feedId: feed.id }, header: { 'Idempotency-Key': newIdempotencyKey() } },
					body: { ...inviteBody({ purpose, expiresInHours: expiresIn, uses, note }), ...(remoteOk && allowRemote ? { allow_remote: true } : {}) }
				})
			);
			fresh = inv;
			invites = [inv, ...invites.filter((x) => x.id !== inv.id)];
			note = '';
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			creating = false;
		}
	}

	async function copy(text: string) {
		try {
			await navigator.clipboard.writeText(text);
			toasts.success(t('people.copied'));
		} catch {
			toasts.error(t('people.copyFailed'));
		}
	}

	async function share(inv: FeedInvite) {
		const text =
			inv.purpose === 'subscribe'
				? t('feedInvites.shareTextSubscribe', { feed: feed.name, code: formatFeedCode(inv.code) })
				: t('feedInvites.shareTextJoin', { feed: feed.name, code: formatFeedCode(inv.code) });
		if (navigator.share) {
			try {
				await navigator.share({ title: feed.name, text, url: inv.url });
			} catch {
				/* cancelled */
			}
		} else void copy(`${text}\n${inv.url}`);
	}

	function revoke(inv: FeedInvite) {
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
				call(api.DELETE('/v1/feeds/{feedId}/invites/{inviteId}', { params: { path: { feedId: feed.id, inviteId: inv.id } } })),
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}

	let qrFor = $state<FeedInvite | null>(null);
	let qrOpen = $state(false);
</script>

{#snippet inviteCard(inv: FeedInvite)}
	<div class="fresh" in:softFly>
		<p class="label">{inv.purpose === 'subscribe' ? t('feedInvites.readySubscribe') : t('feedInvites.readyJoin')}</p>
		<div class="fresh-body">
			<QrCode value={inv.url} size={150} label={t('feedInvites.qrLabel', { feed: feed.name })} />
			<div class="stack-sm grow">
				<div>
					<span class="hint">{t('feedInvites.code')}</span>
					<p class="code num" data-testid="invite-code">{formatFeedCode(inv.code)}</p>
				</div>
				<div class="link-box"><code>{inv.url}</code></div>
				<div class="row-wrap">
					<button class="btn btn-primary btn-sm" onclick={() => copy(inv.url)}><Icon name="copy" size={16} />{t('people.copy')}</button>
					<button class="btn btn-sm" onclick={() => copy(formatFeedCode(inv.code))}><Icon name="copy" size={16} />{t('feedInvites.copyCode')}</button>
					<button class="btn btn-sm" onclick={() => share(inv)}><Icon name="share" size={16} />{t('people.share')}</button>
				</div>
			</div>
		</div>
	</div>
{/snippet}

<div class="grid">
	<section class="card card-pad stack" aria-labelledby="finv-title">
		<div class="section-title"><h2 id="finv-title">{t('feedInvites.title')}</h2></div>
		{#if fresh}
			{@render inviteCard(fresh)}
			<button class="btn btn-ghost btn-sm done" onclick={() => (fresh = null)}>{t('common.done')}</button>
		{:else}
			<form class="stack" onsubmit={create}>
				{#if admin}
					<fieldset class="purpose">
						<legend class="label">{t('feedInvites.purpose')}</legend>
						<label class="p-opt" class:checked={purpose === 'join'}>
							<input type="radio" name="purpose" value="join" bind:group={purpose} />
							<span><strong>{t('feedInvites.join')}</strong><span class="hint">{feed.invite_requires_approval === false ? t('feedInvites.joinHintInstant') : t('feedInvites.joinHint')}</span></span>
						</label>
						<label class="p-opt" class:checked={purpose === 'subscribe'}>
							<input type="radio" name="purpose" value="subscribe" bind:group={purpose} />
							<span><strong>{t('feedInvites.subscribe')}</strong><span class="hint">{t('feedInvites.subscribeHint')}</span></span>
						</label>
					</fieldset>
					{#if remoteOk}
						<label class="switch">
							<input type="checkbox" bind:checked={allowRemote} />
							<span class="sw"><span class="s-label">{t('remote.inviteAllow')}</span><span class="hint">{t('remote.inviteAllowHint')}</span></span>
						</label>
					{/if}
				{:else}
					<p class="muted small">{feed.invite_requires_approval === false ? t('feedInvites.joinHintInstant') : t('feedInvites.joinHint')}</p>
				{/if}
				<div class="two">
					<div class="field">
						<label for="finv-exp">{t('people.expires')}</label>
						<select id="finv-exp" class="select" bind:value={expiresIn}>
							<option value={24}>{t('people.exp1d')}</option>
							<option value={168}>{t('people.exp7d')}</option>
							<option value={720}>{t('people.exp30d')}</option>
						</select>
					</div>
					<div class="field">
						<label for="finv-uses">{t('people.uses')}</label>
						<select id="finv-uses" class="select" bind:value={uses}>
							<option value="single">{t('people.usesSingle')}</option>
							<option value="unlimited">{t('people.usesUnlimited')}</option>
						</select>
					</div>
				</div>
				<div class="field">
					<label for="finv-note">{t('people.note')} <span class="faint">({t('common.optional')})</span></label>
					<input id="finv-note" class="input" maxlength="80" bind:value={note} placeholder={t('people.notePlaceholder')} />
				</div>
				<button class="btn btn-soft create" type="submit" disabled={creating}>
					{#if creating}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="link" size={18} />{/if}{t('people.create')}
				</button>
			</form>
		{/if}
	</section>

	<section class="card card-pad" aria-labelledby="finv-active">
		<div class="section-title"><h2 id="finv-active">{t('people.activeInvites')}</h2></div>
		{#if loadError}
			<p class="error-text small">{problemMessage(loadError)}</p>
		{:else if invites.length === 0}
			<p class="muted small">{t('feedInvites.none')}</p>
		{:else}
			<ul class="list" role="list">
				{#each invites as inv (inv.id)}
					<li class="inv" out:softFly>
						<span class="inv-icon"><Icon name={inv.purpose === 'subscribe' ? 'frame' : 'users'} size={16} /></span>
						<div class="who">
							<span class="small">
								<strong>{inv.purpose === 'subscribe' ? t('feedInvites.subscribe') : t('feedInvites.join')}</strong>
								· <span class="num">{formatFeedCode(inv.code)}</span>{#if inv.note} · {inv.note}{/if}{#if inv.allow_remote} · <span class="badge"><Icon name="globe" size={12} />{t('remote.inviteAllowsRemote')}</span>{/if}
							</span>
							<span class="muted tiny num">
								{inv.max_uses ? t('people.usesOf', { n: inv.uses ?? 0, max: inv.max_uses }) : t('people.usesN', { n: inv.uses ?? 0 })} · {t('people.expiresIn', { when: relativeTime(inv.expires_at, i18n.tag) })}{#if inv.created_by_name} · {t('people.byName', { name: inv.created_by_name })}{/if}
							</span>
						</div>
						<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('feedInvites.showQr')} title={t('feedInvites.showQr')} onclick={() => ((qrFor = inv), (qrOpen = true))}><Icon name="maximize" size={16} /></button>
						<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('people.copy')} onclick={() => copy(inv.url)}><Icon name="copy" size={16} /></button>
						<button class="btn btn-danger-ghost btn-icon btn-sm" aria-label={t('people.revoke')} onclick={() => revoke(inv)}><Icon name="x" size={16} /></button>
					</li>
				{/each}
			</ul>
		{/if}
	</section>
</div>

<Dialog bind:open={qrOpen} title={feed.name}>
	{#if qrFor}{@render inviteCard(qrFor)}{/if}
	{#snippet footer()}
		<button class="btn btn-primary" onclick={() => (qrOpen = false)}>{t('common.done')}</button>
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
			grid-template-columns: 1.1fr 1fr;
		}
	}
	.purpose {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.p-opt {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		padding: 10px 12px;
		border: 1.5px solid var(--border);
		border-radius: var(--r);
		cursor: pointer;
	}
	.p-opt.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface));
	}
	.p-opt > span {
		display: flex;
		flex-direction: column;
	}
	.sw {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 600;
	}
	.p-opt input {
		margin-top: 4px;
	}
	.two {
		display: grid;
		gap: var(--s-3);
		grid-template-columns: 1fr 1fr;
	}
	.create,
	.done {
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
	.fresh-body {
		display: flex;
		gap: var(--s-3);
		flex-wrap: wrap;
		align-items: flex-start;
	}
	.grow {
		flex: 1;
		min-width: 200px;
	}
	.code {
		font-size: 1.6rem;
		font-weight: 700;
		letter-spacing: 0.08em;
		margin: 0;
	}
	.link-box {
		padding: 8px 10px;
		border-radius: var(--r-sm);
		background: var(--surface);
		border: 1px solid var(--border);
		overflow-x: auto;
	}
	code {
		font-size: 0.8rem;
		white-space: nowrap;
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
	.inv {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		padding: 10px 0;
	}
	.who {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
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
