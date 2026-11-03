// Minimal Presentation API typings (https://w3c.github.io/presentation-api/); TypeScript's DOM lib omits them.
interface PresentationConnection extends EventTarget {
	readonly id: string;
	readonly url: string;
	readonly state: 'connecting' | 'connected' | 'closed' | 'terminated';
	send(data: string): void;
	close(): void;
	terminate(): void;
}

interface PresentationConnectionAvailableEvent extends Event {
	readonly connection: PresentationConnection;
}

interface PresentationConnectionList extends EventTarget {
	readonly connections: PresentationConnection[];
}

interface PresentationReceiver {
	readonly connectionList: Promise<PresentationConnectionList>;
}

declare class PresentationRequest extends EventTarget {
	constructor(urls: string | string[]);
	start(): Promise<PresentationConnection>;
	getAvailability(): Promise<EventTarget & { readonly value: boolean }>;
}
