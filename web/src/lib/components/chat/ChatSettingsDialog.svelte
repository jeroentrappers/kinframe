<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, authFetch, call, type Conversation, type ConversationMember, type MuteFor } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { formatDateTime } from '$lib/format';
	import { squareAvatar } from '$lib/media';
	import { problemMessage } from '$lib/problem';
	import { personName } from '$lib/social';
	import { chats } from '$lib/social.svelte';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import MoreMenu, { type MenuItem } from '../social/MoreMenu.svelte';
	import ConversationAvatar from './ConversationAvatar.svelte';
	import NewChatDialog from './NewChatDialog.svelte';

	/** Group management and the caller's own settings for one chat (README §5.19). */
	let { open = $bindable(false), conversation = $bindable() }: { open?: boolean; conversation: Conversation } = $props();

	const c = $derived(conversation);
	const isGroup = $derived(c.kind === 'group');
	const admin = $derived(isGroup && c.my_role === 'admin');
	const me = $derived(auth.me?.id);
	let name = $state('');
	let saving = $state(false);
	let addOpen = $state(false);
	let photoInput: HTMLInputElement | undefined = $state();
	let uploading = $state(false);

	$effect(() => {
		if (open) name = c.name;
	});

	function apply(next: Conversation) {
		conversation = next;
		chats.upsert(next);
	}

	async function rename(e: SubmitEvent) {
		e.preventDefault();
		if (!name.trim() || name.trim() === c.name) return;
		saving = true;
		try {
			apply(await call(api.PATCH('/v1/conversations/{conversationId}', { params: { path: { conversationId: c.id } }, body: { name: name.trim() } })));
			toasts.success(t('common.saved'));
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			saving = false;
		}
	}

	async function onPhoto(e: Event & { currentTarget: HTMLInputElement }) {
		const file = e.currentTarget.files?.[0];
		e.currentTarget.value = '';
		if (!file) return;
		uploading = true;
		try {
			const blob = await squareAvatar(file);
			const res = await authFetch(`/v1/conversations/${encodeURIComponent(c.id)}/photo`, { method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body: blob });
			apply((await res.json()) as Conversation);
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			uploading = false;
		}
	}

	async function removePhoto() {
		try {
			await call(api.DELETE('/v1/conversations/{conversationId}/photo', { params: { path: { conversationId: c.id } } }));
			apply({ ...c, photo_url: null });
		} catch (err) {
			toasts.error(problemMessage(err));
		}
	}

	async function prefs(body: { mute?: MuteFor; archived?: boolean }) {
		try {
			const next = await call(api.PATCH('/v1/conversations/{conversationId}/preferences', { params: { path: { conversationId: c.id } }, body }));
			conversation = next;
			if (body.archived) chats.remove(c.id);
			else chats.upsert(next);
			if (body.mute) toasts.success(body.mute === 'off' ? t('chat.unmuted') : t('chat.mutedToast'));
			if (body.archived !== undefined) toasts.success(body.archived ? t('chat.archivedToast') : t('chat.unarchivedToast'));
		} catch (err) {
			toasts.error(problemMessage(err));
		}
	}

	async function setRole(m: ConversationMember, role: 'admin' | 'member') {
		try {
			apply(await call(api.PATCH('/v1/conversations/{conversationId}/members/{userId}', { params: { path: { conversationId: c.id, userId: m.person.user_id } }, body: { role } })));
		} catch (err) {
			toasts.error(problemMessage(err));
		}
	}

	async function remove(m: ConversationMember) {
		const self = m.person.user_id === me;
		try {
			await call(api.DELETE('/v1/conversations/{conversationId}/members/{userId}', { params: { path: { conversationId: c.id, userId: m.person.user_id } } }));
			if (self) {
				chats.remove(c.id);
				open = false;
				toasts.success(t('chat.leftGroup', { name: c.name }));
				await goto('/chats');
			} else apply({ ...c, members: c.members?.filter((x) => x.person.user_id !== m.person.user_id), member_count: c.member_count - 1 });
		} catch (err) {
			toasts.error(problemMessage(err));
		}
	}

	const MUTES: MuteFor[] = ['off', '1h', '8h', '1w', 'always'];
	let mute = $state<MuteFor>('off');
	$effect(() => {
		if (open) mute = c.muted ? (c.muted_until ? '1w' : 'always') : 'off';
	});

	function memberMenu(m: ConversationMember): MenuItem[] {
		if (!admin || m.person.user_id === me) return [];
		return [
			m.role === 'admin'
				? { label: t('chat.removeAdmin'), icon: 'user', run: () => setRole(m, 'member') }
				: { label: t('chat.makeAdmin'), icon: 'crown', run: () => setRole(m, 'admin') },
			{ label: t('chat.removeFromGroup'), icon: 'trash', danger: true, run: () => remove(m) }
		];
	}
</script>

