package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	warblerDB "gitlab.stergianis.ca/michael/warbler/db"
	"olympos.io/encoding/edn"
)

var (
	serv      *server
	prepareDB func()

	jsonE    = encoder{"json", json.Marshal, json.Unmarshal}
	ednE     = encoder{"edn", edn.Marshal, edn.Unmarshal}
	encoders = [...]encoder{
		jsonE, ednE,
	}
)

const (
	dbName = "warbler_test"
)

// TestMain ...
func TestMain(m *testing.M) {
	var err error

	// use warbler test server
	serv, err = newServer("dbname=" + dbName + " user=warbler sslmode=disable")
	if err != nil {
		log.Fatalln("cannot create connection to testing server", err)
	}
	serv.addRoutes()

	prepareDB, err = warblerDB.PrepareTestDatabase(serv.wdb, "db/fixtures")
	if err != nil {
		log.Fatal(err)
	}

	os.Exit(m.Run())
}

// TestNewUniqueQueryHandler ...
func TestNewUniqueQueryHandler(t *testing.T) {
	prepareDB()
	cases := []struct {
		name     string
		rCode    int
		url      string
		response string
	}{
		{"library successful", http.StatusOK, "/edn/library/1", `{:id 1 :name"Music":path"/home/test/Music"}`},
		{"genre successful", http.StatusOK, "/json/genre/1", `{"id":1,"name":"Jazz"}`},
		{"artist successful", http.StatusOK, "/edn/artist/1", `{:id 1 :name"BADBADNOTGOOD"}`},
		{"album successful", http.StatusOK, "/json/album/1",
			`{"id":1,"artist":1,"title":"III","year":2011,"num-tracks":20,"num-disks":1,"duration":1688}`},
		{"song successful", http.StatusOK, "/edn/song/1",
			`{:id 1 :album 1 :genre 1 :title"In the Night":size 204192 :duration 1993.0 :track 1 :num-tracks 20 :disk 1 :num-disks 1 :artist "BADBADNOTGOOD"}`},
		{"image successful", http.StatusOK, "/json/image/1", `{"id":1}`},
		{"album invalid characters", http.StatusBadRequest, "/edn/album/h9h", ""},
		{"album number not in database", http.StatusNotFound, "/edn/album/99", ""},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			req, err := http.NewRequest("GET", test.url, nil)
			if err != nil {
				t.Fatalf("error creating request: %v", err)
			}

			rr := httptest.NewRecorder()

			serv.router.ServeHTTP(rr, req)

			if test.rCode != rr.Code {
				t.Errorf("error during test %s expected code: %v received code: %v", test.name, test.rCode, rr.Code)
			}

			resp := rr.Body.Bytes()
			if test.response != string(resp) {
				t.Errorf("response did not match expected\n\texpected: %v\n\treceived: %v", test.response, string(resp))
			}

		})

	}
}

