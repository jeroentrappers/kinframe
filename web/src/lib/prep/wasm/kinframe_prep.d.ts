/* tslint:disable */
/* eslint-disable */

/**
 * A prepared master: JPEG bytes and their size.
 */
export class Prepared {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    /**
     * The JPEG (moved out; a second call returns an empty array).
     */
    takeJpeg(): Uint8Array;
    readonly height: number;
    readonly width: number;
}

/**
 * JS: prepare an encoded image. Throws with a short reason when it can't.
 */
export function prepareImage(input: Uint8Array | number[], max_edge: number, quality: number): Prepared;

/**
 * JS: prepare RGBA pixels. `icc` is the image's ICC profile if any; `p3` marks Display P3 pixels without one
 * (HEIF `nclx` colour primaries 12).
 */
export function preparePixels(rgba: Uint8Array | number[], width: number, height: number, icc: Uint8Array | number[] | null | undefined, p3: boolean, max_edge: number, quality: number): Prepared;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_prepared_free: (a: number, b: number) => void;
    readonly prepareImage: (a: number, b: number, c: number, d: number) => [number, number, number];
    readonly preparePixels: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number) => [number, number, number];
    readonly prepared_height: (a: number) => number;
    readonly prepared_takeJpeg: (a: number) => [number, number];
    readonly prepared_width: (a: number) => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
