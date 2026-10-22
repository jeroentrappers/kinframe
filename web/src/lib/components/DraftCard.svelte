<script lang="ts">
	import type { DraftItem } from '$lib/compose.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { CAPTION_MAX, captionRemaining, formatBytes, formatDuration } from '$lib/format';
	import { coverPosition } from '$lib/logic';
	import { isTrimmed, keptSeconds } from '$lib/parity';
	import { problemMessage } from '$lib/problem';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Icon from './Icon.svelte';

	let {
		item,
		index,
		locked,
		onedit,
		onremove
	}: { item: DraftItem; index: number; locked: boolean; onedit: () => void; onremove: () => void } = $props();

	const remaining = $derived(captionRemaining(item.caption));
	const trimmed = $derived(isTrimmed(item.trim, item.durationS));
	const kept = $derived(keptSeconds(item.trim, item.durationS));
	const aspect = $derived(item.width && item.height ? item.width / item.height : 1);
	const pos = $derived(coverPosition(item.focal, aspect, 1));
	const pct = $derived(item.file.size ? Math.round((item.uploaded / item.file.size) * 100) : 0);
	const issueText = $derived(
		item.issue === 'too_large'
			? t('send.issueTooLarge', { max: formatBytes(discovery.limits.max_upload_bytes, i18n.tag) })
			: item.issue === 'video_too_long'
				? t('send.issueTooLong', { max: formatDuration(discovery.limits.max_video_seconds) })
				: null
	);
</script>

