#!/usr/bin/env python3
"""Files new problem reports (#811) as redacted public GitHub issues.

The backend stores each report privately (BACKEND_SPEC §15.11). This script, run by the loop
host, lists the ones not filed yet, strips everything that could identify a rider, and opens a
public issue for each — or adds a "+1" to the open issue that already carries the same crash
signature — then marks the report filed. The event log never leaves the server.

Usage: python3 file_reports.py [--dry-run] [--env /root/.config/detour/loop.env] [--repo owner/name]
"""
import argparse, hashlib, json, os, re, subprocess, sys, urllib.parse, urllib.request

ENV_FILE = "/root/.config/detour/loop.env"
ENV_KEYS = ("DETOUR_API", "KEYCLOAK_TOKEN_URL", "LOOP_CLIENT_ID", "LOOP_CLIENT_SECRET")
PAGE = 50
SIG_FRAMES = 5
OUR_PACKAGE = "com.jellemax.detour"
ESCALATE_AT = 3
FIELD_MAX = 80
HTTP_TIMEOUT_S = 30

SIG_MARK = "<!-- problem-report:sig {} -->"
REPORTER_MARK = "<!-- problem-report:reporter {} -->"
ID_MARK = "<!-- problem-report:id {} -->"
ANONYMOUS_MARK = "<!-- problem-report:anonymous -->"
MARK_LINE = re.compile(r"^<!-- problem-report:(sig|reporter|id|anonymous)(?: ([0-9a-zA-Z-]+))? -->$")

# --- Redaction ----------------------------------------------------------------------------------

# A lat,lon pair, any precision ("51.2, 3.1"), before the lone-number rule eats half of it.
COORD_PAIR = re.compile(r"-?\d{1,3}\.\d+\s*,\s*-?\d{1,3}\.\d+")
# A lone number with 4+ fractional digits: ~11 m of latitude, finer than anything else we print.
COORD = re.compile(r"-?\d+\.\d{4,}")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
BEARER = re.compile(r"(?i)\bbearer\s+\S+")
JWT = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*")
HEX = re.compile(r"\b[0-9a-fA-F]{32,}\b")
# Base64-ish runs of 32+ with a digit and a letter in them, so a long camelCase name survives.
TOKEN = re.compile(r"(?=[A-Za-z0-9+/_-]{32,})(?=[A-Za-z0-9+/_-]*\d)(?=[A-Za-z0-9+/_-]*[A-Za-z])"
                   r"[A-Za-z0-9+/_-]{32,}={0,2}")


def known_names(accounts):
    """Every username and email on the server, longest first so "anna.b" goes before "anna"."""
    names = set()
    for a in accounts:
        for v in (a.get("username"), a.get("email")):
            if v and v.strip():
                names.add(v.strip())
    return sorted(names, key=len, reverse=True)


def redact(text, names):
    """Removes coordinates, emails, tokens and every known username or email from free text."""
    if not text:
        return ""
    for n in names:
        # Word boundaries by hand: usernames may hold "." and "-", where \b misbehaves.
        text = re.sub(r"(?i)(?<![A-Za-z0-9_])" + re.escape(n) + r"(?![A-Za-z0-9_])", "[user]", text)
    text = EMAIL.sub("[email]", text)
    text = BEARER.sub("Bearer [token]", text)
    text = JWT.sub("[token]", text)
    text = COORD_PAIR.sub("[coords]", text)
    text = COORD.sub("[coord]", text)
    text = HEX.sub("[token]", text)
    text = TOKEN.sub("[token]", text)
    return text


def field(value, names):
    """A device field (version, OS, model): anyone can send any string, so a safe charset and a cap."""
    value = re.sub(r"[^A-Za-z0-9 ._,()+/-]", "", value or "")[:FIELD_MAX].strip()
    return redact(value, names) or "unknown"


# --- Stack ---------------------------------------------------------------------------------------

# JVM: "at com.x.Y.z(Y.kt:12)", "(Unknown Source)", "(Native Method)".
JVM_FRAME = re.compile(r"^at [\w$.<>/-]+\([\w$.-]*(?::\d+)?\)$|^at [\w$.<>/-]+\((?:Unknown Source|Native Method)\)$")
# Kotlin/Native: "at 3   shared   0x00000001023a4b5c   kfun:com.x#y(){} + 52".
NATIVE_FRAME = re.compile(r"^at \d+\s+\S+\s+0x[0-9a-fA-F]+\s+\S.*$")
MORE = re.compile(r"^\.\.\. \d+ (?:more|common frames omitted)$")
# An exception header: a qualified class name, then ":" and its message, or nothing.
HEADER = re.compile(r"^(Caused by: |Suppressed: )?([A-Za-z_$][\w$]*(?:\.[\w$]+)+)(?::.*)?$")


