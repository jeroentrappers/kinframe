//! Frame codes: 8 digits shown on the frame as `1234 5678` (docs/api/README.md §5.1).

/// Extracts an 8-digit code from what someone typed or pasted: `4821 3907`, `4821-3907`, or a join link
/// `https://<server>/f/48213907`. `None` unless there are exactly 8 digits.
pub fn normalize(input: &str) -> Option<String> {
    let s = input.trim();
    let s = s
        .rsplit_once("/f/")
        .map(|(_, code)| code.trim_end_matches('/'))
        .unwrap_or(s);
    if s.chars()
        .any(|c| !(c.is_ascii_digit() || c.is_whitespace() || c == '-' || c == '.'))
    {
        return None;
    }
    let digits: String = s.chars().filter(char::is_ascii_digit).collect();
    (digits.len() == 8).then_some(digits)
}

/// Formats (possibly partial) input the way the frame shows it: digits only, a space after the fourth, at most 8.
/// A pasted join link is reduced to its code.
pub fn format_input(input: &str) -> String {
    let source = input.trim().rsplit_once("/f/").map(|(_, c)| c).unwrap_or(input);
    let digits: String = source.chars().filter(char::is_ascii_digit).take(8).collect();
    if digits.len() > 4 {
        format!("{} {}", &digits[..4], &digits[4..])
    } else {
        digits
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes() {
        assert_eq!(normalize("48213907").as_deref(), Some("48213907"));
        assert_eq!(normalize(" 4821 3907 ").as_deref(), Some("48213907"));
        assert_eq!(normalize("4821-3907").as_deref(), Some("48213907"));
        assert_eq!(
            normalize("https://kinframe.appmire.be/f/48213907").as_deref(),
            Some("48213907")
        );
        assert_eq!(normalize("4821390"), None);
        assert_eq!(normalize("482139071"), None);
        assert_eq!(normalize("4821x907"), None);
        assert_eq!(normalize(""), None);
    }

    #[test]
    fn formats_while_typing() {
        assert_eq!(format_input(""), "");
        assert_eq!(format_input("48"), "48");
        assert_eq!(format_input("4821"), "4821");
        assert_eq!(format_input("48213"), "4821 3");
        assert_eq!(format_input("4821 3907"), "4821 3907");
        assert_eq!(format_input("48213907999"), "4821 3907");
        assert_eq!(format_input("ab48-21"), "4821");
        assert_eq!(format_input("https://x.org/f/48213907"), "4821 3907");
    }
}
