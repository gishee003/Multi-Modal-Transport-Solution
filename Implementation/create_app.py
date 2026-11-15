"""Creates an MMTS application folder from app-template.

Usage:  python create_app.py <folder> <short-name> <package> <ClassPrefix> <port> <APP-id> "<Application title>"
Example: python create_app.py app-04-identity identity identity Identity 8084 APP-04 "Identity & Verification Service"

Copies app-template, then renames the package, class, artifact, application name, port, database name and README title.
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TEMPLATE = ROOT / "app-template"


def main():
    folder, short, package, prefix, port, app_id, title = sys.argv[1:8]
    target = ROOT / folder
    if target.exists():
        sys.exit(f"{target} already exists; not overwriting")
    shutil.copytree(TEMPLATE, target, ignore=shutil.ignore_patterns("target"))

    # Move the Java packages: com/mmts/template -> com/mmts/<package>
    for kind in ("main", "test"):
        old = target / "src" / kind / "java" / "com" / "mmts" / "template"
        if old.exists():
            old.rename(old.parent / package)
    app_class = target / "src" / "main" / "java" / "com" / "mmts" / package / "TemplateApplication.java"
    app_class.rename(app_class.with_name(f"{prefix}Application.java"))

    replacements = [
        ("com.mmts.template", f"com.mmts.{package}"),
        ("TemplateApplication", f"{prefix}Application"),
        ("mmts-template", f"mmts-{short}"),
        ("mmts_template", f"mmts_{short.replace('-', '_')}"),
        ("port: 8080", f"port: {port}"),
        ("localhost:8080", f"localhost:{port}"),
        ("app-id: APP-00", f"app-id: {app_id}"),
        ("# APP-00 Template", f"# {app_id} {title}"),
        ("port 8080", f"port {port}"),
    ]
    for path in target.rglob("*"):
        if path.is_file() and path.suffix in {".java", ".xml", ".yml", ".md"}:
            text = path.read_text(encoding="utf-8")
            for old, new in replacements:
                text = text.replace(old, new)
            path.write_text(text, encoding="utf-8")
    print(f"created {folder}")


if __name__ == "__main__":
    main()
