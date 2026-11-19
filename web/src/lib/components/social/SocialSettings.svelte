<script lang="ts">
	import { api, call, type Block, type EmailDigest, type Milestone, type ReminderLevel, type ReminderPreferences, type ReminderPreferencesUpdate } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { MILESTONE_EMOJI, MILESTONE_ORDER } from '$lib/engagement';
	import { formatDateTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { personName } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Icon from '../Icon.svelte';

	/** Settings → reminders, email digest, read receipts, blocked people, milestones (README §5.19–§5.20). */

	// ---- reminders
	let prefs = $state<ReminderPreferences | null>(null);
	let prefsError = $state<unknown>(null);
	$effect(() => {
		if (!discovery.engagement) return;
		call(api.GET('/v1/me/reminders'))
			.then((p) => (prefs = p))
			.catch((e) => (prefsError = e));
	});

	async function update(body: ReminderPreferencesUpdate, quiet = false) {
		try {
			prefs = await call(api.PATCH('/v1/me/reminders', { body }));
			if (!quiet) toasts.success(t('common.saved'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	const snoozed = $derived(!!prefs?.snoozed_until && Date.parse(prefs.snoozed_until) > Date.now());
	const LEVELS: ReminderLevel[] = ['normal', 'fewer', 'off'];
	const DIGESTS: EmailDigest[] = ['off', 'daily', 'weekly'];

	// ---- read receipts
	async function setReceipts(on: boolean) {
		try {
			auth.me = await call(api.PATCH('/v1/me', { body: { show_read_receipts: on } }));
			toasts.success(t('common.saved'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	// ---- blocked people
	let blocks = $state<Block[] | null>(null);
	$effect(() => {
		if (!discovery.chats) return;
		call(api.GET('/v1/blocks'))
			.then((b) => (blocks = b))
			.catch(() => (blocks = []));
	});
	async function unblock(b: Block) {
		try {
			await call(api.DELETE('/v1/blocks/{userId}', { params: { path: { userId: b.person.user_id } } }));
			blocks = (blocks ?? []).filter((x) => x.person.user_id !== b.person.user_id);
			toasts.success(t('chat.unblocked', { name: b.person.display_name }));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	// ---- milestones
	let milestones = $state<Milestone[]>([]);
	$effect(() => {
		if (!discovery.engagement) return;
		call(api.GET('/v1/me/milestones'))
			.then((m) => (milestones = MILESTONE_ORDER.map((k) => m.find((x) => x.key === k)).filter((x): x is Milestone => !!x)))
			.catch(() => (milestones = []));
	});
	async function showMilestone(m: Milestone, shown: boolean) {
		try {
			const next = await call(api.PATCH('/v1/me/milestones/{milestoneKey}', { params: { path: { milestoneKey: m.key } }, body: { shown } }));
			milestones = milestones.map((x) => (x.key === m.key ? next : x));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}
</script>

{#if discovery.engagement}
	<section class="card card-pad stack" aria-labelledby="rem-title" id="reminders">
		<div class="section-title"><h2 id="rem-title">{t('engagement.remindersTitle')}</h2></div>
		<p class="muted small intro">{t('engagement.remindersIntro')}</p>
		{#if prefsError}
			<p class="callout callout-error"><Icon name="alert" size={18} />{problemMessage(prefsError)}</p>
		{:else if !prefs}
			<div class="skeleton" style="height:120px"></div>
		{:else}
			{#if prefs.auto_reduced}
				<div class="callout callout-warn" role="status">
					<Icon name="info" size={18} />
					<div class="stack-sm">
						<span>{t('engagement.autoReduced')}</span>
						<button class="btn btn-sm" onclick={() => update({ auto_reduced_seen: true }, true)}>{t('common.dismiss')}</button>
					</div>
				</div>
			{/if}
			<fieldset class="levels">
				<legend class="label">{t('engagement.reminderLevel')}</legend>
				<div class="segmented" role="radiogroup" aria-label={t('engagement.reminderLevel')}>
					{#each LEVELS as l (l)}
						<label><input type="radio" name="level" value={l} checked={prefs.level === l} onchange={() => update({ level: l })} />{t(`engagement.level.${l}`)}</label>
					{/each}
				</div>
				<p class="hint">{t(`engagement.levelHint.${prefs.level}`)}</p>
			</fieldset>
			<div class="snooze row-wrap">
				{#if snoozed}
					<span class="small">{t('engagement.snoozedUntil', { when: formatDateTime(prefs.snoozed_until, i18n.tag) })}</span>
					<button class="btn btn-sm" onclick={() => update({ snooze_days: 0 })}>{t('engagement.endSnooze')}</button>
				{:else}
					<button class="btn btn-sm btn-soft" onclick={() => update({ snooze_days: 30 })}><Icon name="bellOff" size={16} />{t('engagement.pushSnooze')}</button>
				{/if}
			</div>
			<label class="switch">
				<input type="checkbox" checked={prefs.frame_requests} onchange={(e) => update({ frame_requests: e.currentTarget.checked })} />
				<span><span class="s-label">{t('engagement.frameRequests')}</span><span class="hint">{t('engagement.frameRequestsHint')}</span></span>
			</label>
			<label class="switch">
				<input type="checkbox" checked={prefs.weekly_summary} onchange={(e) => update({ weekly_summary: e.currentTarget.checked })} />
				<span><span class="s-label">{t('engagement.weeklySummary')}</span><span class="hint">{t('engagement.weeklySummaryHint')}</span></span>
			</label>
			{#if discovery.birthdays}
				<label class="switch">
					<input type="checkbox" checked={prefs.birthday_reminders} onchange={(e) => update({ birthday_reminders: e.currentTarget.checked })} />
					<span><span class="s-label">{t('engagement.birthdayReminders')}</span><span class="hint">{t('engagement.birthdayRemindersHint')}</span></span>
				</label>
			{/if}
			<div class="quiet">
				<span class="label">{t('engagement.quietHours')}</span>
				<div class="row-wrap">
					<label class="small" for="qh-start">{t('engagement.quietFrom')}</label>
					<input id="qh-start" class="input time" type="time" value={prefs.quiet_hours.start} onchange={(e) => update({ quiet_hours: { start: e.currentTarget.value, end: prefs!.quiet_hours.end } })} />
					<label class="small" for="qh-end">{t('engagement.quietTo')}</label>
					<input id="qh-end" class="input time" type="time" value={prefs.quiet_hours.end} onchange={(e) => update({ quiet_hours: { start: prefs!.quiet_hours.start, end: e.currentTarget.value } })} />
				</div>
				<p class="hint">{t('engagement.quietHoursHint')}</p>
			</div>
			{#if discovery.emailDigest && prefs.email_digest_available !== false}
				<div class="field">
					<label for="digest">{t('engagement.emailDigest')}</label>
					<select id="digest" class="select" value={prefs.email_digest} onchange={(e) => update({ email_digest: e.currentTarget.value as EmailDigest })}>
						{#each DIGESTS as d (d)}<option value={d}>{t(`engagement.digest.${d}`)}</option>{/each}
					</select>
					<p class="hint">{t('engagement.emailDigestHint')}</p>
				</div>
			{/if}
		{/if}
	</section>
{/if}

{#if discovery.chats}
	<section class="card card-pad stack" aria-labelledby="chat-privacy">
		<div class="section-title"><h2 id="chat-privacy">{t('chat.privacyTitle')}</h2></div>
		<label class="switch">
			<input type="checkbox" checked={auth.me?.show_read_receipts !== false} onchange={(e) => setReceipts(e.currentTarget.checked)} />
			<span><span class="s-label">{t('chat.readReceipts')}</span><span class="hint">{t('chat.readReceiptsHint')}</span></span>
		</label>
		<div class="stack-sm" id="blocked">
			<h3>{t('chat.blockedPeople')}</h3>
			{#if blocks === null}
				<div class="skeleton" style="height:40px"></div>
			{:else if blocks.length === 0}
				<p class="muted small">{t('chat.noBlocked')}</p>
			{:else}
				<ul class="blocks" role="list">
					{#each blocks as b (b.person.user_id)}
						<li>
							<Avatar name={personName(b.person, t('social.formerMember'))} url={b.person.avatar_url} size={32} />
							<span class="nm">{personName(b.person, t('social.formerMember'))}</span>
							<button class="btn btn-sm" onclick={() => unblock(b)}>{t('chat.unblock')}</button>
						</li>
					{/each}
				</ul>
			{/if}
			<p class="hint">{t('chat.blockHint')}</p>
		</div>
		{#if auth.me?.is_operator}
			<a class="btn btn-sm btn-ghost align-start" href="/settings/reports"><Icon name="flag" size={16} />{t('chat.operatorReports')}</a>
		{/if}
	</section>
{/if}

{#if discovery.engagement && milestones.length}
	<section class="card card-pad stack" aria-labelledby="ms-title" id="milestones">
		<div class="section-title"><h2 id="ms-title">{t('engagement.milestones')}</h2></div>
		<p class="muted small intro">{t('engagement.milestonesIntro')}</p>
		{#each milestones as m (m.key)}
			<label class="switch">
				<input type="checkbox" checked={m.shown} onchange={(e) => showMilestone(m, e.currentTarget.checked)} />
				<span><span class="s-label"><span aria-hidden="true">{MILESTONE_EMOJI[m.key]}</span> {t(`engagement.milestone.${m.key}`)}</span><span class="hint">{formatDateTime(m.achieved_at, i18n.tag)}</span></span>
			</label>
		{/each}
	</section>
{/if}

<style>
	.intro {
		margin: 0;
	}
	.levels {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.s-label {
		display: block;
		font-weight: 600;
	}
	.quiet {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.time {
		width: auto;
		min-width: 120px;
	}
	h3 {
		font-size: 0.95rem;
	}
	.blocks {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.blocks li {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.nm {
		flex: 1;
		font-weight: 600;
	}
	.align-start {
		align-self: flex-start;
	}
	.hint {
		margin: 0;
	}
</style>
