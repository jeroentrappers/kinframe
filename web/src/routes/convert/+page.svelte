<script lang="ts">
	// Convert a stock Frameo frame into a kinframe frame, or back (issue #1, tools/convert/README.md). Talks to the
	// frame over WebUSB ADB; the steps and commands come from $lib/convert/plan (shared with the CLI).
	import { onMount } from 'svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, LOCALES, t, type Locale, type MessageKey } from '$lib/i18n/index.svelte';
	import { toasts } from '$lib/toast.svelte';
	import { checkApk, formatFingerprint, isZip, sha256Hex } from '$lib/convert/apk';
	import { resourceProblem } from '$lib/convert/rsce';
	import {
		BOOT_ASSETS,
		BOOT_BRAND,
		DEFAULT_SERVER,
		FRAME_PACKAGE,
		FRAMEO_PACKAGE,
		RELEASE_CERT_SHA256,
		RELEASES_REPO,
		bootAnimationPossible,
		bootLogoPossible,
		canConvert,
		canRevert,
		normalizeServerUrl,
		planConvert,
		planRevert,
		type Identification,
		type Issue,
		type PackageState,
		type Step,
		type StepId
	} from '$lib/convert/plan';
	import { identifyFrame, runPlan, type BootAssets, type StepStatus } from '$lib/convert/runner';
	import type { WebUsbFrame } from '$lib/convert/adb';
	import Icon from '$components/Icon.svelte';
	import LanguagePicker from '$components/LanguagePicker.svelte';
	import Logo from '$components/Logo.svelte';

	type Mode = 'convert' | 'revert';
	type Phase = 'connect' | 'ready' | 'running' | 'finished';

	const REPO_URL = `https://github.com/${RELEASES_REPO}`;
	const CLI_URL = `${REPO_URL}/tree/main/tools/convert#readme`;
	const DEVICES_URL = `${REPO_URL}/blob/main/docs/hardware/supported-devices.md`;

	let adb: typeof import('$lib/convert/adb') | null = $state(null);
	let supported = $state(true);

	let mode = $state<Mode>('convert');
	let dryRun = $state(false);
	let phase = $state<Phase>('connect');

	let frame = $state.raw<WebUsbFrame | null>(null);
	let connecting = $state(false);
	let connectError = $state<string | null>(null);
	let checking = $state(false);
	let id = $state<Identification | null>(null);
	let force = $state(false);

	// Options
	const thisServer = typeof location !== 'undefined' ? (normalizeServerUrl(location.origin) ?? DEFAULT_SERVER) : DEFAULT_SERVER;
	let serverChoice = $state<'this' | 'other'>('this');
	let otherServer = $state('');
	let language = $state<Locale | ''>(i18n.locale);
	let backup = $state(true);
	let keepData = $state(false);
	let restoreFile = $state<File | null>(null);
	let restoreBytes = $state.raw<Uint8Array | undefined>(undefined);
	let bootBranding = $state(true);
	let resourceFile = $state<File | null>(null);
	let resourceBytes = $state.raw<Uint8Array | undefined>(undefined);
	let animFile = $state<File | null>(null);
	let animBytes = $state.raw<Uint8Array | undefined>(undefined);

	// Progress
	let steps = $state<Step[]>([]);
	let statuses = $state<Partial<Record<StepId, { status: StepStatus; detail?: string }>>>({});
	let backups = $state<{ name: string; url: string; size: number }[]>([]);
	let verified = $state<string | null>(null);
	let outcome = $state<{ ok: boolean; failed: StepId[] } | null>(null);
	let logLines = $state<string[]>([]);
	let logOpen = $state(false);

	const server = $derived(serverChoice === 'this' ? thisServer : normalizeServerUrl(otherServer));
	const serverHost = $derived(new URL(thisServer).host);
	const allowed = $derived(id ? (mode === 'convert' ? canConvert(id, force) : canRevert(id)) : false);
	const bootPossible = $derived(id ? bootLogoPossible(id.facts) || bootAnimationPossible(id.facts) : false);
	const restoreFilesOk = $derived((!restoreFile || Boolean(restoreBytes)) && (!resourceFile || Boolean(resourceBytes)) && (!animFile || Boolean(animBytes)));
	const canStart = $derived(allowed && (mode === 'revert' || server !== null) && (mode === 'convert' || restoreFilesOk));
	const logText = $derived(logLines.join('\n'));

	onMount(() => {
		void import('$lib/convert/adb').then((m) => {
			adb = m;
			supported = m.webUsbSupported();
		});
		const warn = (e: BeforeUnloadEvent) => {
			if (phase === 'running') e.preventDefault();
		};
		window.addEventListener('beforeunload', warn);
		return () => {
			window.removeEventListener('beforeunload', warn);
			void frame?.close();
			for (const b of backups) URL.revokeObjectURL(b.url);
		};
	});

	function log(line: string) {
		logLines.push(line);
	}

	function resetLog() {
		logLines = [
			`kinframe convert tool (${location.origin}/convert), ${new Date().toISOString()}`,
			`browser: ${navigator.userAgent}`
		];
	}

	async function connect() {
		if (!adb || connecting) return;
		connectError = null;
		connecting = true;
		try {
			const device = await adb.chooseDevice();
			if (!device) return; // dialog cancelled
			resetLog();
			log(`usb: ${device.name} serial ${device.serial}`);
			const f = await adb.connect(device);
			frame = f;
			void f.disconnected.then(() => {
				if (frame === f && phase !== 'running') {
					frame = null;
					id = null;
					phase = phase === 'finished' ? 'finished' : 'connect';
				}
			});
			checking = true;
			id = await identifyFrame(f, { log });
			phase = 'ready';
		} catch (e) {
			const kind = adb.connectError(e);
			connectError =
				kind === 'busy' ? t('convert.errBusy') : kind === 'denied' ? t('convert.errDenied') : t('convert.errConnect', { error: e instanceof Error ? e.message : String(e) });
			log(`connect failed: ${e instanceof Error ? e.message : String(e)}`);
		} finally {
			connecting = false;
			checking = false;
		}
	}

	async function chooseRestore(e: Event) {
		const file = (e.currentTarget as HTMLInputElement).files?.[0] ?? null;
		restoreFile = file;
		restoreBytes = undefined;
		if (file) {
			const bytes = new Uint8Array(await file.arrayBuffer());
			restoreBytes = isZip(bytes) ? bytes : undefined;
		}
	}

	/** A backed-up resource image: only kept when it is a valid resource image with a device tree. */
	async function chooseResource(e: Event) {
		const file = (e.currentTarget as HTMLInputElement).files?.[0] ?? null;
		resourceFile = file;
		resourceBytes = undefined;
		if (file) {
			const bytes = new Uint8Array(await file.arrayBuffer());
			const problem = resourceProblem(bytes);
			if (problem) log(`${file.name}: ${problem}`);
			else resourceBytes = bytes;
		}
	}

	async function chooseAnimation(e: Event) {
		const file = (e.currentTarget as HTMLInputElement).files?.[0] ?? null;
		animFile = file;
		animBytes = undefined;
		if (file) {
			const bytes = new Uint8Array(await file.arrayBuffer());
			animBytes = isZip(bytes) ? bytes : undefined;
		}
	}

	function saveFile(name: string, data: Uint8Array, type = 'application/octet-stream') {
		const url = URL.createObjectURL(new Blob([data as BlobPart], { type }));
		const a = document.createElement('a');
		a.href = url;
		a.download = name;
		a.click();
		return url;
	}

	/** Gets the newest release from this server (it mirrors the GitHub releases) and checks it before anything is installed. */
	async function fetchRelease(): Promise<Uint8Array> {
		const sdk = id?.facts.sdk ?? 0;
		const res = await fetch(`/v1/frame-app/latest?sdk=${sdk}`, { cache: 'no-store' });
		if (res.status === 204 || res.status === 404) throw new Error(t('convert.errNoRelease'));
		if (!res.ok) throw new Error(`release check failed: HTTP ${res.status}`);
		const rel = (await res.json()) as { version_name: string; version_code: number; sha256: string; size: number };
		log(`release ${rel.version_name} (${rel.version_code}), ${rel.size} bytes, sha256 ${rel.sha256}`);
		const apk = await fetch(`/v1/frame-app/apk/${rel.version_code}`);
		if (!apk.ok) throw new Error(`download failed: HTTP ${apk.status}`);
		const bytes = new Uint8Array(await apk.arrayBuffer());
		const check = await checkApk(bytes, { pinnedCert: RELEASE_CERT_SHA256, sha256: rel.sha256, deviceSdk: sdk });
		log(`downloaded ${bytes.length} bytes, sha256 ${check.sha256} (${check.sha256Ok ? 'matches' : 'does not match'})`);
		log(`v1 certificates: ${check.v1Certs.map(formatFingerprint).join(', ') || 'none'}`);
		log(`v2/v3 certificates: ${check.v2Certs.map(formatFingerprint).join(', ') || 'none'}`);
		log(`pinned certificate: ${formatFingerprint(RELEASE_CERT_SHA256)}`);
		if (!check.ok) throw new Error(`${t('convert.errVerify')} (${check.problem})`);
		verified = rel.version_name;
		return bytes;
	}

	/** The start-up branding from this server's static files, checked against its manifest. */
	async function fetchBoot(): Promise<BootAssets> {
		const base = `${BOOT_ASSETS}/${BOOT_BRAND}`;
		const res = await fetch(`${base}/manifest.json`, { cache: 'no-store' });
		if (!res.ok) throw new Error(`boot branding: HTTP ${res.status}`);
		const manifest = (await res.json()) as Record<'logo' | 'bootanimation', { file: string; sha256: string }>;
		const get = async (entry: { file: string; sha256: string }) => {
			const r = await fetch(`${base}/${entry.file}`);
			if (!r.ok) throw new Error(`boot branding: HTTP ${r.status}`);
			const bytes = new Uint8Array(await r.arrayBuffer());
			if ((await sha256Hex(bytes)) !== entry.sha256) throw new Error(`boot branding: ${entry.file} does not match its sha256`);
			return bytes;
		};
		return { logo: await get(manifest.logo), bootanimation: await get(manifest.bootanimation) };
	}

	async function start() {
		if (!frame || !id || !adb || !canStart) return;
		const a = adb;
		const current = frame;
		steps =
			mode === 'convert'
				? planConvert(id, { server: server!, language: language || undefined, backup, bootBranding: bootBranding && bootPossible })
				: planRevert(id, { keepData, restoreFrameo: Boolean(restoreBytes), restoreBootLogo: Boolean(resourceBytes), restoreBootAnimation: Boolean(animBytes) });
		statuses = Object.fromEntries(steps.map((s) => [s.id, { status: 'pending' as StepStatus }]));
		outcome = null;
		verified = null;
		log(`\n# ${mode}${dryRun ? ' (dry run)' : ''}${mode === 'convert' ? `, server ${server}, language ${language || '(frame asks)'}, backup ${backup}, boot branding ${bootBranding && bootPossible}` : `, keep data ${keepData}, restore apk ${restoreFile?.name ?? 'no'}, restore boot logo ${resourceFile?.name ?? 'no'}, restore boot animation ${animFile?.name ?? 'no'}`}${force ? ', forced' : ''}`);
		phase = 'running';
		try {
			outcome = await runPlan(
				current,
				steps,
				{
					dryRun,
					log,
					step: (sid, status, detail) => (statuses[sid] = { status, detail }),
					file: (name, data) => backups.push({ name, size: data.length, url: saveFile(name, data) }),
					fetchRelease,
					restoreApk: () => restoreBytes,
					fetchBoot,
					restoreBoot: () => ({ resource: resourceBytes, bootanimation: animBytes }),
					reconnect: async () => {
						const next = await a.reconnect(current.serial);
						frame = next;
						return next;
					}
				},
				{ continueOnError: mode === 'revert' }
			);
		} catch (e) {
			log(`error: ${e instanceof Error ? e.message : String(e)}`);
			outcome = { ok: false, failed: [] };
		}
		phase = 'finished';
		logOpen = !outcome.ok || dryRun;
	}

	function startOver() {
		phase = frame ? 'ready' : 'connect';
		steps = [];
		statuses = {};
		outcome = null;
		force = false;
		if (frame) {
			const f = frame;
			checking = true;
			void identifyFrame(f, { log })
				.then((r) => (id = r))
				.catch(() => {
					frame = null;
					id = null;
					phase = 'connect';
				})
				.finally(() => (checking = false));
		}
	}

	async function copyLog() {
		try {
			await navigator.clipboard.writeText(logText);
			toasts.success(t('convert.logCopied'));
		} catch {
			logOpen = true;
		}
	}

	function downloadLog() {
		const url = saveFile(`kinframe-convert-${new Date().toISOString().replace(/[:.]/g, '-')}.log`, new TextEncoder().encode(logText), 'text/plain');
		setTimeout(() => URL.revokeObjectURL(url), 10_000);
	}

	const ISSUE_TEXT: Record<Exclude<Issue, 'model'>, MessageKey> = { sdk: 'convert.issueSdk', abi: 'convert.issueAbi', root: 'convert.issueRoot' };

	function pkgText(p: PackageState | undefined): string {
		if (!p?.installed) return t('convert.notInstalled');
		const v = p.versionName ?? '?';
		return p.enabled === 'disabled' ? t('convert.turnedOff', { version: v }) : v;
	}

	const STATUS_ICON: Record<StepStatus, 'check' | 'x' | 'alert' | 'eye' | 'clock' | 'more'> = {
		pending: 'clock',
		running: 'clock',
		done: 'check',
		warn: 'alert',
		failed: 'x',
		skipped: 'more',
		dry: 'eye'
	};

	const failedDetail = $derived(outcome?.failed.map((f) => statuses[f]?.detail ?? '').join(' ') ?? '');
	const sizeText = (n: number) => new Intl.NumberFormat(i18n.tag, { style: 'unit', unit: n > 1e6 ? 'megabyte' : 'kilobyte', maximumFractionDigits: 1 }).format(n > 1e6 ? n / 1e6 : n / 1e3);
	const addUrl = $derived(mode === 'convert' && server && server !== thisServer ? `${server}/frames/add` : '/frames/add');
