<script lang="ts">
	import type { DraftItem } from '$lib/compose.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { CAPTION_MAX, captionRemaining } from '$lib/format';
	import { framingOrNull, recentre, sendFocal, type Framing, type FramingView, type Orientation } from '$lib/framing';
	import { t } from '$lib/i18n/index.svelte';
	import FocalPicker from './FocalPicker.svelte';
	import FramePreview from './FramePreview.svelte';
	import FramingEditor from './FramingEditor.svelte';
	import Icon from './Icon.svelte';
	import TrimEditor from './TrimEditor.svelte';

	let {
		item,
		screenAspect,
		total,
		onapplyall
	}: { item: DraftItem; screenAspect: number; total: number; onapplyall: (caption: string) => void } = $props();

	const aspect = $derived(item.width && item.height ? item.width / item.height : 4 / 3);
	const remaining = $derived(captionRemaining(item.caption));

	// Framing (docs/spec/framing.md §4): only when the server stores it and the photo fills the screen.
	const framingOn = $derived(discovery.framing && item.kind === 'photo' && item.previewable && item.fit === 'fill');
	let framingOpen = $state(false);
	let framingFor = $state<Orientation>('landscape');
	const orientationLabel = (o: Orientation) => (o === 'landscape' ? t('send.landscape') : t('send.portrait'));

	function editFraming(o: Orientation) {
		framingFor = o;
		framingOpen = true;
	}

	/** The focal point the send carries (the framing's centre, §4), which screens without a view use (§3.1). */
	const shownFocal = $derived(sendFocal(item.framing, item.focal));
	/** Where Reset puts an orientation: the focal point once its own view is gone. */
	function focalWithout(o: Orientation) {
		const rest: Framing = { ...(item.framing ?? {}) };
		delete rest[o];
		return sendFocal(rest, item.focal);
	}

	/** Done in the editor: keep this orientation's view, or remove it (Reset). Only changed orientations are sent. */
	function saveFraming(o: Orientation, view: FramingView | null) {
		const next = { ...(item.framing ?? {}) };
		if (view) next[o] = { x: view.x, y: view.y, zoom: view.zoom };
		else delete next[o];
		item.framing = framingOrNull(next);
	}
</script>

<div class="editor">
	{#if item.kind === 'video' && item.previewable && item.durationS}
		<div class="full"><TrimEditor {item} /></div>
	{/if}
	<div class="left">
		{#if item.previewable}
			<FocalPicker
				src={item.previewUrl}
				kind={item.kind}
				bind:focal={item.focal}
				alt={item.file.name}
				onchange={(f) => (item.framing = recentre(item.framing, f, aspect, screenAspect))}
			/>
		{:else}
			<div class="callout"><Icon name="info" />{t('send.noPreview')}</div>
		{/if}
	</div>
	<div class="right stack">
		{#if item.previewable}
			<div class="previews" aria-label={t('send.previewsLabel')}>
				<FramePreview
					src={item.previewUrl}
					kind={item.kind}
					imageAspect={aspect}
					focal={shownFocal}
					fit={item.fit}
					{screenAspect}
					orientation="landscape"
					label={t('send.landscape')}
					view={framingOn ? (item.framing?.landscape ?? null) : null}
					onedit={framingOn ? () => editFraming('landscape') : undefined}
					editLabel={t('framing.editLabel', { orientation: t('send.landscape') })}
				/>
				<FramePreview
					src={item.previewUrl}
					kind={item.kind}
					imageAspect={aspect}
					focal={shownFocal}
					fit={item.fit}
					{screenAspect}
					orientation="portrait"
					label={t('send.portrait')}
					view={framingOn ? (item.framing?.portrait ?? null) : null}
					onedit={framingOn ? () => editFraming('portrait') : undefined}
					editLabel={t('framing.editLabel', { orientation: t('send.portrait') })}
				/>
			</div>
			{#if framingOn}<p class="hint">{t('framing.tapHint')}</p>{/if}
		{/if}

		<fieldset class="fit">
			<legend class="label">{t('send.fitLegend')}</legend>
			<div class="segmented">
				<label><input type="radio" name="fit-{item.key}" value="fill" bind:group={item.fit} /><Icon name="maximize" size={16} />{t('send.fill')}</label>
				<label><input type="radio" name="fit-{item.key}" value="fit" bind:group={item.fit} /><Icon name="image" size={16} />{t('send.fit')}</label>
			</div>
			<p class="hint">{item.fit === 'fill' ? t('send.fillHint') : t('send.fitHint')}</p>
		</fieldset>

		<div class="field">
			<label for="edit-cap">{t('send.caption')}</label>
			<textarea
				id="edit-cap"
				class="textarea"
				rows="3"
				bind:value={item.caption}
				placeholder={t('send.captionPlaceholder')}
				aria-invalid={remaining < 0}
				aria-describedby="edit-cap-count"
			></textarea>
			<div class="row cap-row">
				<span id="edit-cap-count" class="hint num" class:over={remaining < 0}>{t('send.charsLeft', { n: remaining, max: CAPTION_MAX })}</span>
				<span class="spacer"></span>
				{#if total > 1}
					<button class="btn btn-ghost btn-sm" type="button" onclick={() => onapplyall(item.caption)} disabled={remaining < 0}>
						<Icon name="copy" size={16} />{t('send.applyAll', { n: total })}
					</button>
				{/if}
			</div>
		</div>
	</div>
</div>

{#if framingOn}
	<FramingEditor
		bind:open={framingOpen}
		src={item.previewUrl}
		orientation={framingFor}
		label={orientationLabel(framingFor)}
		{screenAspect}
		imageAspect={aspect}
		width={item.width}
		height={item.height}
		current={item.framing?.[framingFor] ?? null}
		focal={focalWithout(framingFor)}
		onsave={(v) => saveFraming(framingFor, v)}
	/>
{/if}

<style>
	.editor {
		display: grid;
		gap: var(--s-5);
	}
	@media (min-width: 640px) {
		.editor {
			grid-template-columns: 1.1fr 1fr;
			align-items: start;
		}
	}
	.full {
		grid-column: 1 / -1;
		min-width: 0;
		padding-bottom: var(--s-4);
		border-bottom: 1px solid var(--border);
	}
	.editor > div {
		min-width: 0;
	}
	.previews {
		display: flex;
		gap: var(--s-3);
		align-items: flex-end;
	}
	.previews :global(.landscape) {
		flex: 1.6;
	}
	.previews :global(.portrait) {
		flex: 0.6;
		width: auto;
	}
	fieldset {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.cap-row {
		min-height: 36px;
	}
	.over {
		color: var(--danger);
		font-weight: 600;
	}
</style>
