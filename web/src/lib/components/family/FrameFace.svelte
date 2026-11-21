<script lang="ts">
	import { framePhotoUrl } from '$lib/frameFace';
	import { frames } from '$lib/frames.svelte';
	import Icon from '../Icon.svelte';

	/**
	 * A frame as a round face: the photo it shows (your own frames, from `now_showing`), with a green ring while it is
	 * online. Frames that only show your family have no photo for you: the frame icon stays.
	 */
	let { frameId, online, size = 60 }: { frameId: string; online: boolean; size?: number } = $props();

	const photo = $derived(framePhotoUrl(frames.get(frameId)));
	let failed = $state(false);
	$effect(() => {
		void photo;
		failed = false;
	});
</script>

<span class="face" class:online style="--size:{size}px" aria-hidden="true">
	<span class="inner">
		{#if photo && !failed}<img src={photo} alt="" draggable="false" onerror={() => (failed = true)} />{:else}<Icon name="frame" size={Math.round(size / 2.6)} />{/if}
	</span>
</span>

<style>
	.face {
		display: inline-grid;
		place-items: center;
		width: var(--size);
		height: var(--size);
		border-radius: 50%;
		padding: 3px;
		background: var(--border);
		flex: none;
	}
	.face.online {
		background: var(--accent);
	}
	.inner {
		display: grid;
		place-items: center;
		width: 100%;
		height: 100%;
		border-radius: 50%;
		overflow: hidden;
		background: var(--surface-2);
		color: var(--text-muted);
		box-shadow: 0 0 0 2px var(--surface);
	}
	.inner img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
</style>
