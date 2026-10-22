/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />

// Push only: no fetch handler and no caching, so the app always loads fresh from the server.
// Registered by $lib/push.svelte.ts when the user turns notifications on.
import { pushTag, pushUrl, type PushPayload } from '$lib/pushPayload';

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
		sw.registration.showNotification(data.title || 'kinframe', {
			body: data.body ?? '',
			icon: '/favicon.svg',
			badge: '/favicon.svg',
			tag: pushTag(data),
			data: { url: pushUrl(data) }
		})
	);
});

sw.addEventListener('notificationclick', (event) => {
	event.notification.close();
	const path = (event.notification.data as { url?: string } | null)?.url ?? '/';
	event.waitUntil(
		(async () => {
			const windows = await sw.clients.matchAll({ type: 'window', includeUncontrolled: true });
			const open = windows.find((c) => new URL(c.url).origin === sw.location.origin);
			if (open) {
				await open.focus();
				// The app navigates itself (keeps its state); see $lib/push.svelte.ts.
				open.postMessage({ type: 'kinframe.navigate', url: path });
				return;
			}
			await sw.clients.openWindow(path);
		})()
	);
});
