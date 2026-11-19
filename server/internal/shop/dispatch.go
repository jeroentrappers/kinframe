package shop

import "time"

// Stock turns on stock management: the operator keeps a count of frames ready
// to ship per product, paid orders lower it, and the shop shows when an order
// ships: the next business day when in stock, RestockDays business days later
// when not.
type Stock struct {
	// RestockDays are the extra business days to dispatch an item that is out
	// of stock (default 3).
	RestockDays int `yaml:"restock_days"`
}

// brussels is the shop's calendar: business days and "today" are Belgian.
var brussels = func() *time.Location {
	if l, err := time.LoadLocation("Europe/Brussels"); err == nil {
		return l
	}
	return time.FixedZone("CET", 3600)
}()

// Date is a calendar day at midnight UTC (how dates are stored and sent).
func Date(y int, m time.Month, d int) time.Time { return time.Date(y, m, d, 0, 0, 0, 0, time.UTC) }

// BusinessDay reports whether a day is a working day in Belgium: Monday to
// Friday, except the legal public holidays.
func BusinessDay(d time.Time) bool {
	if wd := d.Weekday(); wd == time.Saturday || wd == time.Sunday {
		return false
	}
	return !belgianHoliday(d)
}

func belgianHoliday(d time.Time) bool {
	y, m, day := d.Date()
	switch {
	case m == time.January && day == 1, m == time.May && day == 1, m == time.July && day == 21, m == time.August && day == 15,
		m == time.November && day == 1, m == time.November && day == 11, m == time.December && day == 25:
		return true
	}
	easter := easterSunday(y)
	for _, off := range []int{1, 39, 50} { // Easter Monday, Ascension, Whit Monday
		if Date(y, m, day).Equal(easter.AddDate(0, 0, off)) {
			return true
		}
	}
	return false
}

// easterSunday is the Gregorian Easter date (anonymous Gregorian algorithm).
func easterSunday(y int) time.Time {
	a, b, c := y%19, y/100, y%100
	d, e := b/4, b%4
	f := (b + 8) / 25
	g := (b - f + 1) / 3
	h := (19*a + b - d - g + 15) % 30
	i, k := c/4, c%4
	l := (32 + 2*e + 2*i - h - k) % 7
	m := (a + 11*h + 22*l) / 451
	month := (h + l - 7*m + 114) / 31
	day := (h+l-7*m+114)%31 + 1
	return Date(y, time.Month(month), day)
}

// addBusinessDays moves n business days forward from d.
func addBusinessDays(d time.Time, n int) time.Time {
	for n > 0 {
		d = d.AddDate(0, 0, 1)
		if BusinessDay(d) {
			n--
		}
	}
	return d
}

// ShipsOn is the dispatch day for an order placed at now: the next business
// day, plus RestockDays business days when an item is out of stock.
func (c *Config) ShipsOn(now time.Time, inStock bool) time.Time {
	y, m, d := now.In(brussels).Date()
	day := addBusinessDays(Date(y, m, d), 1)
	if !inStock && c.Stock != nil {
		day = addBusinessDays(day, c.Stock.RestockDays)
	}
	return day
}

// InStock reports whether the stock covers every line of an order.
func InStock(lines []Line, stock map[string]int) bool {
	need := map[string]int{}
	for _, l := range lines {
		need[l.ProductID] += l.Quantity
	}
	for id, n := range need {
		if stock[id] < n {
			return false
		}
	}
	return true
}
