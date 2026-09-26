"""Extract an actual pack profile to an isolated-run sidecar; never alters the input ZIP."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def extract(pack: Path, profile: str):
    with zipfile.ZipFile(pack) as archive:
        raw = archive.read("shaders/shaders.properties").decode()
    profiles = dict(re.findall(r"(?m)^\s*profile\.([A-Za-z0-9_]+)\s*=\s*(.+)$", raw.replace("\\\n", "")))
    def resolve(name, chain=()):
        if name in chain or name not in profiles:
            raise ValueError(f"Missing or cyclic profile: {name}")
        options = {}
        for token in profiles[name].split():
            if token.startswith("profile."):
                options.update(resolve(token[8:], chain + (name,)))
            elif token.startswith("!"):
                options[token[1:]] = "false"
            elif "=" in token:
                key, value = token.split("=", 1)
                options[key] = value
            else:
                options[token] = "true"
        return options
    return resolve(profile), profiles[profile]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--profile", default="ULTRA")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() == args.pack.resolve():
        raise ValueError("Profile output must not replace the pack ZIP")
    options, definition = extract(args.pack, args.profile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(f"{name}={value}\n" for name, value in sorted(options.items())))
    report = {"pack": args.pack.name, "sha256": hashlib.sha256(args.pack.read_bytes()).hexdigest(), "profile": args.profile,
              "definition": definition, "options": options}
    args.output.with_suffix(args.output.suffix + ".profile.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
