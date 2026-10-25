"""Read actual APK metadata/signature. Never infer Android versionCode from a tag."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("channel", choices=("dev", "stable"))
parser.add_argument("tag")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--previous", type=Path)
args = parser.parse_args()
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
build_tools = sdk / "build-tools" / "35.0.0"
windows = os.name == "nt"
aapt = build_tools / ("aapt.exe" if windows else "aapt")
signer = build_tools / ("apksigner.bat" if windows else "apksigner")
badging = subprocess.check_output([str(aapt), "dump", "badging", str(args.apk)], text=True)
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
minimum = re.search(r"sdkVersion:'(\d+)'", badging)
assert package and minimum, "APK metadata missing"
verified = subprocess.check_output([str(signer), "verify", "--print-certs", str(args.apk)], text=True)
digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", verified)
assert len(digests) == 1, "Exactly one APK signer is required"
expected_package = "no.oktodrive.app" + (".dev" if args.channel == "dev" else "")
assert package[1] == expected_package, "Wrong app variant"
assert re.fullmatch(r"[A-Za-z0-9._-]+", args.tag), "Invalid tag"
metadata = {
    "applicationId": package[1], "versionCode": int(package[2]),
    "versionName": package[3], "minSdk": int(minimum[1]),
    "certificateSha256": digests[0].lower(),
    "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
    "sourceCommit": os.environ.get("GITHUB_SHA", "local"),
    "downloadUrl": "https://github.com/Oktopustrh/OktoDrive/releases/download/"
        + args.tag + "/OktoDrive-" + args.channel + ".apk",
}
if args.previous and args.previous.exists():
    previous = json.loads(args.previous.read_text())
    assert metadata["applicationId"] == previous["applicationId"], "Package changed"
    assert metadata["certificateSha256"] == previous["certificateSha256"], "Signing key changed"
    assert metadata["versionCode"] > previous["versionCode"], "Increase versionCode before publishing"
args.output.write_text(json.dumps(metadata, indent=2) + "\n")
