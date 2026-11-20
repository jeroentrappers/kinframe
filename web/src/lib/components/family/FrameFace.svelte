<script lang="ts">
	import { lastPhoto, type FramePhoto } from '$lib/frameFace';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';

	/** A frame as a round face: its last photo, with a green ring while it is online. */
	let { frameId, online, size = 60 }: { frameId: string; online: boolean; size?: number } = $props();

	let photo = $state<FramePhoto | null>(null);
	$effect(() => {
		const id = frameId;
		photo = null;
		void lastPhoto(id).then((p) => id === frameId && (photo = p));
	});
</script>

<span class="face" class:online style="--size:{size}px" aria-hidden="true">
	<span class="inner">
		{#if photo}<Thumb itemId={photo.itemId} kind={photo.kind} radius="50%" />{:else}<Icon name="frame" size={Math.round(size / 2.6)} />{/if}
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
	.inner :global(*) {
		width: 100%;
		height: 100%;
	}
	.inner :global(svg) {
		width: auto;
		height: auto;
	}
</style>
