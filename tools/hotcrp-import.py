#!/usr/bin/env python3
"""Companion script for sites that prefer cron + a script over enabling the
in-app HotCRP plug-in. Reads from HotCRP's MySQL database and POSTs to
recletters' /api/dossiers/bulk endpoint via the bearer token.

Usage:
    HOTCRP_DB_URL=mysql://user:pass@host/hotcrp \
    RECLETTERS_URL=https://letters.example.org \
    RECLETTERS_TOKEN=... \
    RECLETTERS_CALL=award-2026 \
    OPTION_MAPPING='{"4":"supervisor","5":"external","24":"industry"}' \
    python3 hotcrp-import.py
"""
from __future__ import annotations

import json
import os
import sys
import urllib.parse
import urllib.request

try:
    import pymysql
except ImportError:
    sys.stderr.write("Install pymysql first: pip install pymysql\n")
    sys.exit(2)


def parse_db_url(url: str):
    """Parse mysql://user:pass@host[:port]/db into pymysql kwargs."""
    parsed = urllib.parse.urlparse(url)
    return dict(
        host=parsed.hostname,
        port=parsed.port or 3306,
        user=urllib.parse.unquote(parsed.username or ""),
        password=urllib.parse.unquote(parsed.password or ""),
        db=parsed.path.lstrip("/"),
        charset="utf8mb4",
    )


def primary_author_name(blob: str) -> str:
    head = (blob or "").splitlines()[:1]
    if not head:
        return ""
    parts = head[0].split("\t")
    first = parts[0].strip() if len(parts) > 0 else ""
    last = parts[1].strip() if len(parts) > 1 else ""
    return " ".join(p for p in (first, last) if p)


def main() -> int:
    db_url = os.environ["HOTCRP_DB_URL"]
    recl_url = os.environ["RECLETTERS_URL"].rstrip("/")
    token = os.environ["RECLETTERS_TOKEN"]
    call = os.environ["RECLETTERS_CALL"]
    mapping = json.loads(os.environ.get("OPTION_MAPPING", "{}"))
    mapping = {int(k): v for k, v in mapping.items()}

    cn = pymysql.connect(**parse_db_url(db_url))
    try:
        with cn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                "SELECT paperId, title, authorInformation "
                "FROM Paper WHERE timeWithdrawn = 0 AND timeSubmitted > 0"
            )
            papers = cur.fetchall()
            cur.execute(
                "SELECT paperId, optionId, data FROM PaperOption "
                "WHERE optionId IN ({})".format(
                    ",".join(str(k) for k in mapping) or "NULL"
                )
            )
            opts = cur.fetchall()
    finally:
        cn.close()

    by_paper = {}
    for o in opts:
        by_paper.setdefault(o["paperId"], []).append(o)

    dossiers = []
    for p in papers:
        dossiers.append(
            {
                "externalRef": str(p["paperId"]),
                "name": primary_author_name(p["authorInformation"]),
                "url": None,
                "notes": p["title"],
                "referees": [
                    {
                        "email": o["data"],
                        "role": mapping.get(o["optionId"]),
                        "notes": None,
                    }
                    for o in by_paper.get(p["paperId"], [])
                ],
            }
        )

    payload = json.dumps({"call": call, "dossiers": dossiers}).encode("utf-8")
    req = urllib.request.Request(
        recl_url + "/api/dossiers/bulk",
        data=payload,
        method="POST",
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req) as resp:
        sys.stdout.write(resp.read().decode("utf-8"))
        sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
