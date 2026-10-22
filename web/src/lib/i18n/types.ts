import type { MessageKey } from './en';

/** A plural message's base key, e.g. `history.photos` for `history.photos.one` / `.other`. */
export type PluralBase = MessageKey extends infer K ? (K extends `${infer B}.one` ? B : never) : never;

/** Extra CLDR plural categories a language may need besides `one` and `other` (Polish `few`, Arabic `zero`…). */
export type PluralExtraKey = `${PluralBase}.${'zero' | 'two' | 'few' | 'many'}`;

/** A translation: every English key, plus the plural categories its language uses. */
export type Catalog = Record<MessageKey, string> & Partial<Record<PluralExtraKey, string>>;
