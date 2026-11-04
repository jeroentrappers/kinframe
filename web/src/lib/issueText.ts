import { discovery } from './discovery.svelte';
import { formatBytes, formatDuration } from './format';
import { i18n, t } from './i18n/index.svelte';
import type { ItemIssue } from './logic';

/** Why a draft item can't be sent, in the reader's language (shown on the item and when a send stops). */
export function issueText(issue: ItemIssue): string {
	switch (issue) {
		case 'too_large':
			return t('send.issueTooLarge', { max: formatBytes(discovery.limits.max_upload_bytes, i18n.tag) });
		case 'video_too_long':
			return t('send.issueTooLong', { max: formatDuration(discovery.limits.max_video_seconds) });
		case 'photo_unpreparable':
			return t('send.issuePhotoPrep');
		case 'video_unpreparable':
			return t('send.issueVideoPrep');
		case 'clip_unpreparable':
			return t('send.issueClipPrep');
		case 'unsupported':
			return t('problem.unsupported_media');
	}
}
