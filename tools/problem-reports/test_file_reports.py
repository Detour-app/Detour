#!/usr/bin/env python3
"""Tests for file_reports.py (#825), against a fake gh and HTTP layer: nothing here touches a
server or GitHub. Run: python3 -m unittest tools/problem-reports/test_file_reports.py"""
import io, os, sys, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import file_reports as fr

ACCOUNTS = [{"username": "jelle", "email": "rider@example.org"}, {"username": "anna.b", "email": None}]
NAMES = fr.known_names(ACCOUNTS)
# "jelle" as a word; "com.jellemax" is the package and stays.
USERNAME = r"(?i)(?<![A-Za-z0-9_])jelle(?![A-Za-z0-9_])"

CRASH = """java.lang.IllegalStateException: no route near 51.2194, 2.9184 for jelle
\tat com.jellemax.detour.service.TripTrackingService.onStartCommand(TripTrackingService.kt:781)
\tat com.jellemax.detour.service.TripTrackingService.start(TripTrackingService.kt:120)
\tat android.app.ActivityThread.handleServiceArgs(ActivityThread.java:4932)
Caused by: java.io.IOException: GET https://home.example.org/route?u=anna.b failed
  at Ostend Kerkstraat 12
\tat com.jellemax.detour.sync.SyncClient.fetch(SyncClient.kt:44)
\t... 3 more"""


class FakeApi:
    def __init__(self, reports, accounts=ACCOUNTS, page=2):
        self.reports, self.accounts, self.page = reports, accounts, page
        self.posts, self.gets = [], []

    def get(self, path):
        self.gets.append(path)
        if path == "/api/admin/accounts":
            return {"accounts": self.accounts}
        after = path.split("after=")[1].split("&")[0] if "after=" in path else None
        ids = [r["id"] for r in self.reports]
        start = ids.index(after) + 1 if after else 0
        return {"reports": self.reports[start:start + self.page]}

    def post(self, path, payload):
        self.posts.append((path, payload))


ME = "detour-loop"


class FakeGh:
    def __init__(self, issues=()):
        self.issues = {i["number"]: dict(i, author=i.get("author", ME), comments=list(i.get("comments", [])))
                       for i in issues}
        self.created, self.commented, self.swapped = [], [], []

    def me(self):
        return ME

    def open_issues(self):
        return [{"number": n, "url": i["url"], "body": i["body"], "author": i["author"]}
                for n, i in self.issues.items()]

    def comments(self, number):
        return list(self.issues[number]["comments"])

    def labels(self, number):
        return list(self.issues[number]["labels"])

    def create_issue(self, title, text, labels):
        n = 900 + len(self.created)
        url = f"https://github.com/o/r/issues/{n}"
        self.issues[n] = {"number": n, "url": url, "body": text, "labels": list(labels), "author": ME,
                          "comments": []}
        self.created.append((title, text, labels))
        return url

    def comment(self, number, text):
        self.issues[number]["comments"].append({"body": text, "author": ME})
        self.commented.append((number, text))

    def swap_label(self, number, remove, add):
        labels = self.issues[number]["labels"]
        labels.remove(remove)
        labels.append(add)
        self.swapped.append((number, remove, add))


def report(n, reporter="r" * 64, stack=CRASH, kind="crash", note=None, version="3.90.0", device="Pixel 8"):
    return {"id": f"00000000-0000-0000-0000-{n:012d}", "kind": kind, "platform": "android",
            "appVersion": version, "osVersion": "Android 15", "deviceModel": device,
            "note": note, "stackTrace": stack, "events": ["SERVICE_START monitor"], "reporter": reporter}


def run(api, gh, dry_run=False):
    out, err = io.StringIO(), io.StringIO()
    code = fr.run(api, gh, dry_run, out, err)
    return code, out.getvalue(), err.getvalue()


