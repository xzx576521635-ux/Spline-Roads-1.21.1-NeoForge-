# -*- coding: utf-8 -*-
"""Record consistency check for Spline Roads + Quick Build.

Run after every build:
    python spline_work/records_check.py

Verifies that the changelogs, the rollback notes, the installed jars and the
per-version backups in mods_backup/ all agree (names, versions and SHA-256).

Output is ASCII only so it stays readable in any console. Exit code 1 means at
least one check failed.
"""

import glob
import hashlib
import io
import os
import re
import sys

V = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODS = os.path.join(V, "mods")
BACKUP = os.path.join(V, "mods_backup")
MAIN_DOC = os.path.join(V, "spline_work", "\u53d8\u66f4\u8bb0\u5f55.md")
QB_DOC = os.path.join(V, "spline_work", "\u5feb\u901f\u5efa\u9020-\u53d8\u66f4\u8bb0\u5f55.md")
ROLLBACK = os.path.join(BACKUP, "\u56de\u6eda\u8bf4\u660e.txt")

failures = []


def sha(path):
    d = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            d.update(chunk)
    return d.hexdigest().upper()


def read(path):
    return io.open(path, encoding="utf-8-sig", newline=None).read()


def ok(message):
    print("[OK]   " + message)


def warn(message):
    print("[WARN] " + message)


def fail(message):
    failures.append(message)
    print("[FAIL] " + message)


def main_current(doc):
    m = re.search(r"- \u5f53\u524d\u7248\u672c\uff1a`mods/([^`]+)`\uff08v(\d+)", doc)
    h = re.search(r"- \u5f53\u524d\u7248\u672c\uff1a`[^`]+`\uff08v\d+[^\n]*\n  SHA-256: `([0-9A-F]{64})`", doc)
    if not m or not h:
        return None
    return m.group(1), int(m.group(2)), h.group(1)


def qb_current(doc):
    m = re.search(r"> \u5f53\u524d\u7248\u672c\uff1a`([0-9.]+)`\uff08[^\n]*qb(\d+)", doc)
    h = re.search(r"> \u5f53\u524d\u7248\u672c\uff1a`[^`]+`[^\n]*\n> SHA-256\uff1a`([0-9A-F]{64})`", doc)
    if not m or not h:
        return None
    return m.group(1), int(m.group(2)), h.group(1)


def sections(doc, pattern):
    """Returns [(number, version/title, hash-or-None)] for each matching heading."""
    out = []
    for m in re.finditer(pattern, doc, re.M):
        start = m.start()
        end = len(doc)
        nxt = re.search(r"^#{2,3} ", doc[start + 4:], re.M)
        if nxt:
            end = start + 4 + nxt.start()
        body = doc[start:end]
        h = re.search(r"hash\uff1a`([0-9A-F]{64})`", body)
        out.append((int(m.group(1)), m.group(2), h.group(1) if h else None))
    return out


print("== Spline Roads main mod ==")
doc = read(MAIN_DOC)
cur = main_current(doc)
if cur is None:
    fail("main changelog: current version block not parseable")
else:
    jar_name, version, doc_hash = cur
    print("       current: v%d %s" % (version, jar_name))
    installed = [p for p in glob.glob(os.path.join(MODS, "splineroads-*.jar")) if not p.endswith(".disabled")]
    if len(installed) != 1:
        fail("mods/ must contain exactly one splineroads jar, found %d" % len(installed))
    elif os.path.basename(installed[0]) != jar_name:
        fail("installed jar %s != documented %s" % (os.path.basename(installed[0]), jar_name))
    elif sha(installed[0]) != doc_hash:
        fail("installed jar hash != documented hash for v%d" % version)
    else:
        ok("installed jar matches v%d hash" % version)

    backup = os.path.join(BACKUP, "%s.v%d" % (jar_name, version))
    if not os.path.exists(backup):
        fail("missing backup %s" % os.path.basename(backup))
    elif sha(backup) != doc_hash:
        fail("backup hash mismatch %s" % os.path.basename(backup))
    else:
        ok("backup %s matches" % os.path.basename(backup))

    secs = sections(doc, r"^### v(\d+) \u2014 `([^`]+)`")
    known = {n: (t, h) for n, t, h in secs}
    if version not in known:
        fail("changelog has no section for v%d" % version)
    for n in range(max(74, version - 7), version + 1):
        if n not in known:
            fail("changelog missing section v%d" % n)
            continue
        title, hash_value = known[n]
        if hash_value is None:
            fail("v%d section has no hash line" % n)
            continue
        jar_for_n = os.path.join(BACKUP, "%s.v%d" % (title, n))
        if not os.path.exists(jar_for_n):
            fail("v%d backup missing (%s)" % (n, os.path.basename(jar_for_n)))
        elif sha(jar_for_n) != hash_value:
            fail("v%d backup hash mismatch" % n)

    rows = {int(x) for x in re.findall(r"^\| v(\d+) \|", doc, re.M)}
    for n in range(max(74, version - 7), version + 1):
        if n not in rows:
            fail("version table missing v%d" % n)
    if not failures:
        ok("sections + hashes + table consistent for v%d..v%d" % (max(74, version - 7), version))

