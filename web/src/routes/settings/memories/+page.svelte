<script lang="ts">
	import { api, call, type Person, type Schemas } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { withMemories, withPerson, type MemoryPreferences } from '$lib/leaving';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Avatar from '$components/Avatar.svelte';
	import Icon from '$components/Icon.svelte';

	// Settings → Memories (leaving.md story 8, README §5.21): leave people out, hidden memories. Private.
	let prefs = $state<MemoryPreferences | null>(null);
	let error = $state<unknown>(null);
	let people = $state<Person[]>([]);
	let choice = $state('');
	let busy = $state(false);

	$effect(() => {
		if (!discovery.leaving) return;
		call(api.GET('/v1/me/memories'))
			.then((p) => (prefs = p))
			.catch((e) => (error = e));
		call(api.GET('/v1/contacts'))
			.then((c) => (people = c))
			.catch(() => (people = []));
	});

	const hiddenIds = $derived(new Set((prefs?.hidden_people ?? []).map((p) => p.user_id)));
	const candidates = $derived(people.filter((p) => !hiddenIds.has(p.person.user_id)).sort((a, b) => a.person.display_name.localeCompare(b.person.display_name)));

	async function save(body: Schemas['MemoryPreferencesUpdate'], message?: string) {
		busy = true;
		try {
			prefs = await call(api.PUT('/v1/me/memories', { body }));
			if (message) toasts.success(message);
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}

	async function leaveOut(e: SubmitEvent) {
		e.preventDefault();
		const p = people.find((x) => x.person.user_id === choice);
		if (!prefs || !p) return;
		await save(withPerson(prefs, p.person.user_id, true), t('leaving.leftOut', { name: p.person.display_name }));
		choice = '';
	}
</script>

<svelte:head><title>{t('leaving.memoriesTitle')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<a href="/settings" class="back"><Icon name="chevronLeft" size={18} />{t('settings.title')}</a>
	<div class="page-header">
		<div>
			<h1>{t('leaving.memoriesTitle')}</h1>
			<p class="lede">{t('leaving.memoriesLede')}</p>
		</div>
	</div>

	{#if !discovery.leaving}
		<p class="callout"><Icon name="info" size={18} />{t('problem.feature_disabled')}</p>
	{:else if error}
		<div class="callout callout-error" role="alert"><Icon name="alert" />{problemMessage(error)}</div>
	{:else if !prefs}
		<div class="stack-sm" aria-busy="true">{#each [0, 1] as i (i)}<div class="skeleton" style="height:120px"></div>{/each}</div>
	{:else}
		<div class="stack">
			<section class="card card-pad stack" aria-labelledby="mem-people">
				<div>
					<h2 id="mem-people">{t('leaving.leaveOutTitle')}</h2>
					<p class="hint">{t('leaving.leaveOutHint')}</p>
				</div>
				{#if prefs.hidden_people.length === 0}
					<p class="muted small">{t('leaving.noneLeftOut')}</p>
				{:else}
					<ul class="list" role="list">
						{#each prefs.hidden_people as p (p.user_id)}
							<li class="row-item">
								<Avatar name={p.display_name} url={p.avatar_url} size={36} />
								<strong class="grow">{p.display_name}</strong>
								<button class="btn btn-sm btn-ghost" disabled={busy} onclick={() => prefs && save(withPerson(prefs, p.user_id, false), t('leaving.included', { name: p.display_name }))}>
									{t('leaving.include')}
								</button>
							</li>
						{/each}
					</ul>
				{/if}
				{#if candidates.length > 0}
					<form class="row-wrap" onsubmit={leaveOut}>
						<label for="mem-add" class="visually-hidden">{t('leaving.addPerson')}</label>
						<select id="mem-add" class="select grow" bind:value={choice}>
							<option value="" disabled>{t('leaving.pickPerson')}</option>
							{#each candidates as c (c.person.user_id)}<option value={c.person.user_id}>{c.person.display_name}</option>{/each}
						</select>
						<button class="btn btn-soft" type="submit" disabled={!choice || busy}><Icon name="plus" size={16} />{t('leaving.addPerson')}</button>
					</form>
				{/if}
			</section>

			<section class="card card-pad stack-sm" aria-labelledby="mem-hidden">
				<h2 id="mem-hidden">{t('leaving.hiddenTitle')}</h2>
				{#if prefs.hidden_memories.length === 0}
					<p class="muted small">{t('leaving.hiddenNone')}</p>
				{:else}
					<p class="small">{t('leaving.hiddenCount', { n: prefs.hidden_memories.length })}</p>
					<button class="btn btn-sm btn-ghost align-start" disabled={busy} onclick={() => prefs && save(withMemories(prefs, prefs.hidden_memories, false), t('common.saved'))}>
						<Icon name="eye" size={16} />{t('leaving.showAllAgain')}
					</button>
				{/if}
			</section>
		</div>
	{/if}
</div>

<style>
	.narrow {
		max-width: 760px;
	}
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		margin-bottom: var(--s-4);
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
	.row-item {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 8px 0;
	}
	.grow {
		flex: 1;
		min-width: 0;
	}
	.align-start {
		align-self: flex-start;
	}
</style>
