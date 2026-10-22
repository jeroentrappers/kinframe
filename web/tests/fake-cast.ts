// A stand-in for the Google Cast Web Sender SDK (cast_sender.js?loadCastFramework=1) so tests can
// drive the sender without a Chromecast. It records every LOAD in window.__castLoads.
import type { Page } from '@playwright/test';

const SCRIPT = `(() => {
	const loads = (window.__castLoads = []);
	class MediaInfo { constructor(id, type) { this.contentId = id; this.contentType = type; } }
	class LoadRequest { constructor(info) { this.media = info; this.autoplay = true; } }
	const media = {
		MediaInfo, LoadRequest,
		PhotoMediaMetadata: class { constructor() { this.metadataType = 4; } },
		GenericMediaMetadata: class { constructor() { this.metadataType = 0; } },
		StreamType: { BUFFERED: 'BUFFERED' },
		DEFAULT_MEDIA_RECEIVER_APP_ID: 'CC1AD845',
		PlayerState: { IDLE: 'IDLE' }, IdleReason: { FINISHED: 'FINISHED' },
		PlayRequest: class {}, PauseRequest: class {}
	};
	window.chrome = window.chrome || {};
	window.chrome.cast = { media, Image: class { constructor(u) { this.url = u; } }, AutoJoinPolicy: { ORIGIN_SCOPED: 'origin_scoped' } };
	let listeners = [];
	let current = null;
	const session = {
		getCastDevice: () => ({ friendlyName: 'Living room TV' }),
		loadMedia: async (req) => { loads.push({ url: req.media.contentId, type: req.media.contentType, title: req.media.metadata && req.media.metadata.title, metadataType: req.media.metadata && req.media.metadata.metadataType }); },
		getMediaSession: () => null
	};
	const ctx = {
		setOptions(o) { window.__castOptions = o; },
		requestSession: async () => { if (window.__castCancel) throw 'cancel'; current = session; },
		getCurrentSession: () => current,
		addEventListener: (t, f) => listeners.push(f),
		removeEventListener: (t, f) => { listeners = listeners.filter((x) => x !== f); },
		endCurrentSession() { current = null; window.__castEnded = true; listeners.forEach((f) => f({ sessionState: 'SESSION_ENDED' })); }
	};
	window.cast = { framework: {
		CastContext: { getInstance: () => ctx },
		SessionState: { SESSION_ENDED: 'SESSION_ENDED' },
		CastContextEventType: { SESSION_STATE_CHANGED: 'sessionstatechanged' }
	} };
	setTimeout(() => window.__onGCastApiAvailable && window.__onGCastApiAvailable(true), 0);
})();`;

export async function fakeCast(page: Page) {
	await page.route(/www\.gstatic\.com\/cv\/js\/sender\/v1\/cast_sender\.js/, (r) => r.fulfill({ contentType: 'text/javascript', body: SCRIPT }));
}

export type CastLoad = { url: string; type: string; title: string; metadataType: number };

export function castLoads(page: Page): Promise<CastLoad[]> {
	return page.evaluate(() => (window as unknown as { __castLoads?: CastLoad[] }).__castLoads ?? []);
}
