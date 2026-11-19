<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call, type Feed, type FeedUpdate, type Item, type Schemas } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';

	let { feed, onupdated }: { feed: Feed; onupdated?: (f: Feed) => void } = $props();

	type Form = {
		name: string;
		cover_item_id: string | null;
		auto_approve_subscriptions: boolean;
		invite_requires_approval: boolean;
		members_see_reactions: boolean;
		posters_can_invite: boolean;
		posters_can_add_birthdays: boolean;
	};
	const fromFeed = (f: Feed): Form => ({
		name: f.name,
		cover_item_id: f.cover_item_id ?? null,
		auto_approve_subscriptions: f.auto_approve_subscriptions ?? false,
		invite_requires_approval: f.invite_requires_approval ?? true,
		members_see_reactions: f.members_see_reactions ?? false,
		posters_can_invite: f.posters_can_invite ?? false,
		posters_can_add_birthdays: f.posters_can_add_birthdays ?? false
	});

	const EMPTY: Form = {
		name: '',
		cover_item_id: null,
		auto_approve_subscriptions: false,
		invite_requires_approval: true,
		members_see_reactions: false,
		posters_can_invite: false,
		posters_can_add_birthdays: false
	};
	let form = $state<Form>({ ...EMPTY });
	let base = $state<Form>({ ...EMPTY });
	let saving = $state(false);

	$effect(() => {
		const f = fromFeed(feed);
		base = f;
		form = { ...f };
	});

	/** Only the fields that changed (a PATCH with everything would undo someone else's change of another field). */
	const patch = $derived.by(() => {
		const out: FeedUpdate = {};
		const name = form.name.trim();
		if (name && name !== base.name) out.name = name;
		if (form.cover_item_id !== base.cover_item_id) out.cover_item_id = form.cover_item_id;
		for (const k of ['auto_approve_subscriptions', 'invite_requires_approval', 'members_see_reactions', 'posters_can_invite', 'posters_can_add_birthdays'] as const)
			if (form[k] !== base[k]) out[k] = form[k];
		return out;
	});
	const dirty = $derived(Object.keys(patch).length > 0);

	async function save(e: SubmitEvent) {
		e.preventDefault();
		if (!dirty || !form.name.trim()) return;
		saving = true;
		try {
			const updated = (await call(
				api.PATCH('/v1/feeds/{feedId}', { params: { path: { feedId: feed.id } }, body: patch as Schemas['FeedUpdate'] })
			)) as Feed;
			feeds.upsert(updated);
			onupdated?.(updated);
			toasts.success(t('common.saved'));
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			saving = false;
		}
	}

	// ---- cover: one of the feed's own items
	let items = $state<Item[]>([]);
	let itemsLoaded = $state(false);
	$effect(() => {
		void feed.id;
		itemsLoaded = false;
		call(api.GET('/v1/feeds/{feedId}/posts', { params: { path: { feedId: feed.id }, query: { limit: 30 } } }))
			.then((r) => (items = r.posts.flatMap((p) => p.items).filter((i) => i.status !== 'failed' && i.status !== 'processing').slice(0, 48)))
			.catch(() => (items = []))
			.finally(() => (itemsLoaded = true));
	});

	// ---- delete (owner)
	let deleteOpen = $state(false);
	let deleting = $state(false);
	async function remove() {
		deleting = true;
		try {
			await call(api.DELETE('/v1/feeds/{feedId}', { params: { path: { feedId: feed.id } } }));
			deleteOpen = false;
			feeds.remove(feed.id);
			toasts.success(t('feedSettings.deleted', { feed: feed.name }));
			await goto('/feeds');
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			deleting = false;
		}
	}
</script>