def stack_lines(stack):
    """The stack as exception class names and frames only. Every message — the first line's and
    each "Caused by:"'s — is dropped, along with any line that is neither, because a message can
    carry an address, a place or a URL the redaction regexes don't know."""
    out = []
    for i, raw in enumerate((stack or "").splitlines()):
        line = raw.strip()
        if JVM_FRAME.match(line) or NATIVE_FRAME.match(line) or MORE.match(line):
            out.append("    " + line)
            continue
        m = HEADER.match(line)
        # Only the first line or a "Caused by:"/"Suppressed:" one is a header; any other line
        # that looks like one is the continuation of a multi-line message.
        if m and (i == 0 or m.group(1)):
            out.append((m.group(1) or "") + m.group(2))
    return out


def frames(lines):
    return [l.strip() for l in lines if l.strip().startswith("at ")]


def signature(lines):
    """sha1 of the top frames in our package (or the top frames, when none are), with line
    numbers and native addresses stripped so a rebuild doesn't split one crash into two."""
    fs = frames(lines)
    if not fs:
        return None
    ours = [f for f in fs if OUR_PACKAGE in f]
    top = (ours or fs)[:SIG_FRAMES]
    stable = [re.sub(r"0x[0-9a-fA-F]+|\s\+\s\d+$", "", re.sub(r":\d+\)$", ")", f)) for f in top]
    return hashlib.sha1("\n".join(stable).encode()).hexdigest()


def reporter_mark(reporter, sig):
    """Salted with the signature, so one rider's reports can't be linked across issues."""
    return hashlib.sha1((reporter + sig).encode()).hexdigest()[:12]


# --- Issue text ----------------------------------------------------------------------------------

def fenced(text):
    """A code block no backtick run in the text can close, so a note can't @mention, link or
    inject markdown."""
    longest = max((len(r) for r in re.findall(r"`+", text)), default=0)
    fence = "`" * max(3, longest + 1)
    return f"{fence}text\n{text}\n{fence}"


def headline(lines):
    """"IllegalStateException at TripTrackingService.onStartCommand" for a title."""
    exc = next((l for l in lines if not l.startswith(" ")), "")
    exc = exc.removeprefix("Caused by: ").removeprefix("Suppressed: ").rsplit(".", 1)[-1] or "Crash"
    fs = frames(lines)
    ours = [f for f in fs if OUR_PACKAGE in f] or fs
    if not ours:
        return exc
    m = re.match(r"^at ([\w$.<>/-]+)\(", ours[0])
    where = ".".join(m.group(1).split(".")[-2:]) if m else ""
    return f"{exc} at {where}" if where else exc


def body(report, plan, names):
    head = []
    if plan["sig"]:
        head.append(SIG_MARK.format(plan["sig"]))
    if plan["reporter"]:
        head.append(REPORTER_MARK.format(plan["reporter"]))
    if plan["anonymous"]:
        head.append(ANONYMOUS_MARK)
    head.append(ID_MARK.format(report["id"]))
    parts = ["\n".join(head)]
    if plan["anonymous"]:
        parts.append("## Unauthenticated report\n\nSent without a sign-in, so anyone could have sent "
                     "it. It is never merged into another issue and never raises a priority.")
    parts.append(f"Filed automatically from problem report `{report['id']}` (#811). The full report, "
                 "with its event log, stays on the server; this is a redacted copy.")
    parts.append("\n".join([
        "| | |", "|---|---|",
        f"| Kind | `{field(report.get('kind'), names)}` |",
        f"| App version | `{field(report.get('appVersion'), names)}` |",
        f"| Platform | `{field(report.get('platform'), names)}` |",
        f"| OS | `{field(report.get('osVersion'), names)}` |",
        f"| Device | `{field(report.get('deviceModel'), names)}` |",
    ]))
    if plan["lines"]:
        stack = redact("\n".join(plan["lines"]), names)
        parts.append("**Stack** (exception classes and frames; messages dropped)\n\n" + fenced(stack))
    note = redact(report.get("note") or "", names).strip()
    if note:
        parts.append("**Note**\n\n" + fenced(note))
    return "\n\n".join(parts) + "\n"


def plus_one(report, plan, names):
    return (REPORTER_MARK.format(plan["reporter"]) + "\n" + ID_MARK.format(report["id"]) + "\n"
            f"+1 (`{field(report.get('appVersion'), names)}`, `{field(report.get('deviceModel'), names)}`)"
            f" · report `{report['id']}`\n")


