// How a join request came (README §5.16, §5.17): frame code, invite link, or an outside channel's join link.
import type { JoinRequest } from './api/client';
import { t } from './i18n/index.svelte';

/**
 * "Invited by Anna", "Asked to join from the email link", "Asked to join from Telegram"; `fallback` for the
 * frame code (frames) or a plain invite link (feeds). The channel's `via_name` is shown on its own line.
 */
export function joinVia(r: Pick<JoinRequest, 'via' | 'invited_by_name'>, fallback: string): string {
	if (r.via === 'email') return t('people.viaEmail');
	if (r.via === 'telegram') return t('people.viaTelegram');
	if (r.invited_by_name) return t('people.viaInvite', { name: r.invited_by_name });
	return fallback;
}