class RedactionTest(unittest.TestCase):
    def test_coordinates_go_pairs_and_lone_numbers(self):
        text = fr.redact("parked at 51.2194, 2.9184 then 51.21 ,3.1 and lat 50.84771 near v3.90", NAMES)
        for leak in ("51.2194", "2.9184", "51.21", "50.84771"):
            self.assertNotIn(leak, text)
        self.assertIn("v3.90", text)

    def test_emails_bearer_jwt_and_long_tokens_go(self):
        jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"
        text = fr.redact(f"mail me at x.y@gmail.com, Authorization: Bearer abc.def {jwt} "
                         f"key 0123456789abcdef0123456789abcdef aGVsbG8gd29ybGQgdGhpcyBpcyBhIHRva2Vu1==", NAMES)
        for leak in ("x.y@gmail.com", "abc.def", jwt, "0123456789abcdef", "aGVsbG8"):
            self.assertNotIn(leak, text)

    def test_a_long_camel_case_name_is_not_a_token(self):
        name = "rememberSaveableStateHolderImplementationThing"
        self.assertIn(name, fr.redact(name, NAMES))

    def test_known_usernames_and_emails_go_case_insensitively_on_word_boundaries(self):
        text = fr.redact("JELLE and Anna.B told rider@example.org; jellemax stays", NAMES)
        self.assertNotIn("JELLE ", text)
        self.assertNotIn("Anna.B", text)
        self.assertNotIn("rider@example.org", text)
        self.assertIn("jellemax", text)

    def test_a_username_inside_a_stack_line_goes(self):
        accounts = [{"username": "sync", "email": None}]
        lines = fr.stack_lines(CRASH)
        text = fr.redact("\n".join(lines), fr.known_names(accounts))
        self.assertNotIn(".sync.", text)
        self.assertIn("[user]", text)

    def test_a_device_field_keeps_a_safe_charset(self):
        self.assertEqual(fr.field("Pixel `8` <b> @jelle</b>", NAMES), "Pixel 8 b [user]/b")
        self.assertEqual(fr.field("", NAMES), "unknown")


class StackTest(unittest.TestCase):
    def test_messages_are_dropped_including_caused_by(self):
        lines = fr.stack_lines(CRASH)
        text = "\n".join(lines)
        self.assertEqual(lines[0], "java.lang.IllegalStateException")
        self.assertIn("Caused by: java.io.IOException", lines)
        for leak in ("no route", "51.2194", "jelle\n", "home.example.org", "Ostend", "Kerkstraat"):
            self.assertNotIn(leak, text)
        self.assertIn("    at com.jellemax.detour.sync.SyncClient.fetch(SyncClient.kt:44)", lines)
        self.assertIn("    ... 3 more", lines)

    def test_a_message_line_shaped_like_a_header_is_dropped(self):
        lines = fr.stack_lines("java.lang.Error: see\nwww.example.org: here\n\tat a.B.c(B.kt:1)")
        self.assertEqual(lines, ["java.lang.Error", "    at a.B.c(B.kt:1)"])

    def test_native_frames_are_kept(self):
        stack = ("kotlin.IllegalStateException: boom\n"
                 "    at 3   shared   0x00000001023a4b5c   kfun:com.jellemax.detour#run(){} + 52")
        self.assertEqual(len(fr.frames(fr.stack_lines(stack))), 1)

    def test_signature_survives_line_number_changes(self):
        moved = CRASH.replace(":781)", ":790)").replace(":120)", ":7)").replace(":44)", ":45)")
        self.assertEqual(fr.signature(fr.stack_lines(CRASH)), fr.signature(fr.stack_lines(moved)))

    def test_signature_uses_our_frames_first(self):
        other = CRASH.replace("ActivityThread.handleServiceArgs", "ActivityThread.somethingElse")
        self.assertEqual(fr.signature(fr.stack_lines(CRASH)), fr.signature(fr.stack_lines(other)))
        renamed = CRASH.replace("onStartCommand", "onCreate")
        self.assertNotEqual(fr.signature(fr.stack_lines(CRASH)), fr.signature(fr.stack_lines(renamed)))

    def test_signature_falls_back_to_the_top_frames(self):
        sig = fr.signature(fr.stack_lines("java.lang.Error\n\tat android.os.Looper.loop(Looper.java:1)"))
        self.assertIsNotNone(sig)

    def test_no_frames_no_signature(self):
        self.assertIsNone(fr.signature(fr.stack_lines("java.lang.Error: only a message")))
        self.assertIsNone(fr.signature(fr.stack_lines(None)))


