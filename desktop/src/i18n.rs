//! Tiny i18n: one table per language, English as the source of truth and fallback.
//!
//! Keys follow the web catalogs (`web/src/lib/i18n`) where a string means the same thing, so more languages can be
//! ported from there later: add a `Lang` variant, a table, and an entry in [`Lang::ALL`]. Placeholders use `{name}`;
//! plurals use `.one` / `.other` suffixes like the web.

use std::sync::atomic::{AtomicU8, Ordering};

mod en;
mod nl;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Lang {
    En,
    Nl,
}

impl Lang {
    pub const ALL: [Lang; 2] = [Lang::En, Lang::Nl];

    pub fn code(self) -> &'static str {
        match self {
            Lang::En => "en",
            Lang::Nl => "nl",
        }
    }

    /// The language's own name, for the language picker.
    pub fn native_name(self) -> &'static str {
        match self {
            Lang::En => "English",
            Lang::Nl => "Nederlands",
        }
    }

    fn table(self) -> &'static [(&'static str, &'static str)] {
        match self {
            Lang::En => en::EN,
            Lang::Nl => nl::NL,
        }
    }

    /// Best match for a BCP 47 tag such as `nl-BE`, `en_US.UTF-8` or `fr`; `None` when unsupported.
    pub fn from_tag(tag: &str) -> Option<Lang> {
        let primary = tag
            .split(['-', '_', '.'])
            .next()
            .unwrap_or_default()
            .to_ascii_lowercase();
        Lang::ALL.into_iter().find(|l| l.code() == primary)
    }

    /// The OS language when supported, else English.
    pub fn system() -> Lang {
        sys_locale::get_locales()
            .find_map(|t| Lang::from_tag(&t))
            .unwrap_or(Lang::En)
    }
}

static CURRENT: AtomicU8 = AtomicU8::new(0);

pub fn set_lang(lang: Lang) {
    CURRENT.store(lang as u8, Ordering::Relaxed);
}

pub fn lang() -> Lang {
    match CURRENT.load(Ordering::Relaxed) {
        1 => Lang::Nl,
        _ => Lang::En,
    }
}

fn lookup(lang: Lang, key: &str) -> Option<&'static str> {
    lang.table().iter().find_map(|(k, v)| (*k == key).then_some(*v))
}

/// The message for `key` in the current language, falling back to English, then to the key itself.
pub fn t(key: &str) -> &'static str {
    lookup(lang(), key)
        .or_else(|| lookup(Lang::En, key))
        .unwrap_or_else(|| {
            debug_assert!(false, "missing i18n key {key}");
            ""
        })
}

/// `t` with `{name}` placeholders filled in.
pub fn tf(key: &str, args: &[(&str, &dyn std::fmt::Display)]) -> String {
    fill(t(key), args)
}

/// Plural form: looks up `key.one` or `key.other` and fills `{count}` plus `args`.
pub fn tn(key: &str, count: u64, args: &[(&str, &dyn std::fmt::Display)]) -> String {
    let form = if count == 1 { "one" } else { "other" };
    let mut all: Vec<(&str, &dyn std::fmt::Display)> = vec![("count", &count)];
    all.extend_from_slice(args);
    fill(t(&format!("{key}.{form}")), &all)
}

fn fill(template: &str, args: &[(&str, &dyn std::fmt::Display)]) -> String {
    let mut out = template.to_string();
    for (name, value) in args {
        out = out.replace(&format!("{{{name}}}"), &value.to_string());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    fn keys(lang: Lang) -> BTreeSet<&'static str> {
        lang.table().iter().map(|(k, _)| *k).collect()
    }

    #[test]
    fn every_language_has_exactly_the_english_keys() {
        let en = keys(Lang::En);
        assert_eq!(en.len(), Lang::En.table().len(), "duplicate English keys");
        for lang in Lang::ALL {
            let other = keys(lang);
            assert_eq!(other.len(), lang.table().len(), "duplicate keys in {lang:?}");
            let missing: Vec<_> = en.difference(&other).collect();
            let extra: Vec<_> = other.difference(&en).collect();
            assert!(
                missing.is_empty() && extra.is_empty(),
                "{lang:?}: missing {missing:?}, extra {extra:?}"
            );
        }
    }

    #[test]
    fn placeholders_match_english() {
        let ph = |s: &str| -> BTreeSet<String> {
            s.split('{')
                .skip(1)
                .filter_map(|p| p.split_once('}').map(|(n, _)| n.to_string()))
                .collect()
        };
        for lang in Lang::ALL {
            for (k, v) in lang.table() {
                let en = lookup(Lang::En, k).unwrap();
                assert_eq!(ph(v), ph(en), "{lang:?} {k}");
            }
        }
    }

    #[test]
    fn tags_and_plurals() {
        assert_eq!(Lang::from_tag("nl-BE"), Some(Lang::Nl));
        assert_eq!(Lang::from_tag("en_US.UTF-8"), Some(Lang::En));
        assert_eq!(Lang::from_tag("fr"), None);
        assert_eq!(fill("{a} of {b}", &[("a", &1), ("b", &"two")]), "1 of two");
        assert_eq!(tn("import.files", 1, &[]), "1 file");
        assert_eq!(tn("import.files", 3, &[]), "3 files");
    }
}
