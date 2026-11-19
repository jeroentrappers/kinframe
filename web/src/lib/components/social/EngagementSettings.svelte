<script lang="ts">
	import { api, call, type Feed, type FeedEngagement, type FeedEngagementUpdate, type FeedPrompt, type FeedPromptSet, type PromptLibraryEntry } from '$lib/api/client';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	/** A family's streak, weekly prompts, reminders and recap (README §5.20): owner and admins. */
	let { feed }: { feed: Feed } = $props();

	let eng = $state<FeedEngagement | null>(null);
	let error = $state<unknown>(null);
	let goal = $state(3);
	let quiet = $state(7);
	let tz = $state('');
	const zones = (() => {
		try {
			return (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? [];
		} catch {
			return [];
		}
	})();

	$effect(() => {
		const id = feed.id;
		call(api.GET('/v1/feeds/{feedId}/engagement', { params: { path: { feedId: id } } }))
			.then((e) => {
				eng = e;
				goal = e.streak_goal;
				quiet = e.quiet_after_days;
				tz = e.timezone;
				if (e.prompts_enabled) void loadPrompts();
			})
			.catch((e) => (error = e));
	});

	async function save(body: FeedEngagementUpdate) {
		try {
			eng = await call(api.PATCH('/v1/feeds/{feedId}/engagement', { params: { path: { feedId: feed.id } }, body }));
			toasts.success(t('common.saved'));
			if (body.prompts_enabled) void loadPrompts();
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	// ---- prompts
	let prompts = $state<FeedPrompt[]>([]);
	let library = $state<PromptLibraryEntry[]>([]);
	async function loadPrompts() {
		try {
			prompts = await call(api.GET('/v1/feeds/{feedId}/prompts', { params: { path: { feedId: feed.id } } }));
			if (!library.length) library = await call(api.GET('/v1/prompts'));
		} catch {
			/* the list stays as it was */
		}
	}
	const upcoming = $derived(prompts.filter((p) => p.status === 'current' || p.status === 'planned').sort((a, b) => (a.week_of < b.week_of ? -1 : 1)));
	const past = $derived(prompts.filter((p) => p.status === 'past').slice(0, 6));

	let editing = $state<FeedPrompt | null>(null);
	let editOpen = $state(false);
	let choice = $state<'library' | 'custom'>('library');
	let libraryKey = $state('');
	let custom = $state('');

	function edit(p: FeedPrompt) {
		editing = p;
		choice = p.source === 'custom' ? 'custom' : 'library';
		libraryKey = p.library_key ?? library[0]?.key ?? '';
		custom = p.source === 'custom' ? p.title : '';
		editOpen = true;
	}

	async function setPrompt(p: FeedPrompt, body: FeedPromptSet) {
		try {
			const next = await call(api.PUT('/v1/feeds/{feedId}/prompts/{weekOf}', { params: { path: { feedId: feed.id, weekOf: p.week_of } }, body }));
			prompts = prompts.map((x) => (x.week_of === p.week_of ? next : x));
			editOpen = false;
			toasts.success(body.skip ? t('engagement.promptSkipped') : t('common.saved'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	async function reset(p: FeedPrompt) {
		try {
			await call(api.DELETE('/v1/feeds/{feedId}/prompts/{weekOf}', { params: { path: { feedId: feed.id, weekOf: p.week_of } } }));
			await loadPrompts();
			toasts.success(t('engagement.promptReset'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	const week = (d: string) => new Intl.DateTimeFormat(i18n.tag, { day: 'numeric', month: 'long' }).format(new Date(`${d}T12:00:00`));
</script>

<section class="card card-pad stack" aria-labelledby="eng-title">
	<h2 id="eng-title">{t('engagement.settingsTitle')}</h2>
	{#if error}
		<p class="callout callout-error"><Icon name="alert" size={18} />{problemMessage(error)}</p>
	{:else if !eng}
		<div class="skeleton" style="height:160px"></div>
	{:else}
		<label class="switch">
			<input type="checkbox" checked={eng.streak_enabled} onchange={(e) => save({ streak_enabled: e.currentTarget.checked })} />
			<span><span class="s-label">{t('engagement.streakEnabled')}</span><span class="hint">{t('engagement.streakEnabledHint')}</span></span>
		</label>
		{#if eng.streak_enabled}
			<div class="field inline">
				<label for="eng-goal">{t('engagement.streakGoal')}</label>
				<input id="eng-goal" class="input num narrow-input" type="number" min="1" max="20" bind:value={goal} onchange={() => goal >= 1 && goal <= 20 && goal !== eng?.streak_goal && save({ streak_goal: goal })} />
				<span class="hint">{tn('engagement.streakGoalHint', goal)}</span>
			</div>
		{/if}
		<div class="field inline">
			<label for="eng-quiet">{t('engagement.quietAfter')}</label>
			<input id="eng-quiet" class="input num narrow-input" type="number" min="3" max="30" bind:value={quiet} onchange={() => quiet >= 3 && quiet <= 30 && quiet !== eng?.quiet_after_days && save({ quiet_after_days: quiet })} />
			<span class="hint">{tn('engagement.quietAfterHint', quiet)}</span>
		</div>
		<label class="switch">
			<input type="checkbox" checked={eng.monthly_recap} onchange={(e) => save({ monthly_recap: e.currentTarget.checked })} />
			<span><span class="s-label">{t('engagement.monthlyRecap')}</span><span class="hint">{t('engagement.monthlyRecapHint')}</span></span>
		</label>
		<div class="field">
			<label for="eng-tz">{t('engagement.timezone')}</label>
			<input id="eng-tz" class="input" list="eng-tz-list" bind:value={tz} onchange={() => tz && tz !== eng?.timezone && save({ timezone: tz })} />
			<datalist id="eng-tz-list">{#each zones as z (z)}<option value={z}></option>{/each}</datalist>
			<span class="hint">{t('engagement.timezoneHint')}</span>
		</div>
		<label class="switch">
			<input type="checkbox" checked={eng.prompts_enabled} onchange={(e) => save({ prompts_enabled: e.currentTarget.checked })} />
			<span><span class="s-label">{t('engagement.promptsEnabled')}</span><span class="hint">{t('engagement.promptsEnabledHint')}</span></span>
		</label>
		{#if eng.prompts_enabled}
			<div class="stack-sm prompts">
				<h3>{t('engagement.promptsPlan')}</h3>
				{#if upcoming.length === 0}<p class="muted small">{t('engagement.noPrompts')}</p>{/if}
				<ul role="list">
					{#each upcoming as p (p.week_of)}
						<li>
							<span class="wk small muted">{p.status === 'current' ? t('engagement.thisWeek') : t('engagement.weekOf', { date: week(p.week_of) })}</span>
							<span class="ttl">{#if p.status === 'skipped' || !p.title}<em class="muted">{t('engagement.skipped')}</em>{:else}{p.title}{/if}</span>
							<span class="src tiny faint">{p.source === 'custom' ? t('engagement.sourceCustom') : t('engagement.sourceServer')}</span>
							<span class="acts">
								<button class="btn btn-sm btn-ghost" onclick={() => edit(p)}>{t('common.edit')}</button>
								{#if p.status !== 'skipped'}<button class="btn btn-sm btn-ghost" onclick={() => setPrompt(p, { skip: true })}>{t('engagement.skip')}</button>{/if}
								{#if p.set_by || p.status === 'skipped'}<button class="btn btn-sm btn-ghost" onclick={() => reset(p)}>{t('engagement.reset')}</button>{/if}
							</span>
						</li>
					{/each}
				</ul>
				{#if past.length}
					<h3>{t('engagement.pastPrompts')}</h3>
					<ul role="list">
						{#each past as p (p.week_of)}
							<li>
								<span class="wk small muted">{week(p.week_of)}</span>
								<span class="ttl">{p.title || t('engagement.skipped')}</span>
								{#if p.answer_count}<a class="small" href="/home?prompt={p.id}">{tn('engagement.answerCount', p.answer_count)}</a>{/if}
							</li>
						{/each}
					</ul>
				{/if}
			</div>
		{/if}
	{/if}
</section>

{#if editing}
	<Dialog bind:open={editOpen} title={editing.status === 'current' ? t('engagement.thisWeek') : t('engagement.weekOf', { date: week(editing.week_of) })}>
		<div class="stack">
			<div class="segmented" role="radiogroup" aria-label={t('engagement.promptSource')}>
				<label><input type="radio" value="library" bind:group={choice} />{t('engagement.fromList')}</label>
				<label><input type="radio" value="custom" bind:group={choice} />{t('engagement.ownPrompt')}</label>
			</div>
			{#if choice === 'library'}
				<select class="select" bind:value={libraryKey} aria-label={t('engagement.fromList')}>
					{#each library as l (l.key)}<option value={l.key}>{l.title}</option>{/each}
				</select>
			{:else}
				<input class="input" maxlength="80" bind:value={custom} placeholder={t('engagement.ownPromptPlaceholder')} aria-label={t('engagement.ownPrompt')} />
			{/if}
		</div>
		{#snippet footer()}
			<button class="btn" onclick={() => (editOpen = false)}>{t('common.cancel')}</button>
			<button
				class="btn btn-primary"
				disabled={choice === 'library' ? !libraryKey : !custom.trim()}
				onclick={() => editing && setPrompt(editing, choice === 'library' ? { library_key: libraryKey } : { title: custom.trim() })}>{t('common.save')}</button
			>
		{/snippet}
	</Dialog>
{/if}

<style>
	h3 {
		font-size: 0.95rem;
	}
	.s-label {
		display: block;
		font-weight: 600;
	}
	.narrow-input {
		width: 90px;
	}
	.prompts ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.prompts li {
		display: grid;
		grid-template-columns: 110px 1fr auto;
		grid-template-areas: 'wk ttl acts' 'wk src acts';
		align-items: center;
		gap: 0 var(--s-2);
		padding: 6px 0;
		border-bottom: 1px solid var(--border);
	}
	.wk {
		grid-area: wk;
	}
	.ttl {
		grid-area: ttl;
		font-weight: 600;
		min-width: 0;
	}
	.src {
		grid-area: src;
	}
	.acts {
		grid-area: acts;
		display: flex;
		flex-wrap: wrap;
		justify-content: flex-end;
	}
	@media (max-width: 520px) {
		.prompts li {
			grid-template-columns: 1fr;
			grid-template-areas: 'wk' 'ttl' 'src' 'acts';
		}
		.acts {
			justify-content: flex-start;
		}
	}
</style>