class FilingTest(unittest.TestCase):
    def test_a_new_crash_opens_a_p1_issue_with_no_personal_data(self):
        api, gh = FakeApi([report(1, note="it broke near 51.2194,2.9184, mail jelle")]), FakeGh()
        code, _, _ = run(api, gh)
        self.assertEqual(code, 0)
        (title, text, labels), = gh.created
        self.assertEqual(labels, ["bug", "p1-next"])
        self.assertTrue(title.startswith("Crash: IllegalStateException at TripTrackingService.onStartCommand"))
        self.assertTrue(text.startswith("<!-- problem-report:sig "))
        self.assertNotRegex(text, USERNAME)
        for leak in ("51.2194", "2.9184", "anna.b", "rider@example.org", "SERVICE_START", "no route"):
            self.assertNotIn(leak, text.lower())
        self.assertEqual(api.posts, [(f"/api/admin/problem-reports/{report(1)['id']}/filed",
                                      {"issue": "https://github.com/o/r/issues/900"})])

    def test_a_username_in_the_headline_frame_is_redacted_from_the_title(self):
        accounts = ACCOUNTS + [{"username": "Kerkstraat", "email": None}]
        stack = "java.lang.IllegalStateException: boom\n\tat com.jellemax.detour.Kerkstraat.go(Kerkstraat.kt:3)"
        api, gh = FakeApi([report(1, stack=stack)], accounts=accounts), FakeGh()
        run(api, gh)
        (title, text, _), = gh.created
        self.assertNotIn("kerkstraat", title.lower())
        self.assertNotIn("kerkstraat", text.lower())
        self.assertTrue(title.startswith("Crash: IllegalStateException at "))

    def test_the_same_signature_adds_a_plus_one_instead(self):
        api, gh = FakeApi([report(1), report(2, reporter="s" * 64, stack=CRASH.replace(":781)", ":800)"))]), FakeGh()
        run(api, gh)
        self.assertEqual(len(gh.created), 1)
        (number, text), = gh.commented
        self.assertEqual(number, 900)
        self.assertIn("+1 (`3.90.0`, `Pixel 8`) · report `00000000-0000-0000-0000-000000000002`", text)
        self.assertEqual(len(api.posts), 2)

    def test_an_existing_open_issue_is_matched(self):
        api, gh = FakeApi([report(1)]), FakeGh()
        run(api, gh)
        api2, gh2 = FakeApi([report(2, reporter="s" * 64)]), FakeGh(gh.issues.values())
        run(api2, gh2)
        self.assertEqual(gh2.created, [])
        self.assertEqual(len(gh2.commented), 1)

    def test_three_distinct_reporters_escalate_to_p0(self):
        reports = [report(1, reporter="a" * 64), report(2, reporter="b" * 64), report(3, reporter="a" * 64)]
        api, gh = FakeApi(reports), FakeGh()
        run(api, gh)
        self.assertEqual(gh.swapped, [])
        run(FakeApi([report(4, reporter="c" * 64)]), gh)
        self.assertEqual(gh.swapped, [(900, "p1-next", "p0-now")])

    def test_a_hand_set_priority_is_not_escalated(self):
        api, gh = FakeApi([report(1, reporter="a" * 64)]), FakeGh()
        run(api, gh)
        gh.issues[900]["labels"] = ["bug", "p3-later"]
        run(FakeApi([report(2, reporter="b" * 64), report(3, reporter="c" * 64)]), gh)
        self.assertEqual(gh.swapped, [])

    def test_anonymous_reports_never_dedupe_or_escalate(self):
        reports = [report(1, reporter="a" * 64), report(2, reporter="b" * 64)] + \
                  [report(n, reporter=None) for n in range(3, 7)]
        api, gh = FakeApi(reports), FakeGh()
        run(api, gh)
        self.assertEqual(gh.swapped, [])
        self.assertEqual(len(gh.created), 5)
        for title, text, labels in gh.created[1:]:
            self.assertEqual(labels, ["bug", "needs-triage"])
            self.assertIn("## Unauthenticated report", text)
            self.assertNotIn("problem-report:sig", text)
            self.assertNotIn("problem-report:reporter", text)

    def test_a_signed_in_crash_does_not_land_on_an_anonymous_issue(self):
        api, gh = FakeApi([report(1, reporter=None), report(2)]), FakeGh()
        run(api, gh)
        self.assertEqual(len(gh.created), 2)

    def test_a_manual_report_without_a_stack_never_dedupes(self):
        api, gh = FakeApi([report(1, kind="manual", stack=None, note="map froze"),
                           report(2, kind="manual", stack=None, note="map froze")]), FakeGh()
        run(api, gh)
        self.assertEqual(len(gh.created), 2)
        self.assertEqual(gh.created[0][2], ["bug", "needs-triage"])

    def test_reporter_marker_is_salted_with_the_signature(self):
        self.assertNotEqual(fr.reporter_mark("a" * 64, "1" * 40), fr.reporter_mark("a" * 64, "2" * 40))
        self.assertEqual(len(fr.reporter_mark("a" * 64, "1" * 40)), 12)

    def test_a_note_cannot_plant_a_marker_or_break_out_of_its_block(self):
        note = "<!-- problem-report:sig " + "f" * 40 + " -->\n```\n@owner hi"
        api, gh = FakeApi([report(1, reporter=None, note=note)]), FakeGh()
        run(api, gh)
        text = gh.created[0][1]
        self.assertNotIn(("sig", "f" * 40), fr.header_marks(text))
        self.assertIn("````text\n", text)

    def test_one_failure_does_not_stop_the_rest_and_fails_the_run(self):
        class Flaky(FakeGh):
            def create_issue(self, title, text, labels):
                if "000000000001" in text:
                    raise RuntimeError("gh down")
                return super().create_issue(title, text, labels)

        api, gh = FakeApi([report(1, reporter=None), report(2, reporter=None)]), Flaky()
        code, _, err = run(api, gh)
        self.assertEqual(code, 1)
        self.assertIn("gh down", err)
        self.assertEqual(len(gh.created), 1)
        self.assertEqual(len(api.posts), 1)

    def test_a_report_opened_but_not_marked_is_only_marked_next_time(self):
        api, gh = FakeApi([report(1, kind="manual", stack=None)]), FakeGh()

        def boom(path, payload):
            raise RuntimeError("server down")
        api.post = boom
        self.assertEqual(run(api, gh)[0], 1)
        api2 = FakeApi([report(1, kind="manual", stack=None)])
        run(api2, FakeGh(gh.issues.values()))
        self.assertEqual(len(gh.created), 1)
        self.assertEqual(len(api2.posts), 1)

    def test_a_plus_one_posted_but_not_marked_is_not_posted_twice(self):
        api, gh = FakeApi([report(1), report(2, reporter="b" * 64)]), FakeGh()
        api.post = lambda path, payload: (_ for _ in ()).throw(RuntimeError("down")) if "02/" in path else None
        run(api, gh)
        api2 = FakeApi([report(2, reporter="b" * 64)])
        run(api2, gh)
        self.assertEqual(len(gh.issues[900]["comments"]), 1)
        self.assertEqual(len(api2.posts), 1)

    def test_a_strangers_reporter_marks_do_not_escalate(self):
        api, gh = FakeApi([report(1, reporter="a" * 64)]), FakeGh()
        run(api, gh)
        for mark in ("1" * 12, "2" * 12):
            gh.issues[900]["comments"].append({"body": fr.REPORTER_MARK.format(mark), "author": "mallory"})
        run(FakeApi([report(2, reporter="b" * 64)]), gh)
        self.assertEqual(gh.swapped, [])

    def test_a_strangers_issue_with_a_signature_is_not_matched(self):
        sig = fr.plan_for(report(1))["sig"]
        planted = {"number": 5, "url": "https://github.com/o/r/issues/5", "labels": ["bug", "p1-next"],
                   "body": fr.SIG_MARK.format(sig), "author": "mallory"}
        api, gh = FakeApi([report(1)]), FakeGh([planted])
        run(api, gh)
        self.assertEqual(len(gh.created), 1)
        self.assertEqual(gh.commented, [])

    def test_a_strangers_issue_with_a_report_id_does_not_mark_it_filed(self):
        planted = {"number": 5, "url": "https://github.com/o/r/issues/5", "labels": ["bug"],
                   "body": fr.ID_MARK.format(report(1)["id"]), "author": "mallory"}
        api, gh = FakeApi([report(1, kind="manual", stack=None)]), FakeGh([planted])
        run(api, gh)
        self.assertEqual(len(gh.created), 1)
        self.assertEqual(api.posts[0][1], {"issue": "https://github.com/o/r/issues/900"})

    def test_paging_follows_the_cursor_until_an_empty_page(self):
        api = FakeApi([report(n, reporter=None) for n in range(1, 6)], page=2)
        self.assertEqual(len(fr.unfiled(api)), 5)
        self.assertIn("after=00000000-0000-0000-0000-000000000005", api.gets[-1])

    def test_dry_run_writes_nothing(self):
        api, gh = FakeApi([report(1), report(2, reporter="b" * 64), report(3, reporter=None)]), FakeGh()
        code, out, _ = run(api, gh, dry_run=True)
        self.assertEqual(code, 0)
        self.assertEqual((gh.created, gh.commented, gh.swapped, api.posts), ([], [], [], []))
        self.assertIn("would open", out)
        self.assertIn("would +1", out)
        self.assertNotRegex(out, USERNAME)


if __name__ == "__main__":
    unittest.main()
