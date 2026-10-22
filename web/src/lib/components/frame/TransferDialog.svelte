<script lang="ts">
	import { api, call, type Frame, type Member } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import RoleBadge from '../RoleBadge.svelte';

	let {
		open = $bindable(false),
		frame,
		preselect = null,
		leaving = false,
		ondone
	}: {
		open?: boolean;
		frame: Frame;
		/** Member to suggest (from the people list). */
		preselect?: string | null;
		/** Opened from "Leave frame": the owner leaves right after handing over. */
		leaving?: boolean;
		ondone: (result: { left: boolean; newOwner: Member }) => void;
	} = $props();

	const id = $props.id();
	let members = $state<Member[] | null>(null);
	let error = $state<unknown>(null);
	let choice = $state<string | null>(null);
	let step = $state<'choose' | 'confirm'>('choose');
	let leaveAfter = $state(false);
	let removeMine = $state(false);
	let busy = $state(false);

	$effect(() => {
		if (!open) return;
		step = 'choose';
		leaveAfter = leaving;
		removeMine = false;
		error = null;
		members = null;
		call(api.GET('/v1/frames/{frameId}/members', { params: { path: { frameId: frame.id } } }))
			.then((m) => {
				members = m;
				const eligible = m.filter((x) => x.user_id !== auth.me?.id);
				choice = preselect && eligible.some((x) => x.user_id === preselect) ? preselect : (eligible.find((x) => x.role === 'admin') ?? eligible[0])?.user_id ?? null;
			})
			.catch((e) => (error = e));
	});

	// Admins first: they already manage the frame, so they are the natural successors.
	const candidates = $derived(
		(members ?? [])
			.filter((m) => m.user_id !== auth.me?.id)
			.sort((a, b) => (a.role === b.role ? a.display_name.localeCompare(b.display_name) : a.role === 'admin' ? -1 : 1))
	);
	const chosen = $derived(candidates.find((m) => m.user_id === choice) ?? null);

	async function transfer() {
		if (!chosen) return;
		busy = true;
		try {
			await call(api.POST('/v1/frames/{frameId}/transfer', { params: { path: { frameId: frame.id } }, body: { user_id: chosen.user_id } }));
		} catch (e) {
			busy = false;
			toasts.error(problemMessage(e));
			return;
		}
		let left = false;
		if (leaveAfter) {
			try {
				await call(api.POST('/v1/frames/{frameId}/leave', { params: { path: { frameId: frame.id } }, body: { remove_my_items: removeMine } }));
				left = true;
			} catch (e) {
				// The transfer itself worked; say so and explain the leave didn't.
				toasts.error(problemMessage(e));
			}
		}
		busy = false;
		open = false;
		ondone({ left, newOwner: chosen });
	}
</script>

<Dialog bind:open title={step === 'choose' ? (leaving ? t('transfer.leaveTitle', { name: frame.name }) : t('transfer.title')) : t('transfer.confirmTitle', { name: chosen?.display_name ?? '' })}>
	{#if error}
		<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</p>
	{:else if !members}
		<div class="stack-sm" aria-busy="true">{#each [0, 1, 2] as i (i)}<div class="skeleton" style="height:52px"></div>{/each}</div>
	{:else if candidates.length === 0}
		<div class="stack">
			<p class="muted">{leaving ? t('transfer.noOneLeave') : t('transfer.noOne')}</p>
		</div>
	{:else if step === 'choose'}
		<div class="stack">
			<p class="muted small">{leaving ? t('transfer.leaveBody') : t('transfer.body')}</p>
			<fieldset class="people">
				<legend class="label">{t('transfer.pick')}</legend>
				{#each candidates as m (m.user_id)}
					<label class="person" class:checked={choice === m.user_id}>
						<input type="radio" name="{id}-owner" value={m.user_id} bind:group={choice} />
						<Avatar name={m.display_name} url={m.avatar_url} size={36} />
						<span class="p-name">{m.display_name}</span>
						<RoleBadge role={m.role} />
					</label>
				{/each}
			</fieldset>
			{#if !leaving}
				<label class="switch"><input type="checkbox" bind:checked={leaveAfter} /><span>{t('transfer.alsoLeave')}</span></label>
			{/if}
			{#if leaveAfter}
				<label class="switch"><input type="checkbox" bind:checked={removeMine} /><span>{t('overview.removeMine')}</span></label>
			{/if}
		</div>
	{:else if chosen}
		<div class="stack confirm">
			<div class="handover" aria-hidden="true">
				<Avatar name={auth.me?.display_name ?? ''} url={auth.me?.avatar_url} size={48} />
				<span class="arrow"><Icon name="crown" size={20} /></span>
				<Avatar name={chosen.display_name} url={chosen.avatar_url} size={48} />
			</div>
			<ul class="facts" role="list">
				<li><Icon name="check" size={16} />{t('transfer.factOwner', { name: chosen.display_name, frame: frame.name })}</li>
				<li><Icon name="check" size={16} />{leaveAfter ? t('transfer.factLeave') : t('transfer.factAdmin')}</li>
				<li><Icon name="info" size={16} />{t('transfer.factUndo', { name: chosen.display_name })}</li>
			</ul>
		</div>
	{/if}
	{#snippet footer()}
		{#if step === 'confirm'}
			<button class="btn btn-ghost" onclick={() => (step = 'choose')} disabled={busy}>{t('transfer.back')}</button>
			<button class="btn btn-danger" onclick={transfer} disabled={busy}>
				{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{leaveAfter ? t('transfer.doLeave') : t('transfer.do')}
			</button>
		{:else}
			<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
			{#if candidates.length > 0}
				<button class="btn btn-primary" onclick={() => (step = 'confirm')} disabled={!chosen}>{t('transfer.continue')}</button>
			{/if}
		{/if}
	{/snippet}
</Dialog>

<style>
	.people {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
	}
	.people legend {
		margin-bottom: 6px;
	}
	.person {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 8px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
		cursor: pointer;
		min-width: 0;
	}
	.person.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface));
	}
	.person:has(input:focus-visible) {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.person input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.p-name {
		flex: 1;
		min-width: 0;
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.handover {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: var(--s-3);
		padding: var(--s-2) 0;
	}
	.arrow {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--accent-soft);
		color: var(--accent);
	}
	.facts {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.facts li {
		display: flex;
		gap: 10px;
		align-items: flex-start;
	}
	.facts :global(svg) {
		margin-top: 3px;
		color: var(--text-muted);
	}
</style>
