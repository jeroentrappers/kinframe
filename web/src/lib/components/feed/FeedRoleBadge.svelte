<script lang="ts">
	import type { FeedRole } from '$lib/api/client';
	import { t } from '$lib/i18n/index.svelte';

	let { role }: { role: FeedRole } = $props();
</script>

<span class="badge badge-{role === 'poster' ? 'member' : role}">{t(`feedRole.${role}`)}</span>