<form class="stack settings" onsubmit={save}>
	<section class="card card-pad stack" aria-labelledby="fs-general">
		<h2 id="fs-general">{t('feedSettings.general')}</h2>
		<div class="field">
			<label for="fs-name">{t('feeds.name')}</label>
			<input id="fs-name" class="input" required maxlength="40" bind:value={form.name} />
		</div>
		<fieldset class="cover-field">
			<legend class="label">{t('feedSettings.cover')}</legend>
			<p class="hint">{t('feedSettings.coverHint')}</p>
			{#if !itemsLoaded}
				<div class="skeleton" style="height:84px"></div>
			{:else if items.length === 0}
				<p class="muted small">{t('feedSettings.coverEmpty')}</p>
			{:else}
				<div class="covers" role="radiogroup" aria-label={t('feedSettings.cover')}>
					<label class="cv none" class:checked={form.cover_item_id === null}>
						<input type="radio" name="cover" value={null} bind:group={form.cover_item_id} />
						<span>{t('feedSettings.noCover')}</span>
					</label>
					{#each items as it, i (it.id)}
						<label class="cv" class:checked={form.cover_item_id === it.id}>
							<input type="radio" name="cover" value={it.id} bind:group={form.cover_item_id} aria-label={t('feedSettings.coverN', { n: i + 1 })} />
							<Thumb itemId={it.id} variant="thumb_256" kind={it.kind} radius="0" />
						</label>
					{/each}
				</div>
			{/if}
		</fieldset>
	</section>

	<section class="card card-pad stack" aria-labelledby="fs-access">
		<h2 id="fs-access">{t('feedSettings.access')}</h2>
		<label class="switch">
			<input type="checkbox" bind:checked={form.auto_approve_subscriptions} />
			<span><span class="s-label">{t('feedSettings.autoApprove')}</span><span class="hint">{t('feedSettings.autoApproveHint')}</span></span>
		</label>
		<label class="switch">
			<input type="checkbox" bind:checked={form.invite_requires_approval} />
			<span><span class="s-label">{t('feedSettings.inviteApproval')}</span><span class="hint">{t('feedSettings.inviteApprovalHint')}</span></span>
		</label>
		<label class="switch">
			<input type="checkbox" bind:checked={form.posters_can_invite} />
			<span><span class="s-label">{t('feedSettings.postersInvite')}</span><span class="hint">{t('feedSettings.postersInviteHint')}</span></span>
		</label>
		<label class="switch">
			<input type="checkbox" bind:checked={form.members_see_reactions} />
			<span><span class="s-label">{t('feedSettings.membersSee')}</span><span class="hint">{t('feedSettings.membersSeeHint')}</span></span>
		</label>
	</section>

	{#if discovery.birthdays}
		<section class="card card-pad stack" aria-labelledby="fs-birthdays">
			<h2 id="fs-birthdays">{t('feed.tabBirthdays')}</h2>
			<label class="switch">
				<input type="checkbox" bind:checked={form.posters_can_add_birthdays} />
				<span><span class="s-label">{t('feedSettings.postersBirthdays')}</span><span class="hint">{t('feedSettings.postersBirthdaysHint')}</span></span>
			</label>
		</section>
	{/if}

	<div class="row-wrap actions">
		<button class="btn btn-primary" type="submit" disabled={!dirty || saving || !form.name.trim()}>
			{#if saving}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.save')}
		</button>
		{#if dirty}<button class="btn btn-ghost" type="button" onclick={() => (form = { ...base })}>{t('common.cancel')}</button>{/if}
	</div>
</form>

{#if feed.role === 'owner'}
	<section class="card card-pad stack-sm danger-zone" aria-labelledby="fs-danger">
		<h2 id="fs-danger">{t('feedSettings.deleteTitle')}</h2>
		<p class="muted small">{t('feedSettings.deleteBody')}</p>
		<button class="btn btn-danger-ghost" onclick={() => (deleteOpen = true)}><Icon name="trash" size={16} />{t('feedSettings.delete')}</button>
	</section>
{/if}

<Dialog bind:open={deleteOpen} title={t('feedSettings.deleteConfirm', { feed: feed.name })}>
	<p class="muted">{t('feedSettings.deleteBody')}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (deleteOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={remove} disabled={deleting}>
			{#if deleting}<span class="spinner" aria-hidden="true"></span>{/if}{t('feedSettings.delete')}
		</button>
	{/snippet}
</Dialog>

<style>
	.settings {
		max-width: 720px;
	}
	.switch > span {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 600;
	}
	.cover-field {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.covers {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(76px, 1fr));
		gap: 6px;
	}
	.cv {
		position: relative;
		aspect-ratio: 1;
		border-radius: var(--r-sm);
		overflow: hidden;
		border: 2px solid transparent;
		cursor: pointer;
		background: var(--surface-2);
	}
	.cv.checked {
		border-color: var(--primary);
		box-shadow: 0 0 0 2px var(--primary-soft);
	}
	.cv:has(input:focus-visible) {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.cv input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.cv.none {
		display: grid;
		place-items: center;
		text-align: center;
		font-size: 0.78rem;
		color: var(--text-muted);
		padding: 4px;
	}
	.danger-zone {
		max-width: 720px;
		margin-top: var(--s-4);
	}
	.danger-zone .btn {
		align-self: flex-start;
	}
</style>
