import atexit
from pathlib import Path


def _repair_v5_generated_kotlin() -> None:
    path = Path.cwd() / "offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    broken = 'liveStatus.text = "$icon  $title\n$detail"'
    fixed = 'liveStatus.text = "$icon  $title\\n$detail"'
    if broken in text:
        path.write_text(text.replace(broken, fixed, 1), encoding="utf-8")
        print("v5 post-patch repair: escaped liveStatus newline")


atexit.register(_repair_v5_generated_kotlin)
