<script lang="ts">
	import { api, call, ApiError, type Album, type Frame, type FrameSettings, type SleepSchedule } from '$lib/api/client';
	import { formatInterval } from '$lib/format';
	import { mergePatchDiff, rebaseSettings, type JsonObject } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t, type MessageKey } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from '../Icon.svelte';
	import LocationPicker from './LocationPicker.svelte';

	let { frame }: { frame: Frame } = $props();

	const INTERVALS = [5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 21600, 43200, 86400];
	const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;

	let base = $state<FrameSettings | null>(null);
	let form = $state<FrameSettings | null>(null);
	let error = $state<unknown>(null);
	let saving = $state(false);
	let conflict = $state(false);

	const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v));

	/** Fill in spec defaults (product spec §6) for anything the server left out. */
	function withDefaults(s: FrameSettings): FrameSettings {
		const c = clone(s);
		c.slideshow = { interval_s: 30, transition: 'crossfade', order: 'received', reverse: false, fill: true, background: 'blur', album_id: null, on_this_day: false, ...c.slideshow };
		c.display = { brightness: 204, sleep_schedules: [], ...c.display };
		c.widgets = { caption: true, clock: true, weather: false, ...c.widgets };
		c.locale = { temperature_unit: 'c', ...c.locale };
		c.location ??= null;
		return c;
	}

	async function load() {
		error = null;
		try {
			const s = withDefaults(await call(api.GET('/v1/frames/{frameId}/settings', { params: { path: { frameId: frame.id } } })));
			base = s;
			form = clone(s);
			conflict = false;
		} catch (e) {
			error = e;
		}
	}

	let albums = $state<Album[]>([]);

	$effect(() => {
		void frame.id;
		void load();
		call(api.GET('/v1/frames/{frameId}/albums', { params: { path: { frameId: frame.id } } }))
			.then((a) => (albums = a))
			.catch(() => (albums = []));
	});

	const strip = (s: FrameSettings): JsonObject => {
		const { version: _v, ...rest } = clone(s) as unknown as JsonObject & { version?: number };
		return rest as JsonObject;
	};
	const patch = $derived(base && form ? mergePatchDiff(strip(base), strip(form)) : {});
	const dirty = $derived(Object.keys(patch).length > 0);

	async function sendPatch(body: JsonObject, version: number) {
		return call(
			api.PATCH('/v1/frames/{frameId}/settings', {
				params: { path: { frameId: frame.id }, header: { 'If-Match': `"${version}"` } },
				// FrameSettingsPatch is an untyped merge-patch object in the contract.
				body: body as never,
				headers: { 'Content-Type': 'application/merge-patch+json' }
			})
		);
	}

	async function save() {
		if (!base || !form || !dirty) return;
		saving = true;
		const ours = patch;
		try {
			const saved = withDefaults(await sendPatch(ours, base.version));
			base = saved;
			form = clone(saved);
			conflict = false;
			toasts.success(t('settingsForm.saved', { name: frame.name }));
		} catch (e) {
			if (e instanceof ApiError && e.code === 'version_conflict') await resolveConflict(ours);
			else toasts.error(problemMessage(e));
		} finally {
			saving = false;
		}
	}

	async function resolveConflict(ours: JsonObject) {
		if (!base) return;
		try {
			const latest = withDefaults(await call(api.GET('/v1/frames/{frameId}/settings', { params: { path: { frameId: frame.id } } })));
			const plan = rebaseSettings(strip(base), strip(latest), ours);
			if (plan.kind === 'retry') {
				const saved = withDefaults(await sendPatch(plan.patch, latest.version));
				base = saved;
				form = clone(saved);
				toasts.success(t('settingsForm.mergedSaved'));
			} else {
				base = latest;
				form = { ...(clone(plan.merged) as unknown as FrameSettings), version: latest.version };
				conflict = true;
			}
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	function addSchedule() {
		if (!form?.display) return;
		const s: SleepSchedule = {
			id: crypto.randomUUID(),
			sleep_at: '23:00',
			wake_at: '07:00',
			days: [...DAYS],
			enabled: true,
			mode: 'off'
		};
		form.display.sleep_schedules = [...(form.display.sleep_schedules ?? []), s];
	}

	function removeSchedule(id: string) {
		if (!form?.display) return;
		form.display.sleep_schedules = (form.display.sleep_schedules ?? []).filter((s) => s.id !== id);
	}

	function toggleDay(s: SleepSchedule, d: (typeof DAYS)[number]) {
		s.days = s.days.includes(d) ? s.days.filter((x) => x !== d) : DAYS.filter((x) => x === d || s.days.includes(x));
	}

	// Without a place the frame has no weather to show; keep the switch honest.
	$effect(() => {
		if (form?.widgets && base?.location && !form.location && form.widgets.weather) form.widgets.weather = false;
	});

	const brightnessPct = $derived(form?.display?.brightness != null ? Math.round(((form.display.brightness - 15) / 240) * 100) : 0);
</script>

{#if error}
	<div class="callout callout-error" role="alert">
		<Icon name="alert" />
		<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={load}>{t('common.tryAgain')}</button></div>
	</div>
{:else if !form || !form.slideshow || !form.display || !form.widgets}
	<div class="stack" aria-busy="true">
		<div class="skeleton" style="height:180px"></div>
		<div class="skeleton" style="height:140px"></div>
	</div>
{:else}
	{#if !frame.online}
		<p class="callout small"><Icon name="info" size={18} />{t('settingsForm.offlineNote')}</p>
	{/if}
	{#if conflict}
		<p class="callout callout-warn small" role="alert" in:softFly><Icon name="alert" size={18} />{t('settingsForm.conflict')}</p>
	{/if}

	<form
		class="settings"
		onsubmit={(e) => {
			e.preventDefault();
			void save();
		}}
	>
		<section class="card card-pad stack" aria-labelledby="ss-title">
			<h2 id="ss-title">{t('settingsForm.slideshow')}</h2>
			<div class="two">
				<div class="field">
					<label for="s-int">{t('settingsForm.interval')}</label>
					<select id="s-int" class="select" bind:value={form.slideshow.interval_s}>
						{#each INTERVALS as s}<option value={s}>{formatInterval(s)}</option>{/each}
						{#if form.slideshow.interval_s && !INTERVALS.includes(form.slideshow.interval_s)}
							<option value={form.slideshow.interval_s}>{formatInterval(form.slideshow.interval_s)}</option>
						{/if}
					</select>
				</div>
				<div class="field">
					<label for="s-order">{t('settingsForm.order')}</label>
					<select id="s-order" class="select" bind:value={form.slideshow.order}>
						<option value="received">{t('settingsForm.orderReceived')}</option>
						<option value="taken">{t('settingsForm.orderTaken')}</option>
						<option value="shuffle">{t('settingsForm.orderShuffle')}</option>
					</select>
				</div>
			</div>
			<fieldset class="fs">
				<legend class="label">{t('settingsForm.transition')}</legend>
				<div class="segmented">
					{#each [['crossfade', 'settingsForm.crossfade'], ['slide', 'settingsForm.slide'], ['kenburns', 'settingsForm.kenburns']] as [v, l]}
						<label><input type="radio" name="transition" value={v} bind:group={form.slideshow.transition} />{t(l as MessageKey)}</label>
					{/each}
				</div>
			</fieldset>
			{#if form.slideshow.order !== 'shuffle'}
				<label class="switch"><input type="checkbox" bind:checked={form.slideshow.reverse} /><span>{t('settingsForm.reverse')}</span></label>
			{/if}
			<label class="switch">
				<input type="checkbox" bind:checked={form.slideshow.fill} />
				<span><span class="s-label">{t('settingsForm.fill')}</span><span class="hint">{t('settingsForm.fillHint')}</span></span>
			</label>
			{#if !form.slideshow.fill}
				<div class="field" in:softFly>
					<label for="s-bg">{t('settingsForm.background')}</label>
					<select id="s-bg" class="select" bind:value={form.slideshow.background}>
						<option value="blur">{t('settingsForm.bgBlur')}</option>
						<option value="black">{t('settingsForm.bgBlack')}</option>
						<option value="palette">{t('settingsForm.bgPalette')}</option>
					</select>
				</div>
			{/if}
			{#if albums.length > 0 || form.slideshow.album_id}
				<div class="field">
					<label for="s-album">{t('settingsForm.playAlbum')}</label>
					<select id="s-album" class="select" bind:value={form.slideshow.album_id}>
						<option value={null}>{t('settingsForm.allPhotos')}</option>
						{#each albums as a (a.id)}<option value={a.id}>{a.name}</option>{/each}
					</select>
					<span class="hint">{t('settingsForm.playAlbumHint')}</span>
				</div>
			{/if}
			<label class="switch">
				<input type="checkbox" bind:checked={form.slideshow.on_this_day} />
				<span><span class="s-label">{t('settingsForm.onThisDay')}</span><span class="hint">{t('settingsForm.onThisDayHint')}</span></span>
			</label>
			<label class="switch"><input type="checkbox" bind:checked={form.widgets.caption} /><span>{t('settingsForm.showCaption')}</span></label>
			<label class="switch"><input type="checkbox" bind:checked={form.widgets.clock} /><span>{t('settingsForm.showClock')}</span></label>
		</section>

		<section class="card card-pad stack" aria-labelledby="disp-title">
			<h2 id="disp-title">{t('settingsForm.display')}</h2>
			<div class="field">
				<label for="s-bright" class="row"><span>{t('settingsForm.brightness')}</span><span class="spacer"></span><span class="num muted">{brightnessPct}%</span></label>
				<div class="range-row">
					<Icon name="moon" size={18} />
					<input id="s-bright" type="range" min="15" max="255" step="1" bind:value={form.display.brightness} aria-valuetext="{brightnessPct}%" />
					<Icon name="sun" size={20} />
				</div>
			</div>
		</section>

		<section class="card card-pad stack" aria-labelledby="wx-title">
			<h2 id="wx-title">{t('settingsForm.weatherTitle')}</h2>
			<p class="hint">{t('settingsForm.weatherHint')}</p>
			<LocationPicker
				id="s-loc"
				bind:value={form.location}
				onpick={() => {
					// Picking the first place turns the weather on; that is almost always why it is set.
					if (form?.widgets && !base?.location) form.widgets.weather = true;
				}}
			/>
			<label class="switch" class:disabled={!form.location}>
				<input type="checkbox" bind:checked={form.widgets.weather} disabled={!form.location} />
				<span>
					<span class="s-label">{t('settingsForm.showWeather')}</span>
					<span class="hint">{form.location ? t('settingsForm.showWeatherHint') : t('settingsForm.needsLocation')}</span>
				</span>
			</label>
			{#if form.locale}
				<fieldset class="fs">
					<legend class="label">{t('settingsForm.temperatureUnit')}</legend>
					<div class="segmented">
						<label><input type="radio" name="temperature_unit" value="c" bind:group={form.locale.temperature_unit} />{t('settingsForm.celsius')}</label>
						<label><input type="radio" name="temperature_unit" value="f" bind:group={form.locale.temperature_unit} />{t('settingsForm.fahrenheit')}</label>
					</div>
				</fieldset>
			{/if}
			<p class="credit tiny muted">
				<Icon name="cloud" size={14} /><a href="https://open-meteo.com/" target="_blank" rel="noopener noreferrer">{t('settingsForm.weatherCredit')}</a>
			</p>
		</section>

		<section class="card card-pad stack" aria-labelledby="sleep-title">
			<div class="section-title">
				<h2 id="sleep-title">{t('settingsForm.sleep')}</h2>
				<button type="button" class="btn btn-ghost btn-sm" onclick={addSchedule}><Icon name="plus" size={16} />{t('settingsForm.addSchedule')}</button>
			</div>
			<p class="hint">{t('settingsForm.sleepHint')}</p>
			{#if (form.display.sleep_schedules ?? []).length === 0}
				<p class="muted small empty-sched">{t('settingsForm.noSchedules')}</p>
			{/if}
			{#each form.display.sleep_schedules ?? [] as s, i (s.id)}
				<fieldset class="schedule" class:off={!s.enabled} in:softFly>
					<legend class="visually-hidden">{t('settingsForm.scheduleN', { n: i + 1 })}</legend>
					<div class="sched-top">
						<label class="switch compact"><input type="checkbox" bind:checked={s.enabled} /><span class="visually-hidden">{t('settingsForm.enabled')}</span></label>
						<div class="times">
							<label>
								<span class="tiny muted">{t('settingsForm.sleepAt')}</span>
								<input class="input time" type="time" bind:value={s.sleep_at} required />
							</label>
							<span class="arrow" aria-hidden="true">→</span>
							<label>
								<span class="tiny muted">{t('settingsForm.wakeAt')}</span>
								<input class="input time" type="time" bind:value={s.wake_at} required />
							</label>
						</div>
						<button type="button" class="btn btn-danger-ghost btn-icon btn-sm" aria-label={t('settingsForm.removeSchedule')} onclick={() => removeSchedule(s.id)}>
							<Icon name="trash" size={16} />
						</button>
					</div>
					<div class="days" role="group" aria-label={t('settingsForm.days')}>
						{#each DAYS as d}
							<button type="button" class="day" aria-pressed={s.days.includes(d)} onclick={() => toggleDay(s, d)}>{t(`day.${d}`)}</button>
						{/each}
					</div>
					<div class="segmented mode">
						<label><input type="radio" name="mode-{s.id}" value="off" bind:group={s.mode} />{t('settingsForm.modeOff')}</label>
						<label><input type="radio" name="mode-{s.id}" value="dim" bind:group={s.mode} />{t('settingsForm.modeDim')}</label>
					</div>
				</fieldset>
			{/each}
		</section>

		{#if dirty}
			<div class="savebar" transition:softFly={{ y: 24 }}>
				<span class="small"><span class="dot" style="background:var(--warn)"></span> {t('settingsForm.unsaved')}</span>
				<span class="spacer"></span>
				<button type="button" class="btn btn-ghost btn-sm" onclick={() => base && (form = clone(base))}>{t('settingsForm.discard')}</button>
				<button type="submit" class="btn btn-primary btn-sm" disabled={saving}>
					{#if saving}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.save')}
				</button>
			</div>
		{/if}
	</form>
{/if}

<style>
	.callout {
		margin-bottom: var(--s-4);
	}
	.settings {
		display: grid;
		gap: var(--s-4);
		align-items: start;
		padding-bottom: 80px;
	}
	.settings > section {
		/* Grid items default to min-width: auto; long select options would widen the page on phones. */
		min-width: 0;
	}
	.settings select {
		max-width: 100%;
	}
	@media (min-width: 900px) {
		.settings {
			grid-template-columns: 1fr 1fr;
		}
		.settings > section:first-child {
			grid-row: span 2;
		}
	}
	h2 {
		font-size: 1.15rem;
	}
	.two {
		display: grid;
		gap: var(--s-3);
	}
	@media (min-width: 520px) {
		.two {
			grid-template-columns: 1fr 1fr;
		}
	}
	.fs .segmented {
		align-self: flex-start;
	}
	.fs {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.switch > span:not(.visually-hidden) {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 500;
	}
	.switch.disabled {
		opacity: 0.6;
	}
	.credit {
		display: flex;
		align-items: center;
		gap: 6px;
		margin: 0;
	}
	.credit a {
		color: inherit;
	}
	.range-row {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		color: var(--text-muted);
	}
	input[type='range'] {
		flex: 1;
		accent-color: var(--primary);
		height: 28px;
	}
	.schedule {
		min-width: 0;
		border: 1px solid var(--border);
		border-radius: var(--r);
		padding: var(--s-3);
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		transition: opacity var(--dur) var(--ease);
	}
	.schedule.off .times,
	.schedule.off .days,
	.schedule.off .mode {
		opacity: 0.5;
	}
	.sched-top {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	@media (max-width: 480px) {
		/* On phones the two time fields get their own row so they never truncate. */
		.sched-top {
			flex-wrap: wrap;
			justify-content: space-between;
		}
		.sched-top .times {
			order: 3;
			flex: 1 1 100%;
		}
	}
	.switch.compact {
		min-height: 0;
	}
	.times {
		min-width: 0;
		display: flex;
		align-items: flex-end;
		gap: var(--s-2);
		flex: 1;
	}
	.times label {
		flex: 1;
		min-width: 0;
		max-width: 140px;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.time {
		width: 100%;
		min-width: 0;
		min-height: 40px;
		padding: 6px 10px;
		font-variant-numeric: tabular-nums;
	}
	.arrow {
		padding-bottom: 9px;
		color: var(--text-faint);
	}
	.days {
		display: flex;
		gap: 4px;
		flex-wrap: wrap;
	}
	.day {
		min-width: 40px;
		height: 36px;
		padding: 0 8px;
		border-radius: var(--r-full);
		border: 1px solid var(--border-strong);
		background: var(--surface);
		font-weight: 600;
		font-size: 0.8rem;
		cursor: pointer;
	}
	.day[aria-pressed='true'] {
		background: var(--primary);
		border-color: var(--primary);
		color: var(--on-primary);
	}
	.mode {
		align-self: flex-start;
	}
	.empty-sched {
		padding: var(--s-3);
		border-radius: var(--r);
		background: var(--surface-2);
		text-align: center;
	}
	.savebar {
		position: fixed;
		z-index: 40;
		left: 50%;
		transform: translateX(-50%);
		bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom) + 12px);
		width: min(560px, calc(100vw - 24px));
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 10px 8px 16px;
		border-radius: var(--r-lg);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow-lg);
	}
	@media (min-width: 860px) {
		.savebar {
			bottom: 24px;
		}
	}
</style>
