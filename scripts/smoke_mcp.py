#!/usr/bin/env python3
"""Acceptance test for the Minecraft MCP server.

Runs the full scenario: initialization, tool listing, building, mob spawning,
item grants, effects and a screenshot.

Usage:
    python scripts/smoke_mcp.py [--url http://127.0.0.1:8765/mcp] [--token ...]
"""

from __future__ import annotations

import argparse
import base64
import json
import pathlib
import sys
import urllib.error
import urllib.request

DEFAULT_URL = "http://127.0.0.1:8765/mcp"
PROTOCOL_VERSION = "2025-06-18"


class McpClient:
    def __init__(self, url: str, token: str | None = None, timeout: float = 120.0) -> None:
        self.url = url
        self.token = token
        self.timeout = timeout
        self._id = 0
        self.session_id: str | None = None

    def _request(self, payload: dict) -> dict:
        data = json.dumps(payload).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id

        req = urllib.request.Request(self.url, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                sid = resp.headers.get("Mcp-Session-Id")
                if sid:
                    self.session_id = sid
                raw = resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"HTTP {exc.code}: {body}") from exc

        if not raw.strip():
            return {}
        return json.loads(raw)

    def _call(self, method: str, params: dict | None = None) -> dict:
        self._id += 1
        payload = {"jsonrpc": "2.0", "id": self._id, "method": method}
        if params is not None:
            payload["params"] = params
        response = self._request(payload)
        if "error" in response:
            err = response["error"]
            raise RuntimeError(f"{method} -> error {err.get('code')}: {err.get('message')}")
        return response.get("result", {})

    def initialize(self) -> dict:
        result = self._call("initialize", {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "dsh-smoke-test", "version": "1.0.0"},
        })
        self._request({"jsonrpc": "2.0", "method": "notifications/initialized"})
        return result

    def list_tools(self) -> list[dict]:
        return self._call("tools/list").get("tools", [])

    def call_tool(self, name: str, arguments: dict | None = None) -> dict:
        return self._call("tools/call", {"name": name, "arguments": arguments or {}})


def text_of(result: dict) -> str:
    parts = []
    for block in result.get("content", []):
        if block.get("type") == "text":
            parts.append(block.get("text", ""))
    return "\n".join(parts)


def parse_json(result: dict) -> dict:
    if result.get("structuredContent"):
        return result["structuredContent"]
    raw = text_of(result)
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"_raw": raw}


