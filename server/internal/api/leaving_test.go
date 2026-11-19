package api

import (
	"testing"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
)

// The leave choices and their defaults (README §5.21, decision A).
func TestLeavingChoices(t *testing.T) {
	take, keep := gen.PhotosChoiceTake, gen.PhotosChoiceKeep
	yes, no := true, false
	for _, c := range []struct {
		photos         *gen.PhotosChoice
		legacy         *bool
		take, explicit bool
	}{
		{nil, nil, false, false},   // older clients without anything: keep, 204
		{nil, &yes, true, false},   // remove_my_posts / remove_my_items true: take, 204
		{nil, &no, false, false},   // false: keep
		{&take, &no, true, true},   // photos wins
		{&keep, &yes, false, true}, // photos wins
		{&take, nil, true, true},   // 200 with the result
		{&keep, nil, false, true},  // 200 with an empty result
	} {
		gotTake, gotExplicit := photosChoice(c.photos, c.legacy)
		if gotTake != c.take || gotExplicit != c.explicit {
			t.Errorf("photosChoice(%v, %v) = %v, %v", c.photos, c.legacy, gotTake, gotExplicit)
		}
	}
	tk, lv := gen.AccountDeletionPhotosTake, gen.AccountDeletionPhotosLeave
	for _, c := range []struct {
		req  gen.DeleteMeRequestObject
		take bool
	}{
		{gen.DeleteMeRequestObject{}, true},
		{gen.DeleteMeRequestObject{Params: gen.DeleteMeParams{RemoveItems: &no}}, false},
		{gen.DeleteMeRequestObject{Params: gen.DeleteMeParams{RemoveItems: &yes}}, true},
		{gen.DeleteMeRequestObject{Body: &gen.AccountDeletion{Photos: &lv}, Params: gen.DeleteMeParams{RemoveItems: &yes}}, false},
		{gen.DeleteMeRequestObject{Body: &gen.AccountDeletion{Photos: &tk}, Params: gen.DeleteMeParams{RemoveItems: &no}}, true},
		{gen.DeleteMeRequestObject{Body: &gen.AccountDeletion{}}, true},
	} {
		if got := accountDeletionTakesPhotos(c.req); got != c.take {
			t.Errorf("accountDeletionTakesPhotos(%+v) = %v", c.req, got)
		}
	}
	if plural("keep_request", 1) != "keep_request_one" || plural("keep_request", 2) != "keep_request" {
		t.Error("plural")
	}
	if trimRunes("  héllo wörld  ", 5) != "héllo" || trimRunes(" a ", 300) != "a" {
		t.Error("trimRunes")
	}
}

func TestMemoryFilterHides(t *testing.T) {
	tom, lies, post, other := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	f := memoryFilter{HiddenPeople: map[uuid.UUID]bool{tom: true}, HiddenPosts: map[uuid.UUID]bool{post: true}}
	if !f.Hides(&tom, other) || f.Hides(&lies, other) || !f.Hides(&lies, post) || !f.Hides(nil, post) || f.Hides(nil, other) {
		t.Error("Hides")
	}
	if f.empty() || !(memoryFilter{}).empty() {
		t.Error("empty")
	}
}
