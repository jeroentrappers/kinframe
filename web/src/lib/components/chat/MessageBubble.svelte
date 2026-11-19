<script lang="ts">
	import type { Message, PersonRef, ReactionSummary } from '$lib/api/client';
	import { isLocal } from '$lib/chat';
	import { formatDateTime } from '$lib/format';
	import { REACTION_EMOJI } from '$lib/logic';
	import { personName } from '$lib/social';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Icon from '../Icon.svelte';
	import MoreMenu, { type MenuItem } from '../social/MoreMenu.svelte';
	import ReactionRow from '../social/ReactionRow.svelte';
	import RichText from '../social/RichText.svelte';
	import MessageMedia from './MessageMedia.svelte';
	import RecapCard from './RecapCard.svelte';
	import { systemText } from './systemText';
	import VoicePlayer from './VoicePlayer.svelte';

	let {
		message: m,
		mine,
		first,
		last,
		showAuthor,
		seen = [],
		receipts = false,
		menu,
		failed = false,
		onreactions,
		onquote,
		onputonframe,
		onretry
	}: {
		message: Message;
		mine: boolean;
		first: boolean;
		last: boolean;
		showAuthor: boolean;
		seen?: PersonRef[];
		/** "Seen" under the caller's newest message that someone read (direct chats and small groups). */
		receipts?: boolean;
		menu: MenuItem[];
		failed?: boolean;
		onreactions: (s: ReactionSummary) => void;
		onquote: (id: string) => void;
		onputonframe: () => void;
		onretry?: () => void;
	} = $props();

	const author = $derived(personName(m.author, t('social.formerMember')));
	const time = $derived(new Intl.DateTimeFormat(i18n.tag, { hour: '2-digit', minute: '2-digit' }).format(new Date(m.created_at)));
	const local = $derived(isLocal(m));
	const seenNames = $derived(new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(seen.map((p) => personName(p, t('social.formerMember')).split(' ')[0]!)));
	const hasMedia = $derived(m.items.length > 0 && !m.deleted);
	const readyMedia = $derived(m.items.some((i) => i.status === 'ready'));
</script>

