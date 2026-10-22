<script lang="ts">
	import { push } from '$lib/push.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from './Icon.svelte';

	async function enable() {
		try {
			const ok = await push.enable();
			if (ok) toasts.success(t('push.enabled'));
			else if (push.permission === 'denied') toasts.show(t('push.blocked'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
		push.dismissOffer();
	}
</script>

<!-- Asked right after a send, when the reason is obvious; never on page load. -->
<aside class="offer card" aria-labelledby="push-offer-title" transition:softFly>
	<span class="o-icon"><Icon name="bell" size={20} /></span>
	<div class="o-text">
		<h2 id="push-offer-title">{t('push.offerTitle')}</h2>
		<p class="muted small">{t('push.offerBody')}</p>
	</div>
	<div class="o-actions">
		<button class="btn btn-ghost btn-sm" onclick={() => push.dismissOffer()}>{t('push.notNow')}</button>
		<button class="btn btn-primary btn-sm" onclick={enable} disabled={push.busy}>
			{#if push.busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('push.turnOn')}
		</button>
	</div>
</aside>

<style>
	.offer {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		flex-wrap: wrap;
		padding: var(--s-4);
		margin-bottom: var(--s-4);
		border-color: color-mix(in srgb, var(--primary) 40%, var(--border));
		background: color-mix(in srgb, var(--primary-soft) 35%, var(--surface));
	}
	.o-icon {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--primary-soft);
		color: var(--primary-soft-text);
		flex: none;
	}
	.o-text {
		flex: 1;
		min-width: 200px;
	}
	h2 {
		font-size: 1.02rem;
	}
	.o-actions {
		display: flex;
		gap: 6px;
		margin-left: auto;
	}
</style>
