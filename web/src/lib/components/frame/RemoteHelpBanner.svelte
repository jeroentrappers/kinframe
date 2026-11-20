<script lang="ts">
	import { api, call, type Frame } from '$lib/api/client';
	import { canManage } from '$lib/frames.svelte';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { formatClock, isRunning, summaryFromSession, type RemoteHelpSummary } from '$lib/remoteHelp';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Icon from '$components/Icon.svelte';

	// Remote help on this frame (docs/spec/remote-help.md §2): every member sees it, owner and admins can stop it.
	let { frame }: { frame: Frame } = $props();

	let session = $state<RemoteHelpSummary | null>(null);
	let now = $state(new Date());
	let busy = $state(false);

	$effect(() => {
		session = frame.remote_help ?? null;
	});

	$effect(() => {
		const frameId = frame.id;
		return live.on((e) => {
			if (e.type === 'remote_help.updated' && e.frame_id === frameId) session = summaryFromSession(e.session);
		});
	});

	// The banner goes away by itself at the end time, even before the server's event arrives.
	$effect(() => {
		if (!session) return;
		const timer = setInterval(() => (now = new Date()), 30_000);
		return () => clearInterval(timer);
	});

	const running = $derived(isRunning(session, now) ? session : null);

	async function stop() {
		busy = true;
		try {
			await call(api.DELETE('/v1/frames/{frameId}/remote-help', { params: { path: { frameId: frame.id } } }));
			session = null;
			toasts.success(t('remoteHelp.stopped'));
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			busy = false;
		}
	}
</script>

{#if running}
	<div class="callout remote-help" role="status">
		<Icon name="shield" size={18} />
		<span class="text">{t('remoteHelp.banner', { time: formatClock(running.ends_at, i18n.tag), reason: running.reason })}</span>
		{#if canManage(frame)}
			<button class="btn btn-sm" onclick={stop} disabled={busy}><Icon name="x" size={16} />{t('remoteHelp.bannerStop')}</button>
		{/if}
	</div>
{/if}

<style>
	.remote-help {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		flex-wrap: wrap;
		margin-bottom: var(--s-5);
	}
	.text {
		flex: 1;
		min-width: 200px;
		overflow-wrap: anywhere;
	}
</style>
