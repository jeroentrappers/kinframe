// See https://svelte.dev/docs/kit/types#app.d.ts
declare global {
	namespace App {}
	/** `kinframe-web@<version>[+<revision>]`, defined in vite.config.ts (crash reporting release). */
	const __KINFRAME_WEB_RELEASE__: string;
}

interface ImportMetaEnv {
	readonly VITE_DEV_AUTH?: string;
}

export {};
