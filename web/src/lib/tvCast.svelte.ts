// Drives a slideshow on a TV from this browser (README §5.8):
//   cast         Google Cast Web Sender SDK + Default Media Receiver (Chromium). The sender loads one
//                signed media URL per slide on its own timer.
//   presentation Presentation API: the TV shows the /tv page; commands go over the connection.
//   remote       Remote Playback API for a single video.
import { api, apiUrl, call, ApiError } from './api/client';
import {
	castMedia,
	DEFAULT_TV_INTERVAL,
	detectSupport,
	dwellMs,
	keepPlace,
	linkBody,
	needsRefresh,
	parseCommand,
	step,
	type TvLink,
	type TvScope,
	type TvSlide,
	type TvSlideshow,
	type TvState
} from './tv';

export type TvMode = 'cast' | 'presentation' | 'remote';
type Status = 'idle' | 'connecting' | 'live';

const CAST_SDK = 'https://www.gstatic.com/cv/js/sender/v1/cast_sender.js?loadCastFramework=1';

/* eslint-disable @typescript-eslint/no-explicit-any -- the Cast SDK ships no types */
type CastWindow = Window & { cast?: any; chrome?: any; __onGCastApiAvailable?: (ok: boolean) => void };

let sdk: Promise<boolean> | null = null;

/** Load the Cast framework once; resolves false when it isn't available (non-Chromium, offline, blocked). */
export function loadCastSdk(timeoutMs = 8000): Promise<boolean> {
	if (typeof window === 'undefined') return Promise.resolve(false);
	const w = window as CastWindow;
	if (w.cast?.framework) return Promise.resolve(true);
	sdk ??= new Promise<boolean>((resolve) => {
		const timer = setTimeout(() => resolve(false), timeoutMs);
		w.__onGCastApiAvailable = (ok) => {
			clearTimeout(timer);
			resolve(ok && !!w.cast?.framework);
		};
		const s = document.createElement('script');
		s.src = CAST_SDK;
		s.async = true;
		s.onerror = () => {
			clearTimeout(timer);
			resolve(false);
		};
		document.head.appendChild(s);
	});
	return sdk;
}

export async function fetchSlideshow(token: string): Promise<TvSlideshow> {
	let res: Response;
	try {
		res = await fetch(apiUrl(`/v1/tv/${encodeURIComponent(token)}`), { headers: { Accept: 'application/json' } });
	} catch (e) {
		throw new ApiError(0, 'network', 'Network error', e instanceof Error ? e.message : undefined);
	}
	let body: unknown = null;
	try {
		body = await res.json();
	} catch {
		/* not JSON */
	}
	if (!res.ok) throw ApiError.fromResponse(res, body);
	return body as TvSlideshow;
}

export async function createTvLink(frameId: string, scope: TvScope, interval: number, purpose: 'tv' | 'cast'): Promise<TvLink> {
	return call(api.POST('/v1/frames/{frameId}/tv-links', { params: { path: { frameId } }, body: linkBody(scope, interval, purpose) }));
}

export async function revokeTvLink(frameId: string, linkId: string): Promise<void> {
	await call(api.DELETE('/v1/frames/{frameId}/tv-links/{linkId}', { params: { path: { frameId, linkId } } }));
}

export class TvController {
	status = $state<Status>('idle');
	mode = $state<TvMode | null>(null);
	slides = $state<TvSlide[]>([]);
	index = $state(0);
	playing = $state(true);
	interval = $state(DEFAULT_TV_INTERVAL);
	device = $state<string | null>(null);
	error = $state<unknown>(null);
	frameName = '';

	readonly support = detectSupport(typeof window === 'undefined' ? undefined : (window as never));

	#frameId = '';
	#link: TvLink | null = null;
	#show: TvSlideshow | null = null;
	#timer: ReturnType<typeof setTimeout> | null = null;
	#cast: any = null;
	#conn: PresentationConnection | null = null;
	#video: HTMLVideoElement | null = null;
	#unlisten: (() => void)[] = [];

	get current(): TvSlide | undefined {
		return this.slides[this.index];
	}

