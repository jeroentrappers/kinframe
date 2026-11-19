package problem

// Telegram bridge (docs/spec/telegram.md §15.3).
const TelegramAlreadyLinked Code = "telegram_already_linked"

func init() {
	defaults[TelegramAlreadyLinked] = struct {
		status int
		title  string
	}{409, "This account already has a linked Telegram account"}
}
