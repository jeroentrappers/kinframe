<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { normalizeCode } from '$lib/format';

	// QR deep link https://<server>/f/<code>: hand over to the add-frame flow, which redeems it.
	$effect(() => {
		const code = normalizeCode(page.params.code ?? '');
		void goto(`/frames/add?code=${code}`, { replaceState: true });
	});
</script>
