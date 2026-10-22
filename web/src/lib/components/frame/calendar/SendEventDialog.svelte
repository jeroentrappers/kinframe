<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type EventInvitation } from '$lib/api/client';
	import { buildInvitation, defaultEventTimes, type EventFormError } from '$lib/calendar';
	import { problemMessage } from '$lib/problem';
	import { uploadFile, type UploadHandle } from '$lib/upload';
	import { i18n, t, type MessageKey } from '$lib/i18n/index.svelte';
	import Dialog from '../../Dialog.svelte';
	import Icon from '../../Icon.svelte';

	let {
		open = $bindable(false),
		frameId,
		frameName,
		senderName,
		onsent
	}: { open?: boolean; frameId: string; frameName: string; senderName: string; onsent: (inv: EventInvitation) => void } = $props();

	const id = $props.id();

	let title = $state('');
	let allDay = $state(false);
	let start = $state('');
	let end = $state('');
	let startDate = $state('');
	let endDate = $state('');
	let location = $state('');
	let notes = $state('');
	let photo = $state<File | null>(null);
	let photoUrl = $state<string | null>(null);
	let placement = $state<'top' | 'background'>('top');
	let tried = $state(false);
	let sending = $state(false);
	let progress = $state(0);
	let error = $state<string | null>(null);
	let upload: UploadHandle | null = null;

	$effect(() => {
		if (!open) return;
		untrack(() => {
			const d = defaultEventTimes();
			title = '';
			allDay = false;
			start = d.start;
			end = d.end;
			startDate = d.date;
			endDate = d.date;
			location = '';
			notes = '';
			setPhoto(null);
			placement = 'top';
			tried = false;
			sending = false;
			error = null;
		});
	});

	// Keep the end after the start when the start moves.
	let lastStart = '';
	$effect(() => {
		const s = start;
		untrack(() => {
			if (lastStart && s && end) {
				const delta = new Date(s).getTime() - new Date(lastStart).getTime();
				if (Number.isFinite(delta) && delta !== 0) {
					const e = new Date(new Date(end).getTime() + delta);
					const p = (n: number) => String(n).padStart(2, '0');
					end = `${e.getFullYear()}-${p(e.getMonth() + 1)}-${p(e.getDate())}T${p(e.getHours())}:${p(e.getMinutes())}`;
				}
			}
			lastStart = s;
		});
	});
	$effect(() => {
		if (endDate && startDate && endDate < startDate) endDate = startDate;
	});

	function setPhoto(f: File | null) {
		if (photoUrl) URL.revokeObjectURL(photoUrl);
		photo = f;
		photoUrl = f ? URL.createObjectURL(f) : null;
	}

	const built = $derived(buildInvitation({ title, allDay, start, end, startDate, endDate, location, notes }));
	const formError = $derived<EventFormError | null>('error' in built ? built.error : null);

	async function send() {
		tried = true;
		if (!('body' in built) || sending) return;
		sending = true;
		error = null;
		progress = 0;
		try {
			let photoId: string | undefined;
			if (photo) {
				upload = uploadFile(photo, { onProgress: (s, total) => (progress = total ? s / total : 0) });
				photoId = await upload.promise;
			}
			const inv = await call(
				api.POST('/v1/frames/{frameId}/events', {
					params: { path: { frameId }, header: { 'Idempotency-Key': crypto.randomUUID() } },
					body: { ...built.body, ...(photoId ? { photo_upload_id: photoId, photo_placement: placement } : {}) }
				})
			);
			onsent(inv);
			open = false;
		} catch (e) {
			if (!(e instanceof DOMException && e.name === 'AbortError')) error = problemMessage(e);
		} finally {
			sending = false;
			upload = null;
		}
	}

	$effect(() => {
		if (!open) upload?.abort();
	});

	// ---- preview text, as the frame shows it
	const whenText = $derived.by(() => {
		const fmtDay = (d: Date) => new Intl.DateTimeFormat(i18n.tag, { weekday: 'long', day: 'numeric', month: 'long' }).format(d);
		if (allDay) {
			if (!startDate) return '';
			const s = new Date(`${startDate}T12:00`);
			const e = endDate && endDate !== startDate ? new Date(`${endDate}T12:00`) : null;
			return e ? `${fmtDay(s)} – ${fmtDay(e)}` : `${fmtDay(s)} · ${t('cal.allDay')}`;
		}
		const s = new Date(start);
		const e = new Date(end);
		if (Number.isNaN(s.getTime())) return '';
		const tf = new Intl.DateTimeFormat(i18n.tag, { hour: '2-digit', minute: '2-digit' });
		return `${fmtDay(s)} · ${tf.format(s)}${Number.isNaN(e.getTime()) ? '' : ` – ${tf.format(e)}`}`;
	});
