<script lang="ts" module>
	import { api, call, type Circle } from '$lib/api/client';

	// Circles per family, loaded once per page (the caller's own circles are the ones they can post to).
	const cache = new Map<string, Promise<Circle[]>>();
	export function myCircles(feedId: string): Promise<Circle[]> {
		let p = cache.get(feedId);
		if (!p) {
			p = call(api.GET('/v1/feeds/{feedId}/circles', { params: { path: { feedId } } }))
				.then((list) => list.filter((c) => c.my_role != null))
				.catch(() => []);
			cache.set(feedId, p);
		}
		return p;
	}
	export function forgetCircles(feedId?: string) {
		if (feedId) cache.delete(feedId);
		else cache.clear();
	}
</script>

<script lang="ts">
	import { t } from '$lib/i18n/index.svelte';

	/** "Share with: whole family / The cousins" for one family (README §5.18, at most one circle per family). */
	let { feedId, feedName, value = $bindable<string | null>(null), disabled = false }: { feedId: string; feedName: string; value?: string | null; disabled?: boolean } = $props();

	let circles = $state<Circle[]>([]);
	$effect(() => {
		const id = feedId;
		myCircles(id).then((c) => id === feedId && (circles = c));
	});
	const uid = $props.id();
</script>

{#if circles.length > 0}
	<div class="audience">
		<label for="{uid}-aud" class="small muted">{t('social.shareWith', { family: feedName })}</label>
		<select id="{uid}-aud" class="select select-sm" bind:value {disabled} data-testid="audience">
			<option value={null}>{t('social.wholeFamily')}</option>
			{#each circles as c (c.id)}<option value={c.id}>{c.name}</option>{/each}
		</select>
	</div>
{/if}

<style>
	.audience {
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding-inline-start: 46px;
		margin-top: -2px;
		margin-bottom: var(--s-1);
	}
	.select-sm {
		min-height: 36px;
		font-size: 0.9rem;
	}
</style>
