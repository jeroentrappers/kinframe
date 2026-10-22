<script lang="ts">
	import { t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import { toasts } from '$lib/toast.svelte';
	import { tvCast } from '$lib/tvCast.svelte';
	import Icon from './Icon.svelte';

	// While a TV session runs, a small remote stays on screen on every page.
	const cur = $derived(tvCast.current);

	async function stop() {
		await tvCast.stop();
		toasts.success(t('tv.stopped'));
	}
</script>

{#if tvCast.status === 'live'}
	<div class="mini" role="toolbar" aria-label={tvCast.device ? t('tv.castingTo', { device: tvCast.device }) : t('tv.castingNoName')} transition:softFly={{ y: 24 }}>
		<span class="where"><Icon name="cast" size={16} /><span class="label">{tvCast.device ?? t('tv.miniLabel')}</span></span>
		{#if cur?.caption}<span class="cap small">{cur.caption}</span>{/if}
		{#if tvCast.mode !== 'remote'}
			<button class="btn btn-ghost btn-icon btn-sm" onclick={() => tvCast.prev()} aria-label={t('tv.prev')}><Icon name="prev" size={16} /></button>
		{/if}
		<button class="btn btn-ghost btn-icon btn-sm" onclick={() => tvCast.toggle()} aria-label={tvCast.playing ? t('tv.pause') : t('tv.play')}>
			<Icon name={tvCast.playing ? 'pause' : 'play'} size={16} stroke={tvCast.playing ? 2 : 0} />
		</button>
		{#if tvCast.mode !== 'remote'}
			<button class="btn btn-ghost btn-icon btn-sm" onclick={() => tvCast.next()} aria-label={t('tv.next')}><Icon name="next" size={16} /></button>
		{/if}
		<button class="btn btn-ghost btn-icon btn-sm" onclick={stop} aria-label={t('tv.stop')}><Icon name="x" size={16} /></button>
	</div>
{/if}

<style>
	.mini {
		position: fixed;
		z-index: 40;
		inset-inline-end: 16px;
		bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom) + 12px);
		display: flex;
		align-items: center;
		gap: 4px;
		max-width: calc(100vw - 32px);
		padding: 6px 6px 6px 14px;
		border-radius: var(--r-full);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow-lg);
	}
	.where {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-weight: 650;
		color: var(--accent-text);
		margin-inline-end: 4px;
	}
	.label,
	.cap {
		max-width: 16ch;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.cap {
		color: var(--text-muted);
		margin-inline-end: 4px;
	}
	@media (min-width: 860px) {
		.mini {
			bottom: 24px;
		}
	}
	@media (max-width: 480px) {
		.cap {
			display: none;
		}
	}
</style>