</script>

<Dialog bind:open title={t('cal.sendDialogTitle', { frame: frameName })} size="md">
	<div class="layout">
		<form
			id="{id}-form"
			class="stack"
			novalidate
			onsubmit={(e) => {
				e.preventDefault();
				void send();
			}}
		>
			<div class="field">
				<label for="{id}-title">{t('cal.evTitle')}</label>
				<input
					id="{id}-title"
					class="input"
					maxlength="200"
					bind:value={title}
					placeholder={t('cal.evTitlePlaceholder')}
					aria-invalid={tried && formError === 'title'}
					required
				/>
				{#if tried && formError === 'title'}<span class="hint err" role="alert">{t('cal.evErr.title')}</span>{/if}
			</div>
			<label class="switch"><input type="checkbox" bind:checked={allDay} /><span>{t('cal.evAllDay')}</span></label>
			<div class="two">
				{#if allDay}
					<div class="field">
						<label for="{id}-sd">{t('cal.evStartDate')}</label>
						<input id="{id}-sd" class="input" type="date" bind:value={startDate} aria-invalid={tried && formError === 'start'} />
					</div>
					<div class="field">
						<label for="{id}-ed">{t('cal.evEndDate')}</label>
						<input id="{id}-ed" class="input" type="date" min={startDate} bind:value={endDate} aria-invalid={tried && (formError === 'end' || formError === 'order')} />
					</div>
				{:else}
					<div class="field">
						<label for="{id}-s">{t('cal.evStart')}</label>
						<input id="{id}-s" class="input" type="datetime-local" bind:value={start} aria-invalid={tried && formError === 'start'} />
					</div>
					<div class="field">
						<label for="{id}-e">{t('cal.evEnd')}</label>
						<input id="{id}-e" class="input" type="datetime-local" min={start} bind:value={end} aria-invalid={tried && (formError === 'end' || formError === 'order')} />
					</div>
				{/if}
			</div>
			{#if tried && formError && formError !== 'title'}<span class="hint err" role="alert">{t(`cal.evErr.${formError}` as MessageKey)}</span>{/if}
			{#if !allDay}<span class="hint">{t('cal.evTimesLocal')}</span>{/if}
			<div class="field">
				<label for="{id}-loc">{t('cal.evLocation')} <span class="muted">({t('common.optional')})</span></label>
				<input id="{id}-loc" class="input" maxlength="200" bind:value={location} />
			</div>
			<div class="field">
				<label for="{id}-notes">{t('cal.evNotes')} <span class="muted">({t('common.optional')})</span></label>
				<textarea id="{id}-notes" class="textarea" rows="2" maxlength="2000" bind:value={notes}></textarea>
			</div>
			<div class="field">
				<span class="label">{t('cal.evPhoto')} <span class="muted">({t('common.optional')})</span></span>
				{#if photo}
					<div class="photo-row">
						<div class="segmented" role="radiogroup" aria-label={t('cal.evPlacement')}>
							<label><input type="radio" name="{id}-place" value="top" bind:group={placement} />{t('cal.evPlaceTop')}</label>
							<label><input type="radio" name="{id}-place" value="background" bind:group={placement} />{t('cal.evPlaceBackground')}</label>
						</div>
						<button type="button" class="btn btn-ghost btn-sm" onclick={() => setPhoto(null)}><Icon name="trash" size={16} />{t('cal.evRemovePhoto')}</button>
					</div>
				{:else}
					<label class="btn btn-sm add-photo">
						<Icon name="image" size={16} />{t('cal.evAddPhoto')}
						<input
							type="file"
							accept="image/jpeg,image/png,image/webp,image/heic,image/heif"
							class="visually-hidden"
							onchange={(e) => {
								const f = (e.currentTarget as HTMLInputElement).files?.[0];
								if (f) setPhoto(f);
							}}
						/>
					</label>
				{/if}
			</div>
			{#if error}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{error}</p>{/if}
		</form>

		<aside class="preview" aria-label={t('cal.evPreview')}>
			<span class="label">{t('cal.evPreview')}</span>
			<div class="card-ev" class:bg={photoUrl && placement === 'background'} style:--photo={photoUrl ? `url("${photoUrl}")` : 'none'}>
				{#if photoUrl && placement === 'top'}<img src={photoUrl} alt="" />{/if}
				<div class="card-body">
					<span class="from tiny"><Icon name="heart" size={12} />{t('cal.sentBy', { name: senderName })}</span>
					<strong class="card-title">{title.trim() || t('cal.evTitlePlaceholder')}</strong>
					{#if whenText}<span class="small">{whenText}</span>{/if}
					{#if location.trim()}<span class="small loc"><Icon name="mapPin" size={13} />{location.trim()}</span>{/if}
					<div class="card-actions" aria-hidden="true"><span class="fake no">✕</span><span class="fake yes">✓</span></div>
				</div>
			</div>
		</aside>
	</div>
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button type="submit" form="{id}-form" class="btn btn-primary" disabled={sending}>
			{#if sending}<span class="spinner" aria-hidden="true"></span>{#if photo && progress < 1}<span class="num">{Math.round(progress * 100)}%</span>{/if}{:else}<Icon name="send" size={16} />{/if}{t('cal.evSend')}
		</button>
	{/snippet}
</Dialog>

<style>
	.layout {
		display: grid;
		gap: var(--s-4);
	}
	@media (min-width: 620px) {
		.layout {
			grid-template-columns: minmax(0, 1fr) 220px;
			align-items: start;
		}
	}
	.two {
		display: grid;
		gap: var(--s-3);
		/* Two datetime fields only side by side when they fit; one per row next to the preview. */
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 210px), 1fr));
	}
	.two .input {
		min-width: 0;
		width: 100%;
	}
	.err {
		color: var(--danger);
	}
	.photo-row {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		align-items: center;
	}
	.add-photo {
		align-self: flex-start;
		cursor: pointer;
	}
	.callout {
		margin: 0;
		align-items: center;
	}
	.preview {
		display: flex;
		flex-direction: column;
		gap: 8px;
		position: sticky;
		top: 0;
	}
	/* The invitation card as the frame shows it (paper style). */
	.card-ev {
		border-radius: 16px;
		overflow: hidden;
		background: #fffaf2;
		color: #2a221d;
		box-shadow: 0 10px 30px -12px rgb(60 40 20 / 0.45);
		border: 1px solid #eadfcf;
	}
	.card-ev img {
		display: block;
		width: 100%;
		aspect-ratio: 16 / 10;
		object-fit: cover;
	}
	.card-ev.bg {
		background:
			linear-gradient(to bottom, rgb(0 0 0 / 0.05), rgb(0 0 0 / 0.65)),
			var(--photo) center / cover;
		color: #fff;
		min-height: 220px;
		display: flex;
		align-items: flex-end;
	}
	.card-body {
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding: 12px 14px 14px;
		width: 100%;
	}
	.from {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: #da6c92;
		font-weight: 650;
	}
	.bg .from {
		color: #ffd3e1;
	}
	.card-title {
		font-family: var(--font-display);
		font-size: 1.2rem;
		line-height: 1.2;
		overflow-wrap: anywhere;
	}
	.loc {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.card-actions {
		display: flex;
		gap: 8px;
		margin-top: 6px;
	}
	.fake {
		flex: 1;
		text-align: center;
		padding: 6px;
		border-radius: 999px;
		font-weight: 700;
		background: rgb(0 0 0 / 0.07);
	}
	.fake.yes {
		background: #4fb28a;
		color: #fff;
	}
	.bg .fake.no {
		background: rgb(255 255 255 / 0.25);
	}
</style>
