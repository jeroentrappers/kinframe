<script lang="ts">
	import type { Role } from '$lib/api/client';
	import { t } from '$lib/i18n/index.svelte';

	let { role }: { role: Role } = $props();
</script>

<span class="badge badge-{role}">{t(`role.${role}`)}</span>