	/** Start showing `scope` of a frame on a TV with the given mode. */
	async start(mode: TvMode, frameId: string, scope: TvScope, interval: number): Promise<void> {
		await this.stop();
		this.error = null;
		this.status = 'connecting';
		this.mode = mode;
		this.interval = interval;
		this.playing = true;
		this.#frameId = frameId;
		try {
			this.#link = await createTvLink(frameId, scope, interval, 'cast');
			if (mode === 'presentation') return await this.#startPresentation(this.#link);
			const show = await fetchSlideshow(this.#link.token);
			this.#apply(show, 0);
			if (this.slides.length === 0) throw new ApiError(404, 'not_found', 'Nothing to show');
			if (mode === 'cast') await this.#startCast();
			else await this.#startRemote();
		} catch (e) {
			if (e !== 'cancel') console.warn('show on TV failed', e);
			this.error = e;
			await this.stop();
			throw e;
		}
	}

	async stop(): Promise<void> {
		this.#clearTimer();
		for (const off of this.#unlisten.splice(0)) off();
		const link = this.#link;
		this.#link = null;
		try {
			if (this.#cast) (window as CastWindow).cast.framework.CastContext.getInstance().endCurrentSession(true);
		} catch {
			/* already gone */
		}
		this.#cast = null;
		try {
			this.#conn?.terminate();
		} catch {
			/* already closed */
		}
		this.#conn = null;
		if (this.#video) {
			this.#video.pause();
			this.#video.removeAttribute('src');
			this.#video.load();
			this.#video = null;
		}
		this.status = 'idle';
		this.mode = null;
		this.device = null;
		// The cast link was only for this session.
		if (link) void revokeTvLink(this.#frameId, link.id).catch(() => {});
	}

	next() {
		this.#go(1);
	}

	prev() {
		this.#go(-1);
	}

	toggle() {
		this.playing = !this.playing;
		if (this.mode === 'presentation') return this.#send({ cmd: this.playing ? 'play' : 'pause' });
		if (this.mode === 'remote') return void (this.playing ? this.#video?.play() : this.#video?.pause());
		if (this.current?.kind === 'video') this.#castPlayPause();
		if (this.playing) this.#schedule();
		else this.#clearTimer();
	}

	setInterval(seconds: number) {
		this.interval = seconds;
		if (this.mode === 'presentation') return this.#send({ cmd: 'interval', seconds });
		if (this.playing && this.current?.kind !== 'video') this.#schedule();
	}

	// ---------------------------------------------------------------- shared

	#apply(show: TvSlideshow, fallback: number) {
		const prev = this.current;
		this.#show = show;
		this.frameName = show.frame_name;
		this.slides = show.slides;
		this.index = keepPlace(prev, show.slides, fallback);
	}

	#clearTimer() {
		if (this.#timer) clearTimeout(this.#timer);
		this.#timer = null;
	}

	#schedule() {
		this.#clearTimer();
		if (!this.playing || this.mode !== 'cast') return;
		// Videos advance when the receiver reports the end; the timer is only a safety net.
		const ms = this.current?.kind === 'video' ? dwellMs(this.current, this.interval) + 10_000 : dwellMs(this.current, this.interval);
		this.#timer = setTimeout(() => this.#go(1), ms);
	}

	async #go(dir: 1 | -1) {
		if (this.mode === 'presentation') return this.#send({ cmd: dir === 1 ? 'next' : 'prev' });
		if (this.mode !== 'cast' || this.slides.length === 0) return;
		this.index = step(this.index, this.slides.length, dir);
		if (this.#show && this.#link && needsRefresh(this.#show)) {
			try {
				this.#apply(await fetchSlideshow(this.#link.token), this.index);
			} catch (e) {
				this.error = e;
				return this.stop();
			}
		}
		await this.#loadCurrent();
	}

	// ---------------------------------------------------------------- Google Cast

	async #startCast() {
		if (!(await loadCastSdk())) throw new ApiError(0, 'network', 'Cast unavailable');
		const w = window as CastWindow;
		const fw = w.cast.framework;
		const ctx = fw.CastContext.getInstance();
		ctx.setOptions({
			receiverApplicationId: w.chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID,
			autoJoinPolicy: w.chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED
		});
		await ctx.requestSession();
		const session = ctx.getCurrentSession();
		if (!session) throw new ApiError(0, 'network', 'No cast session');
		this.#cast = session;
		this.device = session.getCastDevice?.()?.friendlyName ?? null;
		const onState = (e: { sessionState: string }) => {
			if (e.sessionState === fw.SessionState.SESSION_ENDED) void this.stop();
		};
		ctx.addEventListener(fw.CastContextEventType.SESSION_STATE_CHANGED, onState);
		this.#unlisten.push(() => ctx.removeEventListener(fw.CastContextEventType.SESSION_STATE_CHANGED, onState));
		this.status = 'live';
		await this.#loadCurrent();
	}

	async #loadCurrent() {
		const s = this.current;
		if (!s || !this.#cast) return;
		const w = window as CastWindow;
		const m = castMedia(s, this.frameName);
		const media = w.chrome.cast.media;
		const info = new media.MediaInfo(m.contentId, m.contentType);
		info.streamType = media.StreamType.BUFFERED;
		const meta = m.metadataType === 4 ? new media.PhotoMediaMetadata() : new media.GenericMediaMetadata();
		meta.title = m.title;
		if (m.metadataType === 4) meta.artist = m.subtitle;
		else meta.subtitle = m.subtitle;
		if (m.image) meta.images = [new w.chrome.cast.Image(m.image)];
		if (m.metadataType === 4) {
			meta.width = m.width;
			meta.height = m.height;
			if (m.creationDateTime) meta.creationDateTime = m.creationDateTime;
		}
		info.metadata = meta;
		const req = new media.LoadRequest(info);
		req.autoplay = this.playing;
		try {
			await this.#cast.loadMedia(req);
		} catch (e) {
			// One bad item shouldn't end the show: skip it on the next tick.
			console.warn('cast load failed', e);
		}
		if (s.kind === 'video') this.#watchVideoEnd();
		this.#schedule();
	}

	#watchVideoEnd() {
		const ms = this.#cast?.getMediaSession?.();
		if (!ms) return;
		const w = window as CastWindow;
		const listener = (alive: boolean) => {
			if (ms.playerState === w.chrome.cast.media.PlayerState.IDLE && ms.idleReason === w.chrome.cast.media.IdleReason.FINISHED) {
				ms.removeUpdateListener(listener);
				if (this.playing) void this.#go(1);
			} else if (!alive) ms.removeUpdateListener(listener);
		};
		ms.addUpdateListener(listener);
	}

