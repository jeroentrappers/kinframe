package calendar

import "slices"

// Colors is the calendar palette (README §5.8) in the order new sources get them.
var Colors = []string{"coral", "tangerine", "amber", "olive", "mint", "sage", "teal", "sky", "ocean", "lavender", "plum", "rose"}

// LocalColor is the default colour of the received-events calendar.
const LocalColor = "rose"

// ValidColor reports whether c is a palette key.
func ValidColor(c string) bool { return slices.Contains(Colors, c) }

// NextColor returns the first palette colour not in used (cycling when all are used).
func NextColor(used []string) string {
	count := map[string]int{}
	for _, u := range used {
		count[u]++
	}
	for round := 0; ; round++ {
		for _, c := range Colors {
			if count[c] <= round {
				return c
			}
		}
	}
}
