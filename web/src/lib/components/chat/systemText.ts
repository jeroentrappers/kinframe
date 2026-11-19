// System messages (README §5.19) in the viewer's language, rendered from their fields.
import type { SystemMessage } from '$lib/api/client';
import { monthDate } from '$lib/engagement';
import { i18n, t, tn } from '$lib/i18n/index.svelte';
import { personName } from '$lib/social';

const list = (xs: string[]) => new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(xs);

export function systemText(s: SystemMessage | null | undefined): string {
	if (!s) return '';
	const who = personName(s.person, t('social.formerMember'));
	switch (s.type) {
		case 'put_on_frame': {
			const places = [...(s.frame_names ?? []), ...(s.feed_names ?? [])];
			return tn('chat.sys.putOnFrame', s.item_count ?? 0, { name: who, places: list(places) });
		}
		case 'streak_milestone':
			return tn('chat.sys.streak', s.weeks ?? 0);
		case 'monthly_recap':
			return t('chat.sys.recap', { month: s.month ? new Intl.DateTimeFormat(i18n.tag, { month: 'long', year: 'numeric' }).format(monthDate(s.month)) : '' });
		case 'prompt':
			return t('chat.sys.prompt', { title: s.prompt_title ?? '' });
		case 'member_joined':
			return t('chat.sys.joined', { name: who });
		case 'member_left':
			return t('chat.sys.left', { name: who });
		default:
			return '';
	}
}
