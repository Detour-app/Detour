package com.jellemax.detour.data

/**
 * HERE Traffic API v7 response bodies for the parser tests (#643).
 *
 * Not recorded from a live key — nobody on the project has one yet. Each is
 * HERE's own documented example (docs.here.com, Traffic API v7: "How to request
 * flow data", "How to request incident data", "Location referencing" for the
 * `shape` block, and the OpenAPI error schemas), with `locationReferencing=shape`
 * geometry in place of the `olr` strings the examples were captured with. Swap
 * in a real capture when a key exists; the shape is what the parser is held to.
 */
internal object HereTrafficFixtures {

    /** Two flow items. The first has two links that do not join (HERE says
     *  they need not); the second is a closed road. A third has no shape. */
    const val FLOW = """
{
  "sourceUpdated": "2021-06-08T10:07:23Z",
  "results": [
    {
      "location": {
        "description": "Alt-Friedrichsfelde",
        "length": 966.0,
        "shape": {
          "links": [
            {
              "points": [
                {"lat": 52.501569986343384, "lng": 13.702660035341978},
                {"lat": 52.50179001130164, "lng": 13.702860027551651},
                {"lat": 52.501959996297956, "lng": 13.703020038083196}
              ],
              "length": 50.0,
              "functionalClass": 3
            },
            {
              "points": [
                {"lat": 52.502879993990064, "lng": 13.703829981386662},
                {"lat": 52.50311, "lng": 13.70404}
              ],
              "length": 30.0,
              "functionalClass": 3
            }
          ]
        }
      },
      "currentFlow": {
        "speed": 8.333333,
        "speedUncapped": 8.611111,
        "freeFlow": 13.888889,
        "jamFactor": 4.2,
        "confidence": 0.99,
        "traversability": "open"
      }
    },
    {
      "location": {
        "description": "Rosenfelder Straße",
        "length": 929.0,
        "shape": {
          "links": [
            {
              "points": [
                {"lat": 52.5101, "lng": 13.4851},
                {"lat": 52.5112, "lng": 13.4863}
              ],
              "length": 145.0
            }
          ]
        }
      },
      "currentFlow": {
        "speed": 0.0,
        "freeFlow": 13.611112,
        "jamFactor": 10.0,
        "confidence": 0.99,
        "traversability": "closed"
      }
    },
    {
      "location": {"description": "No shape", "length": 120.0},
      "currentFlow": {"speed": 10.0, "freeFlow": 12.0, "jamFactor": 1.0, "traversability": "open"}
    }
  ]
}
"""

    /** Two incidents: one fully described, one with an unknown criticality,
     *  no description and a malformed start time. */
    const val INCIDENTS = """
{
  "sourceUpdated": "2021-07-06T10:22:01Z",
  "results": [
    {
      "location": {
        "length": 9310.0,
        "shape": {
          "links": [
            {
              "points": [
                {"lat": 51.0301, "lng": 6.9842},
                {"lat": 51.0322, "lng": 6.9871}
              ],
              "length": 300.0
            }
          ]
        }
      },
      "incidentDetails": {
        "id": "3195531507295233286",
        "originalId": "3195531507295233286",
        "startTime": "2019-12-03T23:00:00Z",
        "endTime": "2023-03-12T22:59:59Z",
        "entryTime": "2021-07-04T02:18:03Z",
        "roadClosed": true,
        "criticality": "major",
        "type": "construction",
        "typeDescription": {"value": "Sonstige Neuigkeiten", "language": "de-DE"},
        "codes": [803, 1851],
        "description": {"value": "Bei Leverkusen - Bauarbeiten auf Abschnitt."},
        "summary": {"value": "Bei Leverkusen - Bauarbeiten."}
      }
    },
    {
      "location": {
        "length": 2087.0,
        "shape": {"links": [{"points": [{"lat": 51.04, "lng": 6.99}], "length": 1.0}]}
      },
      "incidentDetails": {
        "id": "3704457286517849505",
        "startTime": "yesterday",
        "roadClosed": false,
        "criticality": "apocalyptic",
        "type": "accident",
        "summary": {"value": "Unfall"}
      }
    }
  ]
}
"""

    const val EMPTY = """{"sourceUpdated":"2021-06-08T10:07:23Z","results":[]}"""

    /** The 401 body from HERE's OpenAPI `UnauthorizedErrorResponse` example. */
    const val UNAUTHORIZED = """{"error":"Unauthorized","error_description":"ApiKey invalid. ApiKey not found."}"""

    /** A 400 in HERE's `ErrorResponse` shape. */
    const val BAD_REQUEST = """
{"title":"Malformed query. Invalid BBox size.","status":400,"code":"E608006",
 "action":"Check the error message for details.","correlationId":"4199533b-6290-41db-8d79-edf4f4019a74"}
"""
}
