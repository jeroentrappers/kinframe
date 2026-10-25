// "Use this screen as a frame" from the TV dialog (signed in): one click enrolls this
// browser as a frame and claims it with its own code, the same steps as a frame on the wall.
import { api, apiUrl, call, type RedeemResult } from './api/client';
import { DeviceClient } from './device/client';
import { browserStorage, CredentialStore, credentialsKey } from './device/credentials';
import { enrollInfo } from './device/browserInfo';

/** Enrolls (or reuses) this browser's frame credential and claims it; returns the frame id. */
export async function makeThisBrowserAFrame(locale: string): Promise<string> {
	const base = apiUrl('');
	const client = new DeviceClient({ base, credentials: new CredentialStore(browserStorage(), credentialsKey(base)), locale: () => locale });
	if (!client.credentials) await client.enroll(enrollInfo(locale));
	const st = await client.state();
	if (st?.claimed && st.frame_id) return st.frame_id;
	const code = await client.code();
	if (!code) throw new Error('no frame code');
	return (await redeemCode(code.code)).frame.id;
}

/** Claims the frame showing `code` (a browser frame on another screen). */
export function redeemCode(code: string): Promise<RedeemResult> {
	return call(api.POST('/v1/frames/redeem', { body: { code } }));
}

/** Where a TV's browser goes to become a frame. */
export function browserFrameUrl(origin: string): string {
	return `${origin}/tv/frame`;
}
