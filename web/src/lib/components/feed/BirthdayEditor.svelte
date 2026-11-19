<script lang="ts">
	import { untrack } from 'svelte';
	import { api, authFetch, call, type BirthdayPerson, type Feed, type FeedMember } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import {
		addMode,
		createBody,
		dateProblem,
		daysInMonth,
		editScope,
		emptyBirthdayForm,
		formOf,
		formYear,
		MIN_BIRTH_YEAR,
		NAME_MAX,
		todayIn,
		updateBody,
		yearAudience,
		type BirthdayForm
	} from '$lib/birthdays';
	import { discovery } from '$lib/discovery.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { squareAvatar } from '$lib/media';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	/**
	 * Add, suggest or edit a birthday person (birthdays.md §3 stories 1–3, 8, 9). `person` null = new; `mine` prefills
	 * the caller ("Add my birthday").
	 */
	let {
		open = $bindable(false),
		feed,
		person = null,
		mine = false,
		members = [],
		list = [],
		onsaved
	}: {
		open?: boolean;
		feed: Feed;
		person?: BirthdayPerson | null;
		mine?: boolean;
		members?: FeedMember[];
		list?: BirthdayPerson[];
		onsaved?: (p: BirthdayPerson, created: boolean) => void;
	} = $props();

	const uid = $props.id();
	const today = todayIn();
	let form = $state<BirthdayForm>(emptyBirthdayForm());
	let base = $state<BirthdayForm>(emptyBirthdayForm());
	let photoFile = $state<File | null>(null);
	let photoPreview = $state<string | null>(null);
	let current = $state<BirthdayPerson | null>(null);
	let busy = $state(false);
	let photoBusy = $state(false);
	let error = $state<string | null>(null);
	let fieldError = $state<{ day?: string; birth_year?: string }>({});
	let key = newIdempotencyKey();

	const viewer = $derived({
		role: feed.role,
		userId: auth.me?.id,
		postersCanAdd: feed.posters_can_add_birthdays === true
	});
	const scope = $derived(current ? editScope(current, viewer) : 'all');
	const mode = $derived(addMode(feed));
	const title = $derived(
		current ? t('birthdays.editTitle', { name: current.display_name }) : mode === 'add' ? t('birthdays.addTitle') : t('birthdays.suggestTitle')
	);

	$effect(() => {
		if (!open) return;
		untrack(() => {
			current = person;
			const f = person ? formOf(person) : emptyBirthdayForm();
			if (!person && mine && auth.me) {
				f.display_name = auth.me.display_name.slice(0, NAME_MAX);
				f.user_id = auth.me.id;
			}
			form = f;
			base = { ...f };
			setPhoto(null);
			error = null;
			fieldError = {};
			key = newIdempotencyKey();
		});
	});

	function setPhoto(file: File | null) {
		if (photoPreview) URL.revokeObjectURL(photoPreview);
		photoFile = file;
		photoPreview = file ? URL.createObjectURL(file) : null;
	}

	const months = $derived(
		Array.from({ length: 12 }, (_, i) => ({
			value: i + 1,
			label: new Intl.DateTimeFormat(i18n.tag, { month: 'long', timeZone: 'UTC' }).format(Date.UTC(2000, i, 1))
		}))
	);
	const year = $derived(formYear(form));
	// 29 February stays pickable; with a non-leap year it is flagged instead of silently moved.
	const maxDay = $derived(daysInMonth(form.month));
	$effect(() => {
		if (form.day > maxDay) form.day = maxDay;
	});

	/** Members who can be linked: not linked to another entry yet. */
	const linkable = $derived.by(() => {
		const taken = new Set(list.filter((p) => p.user_id && p.id !== current?.id && p.status !== 'rejected').map((p) => p.user_id));
		return members.filter((m) => !taken.has(m.user_id));
	});
	const linkedMember = $derived(members.find((m) => m.user_id === form.user_id) ?? null);

	function onLink(e: Event & { currentTarget: HTMLSelectElement }) {
		const id = e.currentTarget.value || null;
		form.user_id = id;
		const m = members.find((x) => x.user_id === id);
		if (m && !form.display_name.trim()) form.display_name = m.display_name.slice(0, NAME_MAX);
	}

	const problem = $derived(dateProblem(form.day, form.month, Number.isNaN(year) ? -1 : year, today));
	const dirty = $derived(current ? Object.keys(updateBody(form, base, scope === 'privacy' ? 'privacy' : 'all')).length > 0 : true);
	const canSubmit = $derived(form.display_name.trim().length > 0 && problem === null && !busy && (dirty || photoFile !== null));
	const shownPhoto = $derived(photoPreview ?? current?.photo_url ?? linkedMember?.avatar_url ?? null);
	const ownPhoto = $derived(photoPreview !== null || current?.has_own_photo === true);

	function explain(e: unknown) {
		fieldError = {};
		if (isCode(e, 'duplicate_birthday')) {
			const other = list.find((p) => p.id === e.detail);
			error = other ? t('birthdays.duplicate', { name: other.display_name }) : t('problem.duplicate_birthday');
		} else if (isCode(e, 'limit_reached')) {
			error = t('birthdays.limitReached', { max: discovery.limits.max_birthdays_per_feed });
		} else if (isCode(e, 'validation_failed') && e.errors?.length) {
			for (const fe of e.errors) {
				if (fe.field === 'day' || fe.field === 'month') fieldError.day = t('birthdays.invalidDay');
				if (fe.field === 'birth_year') fieldError.birth_year = t('birthdays.invalidYear', { year: today.y });
			}
			error = fieldError.day || fieldError.birth_year ? null : problemMessage(e);
		} else {
			error = problemMessage(e);
		}
	}

	async function uploadPhoto(p: BirthdayPerson, file: File): Promise<BirthdayPerson> {
		const blob = await squareAvatar(file);
		const res = await authFetch(`/v1/feeds/${encodeURIComponent(feed.id)}/birthdays/${encodeURIComponent(p.id)}/photo`, {
			method: 'PUT',
			headers: { 'Content-Type': 'image/jpeg' },
			body: blob
		});
		return (await res.json()) as BirthdayPerson;
	}

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		if (!canSubmit) return;
		busy = true;
		error = null;
		fieldError = {};
		try {
			let saved: BirthdayPerson;
			const created = !current;
			if (!current) {
				saved = await call(
					api.POST('/v1/feeds/{feedId}/birthdays', {
						params: { path: { feedId: feed.id }, header: { 'Idempotency-Key': key } },
						body: createBody(form)
					})
				);
				current = saved; // a failed photo upload below must not create it twice
			} else {
				const body = updateBody(form, base, scope === 'privacy' ? 'privacy' : 'all');
				saved = Object.keys(body).length
					? await call(
							api.PATCH('/v1/feeds/{feedId}/birthdays/{birthdayId}', { params: { path: { feedId: feed.id, birthdayId: current.id } }, body })
						)
					: current;
			}
			if (photoFile) {
				try {
					saved = await uploadPhoto(saved, photoFile);
				} catch (err) {
					toasts.error(problemMessage(err));
				}
			}
			open = false;
			onsaved?.(saved, created);
		} catch (err) {
			explain(err);
			key = newIdempotencyKey();
		} finally {
			busy = false;
		}
	}

	async function onPhoto(e: Event & { currentTarget: HTMLInputElement }) {
		const file = e.currentTarget.files?.[0];
		e.currentTarget.value = '';
		if (file) setPhoto(file);
	}

	async function removePhoto() {
		if (photoFile) return setPhoto(null);
		if (!current?.has_own_photo) return;
		photoBusy = true;
		try {
			const p = await call(
				api.DELETE('/v1/feeds/{feedId}/birthdays/{birthdayId}/photo', { params: { path: { feedId: feed.id, birthdayId: current.id } } })
			);
			current = p;
			onsaved?.(p, false);
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			photoBusy = false;
		}
	}

	let photoInput: HTMLInputElement | undefined = $state();
