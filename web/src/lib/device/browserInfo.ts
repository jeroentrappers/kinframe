// What a browser tells the server when it enrolls as a frame, and its heartbeat.
import type { DeviceEnroll, Heartbeat } from './client';

/** Frame renditions are made for this screen; very large TVs get at most this long edge. */
export const MAX_EDGE = 2560;

export function screenSize(s: { width: number; height: number }, dpr: number): DeviceEnroll['screen'] {
	const r = Math.min(2, dpr || 1);
	let w = Math.round(s.width * r);
	let h = Math.round(s.height * r);
	const scale = Math.min(1, MAX_EDGE / Math.max(w, h, 1));
	w = Math.max(320, Math.round(w * scale));
	h = Math.max(240, Math.round(h * scale));
	return { width: w, height: h, orientation: w >= h ? 'landscape' : 'portrait' };
}

/** "Chrome on Android TV": shown as the frame's model in the family's frame details. */
export function browserName(ua: string): string {
	const os = /CrKey/.test(ua)
		? 'Chromecast'
		: /Android/.test(ua)
			? /TV|AFT|BRAVIA/i.test(ua)
				? 'Android TV'
				: 'Android'
			: /Tizen/.test(ua)
				? 'Tizen'
				: /Web0S|webOS/.test(ua)
					? 'webOS'
					: /iPhone|iPad/.test(ua)
						? 'iOS'
						: /Mac OS/.test(ua)
							? 'macOS'
							: /Windows/.test(ua)
								? 'Windows'
								: /Linux/.test(ua)
									? 'Linux'
									: 'a device';
	const br = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\/|CrKey/.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
	return `${br} on ${os}`;
}

export function enrollInfo(locale: string): Omit<DeviceEnroll, 'kind'> {
	return {
		hardware: { brand: 'web', model: browserName(navigator.userAgent) },
		screen: screenSize(screen, window.devicePixelRatio),
		app: { version: 'web', version_code: 1 },
		locale,
		timezone: Intl.DateTimeFormat().resolvedOptions().timeZone
	};
}

export function heartbeat(startedAt: number, screenState: Heartbeat['screen_state']): Heartbeat {
	return {
		app_version: 'web',
		app_version_code: 1,
		uptime_s: Math.round((Date.now() - startedAt) / 1000),
		screen_state: screenState,
		local_time: new Date().toISOString(),
		timezone: Intl.DateTimeFormat().resolvedOptions().timeZone
	};
}
