"""Mock backend for Fuel Alert Android app development (PRD.md ss6,
PROGRESS.md milestone 1).

Serves the same two endpoints as ../../proxy_server.py
(/proxy/tomtom-route, /proxy/nsw-stations) with canned data shaped
exactly like the real NSW FuelCheck / TomTom responses - matching the
field names static/app.js already parses (raw.routes[0].summary,
raw.stations[].location, raw.prices[].stationcode, etc.) - so
PriceFetcher/RouteMatcher (milestone 2) can be built and tested against
a stable contract before real backend hosting (still an open PRD
dependency) exists.

Run: python3 tools/mock_backend.py
Then point the Android app's BuildConfig.BACKEND_BASE_URL at it - the
debug build already defaults to http://10.0.2.2:8765, which is the
Android emulator's alias for this machine's localhost.
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = 8765

# A handful of fixed, clearly-fake stations offset from whatever
# position is queried, so the mock behaves consistently regardless of
# what coordinates the app under test sends - realistic enough to
# exercise sorting/filtering/corridor-distance logic, without pretending
# to be real station data.
_MOCK_STATION_OFFSETS = [
    # (name, brand, lat_offset, lon_offset, price)
    ("Mock Servo North", "BrandA", 0.01, 0.00, 1.72),
    ("Mock Servo East", "BrandB", 0.00, 0.015, 1.79),
    ("Mock Servo South", "BrandC", -0.008, 0.00, 1.68),
    ("Mock Servo West", "BrandA", 0.00, -0.012, 1.85),
    ("Mock Servo Far", "BrandD", 0.03, 0.03, 1.65),
]


def _mock_stations(lat: float, lon: float, fuel_type: str) -> dict:
    stations = []
    prices = []
    for i, (name, brand, dlat, dlon, price) in enumerate(_MOCK_STATION_OFFSETS):
        code = f"MOCK{i:03d}"
        stations.append(
            {
                "code": code,
                "name": name,
                "brand": brand,
                "address": f"{i} Mock Street",
                "location": {"latitude": lat + dlat, "longitude": lon + dlon},
            }
        )
        prices.append({"stationcode": code, "price": price * 100})  # cents, matches real API
    return {"stations": stations, "prices": prices}


def _mock_route(olat: float, olon: float, dlat: float, dlon: float, route_type: str) -> dict:
    # Straight-line points between origin/dest rather than a real road
    # route - fine for a mock exercising the app's own math, not TomTom's.
    steps = 20
    points = [
        {
            "latitude": olat + (dlat - olat) * i / steps,
            "longitude": olon + (dlon - olon) * i / steps,
        }
        for i in range(steps + 1)
    ]
    # Rough haversine so distance/time aren't nonsense zeros.
    import math

    r = 6371000
    p1, p2 = math.radians(olat), math.radians(dlat)
    dphi = math.radians(dlat - olat)
    dlmb = math.radians(dlon - olon)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    length_m = r * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a)) * 1.3  # road-vs-straight-line fudge
    mins = length_m / 1000 / 80 * 60  # assume 80km/h average

    return {
        "routes": [
            {
                "summary": {
                    "lengthInMeters": length_m,
                    "travelTimeInSeconds": mins * 60,
                },
                "legs": [{"points": points}],
            }
        ]
    }


class _MockHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        pass

    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/proxy/tomtom-route":
            q = parse_qs(parsed.query)
            try:
                olat, olon = float(q["olat"][0]), float(q["olon"][0])
                dlat, dlon = float(q["dlat"][0]), float(q["dlon"][0])
                route_type = q.get("routeType", ["fastest"])[0]
            except (KeyError, ValueError, IndexError):
                self._send_json(400, {"error": "missing olat/olon/dlat/dlon"})
                return
            self._send_json(200, _mock_route(olat, olon, dlat, dlon, route_type))
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path != "/proxy/nsw-stations":
            self._send_json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length", 0))
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
            lat, lon = float(body["lat"]), float(body["lon"])
            fuel_type = body["fuelType"]
        except (KeyError, ValueError, json.JSONDecodeError):
            self._send_json(400, {"error": "expected {fuelType, lat, lon, radius}"})
            return
        self._send_json(200, _mock_stations(lat, lon, fuel_type))


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), _MockHandler)
    print(f"Mock backend listening on http://127.0.0.1:{PORT}")
    server.serve_forever()