</script>

<svelte:head>
	<title>{t('convert.pageTitle')} · {discovery.name || 'kinframe'}</title>
</svelte:head>

<div class="convert-page">
	<header class="container top">
		<a href="/" class="home" aria-label="kinframe"><Logo size={30} /></a>
		<div class="spacer"></div>
		<LanguagePicker />
	</header>

	<main id="main" class="container narrow">
		<div class="head">
			<h1>{t('convert.title')}</h1>
			<p class="lede">{t('convert.lede')}</p>
			<p class="links">
				<a href={DEVICES_URL} target="_blank" rel="noopener">{t('convert.devicesLink')}</a>
				<span aria-hidden="true">·</span>
				<a href={CLI_URL} target="_blank" rel="noopener">{t('convert.cliLink')}</a>
			</p>
		</div>

		{#if !supported}
			<div class="callout callout-warn" role="alert">
				<Icon name="alert" size={20} />
				<div>
					<strong>{t('convert.noWebUsbTitle')}</strong>
					<p>{t('convert.noWebUsbBody')}</p>
					<p><a href={CLI_URL} target="_blank" rel="noopener">{t('convert.cliLink')}</a></p>
				</div>
			</div>
		{:else}
			<fieldset class="segmented mode" disabled={phase === 'running'}>
				<legend class="visually-hidden">{t('convert.modeLabel')}</legend>
				<label class:active={mode === 'convert'}><input type="radio" bind:group={mode} value="convert" />{t('convert.modeConvert')}</label>
				<label class:active={mode === 'revert'}><input type="radio" bind:group={mode} value="revert" />{t('convert.modeRevert')}</label>
			</fieldset>

			<ol class="flow">
				<li class="card card-pad">
					<h2><span class="num-badge">1</span>{t('convert.prepareTitle')}</h2>
					<p>{t('convert.prepareBody')}</p>
					<p class="hint">{t('convert.prepareHint')}</p>
				</li>

				<li class="card card-pad">
					<h2><span class="num-badge">2</span>{t('convert.connectTitle')}</h2>
					{#if !id}
						<button class="btn btn-primary btn-lg" onclick={connect} disabled={!adb || connecting}>
							{#if connecting}<span class="spinner" aria-hidden="true"></span>{checking ? t('convert.checking') : t('convert.connecting')}{:else}<Icon
									name="usb"
									size={18}
								/>{t('convert.connect')}{/if}
						</button>
						{#if connecting && !checking}<p class="hint" role="status">{t('convert.allowOnFrame')}</p>{/if}
						{#if connectError}<p class="error-text" role="alert">{connectError}</p>{/if}
					{:else}
						<p class="ok-line"><Icon name="check" size={18} />{id.facts.brand} {id.facts.model} · {id.facts.serial || frame?.serial}</p>
					{/if}
				</li>

				{#if id}
					<li class="card card-pad">
						<h2><span class="num-badge">3</span>{t('convert.checkTitle')}</h2>
						<dl class="facts">
							<dt>{t('convert.factModel')}</dt>
							<dd>{id.facts.brand} {id.facts.model} <span class="faint">({id.facts.device})</span></dd>
							<dt>{t('convert.factAndroid')}</dt>
							<dd>{id.facts.release} <span class="faint">(API {id.facts.sdk})</span></dd>
							<dt>{t('convert.factRoot')}</dt>
							<dd>{id.facts.root ? t('convert.yes') : t('convert.no')}</dd>
							<dt>{t('convert.factFrameo')}</dt>
							<dd>{pkgText(id.facts.packages[FRAMEO_PACKAGE])}</dd>
							<dt>{t('convert.factKinframe')}</dt>
							<dd>{pkgText(id.facts.packages[FRAME_PACKAGE])}</dd>
						</dl>
						{#if mode === 'convert'}
							{#if id.verdict === 'supported'}
								<p class="callout callout-ok"><Icon name="check" size={20} />{t('convert.verdictSupported')}</p>
							{:else if id.verdict === 'untested'}
								<div class="callout callout-warn">
									<Icon name="alert" size={20} />
									<div class="stack-sm">
										<p>{t('convert.verdictUntested')}</p>
										<label class="check"><input type="checkbox" bind:checked={force} disabled={phase === 'running'} />{t('convert.force')}</label>
									</div>
								</div>
							{:else}
								<div class="callout callout-error" role="alert">
									<Icon name="x" size={20} />
									<div>
										<p><strong>{t('convert.verdictUnsupported')}</strong></p>
										<ul>
											{#each id.issues.filter((i) => i !== 'model') as issue (issue)}
												<li>{t(ISSUE_TEXT[issue as Exclude<Issue, 'model'>])}</li>
											{/each}
										</ul>
									</div>
								</div>
							{/if}
						{:else if !id.facts.root}
							<p class="callout callout-error" role="alert"><Icon name="x" size={20} />{t('convert.revertNeedsRoot')}</p>
						{/if}
					</li>

					{#if allowed}
						<li class="card card-pad">
							<h2><span class="num-badge">4</span>{t('convert.optionsTitle')}</h2>
							<fieldset class="stack" disabled={phase === 'running'}>
								{#if mode === 'convert'}
									<div class="field">
										<span class="label" id="server-label">{t('convert.serverLabel')}</span>
										<div class="stack-sm" role="radiogroup" aria-labelledby="server-label">
											<label class="check"><input type="radio" bind:group={serverChoice} value="this" />{t('convert.serverThis', { host: serverHost })}</label>
											<label class="check"><input type="radio" bind:group={serverChoice} value="other" />{t('convert.serverOther')}</label>
											{#if serverChoice === 'other'}
												<input
													class="input"
													type="url"
													inputmode="url"
													autocomplete="url"
													placeholder={t('convert.serverPlaceholder')}
													bind:value={otherServer}
													aria-invalid={otherServer.trim() !== '' && !server}
													aria-describedby="server-help"
												/>
												{#if otherServer.trim() !== '' && !server}<p class="error-text" id="server-help">{t('convert.serverInvalid')}</p>{/if}
											{/if}
										</div>
									</div>
									<div class="field">
										<label class="label" for="frame-lang">{t('convert.languageLabel')}</label>
										<select id="frame-lang" class="select" bind:value={language}>
											<option value="">{t('convert.languageAsk')}</option>
											{#each LOCALES as l (l.id)}
												<option value={l.id} lang={l.id}>{l.label}</option>
											{/each}
										</select>
									</div>
									<div>
										<label class="check"><input type="checkbox" bind:checked={backup} />{t('convert.backup')}</label>
										<p class="hint indent">{t('convert.backupHint')}</p>
									</div>
									{#if bootPossible}
										<div>
											<label class="check"><input type="checkbox" bind:checked={bootBranding} />{t('convert.bootBranding')}</label>
											<p class="hint indent">{t('convert.bootBrandingHint')}</p>
											{#if bootBranding && bootLogoPossible(id.facts)}
												<p class="callout callout-warn indent-callout"><Icon name="alert" size={20} />{t('convert.bootBrandingWarn')}</p>
											{/if}
										</div>
									{/if}
								{:else}
									<div>
										<label class="check"><input type="checkbox" bind:checked={keepData} />{t('convert.keepData')}</label>
										<p class="hint indent">{t('convert.keepDataHint')}</p>
									</div>
									<div class="field">
										<label class="label" for="restore-apk">{t('convert.restoreApk')} <span class="faint">({t('common.optional')})</span></label>
										<input id="restore-apk" type="file" accept=".apk,application/vnd.android.package-archive" onchange={chooseRestore} />
										<p class="hint">{t('convert.restoreApkHint')}</p>
									</div>
									{#if bootLogoPossible(id.facts)}
										<div class="field">
											<label class="label" for="restore-resource">{t('convert.restoreBootLogo')} <span class="faint">({t('common.optional')})</span></label>
											<input id="restore-resource" type="file" accept=".img,application/octet-stream" onchange={chooseResource} />
											{#if resourceFile && !resourceBytes}<p class="error-text" role="alert">{t('convert.restoreBootInvalid')}</p>{/if}
										</div>
									{/if}
									{#if bootAnimationPossible(id.facts)}
										<div class="field">
											<label class="label" for="restore-anim">{t('convert.restoreBootAnimation')} <span class="faint">({t('common.optional')})</span></label>
											<input id="restore-anim" type="file" accept=".zip,application/zip" onchange={chooseAnimation} />
										</div>
									{/if}
									{#if bootPossible}<p class="hint">{t('convert.restoreBootHint')}</p>{/if}
								{/if}
								<label class="check"><input type="checkbox" bind:checked={dryRun} />{t('convert.dryRun')}</label>
							</fieldset>
							{#if phase === 'ready'}
								<button class="btn btn-primary btn-lg go" onclick={start} disabled={!canStart}>
									{dryRun ? t('convert.startDry') : mode === 'convert' ? t('convert.start') : t('convert.startRevert')}
								</button>
							{/if}
						</li>
					{/if}
				{/if}

				{#if steps.length}
					<li class="card card-pad" aria-live="polite">
						<h2><span class="num-badge">5</span>{t('convert.progressTitle')}</h2>
						{#if phase === 'running'}<p class="callout"><Icon name="info" size={20} />{t('convert.keepConnected')}</p>{/if}
						<ul class="steps">
							{#each steps as s (s.id)}
								{@const st = statuses[s.id]?.status ?? 'pending'}
								<li class="step-row {st}">
									{#if st === 'running'}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name={STATUS_ICON[st]} size={18} />{/if}
									<span class="step-name">{t(`convert.step.${s.id}` as MessageKey)}</span>
									<span class="step-status">{t(`convert.status.${st}` as MessageKey)}</span>
									{#if s.id === 'download' && verified}<span class="step-detail ok">{t('convert.verified', { version: verified })}</span>{/if}
									{#if statuses[s.id]?.detail}<span class="step-detail">{statuses[s.id]?.detail}</span>{/if}
								</li>
							{/each}
						</ul>
						{#if backups.length}
							<div class="backups">
								<h3>{t('convert.backupFiles')}</h3>
								<ul>
									{#each backups as b (b.name)}
										<li><a href={b.url} download={b.name}><Icon name="download" size={16} />{b.name}</a> <span class="faint">{sizeText(b.size)}</span></li>
									{/each}
								</ul>
							</div>
						{/if}
					</li>
				{/if}
			</ol>

			{#if phase === 'finished' && outcome}
				<section class="card card-pad result" role="status">
					{#if dryRun && outcome.ok}
						<h2>{t('convert.dryDoneTitle')}</h2>
						<p>{t('convert.dryDoneBody')}</p>
					{:else if outcome.ok && mode === 'convert'}
						<span class="big-icon ok"><Icon name="sparkle" size={30} /></span>
						<h2>{t('convert.doneTitle')}</h2>
						<p>{t('convert.doneBody')}</p>
						<p>{t('convert.doneImport')}</p>
						<a class="btn btn-primary btn-lg" href={addUrl}><Icon name="plus" size={18} />{t('convert.doneAdd')}</a>
					{:else if outcome.ok}
						<span class="big-icon ok"><Icon name="check" size={30} /></span>
						<h2>{t('convert.doneRevertTitle')}</h2>
						<p>{t('convert.doneRevertBody')}</p>
					{:else}
						<span class="big-icon bad"><Icon name="alert" size={30} /></span>
						<h2>{t('convert.failedTitle')}</h2>
						<p>{mode === 'convert' ? t('convert.failedBody') : t('convert.failedRevertBody')}</p>
						{#if /UPDATE_INCOMPATIBLE/.test(failedDetail)}<p class="callout callout-warn">{t('convert.hintIncompatible')}</p>{/if}
					{/if}
					<button class="btn btn-ghost" onclick={startOver}><Icon name="refresh" size={18} />{t('convert.startOver')}</button>
				</section>
			{/if}

			{#if logLines.length}
				<details class="log card" bind:open={logOpen}>
					<summary>{t('convert.log')}</summary>
					<div class="log-actions">
						<button class="btn btn-sm" onclick={copyLog}><Icon name="copy" size={16} />{t('convert.copyLog')}</button>
						<button class="btn btn-sm" onclick={downloadLog}><Icon name="download" size={16} />{t('convert.downloadLog')}</button>
					</div>
					<pre dir="ltr">{logText}</pre>
				</details>
			{/if}
		{/if}
	</main>
</div>

<style>
	.convert-page {
		min-height: 100dvh;
		background:
			radial-gradient(1000px 500px at 90% -10%, color-mix(in srgb, var(--primary-soft) 60%, transparent), transparent 60%),
			var(--bg);
	}
	.top {
		display: flex;
		align-items: center;
		height: 72px;
	}
	.home {
		text-decoration: none;
	}
	.narrow {
		max-width: 760px;
		padding-bottom: var(--s-7);
		display: grid;
		gap: var(--s-4);
	}
	.head {
		margin-block: var(--s-3) var(--s-2);
	}
	h1 {
		font-size: clamp(1.8rem, 1.3rem + 2vw, 2.6rem);
		line-height: 1.1;
		letter-spacing: -0.02em;
	}
	.lede {
		margin-top: var(--s-2);
		font-size: 1.06rem;
		color: var(--text-muted);
	}
	.links {
		margin-top: var(--s-2);
		display: flex;
		gap: var(--s-2);
		flex-wrap: wrap;
	}
	.mode {
		border: 0;
		padding: 0;
		margin: 0;
		justify-self: start;
	}
	.mode input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.flow {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-4);
	}
	.flow > li {
		display: grid;
		gap: var(--s-3);
		justify-items: start;
	}
	h2 {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		font-size: 1.2rem;
	}
	.num-badge {
		flex: none;
		width: 28px;
		height: 28px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--primary);
		color: var(--on-primary);
		font-size: 0.95rem;
		font-weight: 700;
		font-family: var(--font-body);
	}
	.ok-line {
		display: inline-flex;
		align-items: center;
		gap: var(--s-2);
		color: var(--accent-text);
		font-weight: 600;
	}
	.facts {
		display: grid;
		grid-template-columns: max-content 1fr;
		gap: var(--s-1) var(--s-4);
		margin: 0;
	}
	.facts dt {
		color: var(--text-muted);
	}
	.facts dd {
		margin: 0;
		overflow-wrap: anywhere;
	}
	.callout ul {
		margin: var(--s-1) 0 0;
		padding-inline-start: 1.2em;
	}
	fieldset.stack {
		border: 0;
		padding: 0;
		margin: 0;
		width: 100%;
	}
	.check {
		display: inline-flex;
		align-items: flex-start;
		gap: var(--s-2);
		cursor: pointer;
	}
	.check input {
		margin-top: 0.25em;
		flex: none;
	}
	.indent {
		padding-inline-start: 1.6em;
	}
	.indent-callout {
		margin-top: var(--s-2);
		margin-inline-start: 1.6em;
	}
	.field {
		width: 100%;
	}
	.field .input,
	.field .select {
		max-width: 440px;
	}
	.go {
		margin-top: var(--s-2);
	}
	.steps {
		list-style: none;
		margin: 0;
		padding: 0;
		width: 100%;
		display: grid;
		gap: var(--s-2);
	}
	.step-row {
		display: grid;
		grid-template-columns: 22px 1fr auto;
		align-items: center;
		gap: var(--s-1) var(--s-3);
		color: var(--text-muted);
	}
	.step-row.running,
	.step-row.done,
	.step-row.dry,
	.step-row.warn,
	.step-row.failed {
		color: var(--text);
	}
	.step-row.done :global(svg) {
		color: var(--accent);
	}
	.step-row.warn :global(svg) {
		color: var(--warn);
	}
	.step-row.failed :global(svg) {
		color: var(--danger);
	}
	.step-status {
		font-size: 0.88rem;
		color: var(--text-muted);
	}
	.step-detail {
		grid-column: 2 / -1;
		font-size: 0.86rem;
		color: var(--text-muted);
		overflow-wrap: anywhere;
	}
	.step-row.failed .step-detail {
		color: var(--danger);
	}
	.step-detail.ok {
		color: var(--accent-text);
	}
	.backups {
		width: 100%;
	}
	.backups h3 {
		font-size: 1rem;
		margin-bottom: var(--s-2);
	}
	.backups ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-1);
	}
	.backups a {
		display: inline-flex;
		align-items: center;
		gap: var(--s-1);
		overflow-wrap: anywhere;
	}
	.result {
		display: grid;
		justify-items: center;
		text-align: center;
		gap: var(--s-3);
	}
	.result p {
		max-width: 48ch;
	}
	.big-icon {
		width: 64px;
		height: 64px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--surface-2);
	}
	.big-icon.ok {
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.big-icon.bad {
		background: var(--danger-soft);
		color: var(--danger);
	}
	.log {
		padding: var(--s-3) var(--s-4);
	}
	.log summary {
		cursor: pointer;
		font-weight: 600;
	}
	.log-actions {
		display: flex;
		gap: var(--s-2);
		flex-wrap: wrap;
		margin-block: var(--s-3);
	}
	.log pre {
		margin: 0;
		max-height: 420px;
		overflow: auto;
		padding: var(--s-3);
		border-radius: var(--r-sm);
		background: var(--surface-2);
		font-size: 0.8rem;
		line-height: 1.45;
		white-space: pre-wrap;
		overflow-wrap: anywhere;
		text-align: start;
	}
</style>
