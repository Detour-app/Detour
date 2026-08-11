#!/usr/bin/env python3
"""Regenerate the Bruno collection from the API's own OpenAPI document.

The collection is generated, not hand-maintained: 39 requests kept in step with
controllers by hand is a collection that silently rots the first time a route
changes. Anything you edit under the request folders will be overwritten.

The two files that are *not* generated — `collection.bru` and `environments/` —
hold the variables and the OAuth flow, and are yours to edit.

    # with the API running (see backend/README.md)
    python3 bruno/generate.py --url http://localhost:7500/openapi/v1.json

    # or from a spec you already have
    python3 bruno/generate.py --spec openapi.json
"""
import argparse
import json
import pathlib
import re
import shutil
import sys
import urllib.request

# Folders whose requests authenticate with a dashboard key rather than a rider
# session. Everything the key can reach is read-only by construction.
API_KEY_TAGS = {"Dashboard"}

# Reachable with no credential at all.
ANONYMOUS_TAGS = {"Health"}

MAX_EXAMPLE_DEPTH = 6


def fetch(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=30) as response:  # noqa: S310 - a localhost dev URL
        return json.load(response)


def resolve(spec: dict, schema: dict, depth: int = 0) -> dict:
    """Follow a $ref one level. Returns the schema unchanged when it is not a ref."""
    if depth > MAX_EXAMPLE_DEPTH or "$ref" not in schema:
        return schema

    name = schema["$ref"].rsplit("/", 1)[-1]
    return spec.get("components", {}).get("schemas", {}).get(name, {})


def example(spec: dict, schema: dict, depth: int = 0) -> object:
    """A plausible value for a schema, for a request body skeleton."""
    if depth > MAX_EXAMPLE_DEPTH:
        return None

    schema = resolve(spec, schema or {}, depth)

    for combinator in ("allOf", "oneOf", "anyOf"):
        branches = schema.get(combinator)
        if branches:
            # A nullable property is `oneOf: [{type: null}, {...}]`, and the null
            # branch is usually first. Picking it would produce a skeleton whose
            # every optional object is just `null` — useless to fill in.
            real = next((b for b in branches if b.get("type") != "null"), branches[0])
            return example(spec, real, depth + 1)

    if "example" in schema:
        return schema["example"]
    if schema.get("enum"):
        return schema["enum"][0]

    types = schema.get("type")
    if isinstance(types, list):
        types = next((t for t in types if t != "null"), None)

    if types == "object" or "properties" in schema:
        if schema.get("properties"):
            return {
                name: example(spec, prop, depth + 1)
                for name, prop in schema["properties"].items()
            }
        # A dictionary. One sample entry beats an empty object, which reads as
        # "this field takes nothing".
        if isinstance(schema.get("additionalProperties"), dict):
            return {"key": example(spec, schema["additionalProperties"], depth + 1)}
        return {}
    if types == "array":
        return [example(spec, schema.get("items") or {}, depth + 1)]
    if types == "integer":
        return 0
    if types == "number":
        return 0.0
    if types == "boolean":
        return False
    if types == "string":
        return {"date-time": "2026-01-01T00:00:00Z", "uuid": "00000000-0000-0000-0000-000000000000"}.get(
            schema.get("format"), "")

    return None


def slug(text: str) -> str:
    """A filename Bruno and every filesystem will accept."""
    cleaned = re.sub(r"[^\w\s-]", "", text).strip()
    return re.sub(r"\s+", " ", cleaned) or "request"


def indent(text: str, spaces: int = 2) -> str:
    pad = " " * spaces
    return "\n".join(pad + line if line.strip() else line for line in text.splitlines())


