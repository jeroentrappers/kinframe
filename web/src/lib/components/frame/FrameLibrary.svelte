<script lang="ts">
	import { flip } from 'svelte/animate';
	import { api, call, ApiError, type Album, type AlbumSchedule, type Frame, type Placement } from '$lib/api/client';
	import { can } from '$lib/frames.svelte';
	import { formatDateTime } from '$lib/format';
	import { getFrameSettings, patchFrameSettings } from '$lib/frameSettings';
	import { formatMonthDay, moveItem, orderBy, scheduleCovers } from '$lib/parity';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { reducedMotion, softFly } from '$lib/motion';
	import Dialog from '../Dialog.svelte';
	import EmptyState from '../EmptyState.svelte';
	import Icon, { type IconName } from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';
	import TvDialog from '../TvDialog.svelte';
	import type { TvScope } from '$lib/tv';
	import AlbumNameDialog from './AlbumNameDialog.svelte';
	import AlbumPickerDialog from './AlbumPickerDialog.svelte';
	import AlbumScheduleDialog from './AlbumScheduleDialog.svelte';
	import Memories from './Memories.svelte';

	type View = 'visible' | 'hidden' | 'trashed';
	type Action = 'hide' | 'unhide' | 'trash' | 'restore';

	let { frame }: { frame: Frame } = $props();

	let view = $state<View>('visible');
	let items = $state<Placement[]>([]);
	let counts = $state<{ photos?: number; videos?: number; hidden?: number; trashed?: number }>({});
	let cursor = $state<string | null>(null);
	let loading = $state(true);
	let loadingMore = $state(false);
	let error = $state<unknown>(null);
	let selected = $state<Set<string>>(new Set());
	let viewing = $state<Placement | null>(null);
	let viewerOpen = $state(false);

	const seeAll = $derived(can(frame, 'see_library'));
	const manage = $derived(can(frame, 'manage_library'));
	const control = $derived(can(frame, 'change_settings'));

	// ---------------------------------------------------------------- albums
	let albums = $state<Album[]>([]);
	let albumId = $state<string | null>(null);
	let playingId = $state<string | null>(null);
	let settingsVersion = $state<number | undefined>(undefined);
	const album = $derived(albums.find((a) => a.id === albumId) ?? null);

	async function loadAlbums() {
		try {
			albums = await call(api.GET('/v1/frames/{frameId}/albums', { params: { path: { frameId: frame.id } } }));
			if (albumId && !albums.some((a) => a.id === albumId)) albumId = null;
		} catch {
			albums = [];
		}
	}

	async function loadPlaying() {
		if (!control) return;
		try {
			const s = await getFrameSettings(frame.id);
			playingId = s.slideshow?.album_id ?? null;
			settingsVersion = s.version;
		} catch {
			/* not essential */
		}
	}

	function upsertAlbum(a: Album) {
		const i = albums.findIndex((x) => x.id === a.id);
		if (i >= 0) albums[i] = a;
		else albums = [...albums, a].sort((x, y) => x.name.localeCompare(y.name));
	}

	// ---------------------------------------------------------------- placements
	async function load(reset = true) {
		if (reset) {
			loading = true;
			selected = new Set();
			arranging = false;
		} else loadingMore = true;
		error = null;
		try {
			if (albumId) {
				// Albums are shown whole (in their order), so fetch every page.
				let all: Placement[] = [];
				let next: string | null = null;
				do {
					const res: { placements: Placement[]; next_cursor: string | null } = await call(
						api.GET('/v1/frames/{frameId}/placements', {
							params: { path: { frameId: frame.id }, query: { album_id: albumId, limit: 200, ...(next ? { cursor: next } : {}) } }
						})
					);
					all = [...all, ...res.placements];
					next = res.next_cursor;
				} while (next && all.length < 2000);
				items = orderBy(all, album?.placement_ids ?? []);
				cursor = null;
			} else {
				const res = await call(
					api.GET('/v1/frames/{frameId}/placements', {
						params: {
							path: { frameId: frame.id },
							query: { visibility: view, limit: 60, ...(reset || !cursor ? {} : { cursor }) }
						}
					})
				);
				items = reset ? res.placements : [...items, ...res.placements];
				counts = res.counts;
				cursor = res.next_cursor;
			}
		} catch (e) {
			error = e;
		} finally {
			loading = false;
			loadingMore = false;
		}
	}

	$effect(() => {
		void frame.id;
		void loadAlbums();
		void loadPlaying();
	});

	$effect(() => {
		void frame.id;
		void view;
		void albumId;
		void load();
	});

	function selectAlbum(id: string | null) {
		if (id === albumId) return;
		albumId = id;
		if (id) view = 'visible';
	}

	function toggle(id: string) {
		const next = new Set(selected);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		selected = next;
	}

	function open(p: Placement) {
		if (arranging) return;
		if (selected.size > 0) return toggle(p.id);
		viewing = p;
		viewerOpen = true;
	}

	const ACTIONS: Record<View, Action[]> = {
		visible: ['hide', 'trash'],
		hidden: ['unhide', 'trash'],
		trashed: ['restore']
	};
	const ACTION_ICON: Record<Action, IconName> = { hide: 'eyeOff', unhide: 'eye', trash: 'trash', restore: 'restore' };

	function bulk(action: Action, ids: string[] = [...selected]) {
		if (ids.length === 0) return;
		const snapshot = items;
		const snapshotCounts = { ...counts };
		const n = ids.length;
		viewerOpen = false;
		toasts.deferred({
			message: tn(`library.done_${action}`, n),
			undoLabel: t('common.undo'),
			apply: () => {
				items = items.filter((p) => !ids.includes(p.id));
				selected = new Set();
				if (action === 'hide') counts.hidden = (counts.hidden ?? 0) + n;
				if (action === 'unhide') counts.hidden = Math.max(0, (counts.hidden ?? 0) - n);
				if (action === 'trash') {
					counts.trashed = (counts.trashed ?? 0) + n;
					if (view === 'hidden') counts.hidden = Math.max(0, (counts.hidden ?? 0) - n);
				}
				if (action === 'restore') counts.trashed = Math.max(0, (counts.trashed ?? 0) - n);
			},
			rollback: () => {
				items = snapshot;
				counts = snapshotCounts;
			},
			commit: async () => {
				const res = await call(
					api.POST('/v1/frames/{frameId}/placements:bulk', {
						params: { path: { frameId: frame.id } },
						body: { placement_ids: ids, action }
					})
				);
				if (res.failed?.length) {
					toasts.error(tn('library.someFailed', res.failed.length));
					void load();
				}
			},
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}

	// ---------------------------------------------------------------- album actions
	let newOpen = $state(false);
	let renameOpen = $state(false);
	let scheduleOpen = $state(false);
	let deleteOpen = $state(false);
	let pickerOpen = $state(false);
	let pickerIds = $state<string[]>([]);

	async function createAlbum(name: string, placementIds: string[] = []) {
		try {
			const a = await call(
				api.POST('/v1/frames/{frameId}/albums', {
					params: { path: { frameId: frame.id } },
					body: { name, ...(placementIds.length ? { placement_ids: placementIds } : {}) }
				})
			);
			upsertAlbum(a);
			return a;
		} catch (e) {
			toasts.error(problemMessage(e));
			throw e;
		}
	}

	/** PATCH an album with its version; on a conflict reload it and tell the user. */
	async function writeAlbum(a: Album, body: { name?: string; placement_ids?: string[]; cover_placement_id?: string; schedule?: AlbumSchedule | null }) {
		try {
			const updated = await call(
				api.PATCH('/v1/frames/{frameId}/albums/{albumId}', {
					params: { path: { frameId: frame.id, albumId: a.id }, header: { 'If-Match': `"${a.version}"` } },
					body
				})
			);
			upsertAlbum(updated);
			return updated;
		} catch (e) {
			if (e instanceof ApiError && e.code === 'version_conflict') {
				toasts.error(t('albums.conflict'));
				await loadAlbums();
				void load();
			} else toasts.error(problemMessage(e));
			throw e;
		}
	}

	async function addToAlbum(choice: { albumId: string } | { newName: string }) {
		const ids = pickerIds;
		if ('newName' in choice) {
			const a = await createAlbum(choice.newName, ids);
			toasts.success(tn('albums.added', ids.length, { name: a.name }));
		} else {
			const a = albums.find((x) => x.id === choice.albumId)!;
			try {
				const res = await call(
					api.POST('/v1/frames/{frameId}/placements:bulk', {
						params: { path: { frameId: frame.id } },
						body: { placement_ids: ids, action: 'add_to_album', album_id: a.id }
					})
				);
				if (res.failed?.length) toasts.error(tn('library.someFailed', res.failed.length));
				else toasts.success(tn('albums.added', ids.length, { name: a.name }));
			} catch (e) {
				toasts.error(problemMessage(e));
				throw e;
			}
			await loadAlbums();
		}
		selected = new Set();
	}

	function removeFromAlbum(ids: string[] = [...selected]) {
		const a = album;
		if (!a || ids.length === 0) return;
		const snapshot = items;
		const albumSnapshot = { ...a };
		viewerOpen = false;
		toasts.deferred({
			message: tn('albums.removed', ids.length, { name: a.name }),
			undoLabel: t('common.undo'),
			apply: () => {
				items = items.filter((p) => !ids.includes(p.id));
				selected = new Set();
				upsertAlbum({ ...a, placement_count: a.placement_count - ids.length, placement_ids: (a.placement_ids ?? []).filter((id) => !ids.includes(id)) });
			},
			rollback: () => {
				items = snapshot;
				upsertAlbum(albumSnapshot);
			},
			commit: async () => {
				await call(
					api.POST('/v1/frames/{frameId}/placements:bulk', {
						params: { path: { frameId: frame.id } },
						body: { placement_ids: ids, action: 'remove_from_album', album_id: a.id }
					})
				);
				await loadAlbums();
			},
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}

	async function setCover(p: Placement) {
		if (!album) return;
		try {
			await writeAlbum(album, { cover_placement_id: p.id });
			selected = new Set();
			viewerOpen = false;
			toasts.success(t('albums.coverSet'));
		} catch {
			/* reported */
		}
	}

	async function rename(name: string) {
		if (!album) return;
		await writeAlbum(album, { name });
	}

	async function saveSchedule(s: AlbumSchedule | null) {
		if (!album) return;
		await writeAlbum(album, { schedule: s });
		toasts.success(s ? t('albums.scheduleSaved', { from: formatMonthDay(s.from_month_day ?? '', i18n.tag), to: formatMonthDay(s.to_month_day ?? '', i18n.tag) }) : t('albums.scheduleCleared'));
	}

	let deleting = $state(false);
	async function deleteAlbum() {
		const a = album;
		if (!a) return;
		deleting = true;
		try {
			await call(api.DELETE('/v1/frames/{frameId}/albums/{albumId}', { params: { path: { frameId: frame.id, albumId: a.id } } }));
			albums = albums.filter((x) => x.id !== a.id);
			if (playingId === a.id) playingId = null;
			albumId = null;
			deleteOpen = false;
			toasts.success(t('albums.deleted', { name: a.name }));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			deleting = false;
		}
	}

	let playBusy = $state(false);
	async function togglePlaying() {
		if (!album) return;
		const next = playingId === album.id ? null : album.id;
		playBusy = true;
		try {
			const s = await patchFrameSettings(frame.id, { slideshow: { album_id: next } }, settingsVersion);
			playingId = s.slideshow?.album_id ?? null;
			settingsVersion = s.version;
			toasts.success(next ? t('albums.nowPlaying', { name: album.name, frame: frame.name }) : t('albums.playingAll', { frame: frame.name }));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			playBusy = false;
		}
	}

	// ---------------------------------------------------------------- arrange (drag to reorder)
	let arranging = $state(false);
	let arrangeSnapshot: Placement[] = [];
	let dragIndex = $state<number | null>(null);
	let savingOrder = $state(false);

	function startArranging() {
		selected = new Set();
		arrangeSnapshot = items;
		arranging = true;
	}

	function cancelArranging() {
		items = arrangeSnapshot;
		arranging = false;
	}

	async function saveOrder() {
		if (!album) return;
		const shown = items.map((p) => p.id);
		// Keep album members this view doesn't show (hidden ones) after the arranged ones.
		const rest = (album.placement_ids ?? []).filter((id) => !shown.includes(id));
		savingOrder = true;
		try {
			await writeAlbum(album, { placement_ids: [...shown, ...rest] });
			arranging = false;
			toasts.success(t('albums.orderSaved'));
		} catch {
			/* reported */
		} finally {
			savingOrder = false;
		}
	}

	function gripDown(e: PointerEvent, index: number) {
		e.preventDefault();
		(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
		dragIndex = index;
	}

	function gripMove(e: PointerEvent) {
		if (dragIndex === null) return;
		const el = document.elementFromPoint(e.clientX, e.clientY)?.closest<HTMLElement>('[data-index]');
		const to = el ? Number(el.dataset.index) : NaN;
		if (Number.isInteger(to) && to !== dragIndex) {
			items = moveItem(items, dragIndex, to);
			dragIndex = to;
		}
	}

	function gripUp() {
		dragIndex = null;
	}

	function gripKey(e: KeyboardEvent, index: number) {
		// The grid runs right to left in Arabic and Hebrew: the horizontal arrows follow the reading direction.
		const before = i18n.dir === 'rtl' ? 'ArrowRight' : 'ArrowLeft';
		const after = i18n.dir === 'rtl' ? 'ArrowLeft' : 'ArrowRight';
		const delta = e.key === before || e.key === 'ArrowUp' ? -1 : e.key === after || e.key === 'ArrowDown' ? 1 : 0;
		if (!delta) return;
		e.preventDefault();
		const to = index + delta;
		if (to < 0 || to >= items.length) return;
		items = moveItem(items, index, to);
		requestAnimationFrame(() => document.querySelector<HTMLElement>(`[data-index="${to}"] .grip`)?.focus());
	}

	// ---------------------------------------------------------------- show on TV
	let tvOpen = $state(false);
	let tvScope = $state<TvScope>({ kind: 'all' });
	let tvSingleVideo = $state(false);

	function showOnTv(scope: TvScope, singleVideo = false) {
		tvScope = scope;
		tvSingleVideo = singleVideo;
		viewerOpen = false;
		tvOpen = true;
	}

	const playing = (a: Album) => playingId === a.id;
	const scheduledNow = (a: Album) => scheduleCovers(a.schedule);
	const coverItem = $derived(album?.cover_placement_id ? items.find((p) => p.id === album.cover_placement_id) : items[0]);
	const viewingInAlbum = $derived(!!album && !!viewing && items.some((p) => p.id === viewing?.id));
</script>

<!-- Albums: "All photos" plus each album as a chip; managers can make new ones. -->
{#if albums.length > 0 || manage}
	<nav class="albums" aria-label={t('albums.label')}>
		<ul role="list">
			<li>
				<button class="chip" aria-pressed={albumId === null} onclick={() => selectAlbum(null)}>
					<Icon name="image" size={16} />{t('albums.all')}
				</button>
			</li>
			{#each albums as a (a.id)}
				<li>
					<button class="chip" aria-pressed={albumId === a.id} onclick={() => selectAlbum(a.id)}>
						{#if playing(a)}<Icon name="play" size={13} stroke={0} class="playing-icon" />{:else}<Icon name="album" size={16} />{/if}
						<span class="chip-name">{a.name}</span>
						<span class="chip-count num">{a.placement_count}</span>
						{#if a.schedule}<Icon name="calendar" size={14} label={t('albums.scheduled')} />{/if}
					</button>
				</li>
			{/each}
			{#if manage}
				<li>
					<button class="chip chip-new" onclick={() => (newOpen = true)}><Icon name="plus" size={16} />{t('albums.new')}</button>
				</li>
			{/if}
		</ul>
	</nav>
{/if}

{#if album}
	<section class="album-head card" aria-labelledby="album-title" in:softFly>
		<div class="cover">
			{#if coverItem}
				<Thumb itemId={coverItem.item.id} variant="thumb_256" kind={coverItem.item.kind} radius="12px" />
			{:else}
				<span class="cover-empty"><Icon name="album" size={28} /></span>
			{/if}
		</div>
		<div class="a-body">
			<h2 id="album-title">{album.name}</h2>
			<p class="muted small num">{tn('albums.count', album.placement_count)}</p>
			<div class="badges">
				{#if playing(album)}
					<span class="badge badge-play"><Icon name="play" size={11} stroke={0} />{t('albums.playingBadge')}</span>
				{/if}
				{#if album.schedule?.from_month_day && album.schedule.to_month_day}
					<span class="badge" class:badge-on={scheduledNow(album)}>
						<Icon name="calendar" size={13} />{t('albums.scheduleShort', {
							from: formatMonthDay(album.schedule.from_month_day, i18n.tag),
							to: formatMonthDay(album.schedule.to_month_day, i18n.tag)
						})}
					</span>
				{/if}
			</div>
		</div>
		<div class="a-actions">
			{#if arranging}
				<button class="btn btn-ghost btn-sm" onclick={cancelArranging} disabled={savingOrder}>{t('common.cancel')}</button>
				<button class="btn btn-primary btn-sm" onclick={saveOrder} disabled={savingOrder}>
					{#if savingOrder}<span class="spinner" aria-hidden="true"></span>{/if}{t('albums.saveOrder')}
				</button>
			{:else}
				{#if control}
					<button class="btn btn-sm" class:btn-primary={!playing(album)} onclick={togglePlaying} disabled={playBusy}>
						{#if playBusy}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name={playing(album) ? 'x' : 'play'} size={16} stroke={playing(album) ? 2 : 0} />{/if}
						{playing(album) ? t('albums.stopPlaying') : t('albums.play')}
					</button>
				{/if}
				{#if seeAll && items.length > 0}
					<button class="btn btn-sm" onclick={() => album && showOnTv({ kind: 'album', albumId: album.id, name: album.name })}>
						<Icon name="tv" size={16} />{t('tv.show')}
					</button>
				{/if}
				{#if manage}
					<span class="icon-tools">
					<button class="btn btn-ghost btn-icon btn-sm" onclick={startArranging} disabled={items.length < 2} aria-label={t('albums.arrange')} title={t('albums.arrange')}><Icon name="grip" size={18} /></button>
					<button class="btn btn-ghost btn-icon btn-sm" onclick={() => (scheduleOpen = true)} aria-label={t('albums.schedule')} title={t('albums.schedule')}><Icon name="calendar" size={18} /></button>
					<button class="btn btn-ghost btn-icon btn-sm" onclick={() => (renameOpen = true)} aria-label={t('albums.rename')} title={t('albums.rename')}><Icon name="edit" size={18} /></button>
					<button class="btn btn-danger-ghost btn-icon btn-sm" onclick={() => (deleteOpen = true)} aria-label={t('albums.delete')} title={t('albums.delete')}><Icon name="trash" size={18} /></button>
					</span>
				{/if}
			{/if}
		</div>
		{#if arranging}
			<p class="arrange-hint small" role="status"><Icon name="info" size={16} />{t('albums.arrangeHint')}</p>
		{/if}
	</section>
{:else}
	{#if view === 'visible'}
		<Memories {frame} onopen={(p) => open(p)} />
	{/if}
	<div class="toolbar">
		<div class="segmented" role="tablist" aria-label={t('library.filter')}>
			<button role="tab" aria-selected={view === 'visible'} onclick={() => (view = 'visible')}>{t('library.visible')}</button>
			<button role="tab" aria-selected={view === 'hidden'} onclick={() => (view = 'hidden')}>
				{t('library.hidden')}{#if counts.hidden}<span class="count num">{counts.hidden}</span>{/if}
			</button>
			<button role="tab" aria-selected={view === 'trashed'} onclick={() => (view = 'trashed')}>
				{t('library.trashTab')}{#if counts.trashed}<span class="count num">{counts.trashed}</span>{/if}
			</button>
		</div>
		{#if view === 'visible' && (counts.photos != null || counts.videos != null)}
			<p class="muted small num">
				{tn('history.photos', counts.photos ?? 0)}{#if counts.videos}, {tn('history.videos', counts.videos)}{/if}
			</p>
		{/if}
		{#if view === 'visible' && seeAll && items.length > 0}
			<button class="btn btn-sm tv-btn" onclick={() => showOnTv({ kind: 'all' })}><Icon name="tv" size={16} />{t('tv.show')}</button>
		{/if}
	</div>
{/if}

{#if !seeAll}
	<p class="callout small own-only"><Icon name="info" size={18} />{t('library.ownOnly')}</p>
{/if}
{#if !album && view === 'trashed' && items.length > 0}
	<p class="callout small own-only"><Icon name="trash" size={18} />{t('library.trashNote')}</p>
{/if}

{#if loading}
	<div class="grid" aria-busy="true" aria-label={t('common.loading')}>
		{#each Array(12) as _, i (i)}<div class="skeleton tile-skel"></div>{/each}
	</div>
{:else if error}
	<div class="callout callout-error" role="alert">
		<Icon name="alert" />
		<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={() => load()}>{t('common.tryAgain')}</button></div>
	</div>
{:else if items.length === 0}
	<div class="card">
		{#if album}
			<EmptyState compact art="library" title={t('albums.emptyTitle')} body={manage ? t('albums.emptyBody') : undefined}>
				{#snippet actions()}
					{#if manage}<button class="btn btn-primary" onclick={() => selectAlbum(null)}><Icon name="image" size={18} />{t('albums.pickPhotos')}</button>{/if}
				{/snippet}
			</EmptyState>
		{:else}
			<EmptyState
				compact
				art="library"
				title={view === 'visible' ? t('library.emptyTitle') : view === 'hidden' ? t('library.emptyHidden') : t('library.emptyTrash')}
				body={view === 'visible' ? t('library.emptyBody') : undefined}
			>
				{#snippet actions()}
					{#if view === 'visible' && can(frame, 'send')}
						<a class="btn btn-primary" href="/send?frame={frame.id}"><Icon name="send" size={18} />{t('nav.sendPhotos')}</a>
					{/if}
				{/snippet}
			</EmptyState>
		{/if}
	</div>
{:else}
	<ul class="grid" class:arranging role="list" aria-label={album ? album.name : t('frame.tabLibrary')}>
		{#each items as p, i (p.id)}
			{@const isSel = selected.has(p.id)}
			<li
				class="tile"
				class:selected={isSel}
				class:selecting={selected.size > 0}
				class:dragging={dragIndex === i}
				data-index={i}
				animate:flip={{ duration: reducedMotion() ? 0 : 180 }}
			>
				<button class="tile-btn" onclick={() => open(p)} tabindex={arranging ? -1 : 0} aria-label={p.item.caption ?? t('library.itemBy', { name: p.item.sender_name ?? '' })}>
					<Thumb itemId={p.item.id} variant="thumb_256" kind={p.item.kind} radius="0" />
				</button>
				{#if arranging}
					<button
						class="grip"
						aria-label={t('albums.moveItem', { n: i + 1, total: items.length })}
						onpointerdown={(e) => gripDown(e, i)}
						onpointermove={gripMove}
						onpointerup={gripUp}
						onpointercancel={gripUp}
						onkeydown={(e) => gripKey(e, i)}
					>
						<Icon name="grip" size={18} />
					</button>
					<span class="pos num" aria-hidden="true">{i + 1}</span>
				{:else}
					<button class="pick" role="checkbox" aria-checked={isSel} aria-label={t('library.select')} onclick={() => toggle(p.id)}>
						<Icon name="check" size={14} stroke={3} />
					</button>
				{/if}
				{#if album && album.cover_placement_id === p.id}<span class="tag tag-cover"><Icon name="star" size={11} />{t('albums.cover')}</span>{/if}
				{#if p.pending_moderation}<span class="tag">{t('library.pending')}</span>{/if}
			</li>
		{/each}
	</ul>
	{#if cursor}
		<div class="more"><button class="btn" onclick={() => load(false)} disabled={loadingMore}>{t('common.loadMore')}</button></div>
	{/if}
{/if}

{#if selected.size > 0}
	<div class="bulkbar" role="toolbar" aria-label={t('library.bulk')} transition:softFly={{ y: 24 }}>
		<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('library.clearSelection')} onclick={() => (selected = new Set())}><Icon name="x" size={18} /></button>
		<span class="sel num">{tn('library.selected', selected.size)}</span>
		<span class="spacer"></span>
		<button class="btn btn-ghost btn-sm select-all" onclick={() => (selected = new Set(items.map((p) => p.id)))}>{t('library.selectAll')}</button>
		{#if view === 'visible'}
			<button
				class="btn btn-sm"
				onclick={() => {
					const ids = items.filter((p) => selected.has(p.id)).map((p) => p.id);
					const one = ids.length === 1 ? items.find((p) => p.id === ids[0]) : undefined;
					showOnTv({ kind: 'selection', ids }, one?.item.kind === 'video');
				}}
				aria-label={t('tv.show')}
				title={t('tv.show')}
			>
				<Icon name="tv" size={16} /><span class="label-long">{t('tv.show')}</span>
			</button>
		{/if}
		{#if album}
			{#if manage && selected.size === 1}
				<button class="btn btn-sm" onclick={() => { const p = items.find((x) => selected.has(x.id)); if (p) void setCover(p); }}>
					<Icon name="star" size={16} />{t('albums.makeCover')}
				</button>
			{/if}
			{#if manage}
				<button class="btn btn-sm btn-danger" onclick={() => removeFromAlbum()}><Icon name="x" size={16} />{t('albums.remove')}</button>
			{/if}
		{:else}
			{#if manage && view === 'visible'}
				<button class="btn btn-sm btn-soft" onclick={() => { pickerIds = [...selected]; pickerOpen = true; }}>
					<Icon name="album" size={16} /><span class="label-long">{t('albums.addToAlbum')}</span><span class="label-short">{t('albums.addShort')}</span>
				</button>
			{/if}
			{#each ACTIONS[view] as a (a)}
				<button class="btn btn-sm" class:btn-danger={a === 'trash'} class:btn-primary={a !== 'trash'} onclick={() => bulk(a)} aria-label={t(`library.${a}`)}>
					<Icon name={ACTION_ICON[a]} size={16} /><span class="label-long">{t(`library.${a}`)}</span>
				</button>
			{/each}
		{/if}
	</div>
{/if}

<Dialog bind:open={viewerOpen} title={viewing?.item.sender_name ? t('library.from', { name: viewing.item.sender_name }) : t('library.photo')} size="md">
	{#if viewing}
		<div class="viewer stack">
			<div class="big">
				<Thumb itemId={viewing.item.id} variant={viewing.item.kind === 'video' ? 'poster' : 'master'} kind={viewing.item.kind} alt={viewing.item.caption ?? ''} />
			</div>
			{#if viewing.item.caption}<p>{viewing.item.caption}</p>{/if}
			<p class="muted small">
				{#if viewing.item.captured_at}{t('library.taken', { when: formatDateTime(viewing.item.captured_at, i18n.tag) })} · {/if}{t('library.received', { when: formatDateTime(viewing.received_at, i18n.tag) })}
			</p>
		</div>
	{/if}
	{#snippet footer()}
		{#if viewing && view === 'visible' && !viewing.pending_moderation}
			<button class="btn" onclick={() => viewing && showOnTv({ kind: 'selection', ids: [viewing.id] }, viewing.item.kind === 'video')}>
				<Icon name="tv" size={16} />{t('tv.show')}
			</button>
		{/if}
		{#if viewing}
			{#if viewingInAlbum && manage}
				{#if album?.cover_placement_id !== viewing.id}
					<button class="btn" onclick={() => viewing && setCover(viewing)}><Icon name="star" size={16} />{t('albums.makeCover')}</button>
				{/if}
				<button class="btn btn-danger-ghost" onclick={() => viewing && removeFromAlbum([viewing.id])}><Icon name="x" size={16} />{t('albums.remove')}</button>
			{:else if !album}
				{#if manage && view === 'visible' && (albums.length > 0 || manage)}
					<button class="btn" onclick={() => { if (viewing) { pickerIds = [viewing.id]; viewerOpen = false; pickerOpen = true; } }}>
						<Icon name="album" size={16} />{t('albums.addShort')}
					</button>
				{/if}
				{#each ACTIONS[view] as a (a)}
					<button class="btn" class:btn-danger-ghost={a === 'trash'} onclick={() => viewing && bulk(a, [viewing.id])}>
						<Icon name={ACTION_ICON[a]} size={16} />{t(`library.${a}`)}
					</button>
				{/each}
			{/if}
		{/if}
	{/snippet}
</Dialog>

<AlbumNameDialog
	bind:open={newOpen}
	title={t('albums.newTitle')}
	submitLabel={t('albums.create')}
	hint={t('albums.newHint')}
	onsave={async (name) => {
		const a = await createAlbum(name);
		albumId = a.id;
		toasts.success(t('albums.created', { name }));
	}}
/>
<AlbumNameDialog bind:open={renameOpen} title={t('albums.renameTitle')} submitLabel={t('common.save')} initial={album?.name ?? ''} onsave={rename} />
<AlbumScheduleDialog bind:open={scheduleOpen} albumName={album?.name ?? ''} schedule={album?.schedule} onsave={saveSchedule} />
<AlbumPickerDialog bind:open={pickerOpen} {albums} count={pickerIds.length} onpick={addToAlbum} />
<TvDialog bind:open={tvOpen} frameId={frame.id} frameName={frame.name} scope={tvScope} singleVideo={tvSingleVideo} />

<Dialog bind:open={deleteOpen} title={t('albums.deleteConfirm', { name: album?.name ?? '' })}>
	<p class="muted">{album && playing(album) ? t('albums.deleteBodyPlaying') : t('albums.deleteBody')}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (deleteOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={deleteAlbum} disabled={deleting}>
			{#if deleting}<span class="spinner" aria-hidden="true"></span>{/if}{t('albums.delete')}
		</button>
	{/snippet}
</Dialog>

<style>
	.albums {
		margin: 0 calc(-1 * var(--s-4)) var(--s-4);
		min-width: 0;
	}
	.albums ul {
		list-style: none;
		margin: 0;
		padding: 2px var(--s-4) 6px;
		display: flex;
		gap: 8px;
		overflow-x: auto;
		scrollbar-width: thin;
		overscroll-behavior-x: contain;
	}
	@media (min-width: 860px) {
		.albums {
			margin-left: 0;
			margin-right: 0;
		}
		.albums ul {
			flex-wrap: wrap;
			overflow: visible;
			padding-left: 0;
			padding-right: 0;
		}
	}
	.albums li {
		flex: none;
	}
	.chip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 38px;
		padding: 0 14px;
		border-radius: var(--r-full);
		border: 1.5px solid var(--border);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-weight: 600;
		font-size: 0.9rem;
		cursor: pointer;
		max-width: 260px;
		transition:
			background var(--dur) var(--ease),
			border-color var(--dur) var(--ease);
	}
	.chip:hover {
		border-color: var(--border-strong);
	}
	.chip[aria-pressed='true'] {
		background: var(--primary);
		border-color: var(--primary);
		color: var(--on-primary);
	}
	.chip:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.chip-name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		min-width: 0;
	}
	.chip-count {
		font-size: 0.78rem;
		opacity: 0.75;
	}
	.chip :global(.playing-icon) {
		fill: currentColor;
	}
	.chip-new {
		border-style: dashed;
		color: var(--text-muted);
	}
	.album-head {
		display: grid;
		grid-template-columns: auto 1fr;
		gap: var(--s-3) var(--s-4);
		align-items: center;
		padding: var(--s-4);
		margin-bottom: var(--s-4);
	}
	.cover {
		width: 72px;
		height: 72px;
		border-radius: 12px;
		overflow: hidden;
		background: var(--surface-2);
	}
	.cover-empty {
		width: 100%;
		height: 100%;
		display: grid;
		place-items: center;
		color: var(--text-faint);
	}
	.a-body {
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.a-body h2 {
		font-size: 1.25rem;
		overflow-wrap: anywhere;
	}
	.badges {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		margin-top: 4px;
	}
	.badge {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		padding: 2px 10px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-muted);
		font-size: 0.78rem;
		font-weight: 650;
	}
	.badge-on {
		background: var(--accent-soft);
		color: var(--accent);
	}
	.badge-play {
		background: var(--primary-soft);
		color: var(--primary-soft-text);
	}
	.badge-play :global(svg) {
		fill: currentColor;
	}
	.a-actions {
		grid-column: 1 / -1;
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		align-items: center;
	}
	@media (min-width: 720px) {
		.album-head {
			grid-template-columns: auto 1fr auto;
		}
		.a-actions {
			grid-column: auto;
			justify-content: flex-end;
		}
	}
	.icon-tools {
		display: inline-flex;
		gap: 2px;
		margin-inline-start: auto;
	}
	.arrange-hint {
		grid-column: 1 / -1;
		display: flex;
		align-items: center;
		gap: 6px;
		color: var(--text-muted);
	}
	.toolbar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		flex-wrap: wrap;
		margin-bottom: var(--s-4);
	}
	.count {
		min-width: 20px;
		padding: 0 6px;
		border-radius: var(--r-full);
		background: var(--surface-3);
		font-size: 0.74rem;
		line-height: 20px;
		text-align: center;
	}
	.own-only {
		margin-bottom: var(--s-4);
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
		gap: 6px;
	}
	@media (max-width: 480px) {
		.grid {
			grid-template-columns: repeat(3, 1fr);
			gap: 3px;
		}
	}
	.tile-skel {
		aspect-ratio: 1;
		border-radius: 10px;
	}
	.tile {
		position: relative;
		aspect-ratio: 1;
		border-radius: 10px;
		overflow: hidden;
		background: var(--surface-2);
	}
	.tile-btn {
		all: unset;
		position: absolute;
		inset: 0;
		cursor: pointer;
		transition: transform 160ms var(--ease);
	}
	.tile-btn:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: -3px;
	}
	.arranging .tile-btn {
		cursor: default;
	}
	.tile.dragging {
		z-index: 2;
		box-shadow: var(--shadow-lg);
		transform: scale(1.04);
		outline: 3px solid var(--primary);
	}
	.tile.selected .tile-btn {
		transform: scale(0.88);
		border-radius: 8px;
		overflow: hidden;
	}
	.tile.selected {
		background: var(--primary-soft);
	}
	.pick {
		position: absolute;
		top: 6px;
		inset-inline-start: 6px;
		width: 26px;
		height: 26px;
		border-radius: 50%;
		border: 2px solid #fff;
		background: rgb(0 0 0 / 0.25);
		color: transparent;
		display: grid;
		place-items: center;
		cursor: pointer;
		opacity: 0;
		transition: opacity var(--dur) var(--ease);
		box-shadow: 0 1px 3px rgb(0 0 0 / 0.3);
	}
	/* A bigger invisible hit area for fingers. */
	.pick::before {
		content: '';
		position: absolute;
		inset: -8px;
	}
	.tile:hover .pick,
	.pick:focus-visible,
	.selecting .pick {
		opacity: 1;
	}
	.pick:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: 1px;
	}
	@media (hover: none) {
		.pick {
			opacity: 1;
		}
	}
	.selected .pick {
		background: var(--primary);
		border-color: var(--primary);
		color: var(--on-primary);
	}
	.grip {
		position: absolute;
		top: 6px;
		inset-inline-end: 6px;
		width: 36px;
		height: 36px;
		border-radius: 10px;
		border: 0;
		display: grid;
		place-items: center;
		background: rgb(255 255 255 / 0.92);
		color: #2a221d;
		cursor: grab;
		touch-action: none;
		box-shadow: var(--shadow-sm);
	}
	.grip:active {
		cursor: grabbing;
	}
	.grip:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.pos {
		position: absolute;
		inset-inline-start: 6px;
		top: 6px;
		min-width: 24px;
		padding: 2px 6px;
		border-radius: var(--r-full);
		background: rgb(20 14 10 / 0.65);
		color: #fff;
		font-size: 0.74rem;
		font-weight: 700;
		text-align: center;
	}
	.tag {
		position: absolute;
		bottom: 6px;
		inset-inline-end: 6px;
		padding: 1px 7px;
		border-radius: var(--r-full);
		background: var(--warn-soft);
		color: var(--warn);
		font-size: 0.7rem;
		font-weight: 700;
	}
	.tag-cover {
		display: inline-flex;
		align-items: center;
		gap: 3px;
		inset-inline-start: 6px;
		inset-inline-end: auto;
		background: rgb(20 14 10 / 0.65);
		color: #fff;
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--s-4);
	}
	.bulkbar {
		position: fixed;
		z-index: 40;
		left: 50%;
		transform: translateX(-50%);
		bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom) + 12px);
		width: min(680px, calc(100vw - 24px));
		display: flex;
		align-items: center;
		gap: 6px;
		flex-wrap: wrap;
		padding: 8px 10px;
		border-radius: var(--r-lg);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow-lg);
	}
	@media (min-width: 860px) {
		.bulkbar {
			bottom: 24px;
		}
	}
	.sel {
		font-weight: 650;
		white-space: nowrap;
	}
	.label-short {
		display: none;
	}
	@media (max-width: 560px) {
		.select-all {
			display: none;
		}
		.bulkbar {
			flex-wrap: nowrap;
		}
		.label-long {
			display: none;
		}
		.label-short {
			display: inline;
		}
		.bulkbar .btn {
			padding: 0 10px;
		}
	}
	.big {
		aspect-ratio: 4 / 3;
		border-radius: var(--r);
		overflow: hidden;
		background: #000;
	}
	.big :global(img) {
		object-fit: contain !important;
	}
</style>
