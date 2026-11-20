package problem

// Remote help (docs/spec/remote-help.md §4, §5.2). Titles are in i18n locales/remotehelp.
const (
	RemoteHelpActive      Code = "remote_help_active"
	RemoteHelpUnsupported Code = "remote_help_unsupported"
)

func init() {
	for c, d := range map[Code]struct {
		status int
		title  string
	}{
		RemoteHelpActive:      {409, "Remote help is already on for this frame"},
		RemoteHelpUnsupported: {409, "This frame can't get remote help (Play build or no root)"},
	} {
		defaults[c] = d
	}
}