	#castPlayPause() {
		const ms = this.#cast?.getMediaSession?.();
		if (!ms) return;
		const w = window as CastWindow;
		if (this.playing) ms.play(new w.chrome.cast.media.PlayRequest(), () => {}, () => {});
		else ms.pause(new w.chrome.cast.media.PauseRequest(), () => {}, () => {});
	}

	// ---------------------------------------------------------------- Presentation API

	async #startPresentation(link: TvLink) {
		const url = `${link.url}&receiver=1&i=${this.interval}`;
		const req = new PresentationRequest([url]);
		const conn = await req.start();
		this.#conn = conn;
		const onMessage = (e: Event) => {
			try {
				const st = JSON.parse(String((e as MessageEvent).data)) as TvState;
				if (st.type !== 'state') return;
				this.index = st.index;
				this.playing = st.playing;
				this.interval = st.interval;
			} catch {
				/* ignore */
			}
		};
		const onClose = () => {
			if (this.#conn === conn) void this.stop();
		};
		conn.addEventListener('message', onMessage);
		conn.addEventListener('close', onClose);
		conn.addEventListener('terminate', onClose);
		this.#unlisten.push(() => {
			conn.removeEventListener('message', onMessage);
			conn.removeEventListener('close', onClose);
			conn.removeEventListener('terminate', onClose);
		});
		this.status = 'live';
		// Captions and thumbnails for the controls (the TV page fetches its own copy).
		void fetchSlideshow(link.token)
			.then((show) => this.#apply(show, 0))
			.catch(() => {});
	}

	#send(c: { cmd: string; seconds?: number }) {
		if (parseCommand(c) && this.#conn?.state === 'connected') this.#conn.send(JSON.stringify(c));
	}

	// ---------------------------------------------------------------- Remote Playback (one video)

	async #startRemote() {
		const s = this.current;
		if (!s || s.kind !== 'video') throw new ApiError(0, 'network', 'Remote playback needs a video');
		const v = document.createElement('video');
		v.src = s.url;
		v.preload = 'metadata';
		v.playsInline = true;
		this.#video = v;
		const remote = (v as HTMLVideoElement & { remote: RemotePlayback }).remote;
		await remote.prompt();
		const onDisconnect = () => {
			if (this.#video === v) void this.stop();
		};
		remote.addEventListener('disconnect', onDisconnect);
		this.#unlisten.push(() => remote.removeEventListener('disconnect', onDisconnect));
		this.device = null;
		this.status = 'live';
		await v.play().catch(() => (this.playing = false));
	}
}

/** One TV session per tab: it keeps running when the dialog closes (see TvMiniBar). */
export const tvCast = new TvController();