{#if m.kind === 'system'}
	<div class="system" id="m-{m.id}">
		{#if m.system?.type === 'monthly_recap' && m.system.recap}
			<RecapCard recap={m.system.recap} />
		{:else}
			<p class="sys-text">
				{#if m.system?.type === 'streak_milestone'}<span aria-hidden="true">🔥</span>{:else if m.system?.type === 'prompt'}<span aria-hidden="true">💡</span>{:else if m.system?.type === 'put_on_frame'}<Icon name="frame" size={14} />{/if}
				{systemText(m.system)}
			</p>
		{/if}
		{#if m.reactions.total > 0 || (m.system?.type !== 'member_joined' && m.system?.type !== 'member_left')}
			<div class="sys-react"><ReactionRow summary={m.reactions} kind="message" targetId={m.id} size="sm" onchange={onreactions} /></div>
		{/if}
	</div>
{:else}
	<div class="msg" class:mine class:first class:last id="m-{m.id}">
		{#if !mine}
			<span class="av">{#if last}<Avatar name={author} url={m.author?.former_member ? null : m.author?.avatar_url} size={30} />{/if}</span>
		{/if}
		<div class="col">
			{#if showAuthor && first && !mine}<span class="author">{author}</span>{/if}
			<div class="bubble-row">
				<div class="bubble" class:media-only={hasMedia && !m.body && !m.reply_to && !m.voice} class:tomb={m.deleted} class:pending={local} class:failed>
					{#if m.deleted}
						<p class="tomb-text"><Icon name="ban" size={14} />{m.removed_by_admin ? t('chat.messageRemoved') : t('chat.messageDeleted')}</p>
					{:else}
						{#if m.reply_to}
							<button class="quote" onclick={() => onquote(m.reply_to!.id)}>
								<strong>{personName(m.reply_to.author, t('social.formerMember'))}</strong>
								<span>
									{#if m.reply_to.deleted}{t('chat.quoteUnavailable')}{:else if m.reply_to.voice}🎤 {t('chat.voiceMessage')}{:else if m.reply_to.excerpt}{m.reply_to.excerpt}{:else if m.reply_to.media_kind}{m.reply_to.media_kind === 'video' ? `🎬 ${t('chat.video')}` : `📷 ${t('chat.photo')}`}{/if}
								</span>
							</button>
						{/if}
						{#if hasMedia}<MessageMedia items={m.items} />{/if}
						{#if m.voice}<VoicePlayer clip={m.voice} {mine} />{/if}
						{#if m.shared_post}
							{#if m.shared_post.available}
								<a class="shared" href="/posts/{m.shared_post.post_id}">
									{#if m.shared_post.thumb_urls?.length}
										<span class="shared-thumbs">{#each m.shared_post.thumb_urls.slice(0, 3) as u (u)}<img src={u} alt="" />{/each}</span>
									{/if}
									<span class="shared-text">
										<strong>{personName(m.shared_post.poster, t('social.formerMember'))}</strong>
										<span class="small">{m.shared_post.feed_name ?? (m.shared_post.frame_name ? t('social.toFrame', { frame: m.shared_post.frame_name }) : '')}</span>
										{#if m.shared_post.caption}<span class="small muted">{m.shared_post.caption}</span>{/if}
									</span>
								</a>
							{:else}
								<p class="shared unavailable small"><Icon name="eyeOff" size={14} />{t('chat.postUnavailable')}</p>
							{/if}
						{/if}
						{#if m.body}<p class="text"><RichText body={m.body} mentions={m.mentions} /></p>{/if}
						{#if m.link_preview && m.link_preview.status !== 'failed'}
							{#if m.link_preview.status === 'pending'}
								<span class="lp-pending skeleton" aria-hidden="true"></span>
							{:else}
								<a class="lp" href={m.link_preview.url} target="_blank" rel="noopener noreferrer nofollow ugc">
									{#if m.link_preview.image_url}<img src={m.link_preview.image_url} alt="" />{/if}
									<span class="lp-text">
										{#if m.link_preview.site_name}<span class="tiny faint">{m.link_preview.site_name}</span>{/if}
										{#if m.link_preview.title}<strong>{m.link_preview.title}</strong>{/if}
										{#if m.link_preview.description}<span class="small muted lp-desc">{m.link_preview.description}</span>{/if}
									</span>
								</a>
							{/if}
						{/if}
					{/if}
					<span class="meta tiny">
						{#if m.edited_at && !m.deleted}<span>{t('social.edited')}</span>{/if}
						<time datetime={m.created_at} title={formatDateTime(m.created_at, i18n.tag)}>{time}</time>
						{#if local && !failed}<Icon name="clock" size={11} label={t('chat.sending')} />{/if}
					</span>
				</div>
				{#if !m.deleted && !local}
					<div class="tools">
						<ReactionRow summary={m.reactions} kind="message" targetId={m.id} size="sm" showLine={false} onchange={onreactions} />
						<MoreMenu label={t('chat.messageActions')} items={menu} align={mine ? 'end' : 'start'} />
					</div>
				{/if}
			</div>
			{#if failed}
				<p class="failed-note small" role="alert"><Icon name="alert" size={14} />{t('chat.notSent')} {#if onretry}<button class="link-btn" onclick={onretry}>{t('common.tryAgain')}</button>{/if}</p>
			{/if}
			{#if !m.deleted && m.reactions.total > 0}
				<div class="reactions-line"><ReactionRow summary={m.reactions} kind="message" targetId={m.id} size="sm" canReact={false} /></div>
			{/if}
			{#if m.frame_activity?.length}
				<ul class="frame-activity" role="list" aria-label={t('chat.frameActivity')}>
					{#each m.frame_activity as fa (fa.frame_id + fa.item_id)}
						{#if fa.reaction}<li>{REACTION_EMOJI[fa.reaction]} {fa.frame_name}</li>{/if}
						{#each fa.replies ?? [] as r (r)}<li>💬 {fa.frame_name}: {t(`reply.${r}`)}</li>{/each}
					{/each}
				</ul>
			{/if}
			{#if hasMedia && readyMedia && !local}
				<button class="put btn btn-sm btn-soft" onclick={onputonframe}><Icon name="frame" size={15} />{t('chat.putOnFrame')}</button>
			{/if}
			{#if receipts && seen.length > 0}
				<span class="seen tiny faint">{seen.length === 1 && !showAuthor ? t('chat.seen') : t('chat.seenBy', { names: seenNames })}</span>
			{/if}
		</div>
	</div>
{/if}

<style>
	.system {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		margin: var(--s-3) 0;
		scroll-margin-top: 80px;
	}
	.sys-text {
		margin: 0;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 4px 12px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-muted);
		font-size: 0.82rem;
		text-align: center;
	}
	.msg {
		display: flex;
		gap: var(--s-2);
		align-items: flex-end;
		margin-top: 2px;
		scroll-margin-top: 80px;
	}
	.msg.first {
		margin-top: var(--s-3);
	}
	.msg.mine {
		flex-direction: row-reverse;
	}
	.av {
		width: 30px;
		flex: none;
	}
	.col {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		max-width: min(78%, 560px);
		min-width: 0;
		gap: 2px;
	}
	.mine .col {
		align-items: flex-end;
	}
	.author {
		font-size: 0.78rem;
		font-weight: 700;
		color: var(--text-muted);
		padding-inline-start: 12px;
	}
	.bubble-row {
		display: flex;
		align-items: center;
		gap: 4px;
		max-width: 100%;
	}
	.mine .bubble-row {
		flex-direction: row-reverse;
	}
	.bubble {
		position: relative;
		min-width: 0;
		max-width: 100%;
		padding: 8px 12px 6px;
		border-radius: 18px;
		background: var(--surface);
		border: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		gap: 6px;
		overflow-wrap: anywhere;
	}
	.mine .bubble {
		background: var(--primary-soft);
		border-color: transparent;
	}
	.msg:not(.mine):not(.last) .bubble {
		border-end-start-radius: 6px;
	}
	.msg:not(.mine):not(.first) .bubble {
		border-start-start-radius: 6px;
	}
	.mine:not(.last) .bubble {
		border-end-end-radius: 6px;
	}
	.mine:not(.first) .bubble {
		border-start-end-radius: 6px;
	}
	.bubble.media-only {
		padding: 3px;
	}
	.bubble.media-only .meta {
		position: absolute;
		bottom: 8px;
		inset-inline-end: 10px;
		padding: 1px 6px;
		border-radius: var(--r-full);
		background: rgb(0 0 0 / 0.5);
		color: #fff;
	}
	.bubble.pending {
		opacity: 0.75;
	}
	.bubble.failed {
		border-color: var(--danger);
	}
	.bubble.tomb {
		background: transparent;
		border-style: dashed;
	}
	.tomb-text {
		margin: 0;
		display: inline-flex;
		gap: 6px;
		align-items: center;
		font-style: italic;
		color: var(--text-muted);
	}
	.text {
		margin: 0;
	}
	.meta {
		align-self: flex-end;
		display: inline-flex;
		gap: 6px;
		align-items: center;
		color: var(--text-faint);
		margin-top: -2px;
	}
	.quote {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 1px;
		padding: 6px 10px;
		border: 0;
		border-inline-start: 3px solid var(--primary);
		border-radius: 8px;
		background: color-mix(in srgb, var(--surface-2) 80%, transparent);
		color: inherit;
		font: inherit;
		font-size: 0.85rem;
		text-align: start;
		cursor: pointer;
		max-width: 100%;
	}
	.quote span {
		color: var(--text-muted);
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.shared {
		display: flex;
		gap: var(--s-2);
		align-items: center;
		padding: 6px;
		border-radius: 12px;
		background: var(--surface-2);
		color: inherit;
		text-decoration: none;
	}
	.shared-thumbs {
		display: flex;
		gap: 2px;
		flex: none;
	}
	.shared-thumbs img {
		width: 52px;
		height: 52px;
		object-fit: cover;
		border-radius: 8px;
	}
	.shared-text {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.shared.unavailable {
		margin: 0;
		color: var(--text-muted);
	}
	.lp {
		display: flex;
		flex-direction: column;
		border-radius: 12px;
		overflow: hidden;
		background: var(--surface-2);
		color: inherit;
		text-decoration: none;
		max-width: 320px;
	}
	.lp img {
		width: 100%;
		max-height: 180px;
		object-fit: cover;
	}
	.lp-text {
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding: 8px 10px;
	}
	.lp-desc {
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.lp-pending {
		display: block;
		height: 56px;
		border-radius: 12px;
	}
	.tools {
		display: flex;
		align-items: center;
		gap: 2px;
		opacity: 0;
		transition: opacity var(--dur) var(--ease);
	}
	.msg:hover .tools,
	.msg:focus-within .tools {
		opacity: 1;
	}
	@media (pointer: coarse) {
		.tools {
			opacity: 1;
		}
	}
	.reactions-line {
		margin-top: -4px;
	}
	.frame-activity {
		list-style: none;
		margin: 2px 0 0;
		padding: 0;
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
	}
	.frame-activity li {
		padding: 2px 8px;
		border-radius: var(--r-full);
		background: var(--accent-soft);
		color: var(--accent-text);
		font-size: 0.78rem;
		font-weight: 600;
	}
	.put {
		margin-top: 2px;
	}
	.seen {
		padding-inline-end: 6px;
	}
	.failed-note {
		margin: 0;
		color: var(--danger);
		display: inline-flex;
		gap: 4px;
		align-items: center;
	}
	.link-btn {
		border: 0;
		background: none;
		color: inherit;
		font: inherit;
		font-weight: 700;
		text-decoration: underline;
		cursor: pointer;
	}
</style>
