#!/usr/bin/env python3
"""Turn security.crypto yaml into a Mongo keyring document, or compare fingerprints.

Normalizes RSA like CryptoPropertiesInitializer + RsaUtils. Needs python3 + openssl.

  # 手改库：生成可粘贴进 Mongo 的 JSON
  python check-crypto-keyring.py --yaml application.yaml --emit
  python check-crypto-keyring.py --yaml application.yaml --emit --out keyring.json

  # 只看配置 fingerprint / 跟已导出的库文档比
  python check-crypto-keyring.py --yaml application.yaml
  python check-crypto-keyring.py --yaml application.yaml --keyring keyring.json
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Dict, List, Optional

FIELDS = [
    "rsaAlgorithm",
    "privateKeyStr",
    "publicKeyStr",
    "privateKeyStr2048PKCS8",
    "publicKeyStr2048PKCS8",
    "privateKeyStr2048PKCS1",
    "publicKeyStr2048PKCS1",
    "aesKey",
    "aesIv",
]
PRIV_PUB = [
    ("privateKeyStr", "publicKeyStr"),
    ("privateKeyStr2048PKCS8", "publicKeyStr2048PKCS8"),
    ("privateKeyStr2048PKCS1", "publicKeyStr2048PKCS1"),
]
DEFAULT_ALGO = "RSA/ECB/PKCS1Padding"


def openssl_bin() -> str:
    path = shutil.which("openssl") or shutil.which("openssl.exe")
    if path:
        return path
    extras = [
        r"C:\Program Files\Git\usr\bin\openssl.exe",
        r"C:\Program Files\Git\mingw64\bin\openssl.exe",
    ]
    for extra in extras:
        if os.path.isfile(extra):
            return extra
    sys.exit("openssl not found in PATH")


def run_openssl(args: List[str]) -> bytes:
    proc = subprocess.run(
        [openssl_bin(), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", "replace").strip()
        sys.exit("openssl {0} failed: {1}".format(" ".join(args[:3]), err))
    return proc.stdout


def clean(value: str) -> str:
    trimmed = value.strip()
    unresolved = trimmed.startswith("${") or (
        trimmed.startswith("__") and trimmed.endswith("__")
    )
    return "" if unresolved else trimmed


def strip_quotes(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def normalize_private(pem_or_b64: str) -> str:
    raw = clean(pem_or_b64)
    if not raw:
        return ""
    with tempfile.NamedTemporaryFile(delete=False) as tmp:
        src = tmp.name
    try:
        if raw.startswith("-----"):
            blob = raw if raw.endswith("\n") else raw + "\n"
            with open(src, "wb") as fh:
                fh.write(blob.encode("ascii"))
            der = run_openssl(
                [
                    "pkcs8", "-topk8", "-inform", "PEM", "-outform", "DER",
                    "-nocrypt", "-in", src,
                ]
            )
        else:
            der_in = base64.b64decode(re.sub(r"\s+", "", raw))
            with open(src, "wb") as fh:
                fh.write(der_in)
            der = run_openssl(
                [
                    "pkcs8", "-topk8", "-inform", "DER", "-outform", "DER",
                    "-nocrypt", "-in", src,
                ]
            )
    finally:
        try:
            os.unlink(src)
        except OSError:
            pass
    return base64.b64encode(der).decode("ascii")


def derive_public(pkcs8_b64: str) -> str:
    if not pkcs8_b64:
        return ""
    with tempfile.NamedTemporaryFile(delete=False) as tmp:
        tmp.write(base64.b64decode(pkcs8_b64))
        src = tmp.name
    try:
        pub = run_openssl(
            ["pkey", "-inform", "DER", "-in", src, "-pubout", "-outform", "DER"]
        )
    finally:
        try:
            os.unlink(src)
        except OSError:
            pass
    return base64.b64encode(pub).decode("ascii")


def fingerprint(props: Dict[str, str]) -> str:
    payload = "|".join(props.get(name, "") for name in FIELDS)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def parse_crypto_yaml(text: str) -> Dict[str, str]:
    props = {name: "" for name in FIELDS}
    props["rsaAlgorithm"] = DEFAULT_ALGO
    in_security = False
    security_indent: Optional[int] = None
    in_crypto = False
    crypto_indent: Optional[int] = None
    pending: Optional[str] = None
    block_indent: Optional[int] = None
    block_lines: List[str] = []

    def flush_block() -> None:
        nonlocal pending, block_indent, block_lines
        if pending is not None:
            props[pending] = "\n".join(block_lines)
        pending = None
        block_indent = None
        block_lines = []

    for line in text.splitlines():
        if pending is not None:
            if not line.strip():
                block_lines.append("")
                continue
            indent = len(line) - len(line.lstrip(" "))
            if block_indent is None:
                block_indent = indent
            if indent >= block_indent and (line.startswith(" ") or line.startswith("\t")):
                block_lines.append(line[block_indent:])
                continue
            flush_block()

        stripped = line.split("#", 1)[0].rstrip()
        if not stripped.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        key_match = re.match(r"^([A-Za-z][A-Za-z0-9]*)\s*:\s*(.*)$", stripped.strip())
        if not key_match:
            continue
        name = key_match.group(1)
        rest = key_match.group(2).strip()

        if security_indent is not None and indent <= security_indent and name != "security":
            in_security = False
            in_crypto = False
            crypto_indent = None
        if crypto_indent is not None and indent <= crypto_indent and name != "crypto":
            in_crypto = False

        if name == "security" and rest in ("", "{}", "|"):
            in_security = True
            security_indent = indent
            in_crypto = False
            crypto_indent = None
            continue
        if in_security and name == "crypto" and rest in ("", "{}", "|"):
            in_crypto = True
            crypto_indent = indent
            continue
        if not in_crypto or name not in FIELDS:
            continue

        if rest in ("|", ">", "|-", "|+", ">-", ">+"):
            pending = name
            block_indent = None
            block_lines = []
            continue
        props[name] = strip_quotes(rest)

    flush_block()
    return props


def normalize_props(raw: Dict[str, str]) -> Dict[str, str]:
    out = {name: clean(raw.get(name, "")) for name in FIELDS}
    if not out["rsaAlgorithm"]:
        out["rsaAlgorithm"] = DEFAULT_ALGO
    for priv_name, pub_name in PRIV_PUB:
        out[priv_name] = normalize_private(out[priv_name]) if out[priv_name] else ""
        if out[priv_name]:
            out[pub_name] = derive_public(out[priv_name])
        else:
            out[pub_name] = ""
    out["aesKey"] = clean(raw.get("aesKey", ""))
    out["aesIv"] = clean(raw.get("aesIv", ""))
    return out


def load_db_fingerprint(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        text = fh.read().strip()
    if re.fullmatch(r"[0-9a-fA-F]{64}", text):
        return text.lower()
    data = json.loads(text)
    if isinstance(data, list):
        if not data:
            sys.exit("keyring json is an empty list")
        data = data[0]
    if not isinstance(data, dict):
        sys.exit("keyring file must be a JSON object, array, or 64-char hex")
    fp = str(data.get("fingerprint", "") or "").strip()
    if not fp:
        sys.exit("keyring json missing fingerprint")
    return fp.lower()


def emit_keyring(props: Dict[str, str]) -> Dict[str, object]:
    doc: Dict[str, object] = {
        "_id": "global",
        "schemaVersion": 1,
    }
    for name in FIELDS:
        doc[name] = props[name]
    doc["fingerprint"] = fingerprint(props)
    return doc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yaml", required=True, help="application.yaml / Consul dump")
    parser.add_argument(
        "--emit",
        action="store_true",
        help="print normalized Mongo document JSON for manual replace",
    )
    parser.add_argument("--out", help="write --emit JSON to this file")
    parser.add_argument(
        "--keyring",
        help="exported keyring JSON, or a file with only the 64-char fingerprint",
    )
    parser.add_argument("--fingerprint", help="fingerprint hex copied from the DB document")
    args = parser.parse_args()

    with open(args.yaml, encoding="utf-8") as fh:
        raw = parse_crypto_yaml(fh.read())
    local = normalize_props(raw)
    local_fp = fingerprint(local)

    if args.emit:
        doc = emit_keyring(local)
        text = json.dumps(doc, indent=2, ensure_ascii=True)
        if args.out:
            with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text + "\n")
            print("wrote {0}".format(args.out))
        else:
            print(text)
        print("fingerprint: {0}".format(local_fp), file=sys.stderr)
        return 0

    print("config fingerprint: {0}".format(local_fp))
    if args.fingerprint:
        remote_fp = args.fingerprint.strip().lower()
    elif args.keyring:
        remote_fp = load_db_fingerprint(args.keyring)
    else:
        return 0

    print("db     fingerprint: {0}".format(remote_fp))
    if local_fp == remote_fp:
        print("MATCH")
        return 0
    print("MISMATCH (process uses DB, config is ignored)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