</script>

<Dialog bind:open {title} size="md">
	<form id="{uid}-form" class="stack" onsubmit={submit} novalidate>
		{#if scope === 'privacy'}<p class="callout small"><Icon name="shield" size={18} />{t('birthdays.privacyOnly')}</p>{/if}
		{#if !current && mode === 'suggest'}<p class="hint">{t('birthdays.suggestHint')}</p>{/if}

		<div class="photo-row">
			<Avatar name={form.display_name || '🎈'} url={shownPhoto} size={72} />
			<div class="stack-sm">
				<span class="label">{t('birthdays.photo')}</span>
				<input bind:this={photoInput} type="file" accept="image/jpeg,image/png,image/webp" hidden onchange={onPhoto} data-testid="birthday-photo" />
				<div class="row-wrap">
					<button type="button" class="btn btn-sm" onclick={() => photoInput?.click()}><Icon name="image" size={16} />{t('birthdays.choosePhoto')}</button>
					{#if ownPhoto}
						<button type="button" class="btn btn-ghost btn-sm" onclick={removePhoto} disabled={photoBusy}><Icon name="trash" size={16} />{t('birthdays.removePhoto')}</button>
					{/if}
				</div>
				{#if !ownPhoto && linkedMember}<span class="hint">{t('birthdays.photoFallback')}</span>{/if}
			</div>
		</div>

		<div class="field">
			<label for="{uid}-name">{t('birthdays.name')}</label>
			<input id="{uid}-name" class="input" required maxlength={NAME_MAX} bind:value={form.display_name} disabled={scope === 'privacy'} />
			<span class="hint">{t('birthdays.nameHint')}</span>
		</div>

		<fieldset class="date">
			<legend class="label">{t('birthdays.date')}</legend>
			<div class="date-row">
				<div class="field">
					<label for="{uid}-day" class="small muted">{t('birthdays.day')}</label>
					<select id="{uid}-day" class="select" bind:value={form.day} disabled={scope === 'privacy'} aria-invalid={Boolean(fieldError.day || problem === 'day')}>
						{#each Array.from({ length: maxDay }, (_, i) => i + 1) as d (d)}<option value={d}>{d}</option>{/each}
					</select>
				</div>
				<div class="field grow">
					<label for="{uid}-month" class="small muted">{t('birthdays.month')}</label>
					<select id="{uid}-month" class="select" bind:value={form.month} disabled={scope === 'privacy'}>
						{#each months as m (m.value)}<option value={m.value}>{m.label}</option>{/each}
					</select>
				</div>
				<div class="field">
					<label for="{uid}-year" class="small muted">{t('birthdays.year')} <span class="muted">({t('common.optional')})</span></label>
					<input
						id="{uid}-year"
						class="input num year"
						inputmode="numeric"
						maxlength="4"
						placeholder={String(today.y - 30)}
						bind:value={form.year}
						aria-invalid={Boolean(fieldError.birth_year || problem === 'birth_year')}
					/>
				</div>
			</div>
			{#if fieldError.day || problem === 'day'}<p class="error-text" role="alert">{fieldError.day ?? t('birthdays.invalidDay')}</p>{/if}
			{#if fieldError.birth_year || problem === 'birth_year'}<p class="error-text" role="alert">{fieldError.birth_year ?? t('birthdays.invalidYear', { year: today.y })}</p>{/if}
			{#if year !== null}
				<p class="hint">{yearAudience(form.show_age) === 'everyone' ? t('birthdays.yearHintShown') : t('birthdays.yearHint')}</p>
			{/if}
		</fieldset>

		<label class="switch">
			<input type="checkbox" bind:checked={form.show_age} disabled={year === null || Number.isNaN(year)} />
			<span><span class="s-label">{t('birthdays.showAge')}</span><span class="hint">{t('birthdays.showAgeHint')}</span></span>
		</label>

		<div class="field">
			<label for="{uid}-link">{t('birthdays.link')}</label>
			<select id="{uid}-link" class="select" value={form.user_id ?? ''} onchange={onLink}>
				<option value="">{t('birthdays.linkNone')}</option>
				{#each scope === 'privacy' ? members.filter((m) => m.user_id === base.user_id) : linkable as m (m.user_id)}
					<option value={m.user_id}>{m.display_name}</option>
				{/each}
			</select>
			<span class="hint">{t('birthdays.linkHint')}</span>
		</div>

		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" type="submit" form="{uid}-form" disabled={!canSubmit}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{current ? t('common.save') : mode === 'add' ? t('birthdays.add') : t('birthdays.suggest')}
		</button>
	{/snippet}
</Dialog>

<style>
	.photo-row {
		display: flex;
		align-items: center;
		gap: var(--s-4);
	}
	.date {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.date-row {
		display: flex;
		gap: var(--s-3);
		flex-wrap: wrap;
	}
	.date-row .grow {
		flex: 1;
		min-width: 9em;
	}
	.year {
		width: 6.5em;
	}
	.switch > span {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 600;
	}
</style>