def request_bru(spec: dict, path: str, method: str, operation: dict, tag: str, seq: int) -> str:
    name = operation.get("summary", "").rstrip(".") or f"{method.upper()} {path}"

    parameters = operation.get("parameters") or []
    path_params = [p for p in parameters if p.get("in") == "path"]
    query_params = [p for p in parameters if p.get("in") == "query"]

    # Bruno wants :name in the URL and a params:path block beside it.
    url_path = re.sub(r"\{(\w+)\}", r":\1", path)
    url = "{{BASE_URL}}" + url_path
    if query_params:
        url += "?" + "&".join(f"{p['name']}={{{{{p['name']}}}}}" for p in query_params)

    if tag in ANONYMOUS_TAGS:
        auth = "none"
    elif tag in API_KEY_TAGS:
        auth = "none"  # the key travels as a header, set below
    else:
        auth = "inherit"

    body_schema = ((operation.get("requestBody") or {}).get("content") or {}).get("application/json", {}).get("schema")
    body_mode = "json" if body_schema else "none"

    blocks = [
        f"meta {{\n  name: {name}\n  type: http\n  seq: {seq}\n}}",
        f"{method} {{\n  url: {url}\n  body: {body_mode}\n  auth: {auth}\n}}",
    ]

    if query_params:
        lines = [f"  {p['name']}: {{{{{p['name']}}}}}" for p in query_params]
        blocks.append("params:query {\n" + "\n".join(lines) + "\n}")

    if path_params:
        lines = [f"  {p['name']}: {{{{{p['name']}}}}}" for p in path_params]
        blocks.append("params:path {\n" + "\n".join(lines) + "\n}")

    if tag in API_KEY_TAGS:
        blocks.append("headers {\n  X-Api-Key: {{API_KEY}}\n}")

    if body_schema:
        body = json.dumps(example(spec, body_schema), indent=2)
        blocks.append("body:json {\n" + indent(body) + "\n}")

    # Assert on the documented success status rather than a blanket 2xx, so a
    # route that starts answering 200 instead of 204 is a failing request.
    success = next((code for code in operation.get("responses", {}) if code.startswith("2")), None)
    if success:
        blocks.append(f"assert {{\n  res.status: eq {success}\n}}")

    docs = [operation.get("description") or ""]
    if operation.get("responses"):
        documented = [
            f"- {code}: {(body or {}).get('description', '').strip()}"
            for code, body in sorted(operation["responses"].items())
            if (body or {}).get("description")
        ]
        if documented:
            docs.append("Responses:\n" + "\n".join(documented))

    body_text = "\n\n".join(part.strip() for part in docs if part.strip())
    if body_text:
        blocks.append("docs {\n" + indent(body_text) + "\n}")

    return "\n\n".join(blocks) + "\n"


def folder_bru(tag: str, seq: int) -> str:
    if tag in ANONYMOUS_TAGS:
        note = "Reachable with no credential."
        auth = "auth {\n  mode: none\n}"
    elif tag in API_KEY_TAGS:
        note = ("Authenticated by a read-only dashboard key, not a rider session. "
                "Issue one with ApiKeys > Issue a read-only dashboard key and put it in API_KEY.")
        auth = "auth {\n  mode: none\n}"
    else:
        note = "A rider session. Inherits the collection's OAuth flow."
        auth = "auth {\n  mode: inherit\n}"

    return (f"meta {{\n  name: {tag}\n  seq: {seq}\n}}\n\n{auth}\n\n"
            f"docs {{\n{indent(note)}\n}}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--url", default="http://localhost:7500/openapi/v1.json")
    source.add_argument("--spec", type=pathlib.Path)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path(__file__).parent / "detour")
    args = parser.parse_args()

    spec = json.loads(args.spec.read_text()) if args.spec else fetch(args.url)

    by_tag: dict[str, list] = {}
    for path, methods in spec.get("paths", {}).items():
        for method, operation in methods.items():
            if method in {"parameters", "servers"}:
                continue
            tag = (operation.get("tags") or ["Other"])[0]
            by_tag.setdefault(tag, []).append((path, method, operation))

    # Wipe only the generated folders. collection.bru, bruno.json and
    # environments/ are hand-maintained and must survive.
    for existing in sorted(args.out.glob("*")):
        if existing.is_dir() and existing.name != "environments":
            shutil.rmtree(existing)

    written = 0
    for folder_seq, tag in enumerate(sorted(by_tag), start=1):
        folder = args.out / tag
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "folder.bru").write_text(folder_bru(tag, folder_seq))

        operations = sorted(by_tag[tag], key=lambda item: (item[0], item[1]))
        for seq, (path, method, operation) in enumerate(operations, start=1):
            name = slug(operation.get("summary", "").rstrip(".") or f"{method.upper()} {path}")
            (folder / f"{name}.bru").write_text(request_bru(spec, path, method, operation, tag, seq))
            written += 1

    print(f"{written} requests across {len(by_tag)} folders -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