def check(label: str, condition: bool, detail: str = "") -> bool:
    mark = "OK  " if condition else "FAIL"
    print(f"[{mark}] {label}" + (f" — {detail}" if detail else ""))
    return condition


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--token", default=None)
    parser.add_argument("--skip-visual", action="store_true", help="Skip world-mutating checks")
    args = parser.parse_args()

    client = McpClient(args.url, args.token)
    failures = 0

    print(f"Connecting to {args.url}")
    try:
        init = client.initialize()
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] Could not connect: {exc}")
        return 1

    info = init.get("serverInfo", {})
    failures += not check("initialize", info.get("name") == "minecraft-mcp", json.dumps(info, ensure_ascii=False))

    tools = client.list_tools()
    names = [t["name"] for t in tools]
    failures += not check("tools/list", len(tools) >= 20, f"{len(tools)} tools")
    for required in ("mc_version", "mc_get_world_info", "mc_set_block", "mc_fill_blocks",
                     "mc_spawn_entity", "mc_give_item", "mc_set_effects", "mc_build_shape",
                     "mc_run_command", "mc_screenshot", "mc_draw_block_art"):
        failures += not check(f"tool {required}", required in names)

    version = parse_json(client.call_tool("mc_version"))
    failures += not check("mc_version", bool(version.get("mod_version")), json.dumps(version))

    world = parse_json(client.call_tool("mc_get_world_info"))
    players = world.get("players") or []
    failures += not check("mc_get_world_info", bool(players), json.dumps(world)[:200])
    if not players:
        print("No player in the world — further checks are impossible")
        return 1 if failures else 0

    player = players[0]
    px, py, pz = int(player["x"]), int(player["y"]), int(player["z"])
    print(f"Player {player['name']} at {px} {py} {pz} ({player['dimension']})")

    if args.skip_visual:
        print(f"\nTotal failures: {failures}")
        return 1 if failures else 0

    # Build a platform next to the player to keep the landscape tidy.
    bx, by, bz = px + 6, py, pz + 6

    res = parse_json(client.call_tool("mc_fill_blocks", {
        "from": {"x": bx, "y": by, "z": bz},
        "to": {"x": bx + 9, "y": by + 9, "z": bz + 9},
        "block": "minecraft:stone",
    }))
    failures += not check("mc_fill_blocks (10^3 cube)", res.get("placed") == 1000, json.dumps(res))

    res = parse_json(client.call_tool("mc_get_block", {"x": bx + 5, "y": by + 5, "z": bz + 5}))
    failures += not check("mc_get_block", res.get("block") == "minecraft:stone", json.dumps(res))

    res = parse_json(client.call_tool("mc_build_shape", {
        "shape": "sphere", "x": bx + 5, "y": by + 15, "z": bz + 5,
        "block": "minecraft:gold_block", "radius": 4,
    }))
    failures += not check("mc_build_shape (sphere)", res.get("placed", 0) > 0, json.dumps(res))

    res = parse_json(client.call_tool("mc_draw_block_art", {
        "x": bx, "y": by + 11, "z": bz,
        "rows": ["  ####  ", " ###### ", "########"],
        "palette": {"#": "minecraft:red_wool"},
        "axis": "xz",
    }))
    failures += not check("mc_draw_block_art", res.get("placed", 0) > 0, json.dumps(res))

    res = parse_json(client.call_tool("mc_spawn_entity", {
        "type": "minecraft:zombie", "x": bx + 2, "y": by + 10, "z": bz + 2,
        "count": 3, "custom_name": "MCP Zombie", "glowing": True,
    }))
    failures += not check("mc_spawn_entity", res.get("spawned") == 3, json.dumps(res))

    res = parse_json(client.call_tool("mc_list_entities", {"radius": 32, "type": "minecraft:zombie"}))
    failures += not check("mc_list_entities", res.get("count", 0) >= 3, f"found {res.get('count', 0)}")

    res = parse_json(client.call_tool("mc_give_item", {"item": "minecraft:diamond", "count": 16}))
    failures += not check("mc_give_item", res.get("count") == 16, json.dumps(res))

    res = parse_json(client.call_tool("mc_set_effects", {
        "effects": [{"id": "minecraft:speed", "duration_seconds": 60, "amplifier": 1},
                    {"id": "minecraft:regeneration", "duration_seconds": 30}],
    }))
    failures += not check("mc_set_effects", res.get("applied") == 2, json.dumps(res))

    res = parse_json(client.call_tool("mc_get_player_state", {}))
    effects = res.get("effects") or []
    failures += not check("mc_get_player_state (effects)", len(effects) >= 2, f"{len(effects)} effects")

    res = parse_json(client.call_tool("mc_set_time", {"value": "noon"}))
    failures += not check("mc_set_time", "time" in res, json.dumps(res))

    res = parse_json(client.call_tool("mc_run_command", {"command": "weather clear"}))
    failures += not check("mc_run_command", res.get("executed") is True, json.dumps(res))

    res = parse_json(client.call_tool("mc_screenshot", {}))
    b64 = res.get("image_base64", "")
    ok_png = False
    if b64:
        decoded = base64.b64decode(b64)
        ok_png = decoded[:8] == b"\x89PNG\r\n\x1a\n"
        if ok_png:
            out = pathlib.Path("scripts/out")
            out.mkdir(parents=True, exist_ok=True)
            (out / "smoke_screenshot.png").write_bytes(decoded)
            print(f"     screenshot saved: scripts/out/smoke_screenshot.png ({len(decoded)} bytes)")
    failures += not check("mc_screenshot (PNG)", ok_png, f"{len(b64)} base64 chars")

    print(f"\nTotal failures: {failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