def header_marks(text):
    """The problem-report markers at the very top of an issue body or comment. Only the top
    counts: anything below is report text, and a note could be written to look like a marker."""
    marks = []
    for line in (text or "").splitlines():
        m = MARK_LINE.match(line.strip())
        if not m:
            break
        marks.append((m.group(1), m.group(2)))
    return marks


# --- Deciding ------------------------------------------------------------------------------------

def plan_for(report):
    lines = stack_lines(report.get("stackTrace"))
    sig = signature(lines)
    who = report.get("reporter")
    anonymous = not who
    # Only a signed-in report with a stack dedupes and escalates. An anonymous one could be
    # anybody's, a manual one without frames has nothing to match on.
    crash = bool(sig) and not anonymous
    return {
        "lines": lines,
        "sig": sig if crash else None,
        "reporter": reporter_mark(who, sig) if crash else None,
        "anonymous": anonymous,
        "labels": ["bug", "p1-next"] if crash else ["bug", "needs-triage"],
    }


def title_for(report, plan, names):
    platform = field(report.get("platform"), names)
    version = field(report.get("appVersion"), names)
    if plan["anonymous"]:
        return f"Unauthenticated problem report ({platform} {version})"
    if plan["sig"]:
        return f"Crash: {headline(plan['lines'])} ({platform} {version})"
    return f"Problem report ({platform} {version})"


class Filer:
    """Files one run's reports. `api` and `gh` are the only side-effecting seams.

    Only issues and comments the filer's own account wrote are read for markers. The repo is
    public: anyone can open an issue or comment whose first lines look like a marker, and
    trusting those would let a stranger escalate an issue to p0-now, take over a signature, or
    have a report marked filed against their issue."""

    def __init__(self, api, gh, names, dry_run=False, out=sys.stdout):
        self.api, self.gh, self.names, self.dry_run, self.out = api, gh, names, dry_run, out
        self.me = gh.me()
        self.by_sig = {}
        self.filed_ids = set()
        for issue in self._own_issues():
            marks = header_marks(issue.get("body"))
            for kind, value in marks:
                if kind == "sig":
                    self.by_sig.setdefault(value, issue)
                if kind == "id":
                    self.filed_ids.add(value)

    def file(self, report):
        plan = plan_for(report)
        rid = report["id"]
        if rid in self.filed_ids:
            # Opened by an earlier run that died before marking it filed.
            url = next(i["url"] for i in self._own_issues() if ("id", rid) in header_marks(i.get("body")))
            return self._mark(rid, url, "already filed")
        issue = self.by_sig.get(plan["sig"]) if plan["sig"] else None
        if issue:
            return self._plus_one(report, plan, issue)
        title = title_for(report, plan, self.names)
        text = body(report, plan, self.names)
        if self.dry_run:
            print(f"--- would open: {title}  [{', '.join(plan['labels'])}]\n{text}", file=self.out)
            if plan["sig"]:
                self.by_sig[plan["sig"]] = {"number": 0, "url": "(new issue)", "labels": plan["labels"]}
            return
        url = self.gh.create_issue(title, text, plan["labels"])
        if plan["sig"]:
            self.by_sig[plan["sig"]] = {"number": int(url.rstrip("/").rsplit("/", 1)[-1]), "url": url,
                                        "labels": plan["labels"], "body": text}
        self._mark(rid, url, "opened")

    def _plus_one(self, report, plan, issue):
        rid = report["id"]
        if self.dry_run:
            print(f"--- would +1 {issue['url']}\n{plus_one(report, plan, self.names)}", file=self.out)
            return
        number = issue["number"]
        comments = [c["body"] for c in self.gh.comments(number) if c.get("author") == self.me]
        texts = [issue.get("body")] + comments
        if ("id", rid) not in [m for t in comments for m in header_marks(t)]:
            text = plus_one(report, plan, self.names)
            self.gh.comment(number, text)
            texts.append(text)
        reporters = {v for t in texts for k, v in header_marks(t) if k == "reporter"}
        labels = set(self.gh.labels(number))
        # Swap only the label this script set: a priority the owner chose by hand stays.
        if len(reporters) >= ESCALATE_AT and "p1-next" in labels:
            self.gh.swap_label(number, "p1-next", "p0-now")
        self._mark(rid, issue["url"], "+1")

    def _own_issues(self):
        return [i for i in self.gh.open_issues() if i.get("author") == self.me]

    def _mark(self, rid, url, what):
        if self.dry_run:
            print(f"--- would mark {rid} filed as {url}", file=self.out)
            return
        self.api.post(f"/api/admin/problem-reports/{rid}/filed", {"issue": url})
        print(f"{rid}: {what} {url}", file=self.out)


