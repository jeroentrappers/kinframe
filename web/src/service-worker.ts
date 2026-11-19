/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />

// Push only: no fetch handler and no caching, so the app always loads fresh from the server.
// Registered by $lib/push.svelte.ts when the user turns notifications on.
import { PUSH_ACTIONS, pushActions, pushTag, pushUrl, type PushPayload } from '$lib/pushPayload';
import { LABELS_URL, PUSH_LABELS_CACHE } from '$lib/pushLabels';

const sw = self as unknown as ServiceWorkerGlobalScope;

sw.addEventListener('install', () => void sw.skipWaiting());
sw.addEventListener('activate', (event) => event.waitUntil(sw.clients.claim()));

sw.addEventListener('push', (event) => {
	let data: PushPayload = {};
	try {
		data = (event.data?.json() as PushPayload) ?? {};
	} catch {
		data = { body: event.data?.text() };
	}
	event.waitUntil(
		(async () => {
			const actions = pushActions(data);
			// Button labels in the app's language: the app stores them (see $lib/pushLabels.ts); English otherwise.
			const labels = actions.length ? await actionLabels() : {};
			await sw.registration.showNotification(data.title || 'kinframe', {
				body: data.body ?? '',
				icon: '/favicon.svg',
				badge: '/favicon.svg',
				tag: pushTag(data),
				data: { url: pushUrl(data) },
				...(actions.length ? { actions: actions.map((a) => ({ action: a, title: labels[a] ?? DEFAULT_LABELS[a] })) } : {})
			} as NotificationOptions);
		})()
	);
});

const DEFAULT_LABELS: Record<(typeof PUSH_ACTIONS)[number], string> = { snooze_month: 'Snooze 1 month', fewer_reminders: 'Fewer reminders' };

async function actionLabels(): Promise<Record<string, string>> {
	try {
		const res = await (await caches.open(PUSH_LABELS_CACHE)).match(LABELS_URL);
		return res ? ((await res.json()) as Record<string, string>) : {};
	} catch {
		return {};
	}
}

sw.addEventListener('notificationclick', (event) => {
	event.notification.close();
	// "Snooze 1 month" / "Fewer reminders" (README §6.3): the app makes the call (the worker has no session).
	const action = (PUSH_ACTIONS as readonly string[]).includes(event.action) ? event.action : null;
	const path = action ? `/settings?push_action=${action}` : ((event.notification.data as { url?: string } | null)?.url ?? '/');
	event.waitUntil(
		(async () => {
			const windows = await sw.clients.matchAll({ type: 'window', includeUncontrolled: true });
			const open = windows.find((c) => new URL(c.url).origin === sw.location.origin);
			if (open) {
				await open.focus();
				// The app navigates itself (keeps its state); see $lib/push.svelte.ts.
				if (action) open.postMessage({ type: 'kinframe.pushAction', action });
				else open.postMessage({ type: 'kinframe.navigate', url: path });
				return;
			}
			await sw.clients.openWindow(path);
		})()
	);
});
