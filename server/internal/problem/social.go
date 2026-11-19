package problem

// Family social (docs/spec/family-social.md, README §5.18–§5.19). Titles are in i18n locales/social and
// locales/chat.
const (
	CannotMessage  Code = "cannot_message"
	CommentsClosed Code = "comments_closed"
	AudioTooLong   Code = "audio_too_long"
)

func init() {
	for c, d := range map[Code]struct {
		status int
		title  string
	}{
		CannotMessage:  {403, "You can't message this person"},
		CommentsClosed: {409, "Comments are turned off for this post"},
		AudioTooLong:   {422, "The voice message is too long"},
	} {
		defaults[c] = d
	}
}
