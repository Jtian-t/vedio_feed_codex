import json
from pathlib import Path

catalog = json.loads(Path("android-app/app/src/main/assets/mock/videos.json").read_text(encoding="utf-8"))
items = catalog.get("items", [])
errors = []
ids = set()
playable = 0

for item in items:
    item_id = item.get("id")
    if item_id in ids:
        errors.append(f"duplicate id {item_id}")
    ids.add(item_id)
    if item.get("durationMs", 0) <= 0:
        errors.append(f"{item_id}: duration must be positive")
    if item.get("width", 0) <= 0 or item.get("height", 0) <= 0:
        errors.append(f"{item_id}: dimensions must be positive")
    mp4 = [v for v in item.get("variants", []) if v.get("mimeType") == "video/mp4"]
    if mp4:
        playable += 1
    elif item.get("optionalHlsUrl"):
        errors.append(f"{item_id}: HLS-only entry is unsupported")
    for variant in item.get("variants", []):
        if not variant.get("url", "").startswith(("https://", "http://")):
            errors.append(f"{item_id}/{variant.get('variantId')}: invalid URL")
        if variant.get("bitrateKbps", 0) <= 0:
            errors.append(f"{item_id}/{variant.get('variantId')}: invalid bitrate")

if playable < 100:
    errors.append(f"expected >=100 playable MP4 entries, got {playable}")

if errors:
    print("\n".join(errors))
    raise SystemExit(1)

print(f"Catalog OK: {len(items)} base items, {playable} playable MP4 entries")