<article class="draft" class:has-issue={item.issue} class:uploading={item.state === 'uploading'}>
	<div class="media">
		<button class="media-btn" onclick={onedit} disabled={locked} aria-label={t('send.adjustItem', { n: index + 1 })}>
			{#if !item.previewable}
				<span class="no-preview"><Icon name={item.kind === 'video' ? 'video' : 'image'} size={26} /><span>{item.file.name}</span></span>
			{:else if item.kind === 'video'}
				<video src="{item.previewUrl}#t=0.1" muted playsinline preload="metadata" style="object-position:{pos.x}% {pos.y}%"></video>
			{:else}
				<img src={item.previewUrl} alt="" style="object-position:{pos.x}% {pos.y}%" draggable="false" />
			{/if}
			{#if !locked}<span class="adjust"><Icon name="crop" size={15} />{t('send.adjust')}</span>{/if}
		</button>
		{#if item.kind === 'video'}
			<span class="duration num" class:trimmed title={trimmed ? t('trim.trimmedTo', { length: formatDuration(kept ?? 0) }) : undefined}>
				<Icon name={trimmed ? 'scissors' : 'play'} size={11} stroke={trimmed ? 2.4 : 0} />{kept != null ? formatDuration(kept) : '…'}
				{#if !item.includeAudio}<Icon name="volumeOff" size={12} stroke={2.2} />{/if}
			</span>
		{/if}
		{#if item.fit === 'fit'}<span class="fit-tag">{t('send.fitShort')}</span>{/if}
		{#if !locked}
			<button class="remove" onclick={onremove} aria-label={t('send.removeItem', { n: index + 1 })}>
				<Icon name="x" size={16} stroke={2.4} />
			</button>
		{/if}
		{#if item.state === 'uploading' || item.state === 'done'}
			<div class="upload-veil" class:done={item.state === 'done'}>
				{#if item.state === 'done'}
					<span class="done-check"><Icon name="check" size={20} stroke={2.6} /></span>
				{:else}
					<span class="pct num">{pct}%</span>
				{/if}
				<div class="progress"><span style="width:{pct}%"></span></div>
			</div>
		{/if}
	</div>
	{#if issueText}
		<p class="issue" role="alert"><Icon name="alert" size={15} />{issueText}</p>
	{:else if item.state === 'error'}
		<p class="issue" role="alert"><Icon name="alert" size={15} />{problemMessage(item.error)}</p>
	{/if}
	<div class="caption">
		<label class="visually-hidden" for="cap-{item.key}">{t('send.captionFor', { n: index + 1 })}</label>
		<textarea
			id="cap-{item.key}"
			class="textarea"
			rows="2"
			placeholder={t('send.captionPlaceholder')}
			bind:value={item.caption}
			disabled={locked}
			aria-invalid={remaining < 0}
			aria-describedby="cnt-{item.key}"
		></textarea>
		<span id="cnt-{item.key}" class="counter num" class:warn={remaining < 30} class:over={remaining < 0} aria-live="polite">
			{#if remaining < 60}{remaining}{/if}
			<span class="visually-hidden">{t('send.charsLeft', { n: remaining, max: CAPTION_MAX })}</span>
		</span>
	</div>
</article>

<style>
	.draft {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 8px;
		border-radius: var(--r-lg);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow-sm);
	}
	.draft.has-issue {
		border-color: var(--danger);
		box-shadow: 0 0 0 3px var(--danger-soft);
	}
	.media {
		position: relative;
		aspect-ratio: 1;
		border-radius: var(--r);
		overflow: hidden;
		background: var(--surface-2);
	}
	.media-btn {
		all: unset;
		position: absolute;
		inset: 0;
		cursor: pointer;
		display: block;
	}
	.media-btn:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: -3px;
		border-radius: var(--r);
	}
	.media-btn:disabled {
		cursor: default;
	}
	img,
	video {
		width: 100%;
		height: 100%;
		object-fit: cover;
		transition: transform 300ms var(--ease);
	}
	.media-btn:hover:not(:disabled) img,
	.media-btn:hover:not(:disabled) video {
		transform: scale(1.03);
	}
	.no-preview {
		position: absolute;
		inset: 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 6px;
		padding: 10px;
		color: var(--text-muted);
		font-size: 0.75rem;
		text-align: center;
		overflow-wrap: anywhere;
	}
	.adjust {
		position: absolute;
		left: 8px;
		bottom: 8px;
		display: inline-flex;
		align-items: center;
		gap: 5px;
		padding: 4px 10px;
		border-radius: var(--r-full);
		background: rgb(20 14 10 / 0.6);
		color: #fff;
		font-size: 0.76rem;
		font-weight: 600;
		opacity: 0;
		transition: opacity var(--dur) var(--ease);
	}
	.media-btn:hover .adjust,
	.media-btn:focus-visible .adjust {
		opacity: 1;
	}
	@media (hover: none) {
		.adjust {
			opacity: 1;
		}
	}
	.duration,
	.fit-tag {
		position: absolute;
		top: 8px;
		left: 8px;
		display: inline-flex;
		align-items: center;
		gap: 4px;
		padding: 2px 8px;
		border-radius: var(--r-full);
		background: rgb(20 14 10 / 0.65);
		color: #fff;
		font-size: 0.74rem;
		font-weight: 700;
		pointer-events: none;
	}
	.duration:not(.trimmed) :global(svg:first-child) {
		fill: currentColor;
	}
	.duration.trimmed {
		background: color-mix(in srgb, var(--primary) 85%, black);
	}
	.fit-tag {
		left: auto;
		right: 44px;
	}
	.remove {
		position: absolute;
		top: 6px;
		right: 6px;
		width: 32px;
		height: 32px;
		border-radius: 50%;
		border: 0;
		display: grid;
		place-items: center;
		background: rgb(255 255 255 / 0.92);
		color: #2a221d;
		cursor: pointer;
		box-shadow: var(--shadow-sm);
	}
	.remove:hover {
		background: #fff;
		color: var(--danger);
	}
	.upload-veil {
		position: absolute;
		inset: 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 10px;
		padding: 0 14%;
		background: rgb(20 14 10 / 0.45);
		color: #fff;
		transition: background 300ms var(--ease);
	}
	.upload-veil.done {
		background: rgb(20 14 10 / 0.18);
	}
	.upload-veil .progress {
		width: 100%;
		background: rgb(255 255 255 / 0.3);
	}
	.upload-veil .progress span {
		background: #fff;
	}
	.upload-veil.done .progress {
		opacity: 0;
	}
	.pct {
		font-weight: 700;
		font-size: 1.05rem;
	}
	.done-check {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: #3c6a49;
		color: #fff;
		animation: pop 260ms var(--ease);
	}
	@keyframes pop {
		from {
			transform: scale(0.6);
			opacity: 0;
		}
	}
	.issue {
		display: flex;
		align-items: flex-start;
		gap: 6px;
		font-size: 0.8rem;
		color: var(--danger);
		font-weight: 600;
		padding: 0 4px;
	}
	.caption {
		position: relative;
	}
	.caption .textarea {
		min-height: 0;
		font-size: 0.9rem;
		padding: 8px 10px;
		padding-right: 36px;
		border-radius: 10px;
		resize: none;
		border-color: var(--border);
	}
	.counter {
		position: absolute;
		right: 10px;
		bottom: 8px;
		font-size: 0.74rem;
		color: var(--text-faint);
		font-weight: 600;
	}
	.counter.warn {
		color: var(--warn);
	}
	.counter.over {
		color: var(--danger);
	}
</style>