<Dialog bind:open title={t('chat.details')} size="md">
	<div class="stack">
		<div class="who">
			<ConversationAvatar conversation={c} size={64} />
			<div class="stack-sm grow">
				{#if admin}
					<form class="rename" onsubmit={rename}>
						<label class="visually-hidden" for="conv-name">{t('chat.groupName')}</label>
						<input id="conv-name" class="input" maxlength="60" bind:value={name} />
						<button class="btn btn-sm" disabled={saving || !name.trim() || name.trim() === c.name}>{t('common.save')}</button>
					</form>
					<div class="row-wrap">
						<input bind:this={photoInput} type="file" accept="image/jpeg,image/png,image/webp" hidden onchange={onPhoto} />
						<button class="btn btn-sm btn-ghost" onclick={() => photoInput?.click()} disabled={uploading}>
							{#if uploading}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="upload" size={16} />{/if}{t('chat.changePhoto')}
						</button>
						{#if c.photo_url}<button class="btn btn-sm btn-ghost" onclick={removePhoto}>{t('chat.removePhoto')}</button>{/if}
					</div>
				{:else}
					<h3>{c.name}</h3>
				{/if}
				<p class="muted small">
					{#if c.kind === 'family'}{t('chat.familyChatHint')}{:else if c.kind === 'circle'}{t('chat.circleChatHint')}{:else if c.kind === 'direct'}{t('chat.directHint')}{:else}{tn('chat.members', c.member_count)}{/if}
				</p>
				{#if c.kind === 'family' && c.feed_id}<a class="small" href="/feeds/{c.feed_id}">{t('chat.openFamily')}</a>{/if}
				{#if c.kind === 'circle' && c.feed_id}<a class="small" href="/feeds/{c.feed_id}?tab=circles">{t('chat.openCircle')}</a>{/if}
				{#if c.kind === 'direct' && c.other && !c.other.former_member}<a class="small" href="/people/{c.other.user_id}">{t('chat.viewProfile')}</a>{/if}
			</div>
		</div>

		<section class="stack-sm" aria-labelledby="cs-notif">
			<h3 id="cs-notif">{t('chat.notifications')}</h3>
			<div class="field">
				<label for="cs-mute">{t('chat.mute')}</label>
				<select id="cs-mute" class="select" bind:value={mute} onchange={() => prefs({ mute })}>
					{#each MUTES as m (m)}<option value={m}>{t(`chat.muteFor.${m}`)}</option>{/each}
				</select>
				{#if c.muted && c.muted_until}<p class="hint">{t('chat.mutedUntil', { when: formatDateTime(c.muted_until, i18n.tag) })}</p>{/if}
			</div>
			<button class="btn btn-sm btn-ghost align-start" onclick={() => prefs({ archived: !c.archived })}>
				<Icon name="archive" size={16} />{c.archived ? t('chat.unarchive') : t('chat.archive')}
			</button>
		</section>

		{#if c.members && c.kind !== 'direct'}
			<section class="stack-sm" aria-labelledby="cs-members">
				<div class="row">
					<h3 id="cs-members">{tn('chat.members', c.member_count)}</h3>
					<span class="spacer"></span>
					{#if isGroup && !c.read_only}<button class="btn btn-sm btn-soft" onclick={() => (addOpen = true)}><Icon name="plus" size={16} />{t('chat.addPeople')}</button>{/if}
				</div>
				<ul class="members" role="list">
					{#each c.members as m (m.person.user_id)}
						<li>
							<Avatar name={personName(m.person, t('social.formerMember'))} url={m.person.former_member ? null : m.person.avatar_url} size={34} />
							<span class="nm">
								{#if m.person.user_id === me}{t('social.you')}{:else if m.person.former_member}{t('social.formerMember')}{:else}<a href="/people/{m.person.user_id}">{m.person.display_name}</a>{/if}
								{#if m.role === 'admin' && isGroup}<span class="badge badge-admin">{t('chat.admin')}</span>{/if}
							</span>
							<MoreMenu label={t('chat.memberActions', { name: m.person.display_name })} items={memberMenu(m)} />
						</li>
					{/each}
				</ul>
			</section>
		{/if}

		{#if isGroup}
			<button class="btn btn-danger-ghost align-start" onclick={() => me && c.members && remove(c.members.find((m) => m.person.user_id === me) ?? { person: { user_id: me, display_name: '' }, role: 'member' })}>
				<Icon name="logout" size={16} />{t('chat.leaveGroup')}
			</button>
		{/if}
	</div>
</Dialog>

{#if addOpen}<NewChatDialog bind:open={addOpen} addTo={c} onadded={(next) => (conversation = next)} />{/if}

<style>
	.who {
		display: flex;
		gap: var(--s-4);
		align-items: flex-start;
	}
	.grow {
		flex: 1;
		min-width: 0;
	}
	.rename {
		display: flex;
		gap: var(--s-2);
	}
	h3 {
		font-size: 1rem;
	}
	.members {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
		max-height: 320px;
		overflow-y: auto;
	}
	.members li {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.nm {
		flex: 1;
		min-width: 0;
		display: flex;
		align-items: center;
		gap: var(--s-2);
		font-weight: 600;
	}
	.nm a {
		color: inherit;
		text-decoration: none;
	}
	.align-start {
		align-self: flex-start;
	}
	p {
		margin: 0;
	}
</style>
