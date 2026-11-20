<script lang="ts">
	import { view } from '$lib/family.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';

	/** Asked once after joining someone else's family (simple-app.md §6, §8): normal or simple view on this device. */
	let { onchosen }: { onchosen?: (simple: boolean) => void } = $props();

	function choose(simple: boolean) {
		view.set(simple);
		onchosen?.(simple);
	}
</script>

<section class="stack question" aria-labelledby="vq-title">
	<h2 id="vq-title">{t('simple.questionTitle')}</h2>
	<div class="choices">
		<button class="card choice" onclick={() => choose(false)} data-testid="view-normal">
			<Icon name="home" size={28} />
			<span class="ct">{t('simple.normalView')}</span>
			<span class="muted small">{t('simple.normalBody')}</span>
		</button>
		<button class="card choice" onclick={() => choose(true)} data-testid="view-simple">
			<Icon name="heart" size={28} />
			<span class="ct">{t('simple.simpleView')}</span>
			<span class="muted small">{t('simple.simpleBody')}</span>
		</button>
	</div>
</section>

<style>
	.question {
		text-align: start;
	}
	.choices {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
		gap: var(--s-3);
	}
	.choice {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: var(--s-2);
		padding: var(--s-4);
		border: 1px solid var(--border);
		color: inherit;
		font: inherit;
		text-align: start;
		cursor: pointer;
		min-height: 64px;
	}
	.choice:hover {
		border-color: var(--primary);
		box-shadow: var(--shadow);
	}
	.ct {
		font-weight: 700;
		font-size: 1.15rem;
	}
</style>