print()
print("== Quick Build add-on ==")
qdoc = read(QB_DOC)
qcur = qb_current(qdoc)
if qcur is None:
    fail("quickbuild changelog: current version block not parseable")
else:
    qversion, qn, qhash = qcur
    print("       current: %s qb%d" % (qversion, qn))
    installed_qb = [p for p in glob.glob(os.path.join(MODS, "splinequickbuild-*.jar"))]
    if len(installed_qb) != 1:
        fail("mods/ must contain exactly one splinequickbuild jar, found %d" % len(installed_qb))
    elif sha(installed_qb[0]) != qhash:
        fail("installed quickbuild hash != documented qb%d hash" % qn)
    else:
        ok("installed quickbuild jar matches qb%d hash" % qn)

    qbackup = os.path.join(BACKUP, "splinequickbuild-%s.jar.qb%d" % (qversion, qn))
    if not os.path.exists(qbackup):
        fail("missing backup %s" % os.path.basename(qbackup))
    elif sha(qbackup) != qhash:
        fail("backup hash mismatch %s" % os.path.basename(qbackup))
    else:
        ok("backup %s matches" % os.path.basename(qbackup))

    qsecs = sections(qdoc, r"^## qb(\d+) \u2014 `splinequickbuild-([^`]+)`")
    qknown = {n: (t, h) for n, t, h in qsecs}
    if qn not in qknown:
        fail("quickbuild changelog has no section for qb%d" % qn)
    for n in range(max(14, qn - 5), qn + 1):
        if n not in qknown:
            fail("quickbuild changelog missing section qb%d" % n)
            continue
        title, hash_value = qknown[n]
        if hash_value is None:
            fail("qb%d section has no hash line" % n)
            continue
        jar_for_n = os.path.join(BACKUP, "splinequickbuild-%s.qb%d" % (title, n))
        if not os.path.exists(jar_for_n):
            fail("qb%d backup missing (%s)" % (n, os.path.basename(jar_for_n)))
        elif sha(jar_for_n) != hash_value:
            fail("qb%d backup hash mismatch" % n)
        if n >= 22:
            base_version = title[:-4] if title.endswith(".jar") else title
            src = os.path.join(BACKUP, "splinequickbuild-src-%s.qb%d.zip" % (base_version, n))
            if not os.path.exists(src):
                fail("qb%d source zip missing" % n)

    qrows = {int(x) for x in re.findall(r"^\| qb(\d+) \|", qdoc, re.M)}
    for n in range(max(14, qn - 5), qn + 1):
        if n not in qrows:
            fail("quickbuild version table missing qb%d" % n)
    if not [f for f in failures if "qb" in f]:
        ok("quickbuild sections + hashes + table consistent for qb%d..qb%d" % (max(14, qn - 5), qn))

print()
print("== Rollback notes ==")
roll = read(ROLLBACK)
m = re.search(
    r"\u5f53\u524d\u542f\u7528\u7248\u672c\uff08mods \u6587\u4ef6\u5939\uff09\uff1a\s*\n\s*([^\s\uff08]+)\uff08v(\d+)",
    roll,
)
h = re.search(r"\u5f53\u524d\u542f\u7528\u7248\u672c\uff08mods \u6587\u4ef6\u5939\uff09\uff1a\s*\n\s*[^\n]+\n\s*SHA-256: ([0-9A-F]{64})", roll)
if not m or not h:
    fail("rollback notes: main current version not parseable")
else:
    rjar, rver, rhash = m.group(1), int(m.group(2)), h.group(1)
    if cur and (rver != cur[1] or rhash != cur[2]):
        fail("rollback notes main version (%s, v%d) != changelog (v%d)" % (rjar, rver, cur[1] if cur else -1))
    else:
        ok("rollback notes main current matches v%d" % rver)
    if cur and (rjar + ".v%d" % rver) not in roll:
        fail("rollback backup list does not mention %s.v%d" % (rjar, rver))

i = roll.find("\u9644\u5c5e mod\uff1a\u5feb\u901f\u5efa\u9020")
addon_roll = roll[i:] if i >= 0 else ""
m2 = re.search(r"splinequickbuild-([0-9.]+)\.jar\uff08qb(\d+)", addon_roll)
h2 = re.search(r"splinequickbuild-[0-9.]+\.jar\uff08qb\d+[^\n]*\n\s*SHA-256: ([0-9A-F]{64})", addon_roll)
if not m2 or not h2:
    fail("rollback notes: add-on current version not parseable")
elif qcur and (m2.group(1) != qcur[0] or int(m2.group(2)) != qcur[1] or h2.group(1) != qcur[2]):
    fail("rollback notes add-on version/hash != quickbuild changelog")
else:
    ok("rollback notes add-on current matches qb%d" % qcur[1])

print()
if failures:
    print("RESULT: %d problem(s)" % len(failures))
    sys.exit(1)

print("RESULT: all records consistent")