// TestNewQueryHandler ...
func TestNewQueryHandler(t *testing.T) {
	prepareDB()

	cases := []struct {
		enc    encoder
		status int
		url    string
		data   []string
	}{
		// okay cases
		{ednE, http.StatusOK, "/edn/song", []string{`{:artist "BADBADNOTGOOD"}`}},
		{ednE, http.StatusOK, "/edn/song", []string{`{:artist "Iron Maiden"}`, `{:artist "Megadeth"}`}},
		{jsonE, http.StatusOK, "/json/album", []string{`{"num-disks": 1}`}},
		{ednE, http.StatusOK, "/edn/artist", []string{`{}`}},
		{jsonE, http.StatusOK, "/json/artist", []string{`{}`}},
		{jsonE, http.StatusOK, "/json/album", []string{}},

		// error cases
		{ednE, http.StatusBadRequest, "/edn/song", []string{`4`}},
	}
	answers := []string{
		`[[{:id 1 :album 1 :genre 1 :title"In the Night":size 204192 :duration 1993.0 :track 1 :num-tracks 20 :disk 1 :num-disks 1 :artist "BADBADNOTGOOD"}{:id 5 :album 1 :genre 1 :title"Triangle":size 204299 :duration 1999.0 :track 2 :num-tracks 20 :disk 1 :num-disks 1 :artist "BADBADNOTGOOD"}{:id 6 :album 1 :genre 1 :title"Something":size 91841 :duration 9381.0 :track nil :num-tracks nil :disk nil :num-disks nil :artist "BADBADNOTGOOD"}]]`,
		`[[{:id 3 :album 3 :genre 3 :title"The Ides of March":size 2109 :duration 210.0 :track 1 :num-tracks 8 :disk 1 :num-disks 1 :artist "Iron Maiden"}][{:id 4 :album 4 :genre 4 :title"Hangar 18":size 99948 :duration 9994.0 :track 1 :num-tracks 13 :disk 1 :num-disks 1 :artist "Megadeth"}]]`,
		`[[{"id":1,"artist":1,"title":"III","year":2011,"num-tracks":20,"num-disks":1,"duration":1688},{"id":2,"artist":2,"title":"Sour Soul","year":2001,"num-tracks":10,"num-disks":1,"duration":1800},{"id":3,"artist":3,"title":"Killers","year":1980,"num-tracks":8,"num-disks":1,"duration":15440},{"id":4,"artist":4,"title":"Rust in Peace","year":1985,"num-tracks":13,"num-disks":1,"duration":1756},{"id":5,"artist":1,"title":"IV","year":2012,"num-tracks":19,"num-disks":1,"duration":1688}]]`,
		`[[{:id 1 :name"BADBADNOTGOOD"}{:id 2 :name"BADBADNOTGOOD \u0026 Ghostface Killah"}{:id 3 :name"Iron Maiden"}{:id 4 :name"Megadeth"}]]`,
		`[[{"id":1,"name":"BADBADNOTGOOD"},{"id":2,"name":"BADBADNOTGOOD \u0026 Ghostface Killah"},{"id":3,"name":"Iron Maiden"},{"id":4,"name":"Megadeth"}]]`,
		`[[{"id":1,"artist":1,"title":"III","year":2011,"num-tracks":20,"num-disks":1,"duration":1688},{"id":2,"artist":2,"title":"Sour Soul","year":2001,"num-tracks":10,"num-disks":1,"duration":1800},{"id":3,"artist":3,"title":"Killers","year":1980,"num-tracks":8,"num-disks":1,"duration":15440},{"id":4,"artist":4,"title":"Rust in Peace","year":1985,"num-tracks":13,"num-disks":1,"duration":1756},{"id":5,"artist":1,"title":"IV","year":2012,"num-tracks":19,"num-disks":1,"duration":1688}]]`,
		"edn: cannot unmarshal int into Go value of type db.Song",
	}

	for i, testCase := range cases {
		query := testCase.url
		if len(testCase.data) > 0 {
			query += "?data=" + strings.Join(testCase.data, "&data=")
		}

		req, err := http.NewRequest("GET", query, nil)
		if err != nil {
			t.Errorf("error creating request %v", err)
		}

		rr := httptest.NewRecorder()

		serv.router.ServeHTTP(rr, req)

		if rr.Code != testCase.status {
			t.Errorf("handler for %s returned status code %v", req.URL.String(), rr.Code)
		}

		result := string(rr.Body.Bytes())

		if answers[i] != result {
			t.Errorf("answer %d does not match result\n\tquery: %v\n\tresult: %v\n\tanswer: %v", i+1, query, result, answers[i])
		}
	}
}

// TestNewLibrary ...
func TestNewLibrary(t *testing.T) {
	libLoc, err := filepath.Abs("db/test_lib")
	if err != nil {
		t.Fatal(err)
	}
	testCases := []struct {
		name     string
		enc      encoder
		bodyStr  string
		code     int
		expected string
	}{
		{"create \"NewMusic\"", ednE, fmt.Sprintf(`{:name "NewMusic" :path %q}`, libLoc),
			http.StatusOK, fmt.Sprintf(`{:id 10001 :name"NewMusic":path%q}`, libLoc)},
	}
	for _, test := range testCases {
		t.Run(test.name, func(t *testing.T) {
			prepareDB()
			body := strings.NewReader(test.bodyStr)
			req, err := http.NewRequest(http.MethodPost, "/"+test.enc.name+"/library", body)
			if err != nil {
				t.Errorf("error in %s: %v", test.name, err)
			}

			rr := httptest.NewRecorder()

			serv.router.ServeHTTP(rr, req)

			if test.code != rr.Code {
				t.Errorf("expected code in %s: %v received code: %v", test.name, test.code, rr.Code)
			}

			response := string(rr.Body.Bytes())
			if test.expected != response {
				t.Errorf("unexpected body in %q:\n\texpected: %s\n\treceived: %s\n",
					test.name, test.expected, response)
			}
		})
	}
}

// TestNewLibraryUpdate ...
func TestNewLibraryUpdate(t *testing.T) {
	testCases := []struct {
		name    string
		enc     encoder
		bodyStr string
		code    int
	}{
		{"update \"Music\" successfully", ednE, `{:id 1 :name "NewMusic"}`, http.StatusOK},
		{"non numerical id", ednE, `{:id "my-music" :name "NewMusic"}`, http.StatusInternalServerError},
		{"index not found", ednE, `{:id 99 :name "NewMusic"}`, http.StatusInternalServerError},
	}
	for _, test := range testCases {
		t.Run(test.name, func(t *testing.T) {
			prepareDB()

			body := strings.NewReader(test.bodyStr)
			req, err := http.NewRequest(http.MethodPut, "/"+test.enc.name+"/library", body)
			if err != nil {
				t.Errorf("error in %s: %v", test.name, err)
			}

			rr := httptest.NewRecorder()

			serv.router.ServeHTTP(rr, req)

			if test.code != rr.Code {
				t.Errorf("expected code in %s: %v received code: %v", test.name, test.code, rr.Code)
			}
		})
	}

}