def unfiled(api):
    """Every unfiled report, paged by cursor so a dry run or a failed report can't loop."""
    reports, after = [], None
    while True:
        q = {"filed": "false", "limit": str(PAGE)}
        if after:
            q["after"] = after
        page = api.get("/api/admin/problem-reports?" + urllib.parse.urlencode(q))["reports"]
        if not page:
            return reports
        reports += page
        after = page[-1]["id"]


def run(api, gh, dry_run=False, out=sys.stdout, err=sys.stderr):
    """Returns the exit code: 0, or 1 if any report failed (the rest are still filed)."""
    accounts = api.get("/api/admin/accounts")["accounts"]
    reports = unfiled(api)
    filer = Filer(api, gh, known_names(accounts), dry_run, out)
    failed = 0
    for report in reports:
        try:
            filer.file(report)
        except Exception as e:  # noqa: BLE001 — one bad report must not stop the rest
            failed += 1
            print(f"{report.get('id')}: failed: {e}", file=err)
    print(f"{len(reports)} report(s), {failed} failed", file=out)
    return 1 if failed else 0


# --- The real seams ------------------------------------------------------------------------------

class Api:
    def __init__(self, env):
        self.base = env["DETOUR_API"].rstrip("/")
        form = urllib.parse.urlencode({
            "grant_type": "client_credentials",
            "client_id": env["LOOP_CLIENT_ID"],
            "client_secret": env["LOOP_CLIENT_SECRET"],
        }).encode()
        req = urllib.request.Request(env["KEYCLOAK_TOKEN_URL"], data=form, method="POST")
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as r:
            self.token = json.load(r)["access_token"]

    def _call(self, method, path, payload=None):
        data = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as r:
            raw = r.read()
        return json.loads(raw) if raw else None

    def get(self, path):
        return self._call("GET", path)

    def post(self, path, payload):
        return self._call("POST", path, payload)


class Gh:
    def __init__(self, repo=None):
        self.repo = ["-R", repo] if repo else []
        self._issues = None
        self._me = None

    def _gh(self, *args):
        return subprocess.run(["gh", *args, *self.repo], check=True, capture_output=True, text=True).stdout

    def me(self):
        """The login gh is signed in as: the only author whose markers count."""
        if self._me is None:
            self._me = subprocess.run(["gh", "api", "user", "--jq", ".login"], check=True,
                                      capture_output=True, text=True).stdout.strip()
        return self._me

    def open_issues(self):
        if self._issues is None:
            issues = json.loads(self._gh("issue", "list", "--state", "open", "--limit", "1000",
                                         "--json", "number,url,body,author"))
            self._issues = [dict(i, author=(i.get("author") or {}).get("login")) for i in issues]
        return self._issues

    def comments(self, number):
        """Each comment as {"body", "author"}, author being the login."""
        raw = json.loads(self._gh("issue", "view", str(number), "--json", "comments"))["comments"]
        return [{"body": c["body"], "author": (c.get("author") or {}).get("login")} for c in raw]

    def labels(self, number):
        return [l["name"] for l in json.loads(self._gh("issue", "view", str(number), "--json", "labels"))["labels"]]

    def create_issue(self, title, text, labels):
        return self._gh("issue", "create", "--title", title, "--body", text, "--label", ",".join(labels)).strip()

    def comment(self, number, text):
        self._gh("issue", "comment", str(number), "--body", text)

    def swap_label(self, number, remove, add):
        self._gh("issue", "edit", str(number), "--remove-label", remove, "--add-label", add)


def load_env(path):
    env = {}
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.removeprefix("export ").split("=", 1)
                    env[k.strip()] = v.strip().strip("'\"")
    env.update({k: os.environ[k] for k in ENV_KEYS if os.environ.get(k)})
    missing = [k for k in ENV_KEYS if not env.get(k)]
    if missing:
        sys.exit(f"missing in {path}: {', '.join(missing)}")
    return env


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--dry-run", action="store_true", help="print what would be filed; write nothing")
    p.add_argument("--env", default=ENV_FILE)
    p.add_argument("--repo", help="owner/name for gh; default is the checkout's")
    a = p.parse_args()
    env = load_env(a.env)
    sys.exit(run(Api(env), Gh(a.repo), a.dry_run))


if __name__ == "__main__":
    main()
