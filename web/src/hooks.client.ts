import type { HandleClientError } from '@sveltejs/kit';
import { reportError } from '$lib/crash';

// Unexpected errors in loads and rendering go to crash reporting when it is on (docs/spec/crash-reporting.md);
// SvelteKit then shows its error page as before.
export const handleError: HandleClientError = ({ error }) => {
	reportError(error);
};
