package shop

import (
	"testing"
	"time"
)

func TestEaster(t *testing.T) {
	for y, want := range map[int]time.Time{2024: Date(2024, 3, 31), 2025: Date(2025, 4, 20), 2026: Date(2026, 4, 5), 2027: Date(2027, 3, 28)} {
		if got := easterSunday(y); !got.Equal(want) {
			t.Errorf("easter %d: %v, want %v", y, got, want)
		}
	}
}

func TestShipsOn(t *testing.T) {
	c := &Config{Stock: &Stock{RestockDays: 3}}
	at := func(s string) time.Time {
		v, err := time.ParseInLocation("2006-01-02 15:04", s, brussels)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	for _, tc := range []struct {
		now     string
		inStock bool
		want    time.Time
	}{
		{"2026-11-16 10:00", true, Date(2026, 11, 17)},  // Monday → Tuesday
		{"2026-11-16 10:00", false, Date(2026, 11, 20)}, // Tuesday + 3 → Friday
		{"2026-11-13 23:30", true, Date(2026, 11, 16)},  // Friday night (Brussels) → Monday
		{"2026-11-14 12:00", true, Date(2026, 11, 16)},  // Saturday → Monday
		{"2026-11-14 12:00", false, Date(2026, 11, 19)}, // Monday + 3 → Thursday
		{"2026-11-10 09:00", true, Date(2026, 11, 12)},  // 11 November (Armistice) skipped
		{"2026-12-24 09:00", true, Date(2026, 12, 28)},  // Christmas, weekend
		{"2027-03-26 09:00", true, Date(2027, 3, 30)},   // Easter Monday 29 March skipped
		{"2026-11-16 10:00", false, Date(2026, 11, 20)},
	} {
		if got := c.ShipsOn(at(tc.now), tc.inStock); !got.Equal(tc.want) {
			t.Errorf("%s in stock %v: %s, want %s", tc.now, tc.inStock, got.Format("Mon 2006-01-02"), tc.want.Format("Mon 2006-01-02"))
		}
	}
	// Without stock management, "out of stock" adds nothing.
	if got := (&Config{}).ShipsOn(at("2026-11-16 10:00"), false); !got.Equal(Date(2026, 11, 17)) {
		t.Errorf("no stock config: %v", got)
	}
}

func TestInStock(t *testing.T) {
	lines := []Line{{ProductID: "a", Quantity: 2}, {ProductID: "b", Quantity: 1}}
	if !InStock(lines, map[string]int{"a": 2, "b": 5}) || InStock(lines, map[string]int{"a": 1, "b": 5}) || InStock(lines, nil) {
		t.Fatal("InStock")
	}
}
