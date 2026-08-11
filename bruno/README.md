# Bruno collection

39 requests covering every endpoint the backend exposes, grouped by controller.

Open `bruno/detour` in [Bruno](https://usebruno.com), pick the **local**
environment, and point it at the stack from [`docker/dev`](../docker/dev/README.md).

## Generated, not hand-maintained

`bruno/generate.py` builds the request folders from the API's own OpenAPI
document. Keeping 39 requests in step with controllers by hand is a collection
that silently rots the first time a route changes — a request that 404s because
the path moved looks exactly like a broken feature.

```bash
# with the API running
python3 bruno/generate.py
```

Anything under a request folder is overwritten. Two things are **not** generated
and are yours to edit:

| File | Holds |
|---|---|
| `detour/collection.bru` | the OAuth flow every rider request inherits |
| `detour/environments/local.bru` | base URL, IdP endpoints, path and query placeholders |

Request names come from each endpoint's `EndpointSummary`, and the `docs` block
from its `EndpointDescription` — so the reasoning written next to a controller
shows up in Bruno rather than being trapped in the source.

## The three credentials

The backend has three ways in, and the collection mirrors them exactly.

**Rider session** — most folders. Authorization code with PKCE against Keycloak,
inherited from `collection.bru`. There is no client secret because `detour-app`
is a public client; a native app cannot keep one. Bruno opens a browser the
first time a request needs a token.

The realm's password grant is deliberately **off**, so there is no shortcut. If
you want one for a throwaway experiment, enable `directAccessGrantsEnabled` on
`detour-app` in the Keycloak console — and turn it back off, because it is a
standing invitation to ship a password form in a client that should be using a
browser.

**Dashboard key** — the `Dashboard` folder only. Read-only, and it reads its own
owner's data and nothing else. Issue one with *ApiKeys → Issue a read-only
dashboard key*, then paste it into the `API_KEY` variable; the plaintext is
returned once and is unrecoverable afterwards.

**Nothing** — `Health`.

`Admin` inherits the ordinary rider flow. Whether a request is answered depends
on the `detour-admin` realm role on the account you signed in as, not on a
different credential.

## Running it headless

```bash
cd bruno/detour
npx @usebruno/cli run -r --env local
```

Expect `Health` to pass and the rest to return 401: the CLI cannot perform the
browser leg of the PKCE flow. That is still a useful check — it proves every
request parses and every URL resolves, which is what catches a generator or
routing regression. For assertions that actually exercise behaviour, the
integration tests in `backend/Detour/Detour.InfraTests` are the right tool.

## Filling in placeholders

Requests reference path and query values as variables rather than inline
literals, so editing the environment survives a regeneration and editing a URL
does not. `id`, `username` and the numeric query parameters all live in
`environments/local.bru`.

The numeric ones default to `0`, which every endpoint reads as "use the
default". Out-of-range values are clamped rather than refused — a dashboard URL
gets typed by hand into a config file, and refusing it helps nobody.
