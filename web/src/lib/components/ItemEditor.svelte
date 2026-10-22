<script lang="ts">
	import type { DraftItem } from '$lib/compose.svelte';
	import { CAPTION_MAX, captionRemaining } from '$lib/format';
	import { t } from '$lib/i18n/index.svelte';
	import FocalPicker from './FocalPicker.svelte';
	import FramePreview from './FramePreview.svelte';
	import Icon from './Icon.svelte';

	let {
		item,
		screenAspect,
		total,
		onapplyall
	}: { item: DraftItem; screenAspect: number; total: number; onapplyall: (caption: string) => void } = $props();

	const aspect = $derived(item.width && item.height ? item.width / item.height : 4 / 3);
	const remaining = $derived(captionRemaining(item.caption));
</script>

<div class="editor">
	<div class="left">
		{#if item.previewable}
			<FocalPicker src={item.previewUrl} kind={item.kind} bind:focal={item.focal} alt={item.file.name} />
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
					focal={item.focal}
					fit={item.fit}
					{screenAspect}
					orientation="landscape"
					label={t('send.landscape')}
				/>
				<FramePreview
					src={item.previewUrl}
					kind={item.kind}
					imageAspect={aspect}
					focal={item.focal}
					fit={item.fit}
					{screenAspect}
					orientation="portrait"
					label={t('send.portrait')}
				/>
			</div>
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
