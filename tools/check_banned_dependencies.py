from pathlib import Path

banned = ["exoplayer", "ijkplayer", "androidvideocache", "android.media.mediaplayer", "retrofit"]
paths = []
for root in [Path("android-app"), Path("android-app/app/src/main/java")]:
    for pattern in ("*.gradle", "*.gradle.kts", "*.kt"):
        paths.extend(path for path in root.rglob(pattern) if path.is_file())
hits = []
for path in paths:
    text = path.read_text(encoding="utf-8", errors="ignore").lower()
    for word in banned:
        if word in text:
            if path.name in {"README.md", "DECISIONS.md"}:
                continue
            hits.append(f"{path}: {word}")

if hits:
    print("\n".join(hits))
    raise SystemExit(1)

print("Banned dependency/code scan OK")
